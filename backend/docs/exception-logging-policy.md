# 예외 및 외부 연동 실패 로깅 정책

## 목적

이 문서는 백엔드에서 발생하는 예외와 외부 연동 실패를 어떤 위치에서 어떤 수준으로 기록할지 정의한다. 운영 환경의 Backend
컨테이너는 Docker Compose의 `awslogs` 드라이버를 통해 표준 출력과 표준 오류를 CloudWatch Logs로 전송한다. 따라서 기록할 필요가
없는 정상적인 실패를 줄이고, 운영 대응이 필요한 실패에는 검색 가능한 공통 필드를 제공해야 한다.

클라이언트 오류 응답과 로그는 같은 `traceId`로 연결하되 토큰, 요청 본문과 외부 공급자 원문 같은 민감정보는 로그에 포함하지 않는다.

## 기본 원칙

1. HTTP 상태가 아니라 운영 대응 필요성을 기준으로 로그 수준을 결정한다.
2. 하나의 실패는 원인을 가장 잘 이해하는 경계에서 한 번만 기록한다.
3. 예외를 동일한 의미로 다시 던지는 중간 계층은 로그를 남기지 않는다.
4. 예외를 삼키고 처리를 계속하는 계층은 해당 위치에서 실패를 기록한다.
5. 외부 연동 성공과 정상적인 4xx 응답은 로그 대신 필요할 때 메트릭으로 집계한다.
6. 로그 메시지 문구보다 `event`, `errorCode`, `provider`, `operation` 같은 안정적인 필드로 검색한다.
7. 예외 클래스와 API 응답의 `ErrorType`에는 로그 수준을 포함하지 않는다. 같은 오류 코드도 발생 맥락에 따라 심각도가 다를 수 있다.

## 로그 수준

### INFO

운영 장애는 아니지만 사용자 흐름이나 상태 전환을 확인할 가치가 있는 사건을 기록한다. 반복 빈도가 높은 일반 API 인증 실패에는
사용하지 않는다.

- 사용자가 OAuth 공급자 동의를 거부한 경우
- 비활성 사용자 또는 필수 프로필 누락으로 OAuth 로그인이 거부된 경우

스택 트레이스는 기록하지 않는다.

### WARN

요청은 실패했지만 서비스 전체가 동작할 수 있고 재시도 또는 비율 기반 관찰이 필요한 사건을 기록한다.

- Gemini 요청 실패와 타임아웃
- S3 조회, 저장, 삭제와 Presigned URL 발급 실패
- 예상하지 못한 OAuth 공급자 인증 실패
- CSRF 실패처럼 보안상 관찰할 가치가 있는 사건
- 이미지 정리와 같은 보상 작업 실패

외부 연동 및 보상 작업 실패에는 원인 예외를 포함한다. 사용자 행동으로 예상 가능한 인증 실패에는 스택 트레이스를 포함하지 않는다.

### ERROR

코드 결함, 내부 불변식 위반, 필수 설정 누락처럼 즉시 원인 확인이 필요한 사건을 기록한다.

- 처리되지 않은 `RuntimeException`
- Spring MVC가 반환하는 5xx 오류
- 생성 어댑터 또는 프롬프트 필수 설정 누락
- OAuth 내부 데이터 불일치
- OAuth 성공 응답의 Cookie, CSRF 또는 Redirect 작성 실패
- S3 자격 증명, 권한 또는 Bucket 설정 오류

원인 예외와 스택 트레이스를 항상 포함한다.

### 기록하지 않는 예외

다음 예외는 서비스가 정의한 정상적인 거절 또는 조회 결과이므로 개별 요청 로그를 남기지 않는다.

- 요청 본문, 파라미터와 헤더 검증 오류
- 일반적인 401 인증 실패와 Refresh Token 만료
- 일반적인 403 접근 거부
- 일기와 공유 링크 조회 실패
- 생성 진행 중, 멱등성 키 충돌과 게스트 체험 소진
- 일일 생성 사용량 초과
- 저장된 생성 실패 상태를 다시 반환하는 `DiaryGenerationFailedException`

반복적인 인증 실패나 4xx 증가를 탐지해야 할 때는 사용자 입력을 로그에 남기지 않고 오류 코드 기반의 저카디널리티 메트릭을 사용한다.

## 로그 소유권

