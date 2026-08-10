# 하루들 API 명세

## 1. 문서 개요

이 문서는 사용자가 작성한 일기를 AI가 하나의 4컷 이미지로 생성하고, 결과를 히스토리에 저장·조회·공유하는
MVP의 HTTP API 명세입니다.

현재 버전은 다음 조건을 전제로 합니다.

- Kakao OAuth2 로그인 지원
- 이미지 생성은 동기 방식으로 처리
- FE는 생성 API 응답을 기다리는 동안 단계 애니메이션 표시
- 별도의 생성 상태 조회 API, SQS 및 Outbox는 사용하지 않음
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

- 같은 키와 같은 요청이 이미 완료됐다면 AI를 재호출하지 않고 기존 결과를 반환합니다.
- 같은 키의 요청이 아직 처리 중이면 `409 GENERATION_IN_PROGRESS`를 반환합니다.
- 같은 키를 다른 요청 본문에 사용하면 `409 IDEMPOTENCY_KEY_CONFLICT`를 반환합니다.
- 다른 키로 같은 일기 텍스트를 요청하는 것은 허용하며 일일 생성 횟수를 새로 차감합니다.

### 2.6 이미지 URL

- DB의 S3 Object Key는 외부에 노출하지 않습니다.
- 인증된 조회 및 공개 공유 조회 시 만료 시간이 있는 Presigned URL을 반환합니다.
- 안정적인 공유 주소와 만료되는 실제 이미지 URL은 구분합니다.

## 3. API 목록

### 3.1 인증 및 사용자

| Method | Endpoint | 인증 | 설명 |
| --- | --- | ---: | --- |
| `GET` | `/oauth2/authorization/kakao` | 불필요 | Kakao OAuth 로그인 시작 |
| `GET` | `/login/oauth2/code/kakao` | 불필요 | Kakao OAuth 콜백 |
| `POST` | `/api/v1/auth/refresh` | Refresh Token | Access Token 발급·재발급 |
| `POST` | `/api/v1/auth/logout` | 필요 | 현재 로그인 세션 종료 |
| `GET` | `/api/v1/me` | 필요 | 내 프로필 조회 |

### 3.2 일기 및 생성

| Method | Endpoint | 인증 | 설명 |
| --- | --- | ---: | --- |
| `POST` | `/api/v1/diaries` | 필요 | 일기 작성 및 4컷 이미지 동기 생성 |
| `GET` | `/api/v1/me/generation-usage` | 필요 | 오늘 생성 사용량 조회 |
| `GET` | `/api/v1/diaries` | 필요 | 연·월 기준 내 일기 조회 |
| `GET` | `/api/v1/diaries/{diaryId}` | 필요 | 일기 및 생성 결과 상세 조회 |
| `DELETE` | `/api/v1/diaries/{diaryId}` | 필요 | 일기 삭제 |

### 3.3 공유

| Method | Endpoint | 인증 | 설명 |
| --- | --- | ---: | --- |
| `PUT` | `/api/v1/diaries/{diaryId}/share-link` | 필요 | 공유 링크 생성 또는 기존 링크 조회 |
| `GET` | `/api/v1/public/shares/{shareId}` | 불필요 | 공개 공유 결과 조회 |

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
Authorization: Bearer {accessToken}
```

현재 Refresh Token을 폐기하고 Cookie를 삭제합니다.

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
  "createdAt": "2026-08-06T10:30:00+09:00"
}
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
2. 일일 사용량 행을 잠그고 생성 가능 횟수를 확인합니다.
3. 사용 횟수를 증가시킵니다.
4. 일기와 `PROCESSING` 상태의 생성 기록을 저장합니다.
5. DB 트랜잭션을 커밋합니다.
6. LLM을 호출해 일기를 네 개 장면으로 분리합니다.
7. 이미지 생성 API를 호출합니다.
8. 완성된 하나의 4컷 이미지를 S3에 저장합니다.
9. 생성 기록을 `SUCCEEDED`로 변경합니다.
10. 완성 결과를 반환합니다.

외부 AI API 호출 중에는 DB 트랜잭션이나 DB 커넥션을 점유하지 않습니다.

성공 응답:

```http
HTTP/1.1 201 Created
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
    "status": "SUCCEEDED",
    "title": "친구와 보낸 카페 시간",
    "imageUrl": "https://presigned-s3-url.example/...",
    "imageUrlExpiresAt": "2026-08-06T20:20:23+09:00",
    "completedAt": "2026-08-06T20:11:42+09:00"
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

