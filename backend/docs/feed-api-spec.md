# 피드 게시·상세 조회 API

이번 1번 구현은 기존 일기를 공개 피드에 게시하고 게시한 피드를 상세 조회하는 범위다.
목록/커서/정렬, 반응 쓰기, 개인 일기의 `publishedFeedId`, 피드 삭제 및 일기 삭제 시 연결 데이터 정리는 후속 단계에서 구현한다.
기존 포트의 메서드·입력·반환 타입은 변경하지 않는다. 기존 V15 피드 스키마를 사용하므로 추가 마이그레이션은 없다.

## API

| 메서드 | 경로 | 인증 | 성공 응답 |
| --- | --- | --- | --- |
| POST | `/api/v1/feeds` | Bearer + CSRF Cookie/Header | 201, Feed |
| GET | `/api/v1/feeds/{feedId}` | 선택 | 200, Feed |

게시 요청에는 `Authorization: Bearer {accessToken}`, `XSRF-TOKEN` Cookie와 같은 값을 가진 `X-XSRF-TOKEN` Header가 필요하다.
CSRF는 Bearer 인증 요청에도 적용한다. 정상 응답은 `application/json`, 오류는 기존 `application/problem+json` 형식과 `code`, `traceId`를 사용한다.

### 게시

```json
{
  "diaryId": "550e8400-e29b-41d4-a716-446655440002",
  "categoryId": 1
}
```

`diaryId`는 UUID, `categoryId`는 양수 정수다. 카테고리 테이블의 BIGINT ID를 그대로 사용한다.
본인의 삭제되지 않은 일기이며 이미지 생성이 `SUCCEEDED`여야 한다. 활성 카테고리만 신규 게시할 수 있다.
하나의 일기에 활성 피드는 하나만 허용한다. 반복 게시 요청은 `409 DIARY_ALREADY_PUBLISHED`다.
삭제된 피드가 있는 일기는 새로운 피드 ID로 다시 게시할 수 있다.

성공하면 `Location: /api/v1/feeds/{feedId}`와 Feed를 반환한다.
일기 잠금, 카테고리 잠금, 피드 저장, 게시 이벤트 예약은 같은 트랜잭션에서 수행한다.
푸시 예약 저장 실패 시 피드도 롤백한다. 실제 FCM 발송은 푸시 담당의 워커가 수행한다.

### 상세 조회와 Feed 응답

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "author": {
    "id": "550e8400-e29b-41d4-a716-446655440001",
    "nickname": "캐모",
    "profileImageUrl": "https://example.com/profile.png"
  },
  "category": { "id": 1, "name": "일상" },
  "imageUrl": "https://example.com/generated/comic.webp?signature=example",
  "imageUrlExpiresAt": "2026-10-11T10:15:00+09:00",
  "publishedAt": "2026-10-11T10:00:00+09:00",
  "likeCount": 0,
  "commentCount": 0,
  "likedByMe": false,
  "isMine": false,
  "shareUrl": "https://www.harudle.com/feeds/550e8400-e29b-41d4-a716-446655440000"
}
```

원본 일기 내용·제목·일기 ID·작성자의 이메일·이미지 Object Key는 공개 응답에 포함하지 않는다.
기존 `ImageUrlProvider`로 DB 트랜잭션 이후에 S3/R2 접근 URL을 발급한다.
`shareUrl`은 `FEED_PUBLIC_BASE_URL` 뒤에 피드 ID를 붙인 주소다. 로컬 기본값은 `http://localhost:5173/feeds`다.
피드 공유는 기존 일기 공유 API를 대체한다. 공유 버튼은 게시된 피드의 주소를 복사하거나 기기의 공유 기능을 사용한다.
기존 `PUT /api/v1/diaries/{diaryId}/share-link`와 `GET /api/v1/public/shares/{shareId}`는 제거한다.
게시하지 않은 개인 일기를 공개하거나 새 `shareId`를 만드는 경로는 제공하지 않는다.
기존 공유 링크를 피드로 자동 전환하거나 리다이렉트하지 않는다. 공개 게시에는 작성자의 카테고리 선택과 게시 요청이 필요하다.

익명 조회는 `likedByMe`, `isMine`이 모두 false다. 유효한 Bearer 토큰이 있으면 개인화한다.
잘못되거나 만료된 토큰을 보내면 401이므로 클라이언트는 토큰을 갱신하거나 토큰 없이 다시 조회한다.
비활성 카테고리의 기존 피드는 조회할 수 있다.
삭제된 피드·원본 일기, 공개 프로필을 조회할 수 없는 작성자의 피드는 404다.

## 주요 오류

