# 생성 시간·토큰·정리 실패 관측

## 이번 변경

기존 앱이 이미 내보내는 시간·토큰을 CloudWatch로 수집한다. 기존 로그에서 놓치던 정리 실패도 별도 경보로 준비한다. 백엔드 업무 로직·사용량 차감 정책은 변경하지 않는다.

| 대상 | 이번 상태 | 읽는 방법 |
|---|---|---|
| 생성 평균 시간·표본 수 | dev/prod Agent 설정 적용, prod 실제 유입 확인 | 같은 5분 구간·`job/result`의 `Sum(시간 합) / Sum(실행 수)` |
| Gemini 토큰 | 두 환경 수집 설정 적용, prod 실제 유입 확인 | `stage/kind`별 관측량. `total`과 구성 항목은 합산하지 않음 |
| 정리 실패 경보 | 환경별 3개 생성, **알림 비활성** | 전달기 ZIP 갱신 후 활성화·전달 시험 필요 |
| KST 성공률·P50/P95·PROCESSING | 두 환경 DB에서 일회성 읽기 전용 집계 완료 | 재사용 쿼리: [generation-observation.sql](generation-observation.sql) |

2026-10-08 확인 시 dev는 현 배포 이후 생성 호출이 없었다. 기존 Hikari EMF는 1분마다 들어오지만, 시간·토큰 계열은 아직 등록되지 않았다. 새 dev 생성 호출 뒤 해당 계열의 원본·EMF·CloudWatch 유입을 확인해야 한다. 수집 설정 적용과 실제 비즈니스 표본 확인을 구분한다.

## 1. 시간과 토큰을 읽는 기준

- 생성 시간은 최초 claim 이후 실행기의 Gemini·S3 호출, 완료 트랜잭션, 이미지 폐기 처리 등을 포함한다. 최초 claim·HTTP 전달·화면 표시는 제외한다.
- `result=returned`는 실행기 반환, `threw`는 실행기 예외다. **DB 최종 성공률은 `SUCCEEDED / (SUCCEEDED + FAILED)`로 별도 계산**한다.
- Agent는 누적 counter와 summary의 sum/count를 수집 간 증가분으로 변환한다. CloudWatch에서는 `Sum`을 사용하고 `DIFF/RATE`를 다시 적용하지 않는다. 첫 수집 이전 소비량은 소급되지 않는다.
- 평균 위젯은 `IF(c>0,s/c)`다. 무호출을 0초로 채우지 않는다. sum/count의 CloudWatch `p95`는 개별 생성 P95가 아니다.
- 토큰은 공급자 응답의 `usageMetadata`에서 관측한 값이다. 메타데이터가 없는 실패 호출의 소비량까지 보장하지 않으며, 공급자 청구 비용·사용자 일일 차감과 다르다.
- 추가 차원은 `job/result`, `job/stage/kind`로 한정한다. 최대 12개 신규 시계열/환경이며 ID·원문·S3 키·URL을 차원에 넣지 않는다.

[Agent 변환 규칙](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/ContainerInsights-Prometheus-metrics-conversion.html)

## 2. 정리 실패 경보 3개

세 경보 모두 차원 없는 로그 건수의 **5분 Sum ≥ 1, 1회 중 1회**, 무데이터 `notBreaching`으로 준비했다. ALARM·OK 수신처는 환경별 기존 SNS다.

| 경보 suffix / 지표 | 실제 기록 조건 | 뜻 |
|---|---|---|
| `s3-delete-failure` / `S3DeleteFailureLogs` | `external_api_failure`, `provider=s3`, `operation=delete_object` | 객체 삭제 처리 실패. SDK 호출 전 검증·요청 구성 실패도 포함 |
| `image-cleanup-deferred` / `ImageCleanupDeferredLogs` | `discarded_image_delete_deferred` | 안전한 폐기 여부를 확인하지 못해 삭제를 보류함. S3 삭제를 시도한 실패와 다름 |
| `generation-cleanup-failure` / `GenerationCleanupFailureLogs` | `generation_cleanup_failed` | 만료된 생성 정리 스케줄러의 실행 실패. 미처리 생성 개수가 아님 |