FE 동작:

- API 응답이 오기 전까지 네 단계 애니메이션을 순서대로 표시합니다.
- 애니메이션은 실제 백엔드 진행 상태와 연결되지 않습니다.
- 성공 응답을 받으면 결과 화면으로 이동합니다.
- 별도의 생성 진행 상태 조회 API는 제공하지 않습니다.

### 5.2 오늘 생성 사용량 조회

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

해당 월의 모든 날짜를 반환합니다. 일기가 없는 날짜도 `exist: false`로 포함합니다.

```http
HTTP/1.1 200 OK
```

```json
{
  "year": 2026,
  "month": 8,
  "days": [
    {
      "date": "2026-08-05",
      "exist": false,
      "items": []
    },
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
  }
}
```

일기가 없거나 삭제된 경우 `404 DIARY_NOT_FOUND`를 반환합니다.

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
- 연결된 공유 링크를 같은 트랜잭션에서 삭제합니다.
- 이미 사용한 일일 생성 횟수는 복구하지 않습니다.
- 일기가 이미 삭제됐거나 존재하지 않아도 `204 No Content`를 반환합니다.
- 동일한 일기 텍스트로 다시 생성할 수 있으며 생성 횟수는 새로 차감됩니다.

## 7. 공유 API

SNS마다 별도의 백엔드 API를 제공하지 않습니다. 백엔드는 공유 URL을 발급하고 FE가 Web Share API 또는 SNS SDK를 사용해
해당 URL을 공유합니다.

### 7.1 공유 링크 생성 또는 조회

```http
PUT /api/v1/diaries/{diaryId}/share-link
Authorization: Bearer {accessToken}
```

공유 링크가 없으면 생성하고 이미 존재하면 기존 링크를 반환합니다.

신규 생성:

```http
HTTP/1.1 201 Created
```

기존 링크 반환:

```http
HTTP/1.1 200 OK
```

```json
{
  "shareId": "06ed972e-0b79-4da0-9716-c9bd8faec85d",
  "shareUrl": "https://harudle.example/shares/06ed972e-0b79-4da0-9716-c9bd8faec85d",
  "createdAt": "2026-08-06T20:15:00+09:00"
}
```

대상 일기가 없거나 삭제된 경우 `404 DIARY_NOT_FOUND`를 반환합니다.

### 7.2 공개 공유 결과 조회

```http
GET /api/v1/public/shares/{shareId}
```

인증이 필요하지 않습니다.

```http
HTTP/1.1 200 OK
```

```json
{
  "title": "친구와 보낸 카페 시간",
  "diaryDate": "2026-08-06",
  "imageUrl": "https://presigned-s3-url.example/...",
  "imageUrlExpiresAt": "2026-08-06T20:25:00+09:00",
  "createdAt": "2026-08-06T20:10:23+09:00"
}
```

공개 응답에는 사용자의 이메일과 원본 `sourceText`를 포함하지 않습니다.

공유 링크가 없거나 연결된 일기가 삭제된 경우 `404 SHARE_NOT_FOUND`를 반환합니다.

## 8. Problem Details 오류 명세

