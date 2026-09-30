# 생성 계측 코드와 운영 연결 계약

이 문서는 코드에 추가한 계측의 의미를 정의한다. dev/prod 로그·지표 수집 설정은 `deploy/monitoring`과 Compose에 준비했다. AWS 로그 그룹·Agent·알람·Discord 전달은 배포 전후 운영 연결이 필요하다.

## 지표

| 지표 | 의미 | 태그 |
|---|---|---|
| `harudle.generation.executions` | 새로 선점한 생성 실행이 반환되거나 예외를 던진 횟수 | `result=returned\|threw` |
| `harudle.generation.duration` | 새 생성 실행의 전체 소요 시간 | `result` |
| `harudle.generation.finalizations` | DB에 확정한 생성 결과 | `status=SUCCEEDED\|FAILED`, 고정 `errorCode` 또는 `none` |
| `harudle.generation.unexpected.failures` | 예상하지 못한 생성 내부 오류의 단계 | `phase=storyboard\|reference_load\|image_generation\|image_store\|completion` |
| `harudle.gemini.stage.calls` | 애플리케이션이 시작한 Gemini 단계의 최종 결과 | `stage`, `outcome`, `failureType` |
| `harudle.gemini.stage.duration` | 요청 구성, SDK 재시도, 응답 처리를 포함한 단계 소요 시간 | `stage`, `outcome` |
| `harudle.gemini.tokens` | 응답에 사용량이 제공된 경우의 누적 토큰 수 | `stage`, `kind=prompt\|candidate\|thought\|total` |
| `harudle.gemini.finish.reason` | 응답 종료 사유. 예상하지 못한 값은 `OTHER`로 묶음 | `stage`, `reason` |
| `harudle.s3.operation.calls`, `harudle.s3.operation.duration` | S3 저장소 어댑터 호출 결과와 시간 | `operation`, `result`, 고정 `failureType` |
| `harudle.s3.url.signs`, `harudle.s3.url.sign.duration` | 서명 URL 생성 결과와 시간 | `result`, 고정 `failureType` |
| `harudle.image.load.failures` | 로그인 사용자 화면의 이미지 실패 신고를 백엔드가 받은 횟수. 프론트 신고 코드는 담당 팀원 작업 | `surface=timeline\|detail` |

`returned`는 생성 실행기가 값을 반환했다는 뜻이고, `threw`는 예외를 던졌다는 뜻이다. 어느 쪽도 DB 최종 상태를 뜻하지 않으므로 최종 성공·실패는 `generation.finalizations`로 본다. 생성 만료 처리는 `status=FAILED`, `errorCode=GENERATION_INTERRUPTED`다. Gemini 지표 한 건은 SDK 내부 HTTP 재시도 세 건과 같지 않다. 현재 SDK 재시도는 내부적으로 처리되므로 실제 공급자 HTTP 요청 수를 이 지표로 계산하지 않는다. 토큰 수가 응답에 없으면 0으로 기록하지 않고 해당 지표를 내보내지 않는다. 서명 URL 생성 성공도 S3 객체의 존재나 브라우저 표시 성공을 뜻하지 않는다.

`generation.finalizations`는 `PROCESSING`에서 최종 상태로 실제 전환한 트랜잭션이 커밋된 뒤에만 증가한다. 재처리·롤백은 중복 집계하지 않는다. 예상 밖 생성 오류는 고정된 `phase`로 집계하고, 로그에는 `generationId`·예외 종류·정제된 스택만 남긴다. 생성 만료 스케줄러는 실행마다 후보·중단 건수를 `generation_cleanup_run`으로, 실행 실패를 `generation_cleanup_failed`로 남긴다.

최종 상태·예상 밖 오류·이미지 표시 실패 카운터의 가능한 태그 조합은 시작 시 0으로 등록한다. CloudWatch Agent의 첫 수집이 끝난 다음 발생하는 첫 건부터 증가분을 세기 위한 준비다. 시작 직후 첫 수집 전의 사건은 증가분에서 빠질 수 있다. S3·Gemini처럼 실패할 때 처음 만들어지는 시계열의 단일 실패 경보는 구조화 `external_api_failure` 로그 지표 필터를 우선 사용하고, 실제 AWS 적용·검증 절차는 `deploy/monitoring/README.md`를 따른다.

Actuator는 별도 코드 없이 HTTP 요청 상태·소요 시간, JVM·프로세스, HikariCP 풀 지표도 제공한다. DB 쿼리별 소요 시간은 이 지표에 포함되지 않는다. S3 `get_object`는 서버의 참조 이미지 조회이며, 완성 이미지의 브라우저 GET을 세지 않는다. 이미지 로드 실패 신고는 로그인 사용자 타임라인·상세 화면만 대상으로 설계했다. 네트워크 단절·URL 만료일 수도 있어 S3 객체 누락으로 단정할 수 없고, 열어보지 않은 이미지와 게스트·공유 화면은 관측하지 못한다.

