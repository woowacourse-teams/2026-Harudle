# 사용자 차단: 백엔드 상황별 동작 흐름

A는 요청한 회원, B는 상대 작성자, C는 제3자입니다. **A→B는 A가 저장한 차단 한 건**이며, 어느 방향이든 남아 있으면 로그인한 두 회원 사이에 숨김·접촉 제한을 적용합니다.

이 문서는 인증·저장·조회·동시 요청·알림·데이터 보존을 다룹니다. [프론트 화면과 요청·실패 흐름](https://github.com/woowacourse-teams/2026-Harudle/blob/feat/425-user-blocking-frontend/frontend/web/docs/user-blocking-scenarios.md)은 별도 프론트 PR에서 리뷰합니다.

| 확인할 상황                      | 절  |
| -------------------------------- | --- |
| 인증·CSRF                        | S02 |
| 새 차단·중복·자기 차단·탈퇴 대상 | S03 |
| 한쪽·양쪽 차단 해제              | S04 |
| 본인 목록·커서·탈퇴한 상대       | S05 |
| 피드·공유 URL·비로그인 조회      | S07 |
| 부모 피드와 댓글 작성자          | S08 |
| 댓글·좋아요와 차단의 경쟁        | S09 |
| 차단 후 본인 삭제·신고           | S10 |
| 인앱 알림·미읽음 수·읽음 처리    | S11 |
| 푸시 예약과 차단의 경쟁          | S12 |
| 푸시 발송·재시도·한계            | S13 |
| 숨김과 보존                      | S14 |

## S02. 인증·CSRF·응답을 확인하기

```mermaid
flowchart TD
    START["차단 GET·PUT·DELETE"] --> BEARER{"Bearer 인증 통과?"}
    BEARER -->|아니요| UNAUTH["401 · 변경 없음"]
    BEARER -->|예| METHOD{"변경 요청인가?"}
    METHOD -->|GET| ACTOR["인증 주체 A 추출"]
    METHOD -->|PUT·DELETE| CSRF{"CSRF 쿠키·헤더 일치?"}
    CSRF -->|아니요| REJECT["저장 전 403 INVALID_CSRF_TOKEN"]
    CSRF -->|예| ACTOR
    ACTOR --> MEMBER{"DB의 활성 OAuth 회원인가?"}
    MEMBER -->|아니요| UNAUTH
    MEMBER -->|예| HANDLE["본인 범위에서 처리"]
```

- JWT의 인증 주체를 사용하며 요청으로 다른 `blockerId`를 지정할 수 없습니다. JWT 유효성과 DB 활성 회원 여부는 각각 확인합니다.
- GET에는 CSRF를 요구하지 않습니다. 인증된 PUT·DELETE는 CSRF를 검사합니다.
- 경로 UUID 형식·목록 크기 범위 오류는 400입니다. 차단·해제 완료는 204, 빈 본문과 `no-store`로 응답합니다.
- 클라이언트의 토큰 복원과 명확한 CSRF403에 대한 한 번 재시도는 [프론트 시나리오](https://github.com/woowacourse-teams/2026-Harudle/blob/feat/425-user-blocking-frontend/frontend/web/docs/user-blocking-scenarios.md)에서 설명합니다.

참고: [보안 체인](../src/main/java/com/harudle/common/security/BlockSecurityConfiguration.java), [인증 주체 추출](../src/main/java/com/harudle/auth/presentation/AuthenticatedUserIdResolver.java), [인증·CSRF 계약 테스트](../src/test/java/com/harudle/block/presentation/BlockControllerTest.java).

## S03. 서버에 차단을 저장하기

```mermaid
flowchart TD
    START["인증 주체 A · 경로의 대상 B"] --> VALIDATE["A의 활성 회원 여부 · 자기 차단 금지 검사"]
    VALIDATE --> PAIR["A와 B의 공통 사용자 쌍 잠금"]
    PAIR --> TARGET{"B가 차단 가능한 활성 회원인가?"}
    TARGET -->|아니요| NOTFOUND["404 BLOCK_TARGET_NOT_FOUND"]
    TARGET -->|예| PUBLICATION["두 작성자의 푸시 예약 배타 잠금"]
    PUBLICATION --> INSERT["A→B 저장 · 기존 행이면 유지"]
    INSERT --> CANCEL["A↔B의 미발송 푸시 작업 취소"]
    CANCEL --> COMMIT["함께 커밋 · 204 · no-store"]
```

| 경우                                                        | 결과                                                             |
| ----------------------------------------------------------- | ---------------------------------------------------------------- |
| A가 A를 차단                                                | 400 `INVALID_BLOCK`                                              |
| B 부재·탈퇴·OAuth 없는 게스트·개발 환경의 허용 범위 밖 대상 | 404 `BLOCK_TARGET_NOT_FOUND`                                     |
| 유효한 대상에 같은 A→B 반복·동시 요청                       | `UNIQUE`와 `ON CONFLICT DO NOTHING`으로 한 행. 최초 ID·시각 유지 |
| B가 A를 차단                                                | 별도 B→A 행. A→B를 자동으로 만들거나 바꾸지 않음                 |
| 저장 또는 미발송 취소가 실패                                | 같은 트랜잭션의 변경이 함께 롤백                                 |

이미 A→B가 있더라도 B가 그 뒤 탈퇴했다면 PUT의 대상 검사에서 404입니다. **중복 PUT이 언제나 204인 것은 아닙니다.** 해제는 탈퇴한 B에도 가능합니다.

<details>
<summary>잠금이란?</summary>

같은 두 사람의 변경이 순서대로 처리되도록 기다리는 장치입니다. A→B와 B→A는 같은 사용자 쌍 잠금을 사용합니다. 아직 차단 행이 없어도 사용할 수 있고 트랜잭션 종료 때 풀립니다. 푸시 예약용 잠금은 S12에서 별도로 설명합니다.

</details>

참고: [BlockService](../src/main/java/com/harudle/block/service/BlockService.java), [차단 저장 SQL](../src/main/java/com/harudle/block/infrastructure/JpaBlockRepository.java), [미발송 예약 취소 SQL](../src/main/java/com/harudle/push/infrastructure/JpaFeedPushOutbox.java), [DB 제약·롤백 테스트](../src/test/java/com/harudle/block/infrastructure/BlockPersistenceTest.java).

## S04. 본인 방향만 해제하기

```mermaid
flowchart TD
    START["A가 B 차단 해제 DELETE"] --> VALIDATE["A의 활성 회원 여부 · 자기 대상 검사"]
    VALIDATE --> LOCK["A와 B의 공통 사용자 쌍 잠금"]
    LOCK --> DELETE["A→B만 삭제 · 없으면 그대로"]
    DELETE --> OK["커밋 · 204 · 상대 차단 정보는 반환하지 않음"]
    OK --> NEXT["이후 콘텐츠 조회"]
    NEXT --> OTHER{"어느 방향이든 차단이 남아 있는가?"}
    OTHER -->|예| HIDE["로그인한 두 회원 사이의 숨김·접촉 제한 유지"]
    OTHER -->|아니요| SHOW["기존 공개·삭제·권한 조건에 따라 다시 조회 가능"]
```

| 해제 전              | A가 B를 해제한 뒤 | 로그인한 두 회원 사이   |
| -------------------- | ----------------- | ----------------------- |
| A→B만 있음           | 차단 없음         | 기존 조회 규칙으로 복구 |
| A→B와 B→A 둘 다 있음 | B→A 유지          | 제한 유지               |
| B→A만 있음           | B→A 유지          | 제한 유지               |
| 차단 없음            | 그대로            | 기존 조회 규칙 유지     |

B가 탈퇴했거나 물리적으로 삭제됐어도 DELETE는 대상의 활성 여부를 요구하지 않습니다. 없는 행의 반복 해제도 204입니다. A 자신에 대한 요청과 비활성 A는 별도 검증에서 거절됩니다. 취소된 푸시 작업을 다시 예약하지 않습니다.

참고: [해제 서비스](../src/main/java/com/harudle/block/service/BlockService.java), [본인 방향 DELETE](../src/main/java/com/harudle/block/infrastructure/JpaBlockRepository.java), [양방향·탈퇴 해제 테스트](../src/test/java/com/harudle/block/infrastructure/BlockPersistenceTest.java).

## S05. 설정의 차단 목록과 다음 페이지

```mermaid
flowchart TD
    GET["본인 차단 목록 GET · 기본 20개"] --> VALIDATE["활성 회원 · 크기 1~50 · 본인 커서 검사"]
    VALIDATE --> DB["A가 저장한 행만 최신순으로 size+1개 조회"]
    DB --> PROFILE["활성 대상 프로필 결합 · 비활성 대상은 탈퇴한 사용자"]
    PROFILE --> PAGE["최대 size개 · nextCursor · hasNext 반환"]
    PAGE --> NEXT["다음 요청은 마지막 시각·행 ID보다 뒤에서 조회"]
```

- 시각이 같아도 행 ID로 순서를 구분합니다. 커서의 본인 ID·종류·버전·날짜·행 ID를 검사하며 다른 회원의 커서는 400 `INVALID_CURSOR`입니다. SQL도 본인 범위로 제한합니다.
- 경계 행을 해제해 삭제해도 다음 페이지를 조회할 수 있습니다. 새 차단은 앞쪽에 생길 수 있으므로 첫 페이지를 다시 조회하면 최신 상태를 받습니다.
- 탈퇴·OAuth 상실 대상은 `탈퇴한 사용자`, 이미지 없음으로 반환하고 본인 해제를 허용합니다. 상대의 차단 여부는 반환하지 않습니다.
- 활성 대상의 필수 프로필을 제공받지 못하면 503 `BLOCK_UNAVAILABLE`입니다. 모든 임의 예외를 이 코드로 바꾸는 것은 아닙니다.

<details>
<summary>커서와 조회 일관성</summary>

커서는 마지막으로 본 위치를 적은 책갈피입니다. Base64URL은 전달 형식이며 서명·암호화는 아닙니다. 한 페이지는 REPEATABLE READ의 같은 스냅샷으로 읽지만 여러 페이지 탐색 전체를 고정 스냅샷으로 묶지는 않습니다.

</details>

참고: [서버 목록](../src/main/java/com/harudle/block/service/BlockService.java), [커서](../src/main/java/com/harudle/block/service/BlockCursorCodec.java), [목록·경계 행 삭제 테스트](../src/test/java/com/harudle/block/infrastructure/BlockPersistenceTest.java). 화면의 로딩·실패·재시도는 [프론트 시나리오](https://github.com/woowacourse-teams/2026-Harudle/blob/feat/425-user-blocking-frontend/frontend/web/docs/user-blocking-scenarios.md)에서 확인합니다.

## S07. 피드 목록·상세·공유 URL

```mermaid
flowchart TD
    START["피드 조회 · 공유 URL도 같은 피드 상세"] --> PUBLIC["기존 공개·삭제·활성 작성자 조건"]
    PUBLIC --> VIEWER{"인증된 조회자가 있는가?"}
    VIEWER -->|없음| ORIGINAL["개인별 차단 조건 없이 공개 조회"]
    VIEWER -->|있음| RELATION{"조회자↔작성자의 차단이 있는가?"}
    RELATION -->|없음| ORIGINAL
    RELATION -->|있음| TYPE{"목록인가, 상세인가?"}
    TYPE -->|목록| FILTER["DB에서 제외한 뒤 정렬·커서·개수 적용"]
    TYPE -->|상세| NOTFOUND["404 FEED_NOT_FOUND"]
    ORIGINAL --> RESULT["목록 페이지 또는 상세 반환"]
    FILTER --> RESULT
```

- A가 B를 차단했어도 C의 피드는 그대로 보입니다. B가 A를 차단한 경우도 A의 조회에 같은 조건을 적용합니다.
- 목록은 최신순·인기순·카테고리에 같은 차단 필터를 적용합니다. 후속 프로필 조회에서 사라진 작성자는 건너뛰고 페이지를 보충합니다.
- 이미 알고 있는 피드 ID로 요청해도 상세 검사에 합류합니다. 실제 `shareUrl`은 `/feeds/{feedId}`입니다.
- 잘못된 Bearer를 보내면 익명으로 바꾸지 않고 401입니다. 인증 없는 공개 조회와 구분합니다.
- 이미 진행 중인 조회·이미 내려간 이미지까지 회수하지는 않습니다. 차단 성공 화면은 이전 메모리 상태를 버리고 새로 조회합니다.

참고: [목록 SQL](../src/main/java/com/harudle/feed/repository/FeedListRepository.java), [상세](../src/main/java/com/harudle/feed/service/FeedQueryService.java), [공유 URL](../src/main/java/com/harudle/feed/presentation/FeedResponseAssembler.java), [노출 통합 테스트](../src/test/java/com/harudle/feed/service/BlockedContentVisibilityPersistenceTest.java).

## S08. 댓글 목록은 부모 피드와 댓글 작성자를 따로 확인

```mermaid
flowchart TD
    START["피드의 댓글 목록 GET"] --> PARENT{"부모 피드에 접근 가능한가?"}
    PARENT -->|아니요| NOTFOUND["404 FEED_NOT_FOUND · 댓글 목록도 반환하지 않음"]
    PARENT -->|예| AUTHOR["각 댓글의 작성자 확인"]
    AUTHOR --> BLOCK{"로그인 조회자와 댓글 작성자 사이 차단?"}
    BLOCK -->|예| SKIP["그 댓글을 DB에서 제외"]
    BLOCK -->|아니요| KEEP["댓글 유지 · 탈퇴 작성자는 익명 표시"]
    SKIP --> PAGE["필터 뒤 오래된순·커서·페이지 적용"]
    KEEP --> PAGE
```

A가 B를 차단한 예시입니다.

| 조회 대상                                 | 결과                                                                                       |
| ----------------------------------------- | ------------------------------------------------------------------------------------------ |
| B의 피드에 달린 모든 댓글                 | 부모 피드가 숨겨져 댓글 API도 404                                                          |
| C의 피드에 달린 B의 댓글                  | B의 댓글만 제외                                                                            |
| C의 피드에 달린 A·C·다른 회원의 댓글      | 기존 조건대로 표시                                                                         |
| 탈퇴한 댓글 작성자                        | 댓글은 유지하고 `id=null`, `탈퇴한 회원`, 이미지 없음으로 표시. 남아 있는 차단 조건은 적용 |
| 부모 피드·일기 삭제 또는 피드 작성자 탈퇴 | 부모 경로가 유효하지 않아 댓글 목록 404                                                    |

참고: [부모 검사](../src/main/java/com/harudle/comment/service/CommentService.java), [댓글 필터 SQL](../src/main/java/com/harudle/comment/infrastructure/JpaCommentRepository.java), [탈퇴 작성자 응답](../src/main/java/com/harudle/comment/presentation/CommentAuthorResponse.java), [통합 테스트](../src/test/java/com/harudle/feed/service/BlockedContentVisibilityPersistenceTest.java).

## S09. 댓글·좋아요를 저장할 때와 차단이 겹칠 때

```mermaid
flowchart TD
    START["댓글 작성 · 좋아요 추가·취소"] --> MEMBER["활성 회원·요청 내용 확인"]
    MEMBER --> FEED["피드 행 잠금 · 피드·일기·작성자 활성 확인"]
    FEED --> PAIR["상대 작성자와의 사용자 쌍 잠금"]
    PAIR --> CHECK{"어느 방향이든 차단이 있는가?"}
    CHECK -->|예| REJECT["404 FEED_NOT_FOUND · 반응·집계·새 알림 저장 없음"]
    CHECK -->|아니요| WRITE["반응·카운터 반영 · 필요한 인앱 알림 저장"]
    WRITE --> COMMIT["함께 커밋"]
```

```mermaid
sequenceDiagram
    participant R as 댓글·좋아요 요청
    participant L as 두 회원의 공통 잠금
    participant B as 차단 요청
    alt 차단이 먼저 잠금을 얻음
        B->>L: 잠금 획득
        B->>B: 차단 저장·커밋
        B-->>L: 잠금 해제
        R->>L: 대기 후 획득
        R->>R: 새 SELECT로 차단 확인·404
    else 반응이 먼저 잠금을 얻음
        R->>L: 잠금 획득
        R->>R: 차단 없음 확인·반응 커밋
        R-->>L: 잠금 해제
        B->>L: 대기 후 획득
        B->>B: 차단 저장·커밋
    end
```

- 좋아요 취소도 차단 검사 대상입니다. 차단 중에는 기존 좋아요를 취소하지 않고 404로 거절합니다.
- 본인 댓글 삭제는 허용합니다. 좋아요 철회 허용은 숨긴 피드의 집계를 응답하지 않는 계약과 본인 반응 관리 흐름을 함께 검토할 때 다루며, 이번 변경에서는 기존 정책을 유지합니다.
- 차단이 없을 때 중복 좋아요·이미 없는 좋아요 취소는 멱등 처리합니다. 중복 좋아요는 새 알림을 만들지 않습니다.
- 자기 피드 반응은 상대와의 쌍 잠금·차단 조회를 생략하고, 본인에게 인앱 알림을 만들지 않습니다.
- 반응이 먼저 완료됐다면 이후 차단이 그 반응을 소급 삭제하지 않습니다. 저장 과정이 실패하면 반응·집계·새 인앱 알림이 함께 롤백됩니다.
- 반응은 **피드 행 → 사용자 쌍**, 차단은 **사용자 쌍 → 게시 예약 잠금** 순서입니다. 차단이 피드 행 잠금을 추가로 얻지는 않습니다.

<details>
<summary>잠금을 기다린 뒤 다시 조회하는 이유</summary>

반응·차단·해제는 READ COMMITTED를 사용합니다. 기다리는 동안 완료된 차단을 대기 후의 새 조회에서 확인하기 위해서입니다. 사용자 쌍 잠금 번호를 만드는 해시는 암호화가 아닙니다. 실제 차단 판단에는 원래 두 UUID를 사용하므로 해시 충돌은 불필요한 대기만 추가합니다.

</details>

참고: [반응 공통 검사](../src/main/java/com/harudle/feed/service/FeedInteractionService.java), [사용자 쌍 잠금](../src/main/java/com/harudle/block/service/BlockInteractionPolicy.java), [차단 선행 경쟁 테스트](../src/test/java/com/harudle/feed/service/BlockedInteractionPersistenceTest.java), [반응 선행 잠금 테스트](../src/test/java/com/harudle/block/infrastructure/BlockPersistenceTest.java).

## S10. 차단 후에도 가능한 본인 삭제·신고

```mermaid
flowchart TD
    START["차단 관계가 있는 대상에 요청"] --> ACTION{"어떤 요청인가?"}
    ACTION -->|본인 댓글 삭제| COMMENT["활성 부모 피드·본인 작성 여부 확인"]
    COMMENT --> DELETE["본인 댓글 soft delete · 댓글 수 감소"]
    ACTION -->|본인 피드 삭제| FEED["본인 소유권 확인 · 피드 삭제 · 미발송 취소"]
    ACTION -->|대상 신고| REPORTER["활성 신고자 확인"]
    REPORTER --> EXISTING{"본인의 기존 접수가 있는가?"}
    EXISTING -->|예| DUP["대상이 사라졌어도 기존 접수 안내 · 200"]
    EXISTING -->|아니요| TARGET["대상 유효성·자기 신고 금지 검사"]
    TARGET -->|유효한 타인| SAVE["신규면 201 · 동시 중복이면 기존 접수 200"]
    TARGET -->|대상 부재·비활성| NOTFOUND["본인 접수 재확인 후에도 없으면 404"]
    TARGET -->|자기 신고| SELF["400"]
```

- 차단 상대의 활성 피드에 예전에 남긴 **자신의 댓글은 삭제할 수 있습니다.** 남의 댓글 삭제는 403이며, 부모 경로가 이미 삭제·비활성이면 댓글 삭제도 404입니다.
- 본인 피드 삭제 시 앨범의 원본 일기는 유지합니다.
- 신고 대상 SQL은 차단 필터를 적용하지 않습니다. 차단 뒤에도 알고 있는 유효한 사용자·피드·댓글 ID로 신고할 수 있습니다. 화면에서 숨겨진 콘텐츠를 찾아주는 별도 경로를 추가하지는 않습니다.
- 탈퇴한 신고자·OAuth 회원이 아닌 신고자는 기존 접수도 반환받지 않습니다.
- 기존 접수와 최초 사유·상세를 유지합니다. 다른 사람의 접수·상대 차단 여부·콘텐츠 본문을 반환하거나 상대에게 신고 알림을 보내지 않습니다.
- 신규 신고는 대상 활성 조건을 따릅니다. 삭제된 댓글은 신규 신고할 수 없지만, 본인의 기존 접수는 안내합니다. 댓글 작성자만 탈퇴한 경우는 부모와 댓글이 유효하면 신규 신고가 가능합니다.

참고: [댓글 삭제](../src/main/java/com/harudle/comment/service/CommentService.java), [피드 삭제](../src/main/java/com/harudle/feed/service/FeedDeletionService.java), [기존 접수·신규 접수](../src/main/java/com/harudle/report/service/ReportService.java), [차단 후 신고 테스트](../src/test/java/com/harudle/feed/service/BlockedContentVisibilityPersistenceTest.java).

## S11. 인앱 알림 목록·미읽음 수·읽음 처리

좋아요·댓글은 `notifications`에 인앱 알림을 기록합니다. 새 피드의 외부 푸시는 S12~S13의 별도 경로입니다.

```mermaid
flowchart TD
    START["본인의 인앱 알림 GET"] --> MEMBER["활성 회원·본인 커서 확인"]
    MEMBER --> VALID["부모 콘텐츠와 원인 행동의 노출 조건 확인"]
    VALID --> BLOCK{"수신자↔행동자 또는 수신자↔피드 작성자 차단?"}
    BLOCK -->|예| HIDE["DB에서 제외 · 행과 읽음 상태는 유지"]
    BLOCK -->|아니요| SHOW["목록 페이지와 조회 가능한 전체 미읽음 수 계산"]
    SHOW --> RESULT["프로필 결합 · 응답 · GET은 읽음 처리 안 함"]
```

```mermaid
flowchart TD
    PATCH["알림 읽음 PATCH"] --> VISIBLE{"현재 조회 가능한 알림인가?"}
    VISIBLE -->|없음·숨김| NOTFOUND["404"]
    VISIBLE -->|예| OWNER{"본인이 수신자인가?"}
    OWNER -->|아니요| DENIED["403"]
    OWNER -->|예| UPDATE["노출 조건 재확인 · 최초 read_at만 기록"]
    UPDATE -->|그 사이 숨겨짐| NOTFOUND
    UPDATE -->|성공| OK["반복 요청도 최초 읽음 시각 유지"]
```

| 원인·콘텐츠 상태                     | 알림 목록·미읽음 수                                      |
| ------------------------------------ | -------------------------------------------------------- |
| 피드·일기 삭제 또는 피드 작성자 탈퇴 | 제외                                                     |
| 어느 방향이든 관련 차단              | 제외                                                     |
| 원래 좋아요가 취소됨                 | 그 좋아요 알림 제외. 다시 좋아요하면 새 행동의 알림 사용 |
| 원인 댓글만 삭제됨                   | 댓글 알림 유지                                           |
| 행동자만 탈퇴함                      | 나머지 노출 조건을 만족하면 익명 프로필로 유지           |

목록과 전체 미읽음 수는 같은 페이지 조회 트랜잭션의 스냅샷을 사용합니다. 필요한 활성 프로필을 제공받지 못하면 503입니다. 읽기 중 생긴 차단으로 이미 진행 중인 응답을 강제 취소하지는 않습니다. 외부 푸시 수신이 인앱 읽음 상태를 바꾸지는 않습니다.

참고: [목록·읽음 서비스](../src/main/java/com/harudle/notification/service/NotificationService.java), [공통 노출 조건 SQL](../src/main/java/com/harudle/notification/infrastructure/JpaNotificationRepository.java), [알림 통합 테스트](../src/test/java/com/harudle/notification/infrastructure/NotificationPersistenceTest.java).

## S12. 새 피드 게시·푸시 예약과 차단의 경쟁

```mermaid
flowchart TD
    START["본인의 새 피드 게시"] --> FEED["일기 소유권·생성 완료·카테고리 확인 후 피드 저장"]
    FEED --> LOCK["작성자의 푸시 예약 공유 잠금"]
    LOCK --> RECIPIENT["별도 조회로 활성 수신자·기기·세션·차단 확인"]
    RECIPIENT --> ELIGIBLE{"본인·차단 조건까지 통과한 기기가 있는가?"}
    ELIGIBLE -->|아니요| SKIP["예약 0건"]
    ELIGIBLE -->|예| OUTBOX["기기별 PENDING 예약 · 중복 이벤트 제외"]
    OUTBOX --> COMMIT["피드·예약 함께 커밋"]
    SKIP --> COMMIT
    COMMIT --> JOBS{"새 예약이 1건 이상 생겼는가?"}
    JOBS -->|예| WAKE["커밋 후 워커 깨우기"]
    JOBS -->|아니요| DONE["예약 없이도 게시 완료"]
```

```mermaid
sequenceDiagram
    participant P as 피드 게시·예약
    participant L as 작성자별 예약 잠금
    participant B as 두 회원 사이 차단
    alt 예약이 먼저 잠금을 얻음
        P->>L: 작성자 공유 잠금
        B->>L: 두 작성자 배타 잠금 대기
        P->>P: 예약 저장·커밋
        P-->>L: 공유 잠금 해제
        B->>L: 배타 잠금 획득
        B->>B: 차단 저장·기존 미발송 취소·커밋
    else 차단이 먼저 잠금을 얻음
        B->>L: 두 작성자 배타 잠금
        P->>L: 공유 잠금 대기
        B->>B: 차단 저장·미발송 취소·커밋
        B-->>L: 배타 잠금 해제
        P->>L: 공유 잠금 획득
        P->>P: 새 조회에서 차단 수신자를 제외하고 예약
    end
```

- 다른 회원과 차단 중이어도 **본인의 피드 게시 자체는 가능합니다.** 제한하는 것은 상대에게 노출·예약되는 경로입니다.
- 유효한 기기 등록·인증 세션·리프레시 토큰이 있는 활성 회원을 대상으로 합니다. 같은 회원의 여러 기기는 여러 예약을 가질 수 있습니다.
- 차단은 두 작성자의 잠금을 공통 순서로 얻고 A↔B의 `PENDING`·`PROCESSING`만 취소합니다. `SENT`·무관한 회원 작업은 바꾸지 않습니다.
- 같은 작성자의 게시끼리는 공유 잠금을 함께 사용합니다. 차단 저장과 미발송 취소는 함께 커밋·롤백됩니다.
- 이 DB 잠금을 잡은 채 외부 FCM 응답을 기다리지 않습니다. 발송 경계는 다음 절과 같습니다.

참고: [피드 게시](../src/main/java/com/harudle/feed/service/FeedPublicationService.java), [예약 SQL](../src/main/java/com/harudle/push/infrastructure/JpaFeedPushOutbox.java), [공유·배타 잠금](../src/main/java/com/harudle/push/infrastructure/PostgresFeedPushPublicationGuard.java), [두 순서의 DB 경쟁 테스트](../src/test/java/com/harudle/push/infrastructure/BlockedPushDeliveryPersistenceTest.java).

## S13. 예약된 푸시 발송·실패·차단

```mermaid
flowchart TD
    START["커밋 후 이벤트 또는 주기 확인"] --> EXPIRE["기한이 지난 작업 정리"]
    EXPIRE --> CLAIM["실행 가능한 PENDING·선점 만료 PROCESSING 선점"]
    CLAIM --> TARGET{"현재 차단·콘텐츠·회원·기기·세션 조건 유효?"}
    TARGET -->|아니요| CANCEL["전송 없이 CANCELLED"]
    TARGET -->|예| SEND["DB 잠금을 유지하지 않고 FCM 호출"]
    SEND -->|성공| SENT["현재 선점 토큰이 맞으면 SENT"]
    SEND -->|일시 오류| RETRY["기한 안이면 지연 후 재시도"]
    SEND -->|재시도 불가·기한 초과| FAIL["FAILED"]
    RETRY --> CLAIM
```

**차단 관계를 발송 직전에 다시 확인합니다. 다만 그 확인을 통과한 직후 차단되거나 외부 서비스로 이미 전달됐다면 푸시는 도착할 수 있습니다.** DB가 `CANCELLED`여도 이미 진행 중인 외부 전송을 회수한다는 뜻은 아닙니다.

| 경계 상황                               | 실제 처리                                                                    |
| --------------------------------------- | ---------------------------------------------------------------------------- |
| 아직 예약 전 차단                       | 예약 대상에서 제외                                                           |
| 미발송 PENDING·PROCESSING 상태에서 차단 | 관련 작업 취소                                                               |
| 선점 후 대상 확인 시 차단 발견          | FCM 호출 없이 취소                                                           |
| 대상 확인 직후 차단·외부 전송 진행 중   | 도착 가능. DB 취소로 외부 전송을 되돌리지는 못함                             |
| 차단 해제                               | 취소한 과거 작업 재예약 없음                                                 |
| 워커 중단·일시적 FCM 오류               | 만료 선점·재시도 정책에 따라 다시 처리. 다음 시도도 대상 검사                |
| 기기 토큰이 폐기됨                      | 전송 당시 값·버전과 같을 때만 등록 해지. 갱신된 기기 값을 잘못 해지하지 않음 |

선점 이후 취소·재시도·실패를 포함한 상태 변경은 현재 선점 토큰이 일치할 때만 적용합니다. 차단이 이미 취소한 작업을 늦게 도착한 발송 결과가 `SENT`·`PENDING`·`FAILED`로 되돌리지 않습니다.

`SENT`는 기기 수신 확인이 아닙니다. 중복 외부 전송을 완전히 없애거나 도착 가능한 개수를 제한하는 보장도 없습니다. 앱의 발송 시도 기한 24시간과 FCM 전달 TTL 7일은 별개입니다. 푸시에는 일반 안내와 이벤트·피드 ID를 담고 일기 원문·댓글 본문을 복사하지 않습니다.

참고: [발송 워커](../src/main/java/com/harudle/push/infrastructure/FeedPushDeliveryWorker.java), [선점·재검사·상태 변경](../src/main/java/com/harudle/push/infrastructure/JdbcPushDeliveryQueue.java), [FCM 메시지](../src/main/java/com/harudle/push/infrastructure/FcmFeedPushSender.java), [차단 발송 테스트](../src/test/java/com/harudle/push/infrastructure/BlockedPushDeliveryPersistenceTest.java).

## S14. 숨김과 데이터 보존 범위

| 대상                  | 차단 시                                                       | 모든 방향의 차단이 해제된 뒤               |
| --------------------- | ------------------------------------------------------------- | ------------------------------------------ |
| 피드·댓글             | 로그인한 두 회원의 조회에서 제외. 행을 삭제하지 않음          | 기존 공개·삭제 조건을 만족하면 다시 표시   |
| 기존 좋아요·댓글 수   | 삭제·재집계하지 않음. 보이는 댓글 수와 전체 수가 다를 수 있음 | 기존 값 유지                               |
| 인앱 알림·읽음 시각   | 관련 알림을 조회·미읽음 집계에서 제외. 상태는 유지            | 다른 노출 조건도 유효하면 기존 상태로 표시 |
| 미발송 외부 푸시      | 관련 예약 취소                                                | 과거 취소 작업을 재예약하지 않음           |
| 신고 접수·최초 사유   | 보존하며 본인의 기존 접수 재사용                              | 그대로 유지                                |
| 비로그인 공개 열람    | 기존 공개 조건 유지                                           | 그대로 유지                                |
| 탈퇴한 상대의 차단 행 | 소프트 탈퇴·OAuth 상실 시 행 유지. 목록에서 본인이 해제 가능  | 본인 방향 DELETE로 정리 가능               |
| 회원의 물리 삭제      | 외래키의 CASCADE로 관련 차단 행 삭제                          | 보존 예외이며 영구 보존을 보장하지 않음    |

상대가 나를 차단했는지는 목록·차단 API 응답으로 반환하지 않습니다. 목록에는 본인이 저장한 방향만 제공합니다. 관리자 신고 심사·계정 제재는 후속 #427의 범위입니다.

참고: [정책 설명](user-blocking.md), [DB 제약·삭제 규칙](../src/main/resources/db/migration/V36__create_user_blocks.sql), [본인 목록 응답](../src/main/java/com/harudle/block/service/dto/BlockedUserResult.java).
