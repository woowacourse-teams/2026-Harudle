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

`returned`는 생성 실행기가 값을 반환했다는 뜻이고, `threw`는 예외를 던졌다는 뜻이다. 후자는 DB에 `FAILED`가 확정됐다는 뜻이 아니다. Gemini 지표 한 건은 SDK 내부 HTTP 재시도 세 건과 같지 않다. 현재 SDK 재시도는 내부적으로 처리되므로 실제 공급자 HTTP 요청 수를 이 지표로 계산하지 않는다. 토큰 수가 응답에 없으면 0으로 기록하지 않고 해당 지표를 내보내지 않는다. 서명 URL 생성 성공도 S3 객체의 존재나 브라우저 표시 성공을 뜻하지 않는다.

Actuator는 별도 코드 없이 HTTP 요청 상태·소요 시간, JVM·프로세스, HikariCP 풀 지표도 제공한다. DB 쿼리별 소요 시간과 브라우저의 이미지 표시 성공 여부는 이 지표에 포함되지 않는다. S3 객체가 저장 후 삭제되면 서명 URL은 계속 만들어질 수 있으므로, 저장소의 지속적인 가용성은 별도의 객체 조회 점검으로 확인해야 한다.

## Gemini 실패 분류

두 생성 단계에 동일한 `stage=storyboard_generation|image_generation` 태그를 쓴다. 공급자 응답의 HTTP 429는 `RATE_LIMIT`, 5xx는 `PROVIDER_5XX`, 시간 초과는 `TIMEOUT`이다. 스토리보드에서 JSON 파싱 실패는 `INVALID_JSON`, 스키마·도메인 검증 실패는 `SCHEMA_VIOLATION`이다. 이미지 단계에서 이미지 파트 누락과 사용할 수 없는 파트는 각각 `IMAGE_PART_MISSING`, `IMAGE_PART_INVALID`로 나눈다. 종료 사유가 `MAX_TOKENS`인 실패만 `OUTPUT_TOKEN_LIMIT`로 분류한다. 성공 응답에도 `MAX_TOKENS`가 포함될 수 있으므로 종료 사유 지표와 실패 결과를 함께 확인한다.

이미지 참조 파일과 프롬프트를 합친 inline 요청이 로컬 크기 제한을 넘으면 `INLINE_REQUEST_TOO_LARGE`다. 공급자의 일반적인 400 응답만으로 입력 토큰 한도 초과를 확정할 수 없어 `REQUEST_REJECTED`로 집계한다. 입력 한도 전용 알림은 공급자 오류의 안정적인 구조화 필드가 확인된 뒤 추가한다.

사용자 사용량 차감 정책은 다른 작업에서 바뀐다. 이 문서의 `gemini.tokens`는 공급자 사용량이며 사용자 차감 수와 합치지 않는다.

## 로그

요청의 `traceId`와 새 생성 실행의 `generationId`는 MDC에 넣고, 외부 API 실패 로그에는 안전한 고정 필드를 추가한다. 지표 태그에는 사용자 ID, 생성 ID, 일기 원문, 프롬프트, 응답 원문, S3 키 또는 서명 URL을 넣지 않는다. `generationId`는 실행이 끝나면 MDC에서 복원된다.

Spring Boot의 `CONSOLE_LOG_STRUCTURED_FORMAT=logstash`를 설정하면 MDC와 SLF4J 필드가 JSON 로그에 포함된다. 현재 운영 Compose의 `awslogs-multiline-pattern`은 타임스탬프로 시작하는 일반 로그를 가정하므로, **JSON 출력을 켜기 전에** 그 다중 행 패턴을 제거하고 dev/prod 로그 그룹을 분리해야 한다. 이 작업은 인프라 단계에서 함께 배포한다.

## Prometheus 수집 경로

`GET /actuator/prometheus`만 Actuator 웹 경로로 노출한다. 현재 Docker Compose는 backend 8080을 호스트에 publish하지 않고, 프론트 Nginx도 `/actuator`를 프록시하지 않는다. 수집자는 내부 Docker 네트워크에서 접근해야 한다. 인터넷에서 경로에 접근할 수 없는지 배포 후에도 확인한다. 이 저장소는 Prometheus 서버나 Grafana를 상시 실행하지 않는다.
