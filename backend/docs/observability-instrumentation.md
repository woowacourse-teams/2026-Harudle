# 생성 계측 코드와 운영 연결 계약

이 문서는 코드에 추가한 계측의 의미를 정의한다. 로그 수집, CloudWatch 지표 전송, 알람, Discord 전달은 별도 인프라 작업에서 연결한다.

## 지표

| 지표 | 의미 | 태그 |
|---|---|---|
| `harudle.generation.executions` | 새로 선점한 생성 실행이 반환되거나 예외를 던진 횟수 | `result=returned\|threw` |
| `harudle.generation.duration` | 새 생성 실행의 전체 소요 시간 | `result` |
| `harudle.gemini.stage.calls` | 애플리케이션이 시작한 Gemini 단계의 최종 결과 | `stage`, `outcome`, `failureType` |
| `harudle.gemini.stage.duration` | 요청 구성, SDK 재시도, 응답 처리를 포함한 단계 소요 시간 | `stage`, `outcome` |
| `harudle.gemini.tokens` | 응답에 사용량이 제공된 경우의 누적 토큰 수 | `stage`, `kind=prompt\|candidate\|thought\|total` |
| `harudle.gemini.finish.reason` | 응답 종료 사유. 예상하지 못한 값은 `OTHER`로 묶음 | `stage`, `reason` |
| `harudle.s3.operation.calls`, `harudle.s3.operation.duration` | S3 저장소 어댑터 호출 결과와 시간 | `operation`, `result` |
| `harudle.s3.url.signs`, `harudle.s3.url.sign.duration` | 서명 URL 생성 결과와 시간 | `result` |
| `harudle.image.integrity.checks` | 활성 일기의 성공 이미지에 대한 S3 HEAD 결과 | `result=present\|missing\|error\|ineligible` |
| `harudle.image.integrity.runs` | 이미지 점검 실행 결과 | `result=complete\|partial\|query_error` |
| `harudle.image.integrity.last.completed` | 마지막 전체 순회 완료 시각(Unix 초, 아직 완료한 적 없으면 0) | 없음 |
| `harudle.image.integrity.last.sweep.size`, `.missing`, `.errors` | 마지막 전체 순회에서 점검한 기록 수, 누락 수, 점검 오류 수 | 없음 |

`returned`는 생성 실행기가 값을 반환했다는 뜻이고, `threw`는 예외를 던졌다는 뜻이다. 후자는 DB에 `FAILED`가 확정됐다는 뜻이 아니다. Gemini 지표 한 건은 SDK 내부 HTTP 재시도 세 건과 같지 않다. 현재 SDK 재시도는 내부적으로 처리되므로 실제 공급자 HTTP 요청 수를 이 지표로 계산하지 않는다. 토큰 수가 응답에 없으면 0으로 기록하지 않고 해당 지표를 내보내지 않는다. 서명 URL 생성 성공도 S3 객체의 존재나 브라우저 표시 성공을 뜻하지 않는다.

Actuator는 별도 코드 없이 HTTP 요청 상태·소요 시간, JVM·프로세스, HikariCP 풀 지표도 제공한다. DB 쿼리별 소요 시간과 브라우저의 이미지 표시 성공 여부는 이 지표에 포함되지 않는다. S3 객체가 저장 후 삭제되면 서명 URL은 계속 만들어질 수 있으므로, 저장소의 지속적인 가용성은 별도의 객체 조회 점검으로 확인해야 한다.

## Gemini 실패 분류

두 생성 단계에 동일한 `stage=storyboard_generation|image_generation` 태그를 쓴다. 공급자 응답의 HTTP 429는 `RATE_LIMIT`, 5xx는 `PROVIDER_5XX`, 시간 초과는 `TIMEOUT`이다. 스토리보드에서 JSON 파싱 실패는 `INVALID_JSON`, 스키마·도메인 검증 실패는 `SCHEMA_VIOLATION`이다. 이미지 단계에서 이미지 파트 누락과 사용할 수 없는 파트는 각각 `IMAGE_PART_MISSING`, `IMAGE_PART_INVALID`로 나눈다. 종료 사유가 `MAX_TOKENS`인 실패만 `OUTPUT_TOKEN_LIMIT`로 분류한다. 성공 응답에도 `MAX_TOKENS`가 포함될 수 있으므로 종료 사유 지표와 실패 결과를 함께 확인한다.

이미지 참조 파일과 프롬프트를 합친 inline 요청이 로컬 크기 제한을 넘으면 `INLINE_REQUEST_TOO_LARGE`다. 공급자의 일반적인 400 응답만으로 입력 토큰 한도 초과를 확정할 수 없어 `REQUEST_REJECTED`로 집계한다. 입력 한도 전용 알림은 공급자 오류의 안정적인 구조화 필드가 확인된 뒤 추가한다.