`compensation_failure`는 현재 호출 경로가 없는 과거 이벤트다. 이것을 사용량 복구 실패로 해석하거나 전용 필터를 만들지 않는다. `discarded_image_delete_failed`는 같은 실패에서 객체별 S3 로그와 함께 남을 수 있어 합산하지 않는다. 세 경보 역시 서로 겹칠 수 있으므로 고유 장애 건수로 합산하지 않는다.

AWS에 적용한 초기 payload는 [dev](cleanup-alarms.dev.json) / [prod](cleanup-alarms.prod.json)에 보관한다. `ActionsEnabled=false`는 의도된 초기 상태다. 기존 19개 경보는 유지했다. 필터 생성 이전의 로그는 새 지표로 소급 집계되지 않는다.

### PR 검토 후 할 일

1. 승인된 커밋의 `discord_forwarder.py`를 ZIP 루트에 넣어 dev/prod 전달 Lambda를 갱신한다. 환경 변수·기존 역할·SNS 권한은 보존하고 함수 코드 해시를 확인한다.
2. 세 suffix의 ALARM·OK와 고정 원인 문구가 허용되는지 dev에서 확인한다. 외부 전송은 기존 네 필드(환경·알람명·상태·고정 원인)로 유지한다.
3. 각 환경의 새 세 경보만 알림을 활성화하고, 환경별 SNS ARN을 대조한다.
4. dev에서 합성 JSON 로그로 필터 1건 → ALARM → Discord → 자연 OK를 시험한다. prod 시험도 합성 경로 검증으로 기록하며 실제 업무 장애 발생과 구분한다. 이미지 삭제·DB 상태 변경을 일으키는 시험은 필요 없다.

전달기 검토 전 AWS Lambda 소스를 직접 수정하거나, 새 이름을 기존 경보 이름으로 우회하지 않는다. 이 추가 구성에는 백엔드 앱 재배포가 필요하지 않다. 향후 앱 배포에도 설정이 남도록 저장소와 런타임 구성을 맞춘다. 이 PR은 `develop-backend` 대상이므로 다음 dev/prod 릴리스에도 수집 파일 변경을 포함해야 한다. CodeDeploy의 `before_install.sh`는 호스트의 monitoring 파일을 제거한 뒤 배포본으로 교체하므로, 이전 dev/main 배포본을 다시 배포하면 `prometheus.yaml`이 예전 수집 범위로 돌아갈 수 있다. 즉시 앱 재배포는 필요 없지만 다음 배포 전 변경 포함 여부를 확인하고, 배포 후 시간·토큰 수집을 다시 대조한다.

## 3. DB 집계 기준과 실행

[generation-observation.sql](generation-observation.sql)은 생성 테이블의 집계만 조회한다. 사용자·일기 원문·이미지 키·raw token JSON·개별 ID는 조회하지 않는다.

| 조회 | 기준 |
|---|---|
| 일별 성공률 | KST `completed_at` 날짜. 멱등 재요청은 동일 생성 행이므로 중복되지 않음. 새 키의 새 생성은 별도 건 |
| 실패 분류 | 완료일·`error_code`. 실패 일기는 soft delete될 수 있으므로 활성 일기와 조인하지 않음 |
| P50/P95 | 성공·실패를 나누어 `completed_at - created_at` 계산. 실행기 Timer·HTTP 시간과 다름 |
| PROCESSING | 완료일 범위와 관계없이 모든 진행 중 행 확인. 실제 양쪽 설정인 5분과 `updated_at`을 기준으로 정리 대상 집계 |
| 저장된 토큰 | 전체 생성에 성공한 행에 보존된 **스토리보드** 메타데이터. 이미지·실패 호출·전체 공급자 소비량은 제외 |
| 데이터 품질 | 선택한 생성일 범위에서 완료 시각 결측·음수 시간 등 확인 |