모든 오류는 RFC 9457 Problem Details 형식으로 반환합니다.

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json
Retry-After: 13800
```

```json
{
  "type": "https://api.harudle.example/problems/daily-generation-limit-exceeded",
  "title": "Daily generation limit exceeded",
  "status": 429,
  "detail": "하루 최대 3번까지 생성할 수 있습니다.",
  "instance": "/api/v1/diaries",
  "code": "DAILY_GENERATION_LIMIT_EXCEEDED",
  "traceId": "019d71beebed75b19e45f9c51863bcbd"
}
```

`Retry-After`에는 다음 KST 자정까지 남은 초를 설정합니다.

필드 정의:

| 필드 | 필수 | 설명 |
| --- | ---: | --- |
| `type` | O | 오류 유형을 식별하는 URI |
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
  "type": "https://api.harudle.example/problems/validation-error",
  "title": "Validation failed",
  "status": 400,
  "detail": "요청 값이 올바르지 않습니다.",
  "instance": "/api/v1/diaries",
  "code": "VALIDATION_ERROR",
  "traceId": "019d71beebed75b19e45f9c51863bcbd",
  "errors": [
    {
      "field": "sourceText",
      "reason": "일기 내용은 1자 이상 300자 이하여야 합니다."
    }
  ]
}
```

### 오류 코드 목록

| HTTP | Code | 설명 |
| ---: | --- | --- |
| `400` | `VALIDATION_ERROR` | 요청 형식 또는 필드 검증 실패 |
| `400` | `INVALID_IDEMPOTENCY_KEY` | 멱등성 키 누락 또는 형식 오류 |
| `401` | `UNAUTHORIZED` | 인증 정보 없음 또는 Access Token 만료 |
| `401` | `INVALID_REFRESH_TOKEN` | Refresh Token 만료 또는 폐기 |
| `403` | `FORBIDDEN` | 다른 사용자의 리소스 접근 |
| `404` | `DIARY_NOT_FOUND` | 상세 조회·공유 링크 생성 대상 일기가 없거나 삭제됨 |
| `404` | `SHARE_NOT_FOUND` | 공개 공유 링크가 없거나 연결된 일기가 삭제됨 |
| `409` | `GENERATION_IN_PROGRESS` | 동일 멱등 요청이 아직 처리 중 |
| `409` | `IDEMPOTENCY_KEY_CONFLICT` | 동일 키를 다른 요청 본문에 사용 |
| `429` | `DAILY_GENERATION_LIMIT_EXCEEDED` | KST 기준 일일 생성 3회 초과 |
| `502` | `AI_PROVIDER_ERROR` | LLM 또는 이미지 생성 Provider 호출 실패 |
| `503` | `IMAGE_STORAGE_ERROR` | 생성 이미지 S3 저장 실패 |
| `504` | `AI_PROVIDER_TIMEOUT` | AI Provider 응답 시간 초과 |

DELETE API는 대상이 없거나 이미 삭제된 경우에도 `204 No Content`를 반환하므로 `DIARY_NOT_FOUND`를 사용하지 않습니다.

## 9. 생성 실패 처리

- 요청 검증 또는 멱등성 검증에서 실패하면 생성 횟수를 차감하지 않습니다.
- 외부 AI API 호출이 시작된 요청은 성공 여부와 관계없이 생성 횟수에 포함합니다.
- AI 또는 S3 처리에 실패하면 생성 기록을 `FAILED`로 변경하고 오류 코드를 저장합니다.
- 실패 응답은 RFC 9457 Problem Details 형식으로 반환합니다.

## 10. 동기 생성 운영 조건

- FE 요청 제한 시간, ELB Idle Timeout, Spring 서버 제한 시간 및 외부 HTTP 클라이언트 Read Timeout을 예상 최대 생성 시간보다
  길게 설정합니다.
- AI API를 호출하는 동안 DB 트랜잭션이나 DB 커넥션을 점유하지 않습니다.
- 클라이언트가 응답을 받지 못해 재요청하더라도 동일한 `Idempotency-Key`를 사용합니다.
- 동시 생성량이 증가하거나 외부 API의 `429`, 타임아웃이 자주 발생하면 비동기 큐 도입을 재검토합니다.
