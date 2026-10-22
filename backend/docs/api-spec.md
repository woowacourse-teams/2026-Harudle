# 하루들 API 명세

## 1. 문서 개요

이 문서는 사용자가 작성한 일기를 AI가 하나의 4컷 이미지로 생성하고, 결과를 히스토리에 저장·조회·공유하는
MVP의 HTTP API 명세입니다.

피드 게시·공개 목록/상세·삭제 및 개인 일기 연동의 추가 명세는 [피드 API 명세](feed-api-spec.md)를 참고합니다.

현재 버전은 다음 조건을 전제로 합니다.

- Kakao OAuth2 로그인 지원
- 생성 API는 일기와 대기 작업을 저장한 뒤 반환하며, 이미지는 서버의 작업 처리기가 생성
- 생성 접수 완료와 이미지 생성 완료를 구분하며, 프론트엔드 연결은 별도 작업
- 생성 ID로 상태를 조회하는 API를 제공하며 SQS는 사용하지 않음. 새 피드의 푸시 예약은 피드 명세의 Outbox 계약을 따름
- 하나의 일기당 하나의 4컷 이미지 생성
- 사용자당 KST 기준 하루 최대 3회 생성
- 동일한 일기 텍스트도 다시 생성할 수 있으며 각 요청은 일일 생성 횟수에 포함
- 완성 이미지는 S3에 저장하고 API는 접근 가능한 임시 URL 반환
- 오류 응답은 RFC 9457 Problem Details 형식 사용

## 2. 공통 규칙

### 2.1 Base URL

```text
/api/v1
```

OAuth2 인증 시작 및 콜백 경로는 Spring Security 기본 경로를 사용하므로 `/api/v1` 외부에 위치합니다.

### 2.2 인증

인증이 필요한 API는 서비스 Access Token을 사용합니다.

```http
Authorization: Bearer {accessToken}
```

- Access Token은 FE 메모리에 보관합니다.
- 원본 Refresh Token은 Secure, HttpOnly Cookie에 보관합니다.
- 서버는 Refresh Token의 해시만 저장합니다.
- OAuth Provider 토큰과 서비스 Access/Refresh Token은 구분합니다.
- 로그아웃은 만료되지 않은 Access Token을 요구하지 않으며 Refresh Token Cookie로 현재 세션을 식별합니다.
- Bearer 인증 API의 POST·PUT·PATCH·DELETE에는 CSRF Cookie/Header를 요구하지 않습니다. 기존에 보내던 CSRF 값이 있어도 Bearer 요청에는 영향을 주지 않습니다.
- 쿠키를 사용하는 `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout`, `POST /api/v1/guest/session`, `POST /api/v1/guest/diaries`는 `XSRF-TOKEN` Cookie와 같은 값의 `X-XSRF-TOKEN` Header가 필요합니다. Bearer Header를 함께 보내도 CSRF 검증을 생략하지 않습니다.
- 새 쿠키 기반 변경 API를 추가하면 `CookieApiCsrfProtectionMatcher`의 대상 경로에도 등록합니다. 이 경로 목록을 보안 필터와 OpenAPI가 함께 사용합니다.
- 소유권 확인과 `/api/v1/admin/**`의 ADMIN 권한 검증은 서버에서 수행합니다.

### 2.3 Content Type

정상 JSON 요청과 응답:

```http
Content-Type: application/json
```

오류 응답:

```http
Content-Type: application/problem+json
```

### 2.4 날짜와 시간

- 날짜는 `YYYY-MM-DD` 형식을 사용합니다.
- 시간은 타임존이 포함된 ISO-8601 형식을 사용합니다.
- 일일 생성 횟수는 `Asia/Seoul` 기준 요청 날짜로 계산합니다.
- `diaryDate`가 과거여도 사용량은 실제 API 요청 날짜에 차감됩니다.

### 2.5 생성 요청 멱등성

일기 생성 요청에는 클라이언트가 생성한 UUID를 전달합니다.

```http
Idempotency-Key: 7e5cc251-fdde-4cc0-a54e-2c8142750609
```

처리 규칙:

- 서버는 `v1\n{userId}\n{diaryDate}\n{sourceText}` UTF-8 바이트를 SHA-256으로 해시한 소문자 64자리 요청 지문을
  저장합니다.
- 멱등성 키는 사용량을 차감하기 전에 원자적으로 선점하거나 기존 기록을 조회합니다.
- 같은 키와 같은 요청이 `SUCCEEDED` 상태라면 AI를 재호출하지 않고 기존 결과를 반환합니다.
- 같은 키와 같은 요청이 `PENDING` 또는 유효한 `PROCESSING` 상태라면 `200`과 저장된 작업 상태를 반환합니다.
- 같은 키와 같은 요청이 `FAILED` 상태라면 AI를 재호출하거나 사용량을 다시 차감하지 않고 저장된 실패를 반환합니다.
- 같은 키를 다른 요청 본문에 사용하면 `409 IDEMPOTENCY_KEY_CONFLICT`를 반환합니다.
- 실패한 생성을 다시 시도하려면 새로운 `Idempotency-Key`를 사용해야 하며 새 요청은 일일 생성 횟수에 포함됩니다.
- 다른 키로 같은 일기 텍스트를 요청하는 것은 허용하며 일일 생성 횟수를 새로 차감합니다.
- 사용량은 새 멱등성 키를 선점한 요청에만 원자적으로 차감합니다.

`status`가 `PROCESSING`이고 `updated_at`이 현재 시각에서 서버 설정의 처리 제한 시간을 뺀 시각보다 이전이면 고아
작업으로 판단해 `FAILED`와 `GENERATION_INTERRUPTED`로 원자적으로 전환합니다. 이후 같은 키의 요청에는 저장된 실패를
반환합니다.

### 2.6 이미지 URL

- DB의 S3 Object Key는 외부에 노출하지 않습니다.
- 인증된 일기 조회 및 공개 피드 조회 시 만료 시간이 있는 Presigned URL을 반환합니다.
- 안정적인 공유 주소와 만료되는 실제 이미지 URL은 구분합니다.

## 3. API 목록

### 3.1 인증 및 사용자