| 상태 | code | 조건 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 필수 값 누락, 잘못된 UUID, 잘못된 카테고리 ID |
| 401 | `UNAUTHORIZED` | 게시 인증 누락 또는 유효하지 않은 Bearer 토큰 |
| 403 | `INVALID_CSRF_TOKEN` | 게시 CSRF 검증 실패 |
| 403 | `FORBIDDEN` | 다른 사용자의 일기 게시 |
| 404 | `DIARY_NOT_FOUND` | 게시할 일기가 없거나 삭제됨 |
| 404 | `CATEGORY_NOT_FOUND` | 카테고리가 없음 |
| 404 | `FEED_NOT_FOUND` | 공개 상세 조회 대상이 없거나 삭제됨 |
| 409 | `DIARY_NOT_PUBLISHABLE` | 이미지 생성이 완료되지 않음 |
| 409 | `DIARY_ALREADY_PUBLISHED` | 해당 일기의 활성 피드가 이미 있음 |
| 409 | `CATEGORY_INACTIVE` | 비활성 카테고리에 신규 게시 |
| 503 | `FEED_UNAVAILABLE` | 필요한 타 영역 포트 구현이 아직 연결되지 않음 |

## 구현 경계와 연동

| 포트 | 이번 구현 | 제공 담당 |
| --- | --- | --- |
| `DiaryPublicationReader` | 구현: 일기 소유·삭제·생성 성공 검증, 일기 잠금 | 일기 |
| `CategoryReader` | 호출: 게시 시 활성 카테고리 잠금, 조회 시 비활성 포함 조회 | 카테고리 |
| `PublicProfileReader` | 호출: 공개 작성자 정보 조회 | 프로필 |
| `FeedLikeReader` | 호출: 로그인 사용자의 상세 좋아요 여부 | 좋아요 |
| `FeedPushOutbox` | 호출: 게시 트랜잭션에서 이벤트 예약 | 푸시 |
| `FeedInteractionPort` | 2번 단계에서 구현 | 피드 |
| `PushClient` | 이번 범위에 없음 | 외부 연동/푸시 워커 |

역할 표의 `FeedAccess`와 `FeedCounterWriter`는 현재 저장소에서 `FeedInteractionPort` 하나로 합쳐져 있다.
이를 분리하거나 인터페이스 구조를 바꾸지 않는다.
타 영역의 실제 어댑터는 이번 브랜치에서 만들지 않으며 테스트에서는 해당 포트에 대역을 연결한다.
포트가 없더라도 기존 애플리케이션을 시작할 수 있다. 해당 포트가 필요한 피드 요청은 503으로 실패하며 피드나 이벤트를 저장하지 않는다.
카테고리 담당은 `CategoryNotFoundException`, `CategoryInactiveException`으로 실패를 알려야 위 오류 코드로 변환된다.

기존 `share_links` 테이블과 일기 삭제 시의 공유 링크 정리는 기존 데이터 처리를 위해 유지한다.
공유 API 컨트롤러·응답 조립·URL 설정은 제거하며 기존 공유 관련 인터페이스는 변경하지 않는다.
프론트의 기존 `/shares/{shareId}` 화면과 `share-link` 호출은 피드 게시 및 `/feeds/{feedId}` 화면으로 전환해야 한다.

`Feed`는 게시 데이터의 생성 규칙을 담당하고, 서비스가 검증과 트랜잭션 흐름을 조합한다.
Repository는 잠금·저장·조회만 수행한다. `DiaryPublicationReader`는 일기만 잠그고 생성 기록은 조회해 기존 생성 처리와 잠금 순서가 역전되지 않게 한다.
중복 게시에는 일기 잠금과 `uq_feeds_active_diary` 부분 유니크 인덱스를 함께 사용한다.

푸시 수신자 정책에는 확인이 필요하다. 기존 `FeedPushOutbox` 설명은 작성자를 포함하고, 전달받은 정책 수정안은 작성자를 제외한다.
인터페이스를 그대로 유지하므로 이번 구현에서는 이벤트만 전달한다. 수신자 선정은 푸시 담당 구현에서 최신 정책에 맞춰 처리해야 한다.

## 검증

단위·MVC 테스트는 게시 조건, 중복 게시, 공개 응답의 비공개 필드 제외, 익명/로그인 조회, 오류 코드, 실제 Bearer 요청의 CSRF Cookie/Header를 검증한다.
`FeedPublicationPersistenceTest`는 PostgreSQL에서 저장·조회, 동시 게시, 푸시 예약 실패 시 롤백, 삭제된 대상 제외와 재게시를 검증한다.
Testcontainers 테스트는 Docker가 없는 환경에서 건너뛰므로 Docker 사용 환경에서 실행해야 한다.

```powershell
cd backend
.\gradlew.bat test
```
