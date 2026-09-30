# dev/prod 관측 인프라 적용 계획

2026-09-30 기준 준비·진행 기록이다. dev 로그 지표 필터와 경보가 생성됐으며, 로그 유입과 알람 전달은 아직 검증 전이다. 계측 코드는 [PR #280](https://github.com/woowacourse-teams/2026-Harudle/pull/280)에 있으며, S3 이미지 경로 분리는 다른 팀원의 작업이다. 환경별 설정·알람 상세는 [운영 안내](README.md)를 따른다.

## 지금 확인된 것과 미확인인 것

저장소에는 CodePipeline → CodeBuild → CodeDeploy → EC2 배포 흐름, Docker `awslogs`, 내부 관리 포트 `127.0.0.1:19091`, CloudWatch Agent의 1분 Prometheus 수집 설정이 있다. 백엔드·Nginx 로그는 Docker `awslogs`가 CloudWatch Logs에 직접 보내고, 이 Agent 조각은 Prometheus를 수집해 EMF로 게시한다. dev는 `compose.prod.yaml`에 `compose.dev.yaml`을 덧붙여 로그 그룹을 변경한다. Agent 설정 파일은 배포 아티팩트로 복사되지만 호스트에 자동 적용되지는 않는다. 알람·SNS·Lambda·Secrets Manager·대시보드를 생성하는 IaC도 없다.

2026-09-30 점검 시점, dev 로그 그룹 3개(백엔드·프론트·Prometheus EMF)는 모두 Standard 클래스와 14일 보존, prod 백엔드·프론트 로그 그룹 2개는 Standard 클래스와 30일 보존으로 확인됐다. dev 백엔드 로그 그룹의 스트림은 0개였다. 새 계측 코드는 아직 dev에 배포되지 않았고 Docker 로그 쓰기 권한도 확인되지 않아, 이 상태만으로 CloudWatch Agent의 수집 장애를 판단할 수 없다. `Harudle/Dev` 네임스페이스의 **로그 지표 필터 13개**(생성 2개, S3 6개, Gemini 4개, 이미지 신고 1개)가 생성됐지만 실제 JSON 로그 한 건의 일치 여부는 아직 시험하지 못했다. `harudle-dev-alerts` SNS 주제 정책의 Allow 문은 CloudWatch 서비스의 `sns:Publish`를 같은 계정의 `harudle-dev-*` 알람 ARN으로 제한한다. 이 Allow 문만으로 같은 계정의 다른 identity policy가 허용한 발행까지 배제하지는 않는다. 명시적 Deny 추가 시도는 SNS `InvalidParameter`로 거절되어 반영되지 않았다. 구독은 0개이고 CloudWatch 알람 화면도 SNS 엔드포인트가 없다고 경고하므로 Discord 전달은 연결 전이다.

CodePipeline 화면에서 `harudle-dev-pipeline`의 소스는 GitHub `dev` 브랜치이며 최근 `c4c7d8a5` 빌드·배포가 성공한 것으로 확인됐다. `harudle-prod-pipeline`은 `main` 브랜치의 `9a401b97` 빌드·배포가 성공했다. PR #280의 계측 코드는 아직 dev 배포에 포함되지 않았다.

EC2 화면에서 dev 인스턴스 `i-0f899d8ec0a6fb988`와 prod 인스턴스 `i-00944495cd72e61e8`은 모두 `관리형 false`이며 같은 IAM 역할 `ec2-project`를 사용한다. 따라서 dev 호스트의 Agent 설정 적용에는 승인된 호스트 접근 또는 SSM 등록이 먼저 필요하다. 공유 역할의 권한을 dev 전용이라고 가정해 변경하지 않는다. EC2 Connect 접근 시도는 자동 검토에서 거절됐고 IAM `GetRole` 조회도 거부됐다.

dev 경보 **13개**의 생성과 목록을 확인했다. 로그 필터 경보는 12개다. 생성 2개(`generation-unexpected-failure`, `generation-interrupted`)와 S3 6개(`s3-put-failure`, `s3-authentication`, `s3-authorization`, `s3-configuration`, `s3-reference-get-failure`, `s3-url-sign-failure`)는 각각 5분 `Sum > 0`이다. Gemini 일시 오류 2개(`gemini-storyboard-transient`, `gemini-image-transient`)는 15분 `Sum >= 3`, 응답 처리 오류 2개(`gemini-storyboard-response`, `gemini-image-response`)는 15분 `Sum >= 2`다. 로그 필터 경보 12개 모두 평가 1회 중 1회, 무데이터 `notBreaching`, ALARM·OK를 dev SNS 주제에 연결했다. 별도의 `harudle-dev-image-load-failure-report` 필터는 `{ $.event = "image_load_failure_reported" }` 패턴으로 `Harudle/Dev/ImageLoadFailureReportLogs` 지표를 값 1·기본값 0·차원 없이 게시하도록 준비했다. 프론트 신고 연동과 실제 로그 유입이 확인되지 않아 이 필터에는 경보를 만들지 않았다. 추가된 `harudle-dev-ec2-status-check`는 dev 인스턴스 `i-0f899d8ec0a6fb988`의 `StatusCheckFailed`를 1분 `Average >= 1`이 2회 연속 발생할 때 알리고, 같은 SNS 주제에 연결했다. Discord 전달 함수에는 `ec2-status-check`가 이미 허용돼 있다. 아래 표의 prod 값과 아직 확인하지 못한 dev 항목은 **목표**다.

| 구성 | dev 상태·목표 | prod 상태·목표 | 확인할 내용 |
|---|---|---|---|
| 애플리케이션 로그 | 확인: `/harudle/dev/backend`, `/harudle/dev/frontend-nginx` (Standard, 14일) | 확인: `/harudle/prod/backend`, `/harudle/prod/frontend-nginx` (Standard, 30일) | 실제 로그 수집, EC2 Docker 역할의 쓰기 권한 |
| EMF 로그 | 확인: `/harudle/dev/prometheus-emf` (Standard, 14일) | 목표: `/harudle/prod/prometheus-emf` (존재·설정 미확인) | Agent 전송·쓰기 권한과 기존 호스트 지표 유지 |
| 지표 | `Harudle/Dev` | `Harudle/Prod` | 9개 계열과 필요한 태그 조합만 게시 |
| 알람·전달 | dev 로그 지표 필터/알람 → dev SNS → dev Lambda → 팀 Discord | prod 로그 지표 필터/알람 → prod SNS → prod Lambda → 팀 Discord | dev SNS 구독·Lambda 연결, 단일 실패 로그 집계, ALARM·OK 시험 |
| Webhook | dev Secrets Manager 비밀 | prod Secrets Manager 비밀 | 값 출력 금지, 해당 Lambda만 읽기 |

## S3 분리와 무관하게 지금 준비할 것

- [ ] AWS 로그인 뒤 prod 대시보드·기존 Agent 설정·로그 그룹·IAM·알람·SNS·Lambda·파이프라인 source branch를 **읽기 전용으로** 목록화한다. 저장소 설정을 실제 리소스 현황으로 단정하지 않는다.
- [x] dev 로그 그룹 3개의 존재와 Standard 클래스·14일 보존을 확인했다. prod 백엔드·프론트 로그 그룹의 Standard 클래스·30일 보존도 확인했다. dev는 `awslogs-create-group=false`이므로 배포 전 그룹 존재를 다시 확인한다.
- [ ] dev EC2의 Docker daemon이 사용하는 역할에 해당 로그 그룹의 `CreateLogStream`, `PutLogEvents`가 있는지 확인한다. Agent의 EMF 로그 쓰기 권한도 별도로 확인한다.
- [ ] dev 호스트에 접근할 승인된 방법 또는 SSM 등록 경로를 확인하고 `DEPLOY_ENV=dev`, `19091` 포트 충돌·외부 접근 차단, Docker 버전, 기존 Agent 설정을 확인한다. `ec2-project` 역할은 prod와 공유하므로 권한 변경 전에 양쪽 영향을 검토한다. 호스트의 활성 Agent 설정은 기록·백업하고 새 Prometheus 조각은 `append-config`로만 추가한다.
- [ ] 생성된 dev 로그 지표 필터 13개를 실제 JSON 로그로 시험하고, Lambda 역할·Webhook 비밀·대시보드를 준비한다. 생성 내부 오류·만료 처리의 첫 건은 Agent 카운터가 아닌 로그 필터로 경보를 건다. 이미지 신고 필터는 프론트 연동 뒤 실제 신고 로그 유입을 확인하되, 그전에는 경보를 만들지 않는다. 로그 필터 경보 12개와 EC2 상태 검사 경보 1개의 ALARM·OK SNS 동작을 검증한다. 한 번의 Gemini 장애가 여러 지표를 올릴 수 있으므로 Discord에서 중복 대응을 묶을 운영 규칙을 정한다.
- [ ] dev/prod EC2 InstanceId와 RDS DBInstanceIdentifier, EC2 Agent의 `mem_used_percent`·`disk_used_percent`, RDS 인스턴스 메모리·할당 스토리지·`max_connections`를 확인한다. CPU·상태 검사는 AWS 기본 지표로, 메모리·디스크가 없다면 기존 Agent 구성을 보존하며 수집을 추가한다. 실제 크기로 경보 임계값을 변환한다.

## S3 담당 팀원의 인수 조건

같은 버킷의 dev/prod 폴더(prefix) 분리는 이번 관측 인프라 작업의 범위 밖이다. 현재 캡처상 생성 이미지가 `harudle/generated/diary-images/` 아래에 있으므로, 그 하위에 `dev/`, `prod/`를 두는 방식이 예상된다. 정확한 경로는 담당 팀원이 전달한 설정으로 확정한다. dev 병합이 자동 배포를 시작하므로 **병합 전** 다음 증거를 확인한다.

- [ ] 실제 dev/prod의 `S3_GENERATED_PREFIX`가 다르고, 새 이미지 저장 키가 각각 기대한 prefix에 생긴다.
- [ ] 새 생성 이미지의 URL 조회와 실패 후 폐기 이미지 삭제가 각 환경에서 동작한다. `S3_GENERATED_PREFIX`는 새 업로드 키 생성에만 적용되고, 조회·삭제 코드는 전달받은 전체 키의 prefix를 검증하지 않는다는 점을 확인한다.
- [ ] 기존 DB 이미지 키의 객체와 프롬프트 DB의 참조 이미지 키가 계속 읽힌다. 참조 이미지를 이동한다면 환경 변수만 수정해서는 이미 저장된 프롬프트 키가 바뀌지 않는다. 과거 키의 복구 PUT 정책도 결정한다.
- [ ] 교차 prefix 쓰기·삭제를 IAM/버킷 정책으로 제한할 수 있는지 인프라 담당자와 확인한다. [AWS 자체는 prefix별 객체 정책을 지원](https://docs.aws.amazon.com/AmazonS3/latest/userguide/security_iam_service-with-iam.html)하지만 공유 인프라에서 역할·정책 변경 권한이 없을 수 있다. 지금 불가능하다면 **권한 차단은 미적용**으로 명시하고, 생성·폐기 삭제가 해당 작업의 키에만 적용되는지 확인한다. 현재 코드에는 버킷 전체를 순회하는 고아 이미지 삭제기가 없지만, prefix 설정만으로 잘못 전달된 키의 삭제를 막지는 못한다.

## dev 배포 뒤 관측 연결

1. CodePipeline/CodeDeploy와 서비스 health를 확인한다. 백엔드·프론트 로그 그룹에 각각 새 로그가 들어오는지 확인하고, 오류 로그에 일기 원문·프롬프트·S3 키·서명 URL이 없는지 검사한다.
2. EC2 호스트에서 `127.0.0.1:19091/actuator/prometheus`가 보이고 외부와 API 포트에서 관리 경로가 보이지 않는지 확인한다.
3. 기존 Agent 설정을 유지한 채 dev 설정 파일 하나를 추가한다. EMF 로그와 `Harudle/Dev`의 9개 지표 계열을 확인한다. 새 설정 때문에 기존 CPU·메모리·디스크 지표가 사라지지 않아야 한다.
4. 생성 최종화·예상 밖 오류·이미지 표시 실패 시계열이 첫 수집에 0으로 노출되는지 확인한다. 첫 정상 수집 뒤 안전한 dev 단일 생성 오류를 일으켜 `/actuator/prometheus` 누적값 `0 → 1`, EMF 증가분 `1`, CloudWatch 카운터 5분 `Sum=1`을 대조한다. 첫 수집 이전 이벤트는 카운터 증가분에서 빠질 수 있다. 백엔드 재시작 뒤에도 오탐 증가분이 없는지 검사한다. `hikaricp_connections_pending`은 증가분이 아닌 게이지이므로 `Max`·`Average`로 본다.
5. dev에 생성된 생성 내부 오류(`generation_unexpected_failure`)·만료 처리(`generation_finalized`, `status=FAILED`, `errorCode=GENERATION_INTERRUPTED`)와 S3·Gemini의 `external_api_failure` JSON 로그 필터가 단일 실패 로그를 정확히 1건으로 집계하는지 시험하고, 12개 경보의 평가 결과를 확인한다. 이미지 신고 필터의 `image_load_failure_reported` 집계도 프론트 연동 후 실제 로그로 시험한다. Gemini 단계별 일시 오류(`transient`)는 15분 3건 이상, 응답 처리 오류(`response`)는 15분 2건 이상으로 설정했다. HTTP `job` 전체 수와 `outcome=SERVER_ERROR` 수로 5xx 비율을 대조한 뒤 오류율 경보를 켠다. 사용자 영향·원인·EC2/RDS 자원 영역으로 대시보드를 만들고 [운영 안내의 초기 임계값](README.md#알람-설계)을 dev 알람에 적용한다. EC2 메모리·디스크는 Agent가 실제 게시하는지 확인한 뒤 경보를 활성화한다. 오류 지표의 무데이터는 정상으로 두되, 매분 게시가 확인된 Hikari 같은 **지속 게이지의 무데이터**에는 별도 수집 중단 알람을 둔다.
6. 환경별 SNS → Lambda → Discord로 **환경·알람명·상태·고정 원인** 네 필드만 보낸다. Lambda 요청의 `DiscordBot` User-Agent와 ALARM → OK 양쪽 상태 전환, 수신 시간, 중복·누락을 시험한다. Lambda 전달 실패는 동일 Discord 경로만으로 감시하지 않는다.

프론트 이미지 실패 신고는 담당 팀원의 별도 작업이다. `POST /api/v1/telemetry/image-load-failures/{timeline|detail}`에 인증·CSRF를 갖춘 본문 없는 요청이 실제 화면 실패 때 도착하고 204로 끝나는지 dev에서 확인한다. 그 뒤 `harudle_image_load_failures_total` 증가와 `image_load_failure_reported` 로그 및 필터 집계를 대조한다. 연동 전에는 `image-load-failure` 알람을 만들거나 활성화하지 않는다.

부하 시험에서는 요청별 평균·P95·P99와 5xx 비율을 부하 도구 결과로 기록하고 EC2/RDS·Hikari·API 지표와 같은 시간축에서 비교한다. 현재 Agent 설정은 HTTP `_count`만 보내므로 CloudWatch의 평균 응답 시간도 아직 없다. Agent는 Prometheus histogram을 버리므로 P95·P99를 CloudWatch에 이미 수집한다고 가정하지 않는다. 시험 후 API 유형별 목표와 충분한 표본 수를 결정하고, 평균에 필요한 `_sum`의 수집 차원·비용 및 P95·P99용 summary quantile 또는 별도 Prometheus 수집 경로를 dev에서 검증한 뒤 지연 지표와 `api-p95`·`api-p99` 알람을 추가한다.

## prod 적용과 되돌리기

dev 시험이 통과한 후 prod의 기존 로그·Agent·대시보드 구성을 기록하고 같은 순서로 별도 적용한다. prod에서는 실제 안전한 요청으로 지표와 알람 전달을 확인한다. 신규 알람이 오작동하면 먼저 알람 action을 비활성화하고, Agent 문제가 생기면 추가한 설정 조각만 제거해 기존 호스트 지표를 보존한다. 애플리케이션 문제가 생기면 CodeDeploy 이전 revision으로 되돌린다. 적용한 AWS 리소스 ID, 확인 시간, 담당자와 결과를 운영 기록에 남긴다.

이미지 전체 점검·CloudTrail S3 객체 삭제 이벤트·버전 관리는 이번 계측 범위의 후속 결정이다. 전체 점검 전에는 아무도 열어보지 않은 유실 이미지를 자동으로 발견할 수 없다는 한계를 대시보드와 장애 대응 기록에 명시한다.