| Method | Endpoint | 인증 | 설명 |
| --- | --- | ---: | --- |
| `GET` | `/oauth2/authorization/kakao` | 불필요 | Kakao OAuth 로그인 시작 |
| `GET` | `/login/oauth2/code/kakao` | 불필요 | Kakao OAuth 콜백 |
| `GET` | `/api/v1/auth/csrf` | 불필요 | Cookie 기반 요청에 사용할 CSRF Token 발급 |
| `POST` | `/api/v1/auth/refresh` | Refresh Token | Access Token 발급·재발급 |
| `POST` | `/api/v1/auth/logout` | 불필요 | Refresh Token Cookie를 이용한 현재 로그인 세션 종료 |
| `POST` | `/api/v1/guest/session` | 불필요 | 로그인 전 체험용 게스트 세션 발급 또는 재사용 |
| `GET` | `/api/v1/me` | 필요 | 내 프로필 조회 |

### 3.2 일기 및 생성

| Method | Endpoint | 인증 | 설명 |
| --- | --- | ---: | --- |
| `POST` | `/api/v1/diaries` | 필요 | 일기 작성 및 4컷 이미지 생성 접수 |
| `POST` | `/api/v1/guest/diaries` | 게스트 세션 | 로그인 전 1회 일기 작성 및 4컷 이미지 생성 접수 |
| `GET` | `/api/v1/guest/diaries/{diaryId}` | 게스트 세션 | 게스트 세션에 연결된 일기 생성 결과 조회 |
| `GET` | `/api/v1/diary-generations/{generationId}` | 필요 | 본인의 생성 작업 상태 조회 |
| `GET` | `/api/v1/guest/diary-generations/{generationId}` | 게스트 세션 | 현재 게스트 세션의 생성 작업 상태 조회 |
| `GET` | `/api/v1/me/generation-usage` | 필요 | 오늘 생성 사용량 조회 |
| `GET` | `/api/v1/diaries` | 필요 | 연·월 기준 내 일기 조회 |
| `GET` | `/api/v1/diaries/{diaryId}` | 필요 | 일기·생성 결과 및 연결된 활성 피드 ID 상세 조회 |
| `DELETE` | `/api/v1/diaries/{diaryId}` | 필요 | 일기 삭제 |

### 3.3 피드 게시 및 공유

| Method | Endpoint | 인증 | 설명 |
| --- | --- | ---: | --- |
| `POST` | `/api/v1/feeds` | 필요 | 기존 일기의 만화를 카테고리에 공개 게시 |
| `GET` | `/api/v1/feeds` | 선택 | 최신순·인기순, 카테고리 필터와 커서로 공개 목록 조회 |
| `GET` | `/api/v1/feeds/{feedId}` | 선택 | 공개 피드 상세 조회 |
| `DELETE` | `/api/v1/feeds/{feedId}` | 필요 | 본인 피드 소프트 삭제 및 미발송 푸시 취소 |

### 3.4 카테고리

| Method | Endpoint | 인증 | 설명 |
| --- | --- | ---: | --- |
| `GET` | `/api/v1/categories` | 불필요 | 활성 카테고리 목록 |
| `GET` | `/api/v1/admin/categories` | ADMIN | 활성·비활성 전체 카테고리 목록 |
| `POST` | `/api/v1/admin/categories` | ADMIN | 카테고리 추가 |
| `PATCH` | `/api/v1/admin/categories/{categoryId}` | ADMIN | 카테고리 수정과 비활성화 |

두 목록 조회 API는 `sortOrder` 오름차순, 같은 순서에서는 `id` 오름차순으로 정렬한 JSON 배열을 반환한다.
`id`는 JSON 숫자이며, 항목이 없으면 `[]`를 반환한다.

```json
[
  { "id": 1, "name": "일상", "sortOrder": 0, "active": true },
  { "id": 2, "name": "우테코", "sortOrder": 1, "active": true }
]
```

관리자 목록은 비로그인 요청에 `401`, 일반 회원 요청에 `403`을 반환한다.

카테고리 추가 요청은 다음과 같다. 관리자 Bearer 인증이 필요하며 CSRF 토큰은 요구하지 않는다.

```json
{ "name": "여행", "sortOrder": 2 }
```

- 이름은 앞뒤 공백 제거와 NFC 정규화 후 1~20자이며 영문 대소문자는 구분한다.
- `sortOrder`는 0 이상의 정수다. 생략하면 활성과 비활성 전체 항목의 최대 순서에 1을 더한다. 항목이 없으면 0이다.
- 새 항목은 `active=true`로 저장하고 `201`과 생성된 Category 객체를 반환한다.
- 비활성 항목과도 이름이 중복되면 `409 CATEGORY_NAME_ALREADY_USED`, 잘못된 입력은 `400 VALIDATION_ERROR`다.

수정은 관리자 Bearer 인증으로 호출하며 CSRF 토큰을 요구하지 않는다.

```json
{ "name": "우테코 생활", "sortOrder": 2, "active": false }
```

- 보낸 항목만 수정하고 `200`과 수정된 Category 객체를 반환한다. 이름과 순서 검증은 생성과 같다.
- 빈 요청과 명시적인 `null`은 `400 VALIDATION_ERROR`, 없는 ID는 `404 CATEGORY_NOT_FOUND`, 이름 중복은 `409 CATEGORY_NAME_ALREADY_USED`다.
- 비활성화 후에도 기존 피드의 카테고리 조회는 유지되며 새 게시만 거부한다. `active=true`로 다시 활성화할 수 있다.
- 수정은 카테고리 행을 잠그고 같은 행의 게시 검증과 순서대로 처리한다. 삭제 API는 제공하지 않는다.

## 4. 인증 및 사용자 API

### 4.1 Kakao OAuth 로그인 시작

```http
GET /oauth2/authorization/kakao
```

사용자를 Kakao 인증 화면으로 리다이렉트합니다.

### 4.2 Kakao OAuth 콜백

```http
GET /login/oauth2/code/kakao?code={authorizationCode}&state={state}
```

Spring Security가 콜백을 처리합니다.