| 실패 경계 | 로그 소유자 | 책임 |
|---|---|---|
| MVC에서 처리되지 않은 내부 오류 | `GlobalExceptionHandler` | `ERROR` 기록과 500 응답 변환 |
| Spring MVC 5xx | `GlobalExceptionHandler` | `ERROR` 기록과 프레임워크 응답 보존 |
| OAuth 인증 및 로그인 흐름 | OAuth 성공·실패 핸들러 | 실패 사유 분류와 수준 결정 |
| Gemini 호출과 응답 변환 | Gemini 출력 어댑터 | 공급자, 작업과 실패 유형 기록 |
| S3 호출과 응답 검증 | S3 출력 어댑터 | 작업과 AWS 오류 정보 기록 |
| 예외를 삼키는 보상·정리 작업 | 예외를 잡은 서비스 또는 어댑터 | 별도 보상 실패 이벤트 기록 |

원본 예외를 애플리케이션 예외로 번역하는 지점을 외부 연동 로그의 기본 소유자로 삼는다. 번역된 `AiGenerationException`이나
`ImageStorageException`을 상위 계층이 그대로 다시 던질 때는 추가로 기록하지 않는다. `GlobalExceptionHandler`는 이미 기록된 외부
연동 예외의 응답만 생성한다.

서비스 내부의 처리 오류를 새 `AiGenerationException`으로 변환하는 경우에는 해당 변환 지점이 로그 소유자가 된다. 최초 실패가 아닌
저장된 실패 결과를 조회한 경우에는 중복 로그를 만들지 않는다.

## 공통 로그 필드

모든 예외 로그는 가능한 범위에서 다음 필드를 사용한다.

| 필드 | 설명 |
|---|---|
| `event` | 검색과 집계에 사용하는 안정적인 이벤트 이름 |
| `errorCode` | API 또는 애플리케이션 오류 코드 |
| `exceptionType` | 원인 파악을 위한 예외 클래스의 단순 이름 |
| `traceId` | MDC에서 출력하는 요청 추적 ID |
| `httpStatus` | 최종 HTTP 상태가 존재할 때 기록 |
| `method` | HTTP 요청 메서드가 존재할 때 기록 |
| `path` | Query String을 제외한 라우트 패턴 또는 요청 경로 |

외부 연동 로그에는 다음 필드를 추가한다.

| 필드 | 설명 |
|---|---|
| `provider` | `gemini`, `s3` 등 외부 시스템 |
| `operation` | `storyboard_generation`, `put_object` 등 실패 작업 |
| `failureType` | 타임아웃, 공급자 오류, 응답 검증 오류 등 분류 |
| `providerStatus` | 외부 공급자가 제공한 안전한 상태 코드 또는 상태 이름 |
| `providerRequestId` | 공급자가 제공하고 노출해도 안전한 요청 식별자 |

업무 식별자가 필요하면 `generationId`처럼 서버가 생성한 값만 사용한다. 같은 값이 메트릭 태그로 사용되어 카디널리티를 높이지 않도록
로그 필드와 메트릭 태그를 구분한다.

## 이벤트 이름

이벤트 이름은 메시지 문구와 독립적으로 다음 값을 사용한다.

| 이벤트 | 용도 |
|---|---|
| `api_exception` | 예상하지 못한 MVC 및 서버 내부 오류 |
| `oauth_login_rejected` | 예상 가능한 OAuth 로그인 거절 |
| `oauth_login_failure` | 공급자 또는 내부 OAuth 로그인 실패 |
| `external_api_failure` | Gemini와 S3 외부 연동 실패 |
| `compensation_failure` | 이미지 삭제 등 보상 작업 실패 |

## 공급자별 기준

### OAuth

- `PROVIDER_ACCESS_DENIED`, `INACTIVE_USER`, `REQUIRED_PROFILE_MISSING`는 `INFO`로 기록한다.
- `PROVIDER_AUTHENTICATION_FAILED`는 `WARN`으로 기록한다.
- `UNSUPPORTED_PROVIDER`, `INTERNAL_CONSISTENCY_ERROR`, `INTERNAL_ERROR`는 `ERROR`로 기록한다.
- 인가 코드, Access Token, Refresh Token, OAuth 프로필과 이메일은 기록하지 않는다.

### Gemini