이번 PR은 `POST /api/v1/telemetry/image-load-failures/timeline`과 `/detail`의 인증된 신고 API, 본문 없는 요청과 204 응답, 고정 `surface` 지표만 제공한다. 프론트 신고 코드는 담당 팀원이 별도 반영한다. 실제 프론트 코드가 두 경로와 인증·CSRF 정책에 맞게 호출하는지 dev에서 확인하기 전에는 이 지표의 0을 이미지 정상으로 해석하거나 관련 알람을 활성화하지 않는다.

## Gemini 실패 분류

두 생성 단계에 동일한 `stage=storyboard_generation|image_generation` 태그를 쓴다. 공급자 응답의 HTTP 429는 `RATE_LIMIT`, 5xx는 `PROVIDER_5XX`, 시간 초과는 `TIMEOUT`이다. 스토리보드에서 JSON 파싱 실패는 `INVALID_JSON`, 스키마·도메인 검증 실패는 `SCHEMA_VIOLATION`이다. 이미지 단계에서 이미지 파트 누락과 사용할 수 없는 파트는 각각 `IMAGE_PART_MISSING`, `IMAGE_PART_INVALID`로 나눈다. 종료 사유가 `MAX_TOKENS`인 실패만 `OUTPUT_TOKEN_LIMIT`로 분류한다. 성공 응답에도 `MAX_TOKENS`가 포함될 수 있으므로 종료 사유 지표와 실패 결과를 함께 확인한다.

이미지 참조 파일과 프롬프트를 합친 inline 요청이 로컬 크기 제한을 넘으면 `INLINE_REQUEST_TOO_LARGE`다. 공급자의 일반적인 400 응답만으로 입력 토큰 한도 초과를 확정할 수 없어 `REQUEST_REJECTED`로 집계한다. 입력 한도 전용 알림은 공급자 오류의 안정적인 구조화 필드가 확인된 뒤 추가한다.

사용자 사용량 차감 정책은 다른 작업에서 바뀐다. 이 문서의 `gemini.tokens`는 공급자 사용량이며 사용자 차감 수와 합치지 않는다.

## 후속: 저장 후 이미지 누락 점검

DB가 참조하는 모든 완성 이미지를 S3 HEAD로 주기적으로 확인하는 점검과 관련 지표·알람은 후속 작업으로 미룬다. 화면 로드 실패 이벤트는 로그인 사용자가 실제로 열어본 이미지에만 반응하므로 아무도 열어보지 않은 기존 이미지의 삭제를 자동 감지할 수 없다. 현재 공유 버킷의 dev/prod 저장 prefix 분리는 다른 팀원 작업이며, 교차 prefix 삭제 권한을 제한할 수 있는지도 확인해야 한다. S3 객체 수준 CloudTrail `DeleteObject` 데이터 이벤트는 기본 수집되지 않으므로 삭제 주체 추적이 필요하면 별도로 활성화하고 비용·보존 기간을 결정한다.

## 로그

요청의 `traceId`와 새 생성 실행의 `generationId`는 MDC에 넣고, 외부 API 실패 로그에는 안전한 고정 필드를 추가한다. 지표 태그에는 사용자 ID, 생성 ID, 일기 원문, 프롬프트, 응답 원문, S3 키 또는 서명 URL을 넣지 않는다. `generationId`는 실행이 끝나면 MDC에서 복원된다.

완료되지 못한 생성 이미지의 삭제는 `discarded_image_deleted`, 실패는 `discarded_image_delete_failed`, 안전 여부를 판단하지 못해 보류한 경우는 `discarded_image_delete_deferred` 이벤트로 기록한다. 삭제 사유는 코드에서 정한 고정 값이고 `generationId`만 연결한다. 객체 키는 로그와 S3 예외 메시지에 남기지 않는다. 예상하지 못한 API 오류는 `api_exception` 이벤트에 상태 코드, 오류 코드, HTTP 메서드, 라우트 패턴을 구조화 필드로 남긴다. 라우트 패턴이 없으면 원본 URI 대신 `UNMATCHED`를 사용한다.

Compose는 Spring Boot의 `CONSOLE_LOG_STRUCTURED_FORMAT=logstash`를 설정해 MDC와 SLF4J 필드를 한 줄 JSON 로그로 출력한다. JSON과 충돌하는 기존 운영 `awslogs-multiline-pattern`은 같은 변경에서 제거하고, dev/prod 로그 그룹을 분리했다. dev 그룹과 권한은 dev 배포 **전에** 준비해야 한다.

## Prometheus 수집 경로

`GET /actuator/prometheus`만 Actuator 웹 경로로 노출한다. 배포 시 관리 포트 8081을 애플리케이션 포트 8080과 분리해 호스트 `127.0.0.1:19091`에만 publish한다. 프론트 Nginx는 `/actuator`를 프록시하지 않는다. CloudWatch Agent가 호스트 루프백에서 1분마다 수집하며, 인터넷에서 19091에 접근할 수 없는지 배포 후 확인한다. 이 저장소는 Prometheus 서버나 Grafana를 상시 실행하지 않는다. 설정·IAM 선행 조건·알람 기준은 `deploy/monitoring/README.md`를 따른다.