1. Kakao 사용자 식별자를 확인합니다.
2. 서비스 사용자와 OAuth 계정을 생성하거나 조회합니다.
3. Refresh Token을 Secure, HttpOnly Cookie로 설정합니다.
4. FE 로그인 완료 페이지로 리다이렉트합니다.
5. FE는 Access Token 발급 API를 호출합니다.

이메일만 같다는 이유로 서로 다른 OAuth 계정을 자동으로 병합하지 않습니다.

### 4.3 Access Token 발급·재발급

```http
POST /api/v1/auth/refresh
```

브라우저가 Refresh Token Cookie를 자동으로 전송합니다.

```http
HTTP/1.1 200 OK
Cache-Control: no-store
```

```json
{
  "accessToken": "eyJhbGciOi...",
  "tokenType": "Bearer",
  "expiresIn": 1800
}
```

### 4.4 로그아웃

```http
POST /api/v1/auth/logout
Cookie: refresh_token={refreshToken}
```

만료되지 않은 Access Token은 요구하지 않습니다. Refresh Token Cookie가 있으면 서버에 저장된 토큰 해시를 찾아 현재
세션을 폐기하고, Cookie는 설정할 때와 동일한 속성으로 삭제합니다. Cookie가 없거나 토큰이 만료·폐기·위조된 경우에도
Cookie를 삭제하고 동일하게 성공 처리합니다.

```http
HTTP/1.1 204 No Content
```

### 4.5 내 프로필 조회

```http
GET /api/v1/me
Authorization: Bearer {accessToken}
```

```http
HTTP/1.1 200 OK
```

```json
{
  "id": "08d69a34-6d70-4d42-a158-671bc67733c9",
  "name": "하루들",
  "email": "harudle.official@gmail.com",
  "oauthProvider": "KAKAO",
  "role": "USER",
  "createdAt": "2026-08-06T10:30:00+09:00"
}
```

### 4.6 게스트 세션 발급

먼저 CSRF Token을 발급받습니다.

```http
GET /api/v1/auth/csrf
```

응답 본문의 Token을 `X-XSRF-TOKEN` Header에 넣고, CSRF Cookie가 함께 전송되도록 요청합니다.

```http
POST /api/v1/guest/session
Cookie: XSRF-TOKEN={csrfToken}
X-XSRF-TOKEN: {csrfToken}
```

- Access Token은 요구하지 않습니다.
- 유효한 `guest_session` Cookie가 있으면 기존 게스트 세션을 재사용합니다.
- Cookie가 없거나 저장된 세션과 일치하지 않거나 만료되었으면 새 게스트 세션을 발급합니다.
- 원문 게스트 Token은 HttpOnly Cookie로만 전달하고 DB에는 SHA-256 해시만 저장합니다.
- 운영 환경에서는 `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/api/v1/guest` 속성을 사용합니다.
- CSRF Header가 없거나 Cookie의 CSRF Token과 일치하지 않으면 `403 Forbidden`을 반환합니다.

```http
HTTP/1.1 204 No Content
Cache-Control: no-store
Set-Cookie: guest_session={guestToken}; Path=/api/v1/guest; Max-Age=2592000; Secure; HttpOnly; SameSite=Lax
```

## 5. 일기 및 생성 API

### 5.1 일기 작성 및 4컷 생성

```http
POST /api/v1/diaries
Authorization: Bearer {accessToken}
Idempotency-Key: 7e5cc251-fdde-4cc0-a54e-2c8142750609
Content-Type: application/json
```

요청:

```json
{
  "diaryDate": "2026-08-06",
  "sourceText": "오늘 친구와 카페에 가서 오래 이야기했다."
}
```

검증 조건:

- `diaryDate`: 필수
- `sourceText`: 필수, 1~300자
- `Idempotency-Key`: 필수 UUID
- KST 기준 하루 최대 3회 생성

동작:

1. 요청 값과 Idempotency Key를 검증합니다.
2. 정의된 정규 표현으로 인증 사용자 ID, `diaryDate`, `sourceText`의 SHA-256 요청 지문을 계산합니다.
3. 접수 트랜잭션의 DB 잠금을 얻습니다. 같은 키가 있으면 요청 지문을 검사합니다. 같은 요청은 기존 상태와 결과 또는 저장된 오류를 반환합니다. 다른 요청은 `409`로 거절합니다.
4. 새 요청은 `PENDING`과 `PROCESSING`의 합이 `GENERATION_WORKER_CONCURRENCY`보다 적을 때만 일기와 작업을 저장합니다. 이미 상한에 도달했으면 `503 GENERATION_CAPACITY_EXCEEDED`로 거절합니다. 거절된 요청은 일기와 작업을 저장하지 않고 사용량도 차감하지 않습니다.
5. 새 키를 선점한 경우에만 일일 사용량 행을 원자적으로 생성 또는 증가시키고 생성 가능 횟수를 확인합니다.
6. 한도를 초과하면 전체 트랜잭션을 롤백하고 `429 DAILY_GENERATION_LIMIT_EXCEEDED`를 반환합니다.
7. DB 트랜잭션을 커밋합니다.
8. 이미지 생성을 기다리지 않고 일기 ID, 생성 ID, `PENDING` 상태와 사용량을 반환합니다.

작업 처리기는 별도로 대기 작업을 선점하고 `PROCESSING`으로 변경합니다. 선점 트랜잭션이 끝나면
스토리보드와 이미지를 생성하고 S3에 저장합니다. 마지막으로 같은 생성 기록에 성공 또는 실패를 저장합니다.

접수 상한은 현재 백엔드 한 대의 동시 실행 수와 같습니다. 선점 전 작업도 상한에 포함합니다.
회원과 게스트, 부하 테스트가 이 상한을 함께 사용합니다. 같은 요청의 재전송은 기존 작업으로 처리합니다.
기본 설정에서 저장한 지 120초 이상 지난 `PENDING` 작업은 자동 만료 대상입니다.
5초 간격으로 확인해 `FAILED`와 `GENERATION_PENDING_TIMEOUT` 오류를 저장합니다.
만료된 작업은 실행하지 않습니다. 실제 실패 저장 시점은 확인 간격과 DB 처리 시간에 따라 늦어질 수 있습니다.
이미 `PROCESSING`인 작업에는 이 대기 제한을 적용하지 않습니다.