기간은 SQL 상단에서 지정한다. 기본은 2026-10-01 00:00 KST부터 조회 시각까지로 오늘의 일부를 포함한다. 종료 시각은 배타적이며, 0건의 성공률·지연은 NULL이다. 토큰 누락도 0으로 바꾸지 않는다. 계정 hard delete로 생성 이력이 사라질 수 있어 보존된 이력의 집계로 해석한다.

**운영 실행:** 해당 환경의 기존 DB 연결 정보와 승인된 클라이언트를 사용한다. 값은 명령 인수·출력에 남기지 않는다. 먼저 catalog 조회 2개로 크기·인덱스를 확인한 뒤 집계한다. 완료 시각과 `(status, updated_at)` 인덱스가 없어 스캔할 수 있으므로 25 MiB/추정 10만 행 이상이면 실행 계획 검토를 먼저 한다. `READ ONLY`, 쿼리당 5초·잠금 대기 1초 제한과 마지막 `ROLLBACK`을 유지한다. 제한에 걸리면 운영에서 자동 재실행하거나 범위를 넓히지 않는다. 이 쿼리는 주기 실행되거나 scrape 시 DB에 접근하지 않는다.

## 4. 2026-10-08 적용·검증 기록 (KST)

- 10:57 dev / 10:57 prod: 기존 호스트 Agent 조각의 SHA를 보존하며 `append-config` 적용. 백엔드 컨테이너 변경 없이 healthy, Agent active.
- 11:08: AWS native `TestMetricFilter` 양성·음성 26개 통과 후 dev/prod 필터·경보 3개씩 생성. 새 경보 6개 모두 알림 비활성·OK 확인.
- 11:10: dev 대시보드 15→20, prod 35→40개 위젯. 기존 위젯을 전부 보존하고 평균·표본·토큰·정리 실패 영역 추가. AWS validation 메시지 없음.
- 11:10 dev / 11:11 prod: 읽기 전용 DB 집계 완료. 실제 runtime timeout은 양쪽 5분. 조회 시점의 PROCESSING·정리 대상·선택 범위의 완료 시각 결측/음수 시간 모두 0건.
- prod 원본 Timer·토큰, EMF 증가분, CloudWatch 등록 차원, 실제 0보다 큰 평균·토큰 위젯 확인. 두 환경 기존 Hikari EMF는 최근 5분에 5회 확인.
- prod 백엔드 로그 그룹의 누락된 필수 태그 보완. 양쪽 backend·prometheus-emf 그룹에 `Service=techcourse`, `Role=techcourse-etc`, `ProjectTeam=harudle` 확인.
- 기존 전체 전달 시험은 dev 10/1, prod 10/7에 ALARM·자연 OK까지 완료했다. **새 정리 경보 3종의 Discord 전달은 아직 시험하지 않았다.**

백업은 각 호스트 `/opt/harudle/monitoring-backups/{env}-metrics-20261008T...`에 있다. 추가 경보의 원설정·대시보드 원본은 prod의 `staged-alarms-20261008T020816Z`, `dashboard-metrics-20261008T021047Z`에 보관했다. 수집 롤백 시 해당 환경의 이전 앱 조각·prometheus.yaml을 복원하고 같은 `append-config`를 적용한다. 기존 호스트 조각을 대체하지 않는다.

## 5. 확인 링크

- [dev 대시보드](https://ap-northeast-2.console.aws.amazon.com/cloudwatch/home?region=ap-northeast-2#dashboards/dashboard/DASHBOARD-harudle-dev)
- [prod 대시보드](https://ap-northeast-2.console.aws.amazon.com/cloudwatch/home?region=ap-northeast-2#dashboards/dashboard/DASHBOARD-harudle-prod)
- [새 prod 삭제 실패 경보](https://ap-northeast-2.console.aws.amazon.com/cloudwatch/home?region=ap-northeast-2#alarmsV2:alarm/harudle-prod-s3-delete-failure)

이미지 전체 점검, 실시간 DB 집계 관측기, HTTP/생성 summary 백분위와 지연 경보는 별도 확장이다. 이번 평균과 일회성 DB P50/P95를 지속 수집되는 요청 백분위로 표시하지 않는다.