사용자 사용량 차감 정책은 다른 작업에서 바뀐다. 이 문서의 `gemini.tokens`는 공급자 사용량이며 사용자 차감 수와 합치지 않는다.

## 저장 후 이미지 누락 점검

`IMAGE_INTEGRITY_ENABLED=true`로 켜면 활성 사용자·활성 일기에 연결된 `SUCCEEDED` 생성의 DB Object Key를 UUID 순서로 읽고 S3 `HEAD`로 확인한다. 실제 사용자 이미지가 삭제되는 사고를 발견하기 위한 **읽기 전용** 점검이다. S3 객체를 새로 만들거나 지우지 않는다. 기본값은 꺼짐이며, 두 환경의 S3 권한과 버킷 분리를 확인한 뒤 각각 활성화한다.

한 번 실행할 때 기본 최대 500건을 점검하며 다음 실행에서 이전 커서부터 이어간다. 커서는 프로세스 메모리에만 있으므로 재시작하면 처음부터 다시 순회한다. `IMAGE_INTEGRITY_INTERVAL`, `IMAGE_INTEGRITY_MAX_CHECKS_PER_RUN`, `IMAGE_INTEGRITY_PAGE_SIZE`, `IMAGE_INTEGRITY_MAX_RUN_DURATION`으로 속도를 조절한다. 현재 생성 중인 이미지와 커밋 직후 이미지를 피하려고 기본 5분이 지난 생성만 대상으로 삼으며 `IMAGE_INTEGRITY_MINIMUM_AGE`로 조정할 수 있다. 별도 스케줄러를 사용해 기존 생성 만료 처리 작업을 막지 않는다. S3 HEAD의 전체 요청 제한은 10초다.

HEAD 404는 누락으로 기록하기 전에 DB에서 여전히 사용자에게 보이는 같은 생성·키인지 재확인한다. 조회 중 일기가 삭제됐다면 `ineligible`로 집계한다. HEAD 403·5xx·네트워크 오류는 `error`이며 누락으로 추정하지 않는다. **S3 `ListBucket` 권한이 없으면 없는 키의 HEAD가 403으로 반환될 수 있으므로** dev/prod에서 알려진 없는 키와 있는 키를 각각 시험해야 한다. 누락 로그에는 `generationId`만 남기고 키·서명 URL은 남기지 않는다.

Discord 알람 연결 시 마지막 전체 순회의 `missing >= 1`은 즉시 확인할 장애로, `errors >= 1`과 `query_error`는 점검 자체의 실패로 구분한다. `last.completed`가 0이거나 현재 시각에서 너무 오래됐다면 점검 중단 또는 전체 순회 지연을 알린다. 허용 지연 시간은 `활성 성공 이미지 수 ÷ 실행당 점검 수 × 실행 주기`를 기준으로 정한다. 서명 URL 성공과 HEAD 성공은 브라우저가 이미지를 정상 표시했다는 보장은 아니므로 브라우저 오류 수집은 별도 단계다.

## 로그

요청의 `traceId`와 새 생성 실행의 `generationId`는 MDC에 넣고, 외부 API 실패 로그에는 안전한 고정 필드를 추가한다. 지표 태그에는 사용자 ID, 생성 ID, 일기 원문, 프롬프트, 응답 원문, S3 키 또는 서명 URL을 넣지 않는다. `generationId`는 실행이 끝나면 MDC에서 복원된다.

Spring Boot의 `CONSOLE_LOG_STRUCTURED_FORMAT=logstash`를 설정하면 MDC와 SLF4J 필드가 JSON 로그에 포함된다. 현재 운영 Compose의 `awslogs-multiline-pattern`은 타임스탬프로 시작하는 일반 로그를 가정하므로, **JSON 출력을 켜기 전에** 그 다중 행 패턴을 제거하고 dev/prod 로그 그룹을 분리해야 한다. 이 작업은 인프라 단계에서 함께 배포한다.

## Prometheus 수집 경로

`GET /actuator/prometheus`만 Actuator 웹 경로로 노출한다. 현재 Docker Compose는 backend 8080을 호스트에 publish하지 않고, 프론트 Nginx도 `/actuator`를 프록시하지 않는다. 수집자는 내부 Docker 네트워크에서 접근해야 한다. 인터넷에서 경로에 접근할 수 없는지 배포 후에도 확인한다. 이 저장소는 Prometheus 서버나 Grafana를 상시 실행하지 않는다.