일일 사용량 행은 PostgreSQL의 원자적 Upsert로 최초 행 생성과 증가를 함께 처리합니다. 최초 생성과 관리자 한도 변경이
동시에 실행될 때는 사용자 행 잠금으로 두 작업을 직렬화하며, 사용 횟수가 제한보다 작은 경우에만 증가합니다.

외부 AI API 호출 중에는 DB 트랜잭션이나 DB 커넥션을 점유하지 않습니다.

신규 접수 응답:

```http
HTTP/1.1 202 Accepted
Location: /api/v1/diaries/6b66acba-0136-4822-8a59-f355dd7c977d
```

```json
{
  "id": "6b66acba-0136-4822-8a59-f355dd7c977d",
  "diaryDate": "2026-08-06",
  "sourceText": "오늘 친구와 카페에 가서 오래 이야기했다.",
  "createdAt": "2026-08-06T20:10:23+09:00",
  "generation": {
    "id": "17ac16ef-c45a-40bb-92ea-aed37659ef1c",
    "status": "PENDING",
    "title": null,
    "imageUrl": null,
    "imageUrlExpiresAt": null,
    "completedAt": null
  },
  "usage": {
    "usageDate": "2026-08-06",
    "usedCount": 2,
    "limitCount": 3,
    "remainingCount": 1
  }
}
```

멱등 재요청으로 기존 결과를 반환하는 경우:

```http
HTTP/1.1 200 OK
```

현재 구현 범위:

- 신규 접수는 `202 Accepted`, 같은 요청의 기존 상태와 결과는 `200 OK`로 반환합니다.
- `202`는 작업을 접수했다는 뜻입니다. 응답의 `id`는 일기 ID, `generation.id`는 생성 작업 ID입니다.
- `Location`은 기존 일기 상세 조회 주소를 가리킵니다. 상태를 반복 조회할 때는 `generation.id`로 생성 상태 조회 API를 호출합니다.
- `PENDING`과 `PROCESSING`은 이미지 생성 완료를 뜻하지 않습니다. 이미지 URL과 완료 시간은 `null`입니다.
- 프론트엔드의 상태 조회와 모달 연결은 별도 작업입니다.

### 5.2 게스트 일기 작성 및 4컷 생성

게스트 세션을 먼저 발급한 뒤 요청합니다.

```http
POST /api/v1/guest/diaries
Cookie: guest_session={guestToken}; XSRF-TOKEN={csrfToken}
X-XSRF-TOKEN: {csrfToken}
Idempotency-Key: 7e5cc251-fdde-4cc0-a54e-2c8142750609
Content-Type: application/json
```

```json
{
  "diaryDate": "2026-08-20",
  "sourceText": "오늘 친구와 카페에 가서 오래 이야기했다."
}
```

동작:

1. 원문 게스트 Token을 SHA-256으로 해시해 게스트 세션을 쓰기 잠금으로 조회합니다.
2. 만료된 세션은 `401 GUEST_SESSION_EXPIRED`로 거절합니다.
3. 미사용 세션이면 공통 접수 상한을 확인하고 게스트 사용자 ID로 일기와 `PENDING` 생성 기록을 저장합니다. 상한에 도달했으면 `503 GENERATION_CAPACITY_EXCEEDED`로 거절하고 체험 횟수도 차감하지 않습니다.
4. 같은 트랜잭션에서 `guest_sessions.diary_id`와 `used_at`을 기록합니다.
5. 게스트 생성은 회원의 `daily_generation_usage`를 조회하거나 변경하지 않습니다.
6. 트랜잭션을 커밋한 뒤 이미지 생성을 기다리지 않고 접수 결과를 반환합니다. 이미지는 작업 처리기가 생성합니다.
7. 사용한 세션의 동일한 키와 동일한 요청은 기존 결과를 반환하고 다시 생성하지 않습니다.
8. 사용한 세션에서 새로운 키로 생성하면 `409 GUEST_TRIAL_ALREADY_USED`를 반환합니다.

게스트 사용권은 AI 호출 완료 시점이 아니라 일기 생성을 선점한 시점에 사용됩니다. 외부 생성이 실패하더라도 다른 키로 두
번째 일기를 만들 수 없으며, 동일한 키의 재요청은 저장된 실패 결과를 반환합니다.

신규 접수 응답:

```http
HTTP/1.1 202 Accepted
Location: /api/v1/guest/diaries/593363cb-1dc3-46bc-a858-5926f7601ca9
Cache-Control: no-store
```

```json
{
  "id": "593363cb-1dc3-46bc-a858-5926f7601ca9",
  "diaryDate": "2026-08-20",
  "sourceText": "오늘 친구와 카페에 가서 오래 이야기했다.",
  "createdAt": "2026-08-20T09:00:00+09:00",
  "generation": {
    "id": "17ac16ef-c45a-40bb-92ea-aed37659ef1c",
    "status": "PENDING",
    "title": null,
    "imageUrl": null,
    "imageUrlExpiresAt": null,
    "completedAt": null
  }
}
```

신규 접수는 `202 Accepted`로 반환합니다. 응답의 `id`는 일기 ID, `generation.id`는 생성 작업 ID입니다.
`Location`은 기존 게스트 일기 조회 주소를 가리킵니다.
동일한 멱등 요청의 대기 상태, 처리 중 상태 또는 기존 성공 결과는 `200 OK`로 반환합니다.
게스트 응답에는 회원 일일 사용량을 포함하지 않습니다.

### 5.3 게스트 일기 결과 조회

```http
GET /api/v1/guest/diaries/593363cb-1dc3-46bc-a858-5926f7601ca9
Cookie: guest_session={guestToken}
```

- Access Token과 CSRF Header는 요구하지 않습니다.
- 현재 게스트 세션의 `diary_id`와 요청한 `diaryId`가 같아야 합니다.
- 일기의 실제 소유자가 세션의 게스트 사용자와 일치해야 합니다.
- 다른 세션에 연결된 일기는 존재 여부를 노출하지 않고 `404 DIARY_NOT_FOUND`를 반환합니다.
- 공개 공유 API가 아니며, 게스트 세션 Cookie를 가진 브라우저만 조회할 수 있습니다.