- 스토리보드 생성과 이미지 생성을 서로 다른 `operation`으로 기록한다.
- 공급자 오류와 타임아웃은 `WARN`으로 기록한다.
- 애플리케이션의 응답 매핑 및 내부 불변식 오류는 `ERROR`로 기록한다.
- 모델 이름과 공급자 상태 코드는 기록할 수 있다.
- 일기 본문, 프롬프트, Gemini 원본 응답과 이미지 데이터는 기록하지 않는다.

### S3

- 조회, 저장, 삭제와 Presigned URL 발급을 서로 다른 `operation`으로 기록한다.
- 일시적인 AWS 서비스 실패는 `WARN`으로 기록한다.
- 자격 증명, 권한과 Bucket 설정 오류는 `ERROR`로 기록한다.
- 저장 실패 후 삭제까지 실패하면 주 실패에 원인 예외를 보존하고 `compensation_failure`의 소유권을 한 곳으로 제한한다.
- 자격 증명, Presigned URL, Bucket 이름과 전체 Object Key는 기록하지 않는다. 필요하면 `generationId` 또는 안전한 Object Key 해시를
  사용한다.

## 민감정보 및 고카디널리티 데이터

다음 값은 로그 메시지, 구조화 필드와 예외 메시지에 포함하지 않는다.

- `Authorization` 헤더
- Access Token, Refresh Token과 Guest Session Token
- Cookie와 CSRF Token 값
- OAuth 인가 코드, 사용자 프로필과 이메일
- 요청 및 응답 전체 Body와 Query String
- 일기 본문과 AI Prompt
- 이미지 Binary와 Base64 데이터
- Gemini 원본 응답
- S3 자격 증명, Presigned URL과 전체 Object Key

외부 SDK 예외 메시지에 위 값이 포함될 가능성이 있으면 메시지를 직접 출력하지 않고 허용된 상태 코드와 예외 유형만 기록한다.

## Trace ID와 CloudWatch

`TraceIdFilter`가 요청마다 생성한 값을 MDC의 `traceId`에 넣고 `ProblemDetailFactory`가 같은 값을 오류 응답에 사용한다. 로그 출력
설정은 MDC의 `traceId`를 포함해야 한다. 요청 처리가 끝나면 기존과 같이 MDC 값을 제거한다.

운영 애플리케이션의 기본 로그 수준은 `INFO`로 두며 `DEBUG`와 `TRACE`는 출력하지 않는다. `awslogs` 드라이버는 로그 수준을 별도로
판단하지 않고 Backend 컨테이너의 표준 출력과 표준 오류를 CloudWatch Logs로 전송한다. 로그 전송 여부와 CloudWatch 대시보드 노출
여부는 구분한다. 대시보드는 메시지의 `Exception` 문자열이 아니라 이 문서의 `event`, `provider`, `operation`, `errorCode` 기준으로
조회하도록 별도 작업에서 변경한다.

외부 호출 성공을 요청마다 `INFO`로 기록하지 않는다. 성공률, 지연 시간과 오류율은 별도의 저카디널리티 메트릭으로 관리한다.

## 검증 기준

- 각 실패 경계의 테스트가 기대하는 로그 수준을 검증한다.
- `WARN` 및 `ERROR` 로그에 정의된 공통 필드가 포함되는지 검증한다.
- 요청 로그와 Problem Details의 `traceId`가 동일한지 검증한다.
- 예상된 4xx 예외가 운영 로그를 생성하지 않는지 검증한다.
- 번역된 외부 연동 예외가 두 계층에서 중복 기록되지 않는지 검증한다.
- 토큰, Cookie, 요청 본문, Prompt와 외부 공급자 원문이 출력되지 않는지 검증한다.
- 외부 연동과 내부 오류의 스택 트레이스 정책이 지켜지는지 검증한다.

## 후속 작업

1. MDC `traceId`와 공통 필드를 출력하는 로그 형식을 구성한다.
2. `GlobalExceptionHandler`의 내부 오류 로그를 공통 형식으로 변경한다.
3. OAuth 성공·실패 핸들러의 로그 수준과 필드를 이 정책에 맞춘다.
4. Gemini와 S3 출력 어댑터에 외부 연동 실패 로그를 추가한다.
5. CloudWatch 대시보드의 로그 쿼리와 로그 그룹 보존 기간을 정비한다.
6. 예외 로그 수준, 중복과 민감정보를 검증하는 테스트를 추가한다.