```http
HTTP/1.1 200 OK
Cache-Control: no-store
```

응답 본문은 게스트 생성 응답과 동일합니다.

### 5.4 오늘 생성 사용량 조회

```http
GET /api/v1/me/generation-usage
Authorization: Bearer {accessToken}
```

```http
HTTP/1.1 200 OK
```

```json
{
  "usageDate": "2026-08-06",
  "usedCount": 2,
  "limitCount": 3,
  "remainingCount": 1
}
```

사용 기록이 없는 날:

```json
{
  "usageDate": "2026-08-06",
  "usedCount": 0,
  "limitCount": 3,
  "remainingCount": 3
}
```

### 5.5 생성 작업 상태 조회

회원은 Access Token으로 조회합니다. `generationId`에는 접수 응답의 `generation.id`를 사용합니다.

```http
GET /api/v1/diary-generations/17ac16ef-c45a-40bb-92ea-aed37659ef1c
Authorization: Bearer {accessToken}
```

게스트는 현재 세션 쿠키로 조회합니다. Access Token과 CSRF 헤더는 필요하지 않습니다.

```http
GET /api/v1/guest/diary-generations/17ac16ef-c45a-40bb-92ea-aed37659ef1c
Cookie: guest_session={guestToken}
```

두 API는 같은 응답 형식을 사용합니다. 조회에 성공하면 생성 상태와 관계없이 `200 OK`입니다.
매번 현재 상태를 확인하도록 `Cache-Control: no-store`를 반환합니다.

```json
{
  "id": "17ac16ef-c45a-40bb-92ea-aed37659ef1c",
  "status": "SUCCEEDED",
  "diaryId": "6b66acba-0136-4822-8a59-f355dd7c977d",
  "errorCode": null
}
```

| 상태 | 뜻 | `diaryId` | `errorCode` |
| --- | --- | --- | --- |
| `PENDING` | 실행 대기 | `null` | `null` |
| `PROCESSING` | 이미지 생성 중 | `null` | `null` |
| `SUCCEEDED` | 생성 완료 | 완성된 일기의 ID | `null` |
| `FAILED` | 생성 실패 | `null` | 저장된 생성 오류 코드 |

실패 상태의 `errorCode`는 `AI_PROVIDER_ERROR`, `AI_PROVIDER_RATE_LIMITED`, `AI_PROVIDER_TIMEOUT`,
`AI_OUTPUT_TRUNCATED`, `AI_RESPONSE_PROCESSING_ERROR`, `GENERATION_INTERNAL_ERROR`,
`GENERATION_INTERRUPTED`, `GENERATION_PENDING_TIMEOUT`, `IMAGE_STORAGE_ERROR` 중 하나입니다.
작업이 실패한 것은 상태 조회 API의 오류가 아니므로 `200 OK`와 `FAILED`로 반환합니다.

기본 설정에서 저장한 지 120초 이상 지난 `PENDING` 작업은 대기 만료 대상입니다.
5초 간격으로 확인하며, 실패 저장이 끝나면 회원과 게스트에게 아래 결과를 반환합니다.
실제 실패 저장 시점은 확인 간격과 DB 처리 시간에 따라 늦어질 수 있습니다.
대기 만료는 `PROCESSING` 작업에는 적용하지 않습니다.

```http
HTTP/1.1 200 OK
Cache-Control: no-store
Content-Type: application/json
```

```json
{
  "id": "17ac16ef-c45a-40bb-92ea-aed37659ef1c",
  "status": "FAILED",
  "diaryId": null,
  "errorCode": "GENERATION_PENDING_TIMEOUT"
}
```

대기 만료 시 일기는 숨기고, 회원의 생성 횟수는 요청을 접수한 사용 날짜에 한 번 돌려줍니다.
게스트의 체험 사용 상태는 유지합니다. 생성 기록은 남으므로 같은 요청 키를 다시 보내도 새 작업을 만들지 않습니다.

- 회원은 로그인한 사용자가 소유한 작업만 조회할 수 있습니다.
- 게스트는 세션의 소유자와 세션에 연결된 일기를 모두 확인합니다.
- 존재하지 않는 작업과 다른 사람의 작업은 모두 `404 GENERATION_NOT_FOUND`로 반환합니다.
- 실패 처리로 일기가 소프트 삭제되어도 본인은 `FAILED`와 오류 코드를 확인할 수 있습니다.
- 사용자가 삭제한 대기 또는 처리 중 또는 성공한 일기는 `404 GENERATION_NOT_FOUND`로 반환합니다.
- 인증이 없으면 회원은 `401 UNAUTHORIZED`, 게스트는 `401 GUEST_SESSION_REQUIRED`입니다.
- 만료된 게스트 세션은 `401 GUEST_SESSION_EXPIRED`, UUID 형식이 아닌 ID는 `400 VALIDATION_ERROR`입니다.
- 이 조회는 상태를 바꾸거나 작업을 선점하지 않습니다. 이미지 URL도 발급하지 않습니다.
- `SUCCEEDED`를 받으면 `diaryId`로 기존 일기 상세 API를 호출합니다. `FAILED`를 받으면 반복 조회를 끝냅니다.

## 6. 히스토리 API

### 6.1 월간 조회

```http
GET /api/v1/diaries?year=2026&month=8
Authorization: Bearer {accessToken}
```

Query Parameter:

| 이름 | 필수 | 설명 |
| --- | ---: | --- |
| `year` | O | 조회 연도 |
| `month` | O | 조회 월, 1~12 |

해당 월의 모든 날짜를 최신 날짜부터 내림차순으로 반환합니다. 일기가 없는 날짜도 `exist: false`로 포함하며,
같은 날짜의 일기는 생성 시각이 최신인 순서로 반환합니다.

```http
HTTP/1.1 200 OK
```

```json
{
  "year": 2026,
  "month": 8,
  "days": [
    {
      "date": "2026-08-06",
      "exist": true,
      "items": [
        {
          "id": "6b66acba-0136-4822-8a59-f355dd7c977d",
          "title": "비가 와도 나는 괜찮았다.",
          "thumbnailUrl": "https://presigned-s3-url.example/..."
        },
        {
          "id": "37d5b686-f260-42bd-a1bd-c82ae381c21c",
          "title": "친구와 보낸 저녁",
          "thumbnailUrl": "https://presigned-s3-url.example/..."
        }
      ]
    },
    {
      "date": "2026-08-05",
      "exist": false,
      "items": []
    }
  ]
}
```

### 6.2 일기 상세 조회

```http
GET /api/v1/diaries/{diaryId}
Authorization: Bearer {accessToken}
```

본인이 소유한 삭제되지 않은 일기만 조회할 수 있습니다.

```http
HTTP/1.1 200 OK
```

```json
{
  "id": "6b66acba-0136-4822-8a59-f355dd7c977d",
  "diaryDate": "2026-08-06",
  "sourceText": "오늘 친구와 카페에 가서 오래 이야기했다.",
  "createdAt": "2026-08-06T20:10:23+09:00",
  "generation": {
    "id": "17ac16ef-c45a-40bb-92ea-aed37659ef1c",
    "status": "SUCCEEDED",
    "title": "친구와 보낸 카페 시간",
    "imageUrl": "https://presigned-s3-url.example/...",
    "imageUrlExpiresAt": "2026-08-06T20:20:23+09:00",
    "completedAt": "2026-08-06T20:11:42+09:00"
  },
  "publishedFeedId": null
}
```

일기가 없거나 삭제된 경우 `404 DIARY_NOT_FOUND`를 반환합니다.
`publishedFeedId`는 연결된 활성 피드의 UUID입니다. 게시 전 또는 피드 삭제 후에는 null, 삭제 후 재게시하면 새 피드 ID를 반환합니다.
본인 소유 확인 후 게시 여부를 조회합니다. 다른 사용자의 일기는 `403 FORBIDDEN`입니다.

### 6.3 일기 삭제

```http
DELETE /api/v1/diaries/{diaryId}
Authorization: Bearer {accessToken}
```

```http
HTTP/1.1 204 No Content
```

처리 규칙:

- 본인 소유의 일기가 존재하면 소프트 삭제합니다.
- 기존 공유 링크 데이터가 있으면 같은 트랜잭션에서 정리합니다.
- 일기와 연결된 활성 피드에 같은 삭제 시각을 기록하고 미발송 푸시를 취소합니다. 기존 공유 링크 정리와 함께 같은 트랜잭션에서 처리하며 하나라도 실패하면 모두 롤백합니다.
- 연결 피드가 없거나 이미 삭제됐으면 일기만 삭제하며 푸시 포트를 호출하지 않습니다. 푸시 구현이 없으면 취소를 생략하고 일기·연결 피드 삭제와 공유 링크 정리를 정상 처리합니다.
- 삭제된 일기와 피드는 각각 상세 조회에서 `404 DIARY_NOT_FOUND`, `404 FEED_NOT_FOUND`입니다. 공개 목록과 반응 쓰기에서도 제외합니다.
- 생성 기록·이미지 객체와 기존 반응·인앱 알림 행은 유지합니다. 연결 데이터 조회와 알림 미읽음 수의 제외는 각 담당 영역에서 처리합니다.
- 이미 사용한 일일 생성 횟수는 복구하지 않습니다.
- 일기가 이미 삭제됐거나 존재하지 않아도 `204 No Content`를 반환합니다.
- 동일한 일기 텍스트로 다시 생성할 수 있으며 생성 횟수는 새로 차감됩니다.

## 7. 피드 공유

기존 일기 공유 API를 피드 게시·공개 목록·상세 조회로 대체합니다.
작성자는 완성된 만화를 확인하고 카테고리를 선택해 게시합니다. 게시된 피드만 공개 주소로 공유할 수 있습니다.
요청·응답 및 오류의 상세 명세는 [피드 API 명세](feed-api-spec.md)를 참고합니다.

### 7.1 공개 게시

```http
POST /api/v1/feeds
Authorization: Bearer {accessToken}
Content-Type: application/json
```

```json
{
  "diaryId": "550e8400-e29b-41d4-a716-446655440002",
  "categoryId": 1
}
```

본인의 삭제되지 않은 일기이고 이미지 생성이 완료됐으며 선택한 카테고리가 활성 상태여야 합니다.
성공하면 `201 Created`, `Location: /api/v1/feeds/{feedId}`와 `shareUrl`을 포함한 Feed 응답을 반환합니다.
같은 일기의 중복 게시는 `409 DIARY_ALREADY_PUBLISHED`를 반환합니다.

### 7.2 공개 상세 조회와 주소 공유

```http
GET /api/v1/feeds/{feedId}
```

비로그인 상태에서도 조회할 수 있습니다. 로그인 사용자는 좋아요 여부와 소유 여부도 확인할 수 있습니다.
공개 응답에는 만화 이미지·공개 작성자 정보·카테고리·반응 수·게시 시각·`shareUrl`이 포함됩니다.
개인 일기 원문·제목·이메일·OAuth 정보는 포함하지 않습니다.

`shareUrl`은 환경별 프론트 도메인의 `/feeds/{feedId}` 주소입니다.
FE는 이 주소를 복사하거나 Web Share API로 공유합니다. SNS별 백엔드 API나 별도 공유 링크 생성 API는 제공하지 않습니다.
피드나 원본 일기가 삭제됐거나 공개 작성자를 조회할 수 없으면 `404 FEED_NOT_FOUND`를 반환합니다.

기존 `PUT /api/v1/diaries/{diaryId}/share-link`와 `GET /api/v1/public/shares/{shareId}`는 제거합니다.
기존 공유 링크는 피드로 자동 변환하지 않으며 프론트는 게시/상세 조회 API와 `/feeds/{feedId}` 화면으로 전환해야 합니다.

### 7.3 공개 목록 조회

```http
GET /api/v1/feeds?sort=LATEST&categoryId=1&size=20
```

인증은 선택이며 `sort`는 `LATEST`(기본값) 또는 `POPULAR`입니다. `categoryId` 생략 시 모든 카테고리, `size`는 기본 20이며 1~50입니다.
응답은 `items`, `nextCursor`, `hasNext`를 반환합니다. 다음 페이지는 같은 정렬·카테고리 조건에 이전 `nextCursor`를 `cursor`로 전달합니다.
마지막 페이지의 `nextCursor`는 null입니다. 커서 형식 오류와 정렬·카테고리 조건 불일치는 `400 INVALID_CURSOR`입니다.
인기순은 기간 제한 없이 현재 좋아요 수를 기준으로 정렬하므로 페이지 사이의 좋아요 변경에 따른 중복·누락이 허용됩니다.
자세한 정렬·필터·개인화 규칙은 [피드 API 명세](feed-api-spec.md#공개-목록-조회)를 참고합니다.

### 7.4 피드 삭제

```http
DELETE /api/v1/feeds/{feedId}
Authorization: Bearer {accessToken}
```

본문 없이 본인 피드를 소프트 삭제하며 성공하면 `204 No Content`를 반환합니다.
타인의 활성 피드는 `403 FORBIDDEN`, 없거나 이미 삭제된 피드는 `404 FEED_NOT_FOUND`입니다. 반복 삭제에도 404를 반환합니다.
원본 일기·생성 기록·이미지와 기존 반응·인앱 알림 행은 유지합니다. 삭제된 피드는 공개 조회와 반응 쓰기에서 제외하며, 연결 데이터 조회·알림 미읽음 수의 제외는 각 담당 영역에서 처리합니다.
개인 일기 상세의 `publishedFeedId`는 null이 됩니다. 재게시하면 새 피드 ID·게시 시각·이벤트를 사용하고 반응 수는 0부터 시작합니다.
실제 푸시 구현이 있으면 피드 상태 변경과 미발송 푸시 취소는 같은 트랜잭션이며, 취소 실패 시 삭제도 롤백합니다. 푸시 구현이 없으면 예약·취소를 생략하고 피드 게시·삭제를 정상 처리합니다. 생략한 푸시는 예약 기록이 없어 나중에 소급 발송하지 않습니다.
개인 일기 삭제 서비스는 `FeedLifecycle`을 호출해 연결된 활성 피드를 같은 트랜잭션에서 삭제합니다. 프론트에서 피드 삭제 API를 별도로 호출하지 않습니다.

## 8. Problem Details 오류 명세

모든 오류는 RFC 9457 Problem Details 형식으로 반환합니다.

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json
Retry-After: 13800
```

```json
{
  "type": "urn:harudle:problem:daily-generation-limit-exceeded",
  "title": "Daily generation limit exceeded",
  "status": 429,
  "detail": "오늘 생성 가능한 횟수를 모두 사용했습니다.",
  "instance": "/api/v1/diaries",
  "code": "DAILY_GENERATION_LIMIT_EXCEEDED",
  "traceId": "019d71beebed75b19e45f9c51863bcbd"
}
```

`Retry-After`에는 다음 KST 자정까지 남은 초를 설정합니다.

필드 정의:

| 필드 | 필수 | 설명 |
| --- | ---: | --- |
| `type` | O | `urn:harudle:problem:{오류 코드 slug}` 형식의 오류 식별자 |
| `title` | O | 오류 유형의 짧은 제목 |
| `status` | O | HTTP 상태 코드 |
| `detail` | O | 사용자 또는 개발자가 확인할 상세 메시지 |
| `instance` | O | 오류가 발생한 요청 경로 |
| `code` | O | 클라이언트 분기 처리용 서비스 오류 코드 |
| `traceId` | O | 로그 및 모니터링 추적 ID |
| `errors` | X | 필드 검증 오류 목록 |

검증 오류 예시:

```json
{
  "type": "urn:harudle:problem:validation-error",
  "title": "Validation failed",
  "status": 400,
  "detail": "요청 값이 올바르지 않습니다.",
  "instance": "/api/v1/diaries",
  "code": "VALIDATION_ERROR",
  "traceId": "019d71beebed75b19e45f9c51863bcbd",
  "errors": [
    {
      "field": "sourceText",
      "reason": "일기 내용은 필수입니다."
    }
  ]
}
```

### 오류 코드 목록

| HTTP | Code | 설명 |
| ---: | --- | --- |
| `400` | `VALIDATION_ERROR` | 요청 형식 또는 필드 검증 실패 |
| `400` | `INVALID_IDEMPOTENCY_KEY` | 멱등성 키 누락 또는 형식 오류 |
| `400` | `INVALID_CURSOR` | 잘못된 피드 커서 또는 정렬·카테고리 조건 불일치 |
| `401` | `UNAUTHORIZED` | 인증 정보 없음 또는 Access Token 만료 |
| `401` | `GUEST_SESSION_REQUIRED` | 게스트 세션 Cookie가 없거나 저장된 세션과 일치하지 않음 |
| `401` | `GUEST_SESSION_EXPIRED` | 게스트 세션이 만료됨 |
| `401` | `INVALID_REFRESH_TOKEN` | Refresh Token 만료 또는 폐기 |
| `401` | `INVALID_CURRENT_USER` | Access Token은 유효하지만 현재 사용자를 확인할 수 없음 |
| `403` | `FORBIDDEN` | 다른 사용자의 리소스 접근 |
| `403` | `INVALID_CSRF_TOKEN` | 쿠키를 사용하는 인증·게스트 변경 요청의 CSRF Token 누락 또는 불일치 |
| `404` | `API_NOT_FOUND` | 존재하지 않는 API 경로 요청 |
| `404` | `DIARY_NOT_FOUND` | 상세 조회·피드 게시 대상 일기가 없거나 삭제됨 |
| `404` | `GENERATION_NOT_FOUND` | 생성 작업이 없거나 본인 소유 또는 게스트 세션 연결 조건에 맞지 않음 |
| `404` | `FEED_NOT_FOUND` | 공개 피드가 없거나 피드/원본 일기가 삭제됨 |
| `404` | `CATEGORY_NOT_FOUND` | 게시하거나 목록을 조회할 카테고리가 없음 |
| `405` | `METHOD_NOT_ALLOWED` | 지원하지 않는 HTTP 메서드 요청 |
| `406` | `NOT_ACCEPTABLE` | 생성할 수 없는 응답 미디어 타입 요청 |
| `409` | `GENERATION_FAILED` | 생성 결과가 실패 상태임 |
| `409` | `DIARY_NOT_PUBLISHABLE` | 피드에 게시할 만화 생성이 완료되지 않음 |
| `409` | `DIARY_ALREADY_PUBLISHED` | 일기의 활성 피드가 이미 존재함 |
| `409` | `CATEGORY_INACTIVE` | 비활성 카테고리에 신규 게시 |
| `503` | `FEED_UNAVAILABLE` | 필요한 카테고리·프로필·좋아요 포트가 아직 연결되지 않음 |
| `409` | `IDEMPOTENCY_KEY_CONFLICT` | 동일 키를 다른 요청 본문에 사용 |
| `409` | `GUEST_TRIAL_ALREADY_USED` | 게스트 세션의 로그인 전 1회 사용권을 이미 사용함 |
| `413` | `PAYLOAD_TOO_LARGE` | 허용 크기를 초과한 요청 본문 |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 요청 미디어 타입 |
| `429` | `DAILY_GENERATION_LIMIT_EXCEEDED` | KST 기준 일일 생성 3회 초과 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 내부의 예기치 않은 오류 |
| `502` | `AI_PROVIDER_ERROR` | LLM 또는 이미지 생성 Provider 호출 실패 |
| `503` | `GENERATION_UNAVAILABLE` | 생성 어댑터 또는 생성 프롬프트를 사용할 수 없음 |
| `503` | `GENERATION_CAPACITY_EXCEEDED` | 미완료 생성 작업이 동시 실행 수에 도달해 신규 접수를 거절함 |
| `503` | `GENERATION_INTERRUPTED` | 서버 중단 등으로 생성 처리가 완료되지 못함 |
| `503` | `IMAGE_STORAGE_ERROR` | 생성 이미지 S3 저장 실패 |
| `504` | `AI_PROVIDER_TIMEOUT` | AI Provider 응답 시간 초과 |

표에 별도 코드가 없는 Spring 표준 오류는 HTTP 상태를 유지하고 `HTTP_{status}` 형식의 `code`를 반환합니다.
모든 API 오류 응답에는 `code`와 `traceId`가 포함되며, FE는 `type`이나 `detail` 문자열 대신 `code`로 분기합니다.

일기 삭제 API (`DELETE /api/v1/diaries/{diaryId}`)는 대상이 없거나 이미 삭제된 경우에도 `204 No Content`를 반환하므로 `DIARY_NOT_FOUND`를 사용하지 않습니다.
피드 삭제 API (`DELETE /api/v1/feeds/{feedId}`)는 대상이 없거나 이미 삭제된 경우 `404 FEED_NOT_FOUND`를 반환하며, 반복 삭제도 동일합니다.

## 9. 생성 실패 처리

- 요청 검증 또는 멱등성 검증에서 실패하면 생성 횟수를 차감하지 않습니다.
- 접수 트랜잭션에서 회원 사용량을 증가시킵니다. 생성 실패와 중단 시에는 기존 사용량 복구 규칙을 적용합니다.
- AI 또는 S3 처리에 실패하면 생성 기록을 `FAILED`로 변경하고 오류 코드와 완료 시간을 저장하며, 연결된 일기를 같은
  트랜잭션에서 소프트 삭제합니다.
- 동일한 Idempotency Key로 실패 요청을 재전송하면 저장된 실패를 반환하며 AI 재호출과 추가 차감은 하지 않습니다.
- 실패한 생성을 새 Idempotency Key로 재시도하면 새로운 요청으로 처리하고 생성 횟수를 차감합니다.
- 이미지 업로드 후 생성 완료 저장 결과가 불명확하면 DB 상태를 다시 확인하고, 성공 결과가 참조할 가능성이 없는 이미지만
  best-effort로 삭제합니다.
- 요청을 거절하는 오류 응답은 RFC 9457 Problem Details 형식으로 반환합니다. 상태 조회에서 확인한 생성 실패는 `200 OK`와 `FAILED`로 반환합니다.

## 10. 비동기 생성 운영 조건

- 작업 처리기는 백엔드 앱 시작 시 항상 등록됩니다. 동시 실행 수와 확인 간격만 설정합니다.
- 생성 어댑터가 준비되지 않으면 신규 접수를 거절합니다. 같은 요청의 기존 상태와 결과는 반환할 수 있습니다.
- 일반 생성 요청은 저장 완료까지만 기다립니다. 외부 호출 제한 시간은 작업 처리기의 실행에 적용됩니다.
- AI API를 호출하는 동안 DB 트랜잭션이나 DB 커넥션을 점유하지 않습니다.
- 클라이언트가 응답을 받지 못해 재요청하더라도 동일한 `Idempotency-Key`를 사용합니다.
- `PROCESSING` 제한 시간은 외부 호출 전체 제한 시간보다 길게 설정합니다.
- 같은 키가 재요청되면 제한 시간을 넘긴 `PROCESSING`을 `FAILED`로 원자적으로 전환합니다.
- 주기적 복구 스케줄러도 제한 시간을 넘긴 `PROCESSING`을 배치로 선정해 각각 `FAILED`로 전환하고,
  연결된 일기를 같은 트랜잭션에서 소프트 삭제합니다.
- 외부 작업 완료 시 상태 변경은 `PROCESSING` 상태에만 허용해 재요청 복구가 만든 `FAILED` 상태를 덮어쓰지 않습니다.
- 대기 작업 조회 부하와 처리 시간을 측정하고, 필요한 경우 동시 실행 수와 확인 간격을 조정하거나 SQS 도입을 재검토합니다.
- 부하 테스트 API도 같은 대기열과 작업 처리기를 사용합니다. 이 API가 접수한 생성 ID를 메모리에서 가짜 AI 실행기에 연결합니다.
- 테스트 연결은 요청을 받은 백엔드 프로세스에서만 유지됩니다. 서버를 재시작하면 남은 테스트 작업은 일반 작업과 구분할 수 없습니다.
- 부하 테스트 실행기는 dev에서만 등록하며, 일반 작업과 테스트 작업의 동시 실행 수를 함께 제한합니다.
