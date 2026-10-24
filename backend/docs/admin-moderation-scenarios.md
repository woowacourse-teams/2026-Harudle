# 관리자 심사: 상황별 처리 흐름

[정책 요약](admin-moderation.md). 심사·제재·숨김은 별도 상태로 저장하며 사유와 처리 정보를 보존한다.

## 1. 신고 조회와 삭제된 대상

```mermaid
flowchart TD
    OPEN["신고 목록 · 상태와 페이지 선택"] --> AUTH{"활성 관리자 권한인가?"}
    AUTH -->|아니요| REJECT["인증·권한 오류 · 조회 없음"]
    AUTH -->|예| LIST["신고 목록 · 최신 시각과 ID 순서"]
    LIST --> SELECT["신고 상세 선택"]
    SELECT --> EXISTS{"대상 원본이 남아 있는가?"}
    EXISTS -->|예| CONTENT["공개 대상의 제목·이미지·댓글 · 삭제·숨김 상태"]
    EXISTS -->|아니요| MISSING["신고 기록 유지 · 원본 없음 표시"]
```

논리 삭제된 원본은 관리자 전용 조회에서 확인한다. 물리 삭제된 원본은 복구하지 않으며 비공개 일기 원문을 증거로 반환하지 않는다. 신고자·처리자 연결이 없어도 상세는 조회할 수 있다.

## 2. 심사 종결과 동시 처리

```mermaid
flowchart TD
    SEND["인증 관리자 · 종결 상태와 필수 사유"] --> LOCK["신고 행 잠금"]
    LOCK --> STATUS{"현재 접수 상태인가?"}
    STATUS -->|예| SAVE["종결 상태·최초 처리자·시각·사유 저장"]
    SAVE --> TX{"저장 성공?"}
    TX -->|예| OK["종결 결과 반환"]
    TX -->|아니요| ROLLBACK["전체 롤백 · 접수 상태 유지"]
    STATUS -->|아니요| SAME{"같은 결정과 정규화 사유인가?"}
    SAME -->|예| ORIGINAL["최초 처리 결과 반환 · 추가 저장 없음"]
    SAME -->|아니요| CONFLICT["409 · 최신 상세 확인"]
```

반려·처리 완료는 자동으로 계정을 제재하거나 콘텐츠를 숨기는 명령이 아니다. 이미 적용한 조치도 종결 실패만으로 되돌리지 않는다. 일반 사용자의 동일 대상 재신고는 기존 접수 안내를 유지한다.

## 3. 계정·콘텐츠 조치와 오래된 요청

```mermaid
flowchart TD
    READ["현재 상태와 버전 확인"] --> CONFIRM["조치·해제 선택 · 사유 입력"]
    CONFIRM --> SEND["원하는 상태와 읽은 버전 전송"]
    SEND --> LOCK["대상·조치 행 잠금"]
    LOCK --> REPLAY{"직전의 같은 관리자·명령 재요청인가?"}
    REPLAY -->|예| ORIGINAL["저장된 결과 반환"]
    REPLAY -->|아니요| VERSION{"현재 버전과 일치하는가?"}
    VERSION -->|아니요| CONFLICT["409 · 재전송 없이 최신 상태 확인"]
    VERSION -->|예| SAME{"이미 원하는 상태인가?"}
    SAME -->|예| NOOP["기존 상태·사유 보존"]
    SAME -->|아니요| SAVE["상태·새 버전·감사 이력 함께 저장"]
    SAVE --> ACCOUNT{"계정 제재인가?"}
    ACCOUNT -->|예| REVOKE["같은 트랜잭션에서 Refresh Token 폐기"]
    ACCOUNT -->|아니요| DONE["완료"]
    REVOKE --> DONE
```

해제 이후 과거의 제재 요청이 늦게 도착해도 버전이 달라 다시 제재하지 않는다. 이력 저장이나 토큰 폐기가 실패하면 상태 변경도 롤백한다. 관리자 계정·없는 대상의 변경은 거절한다. 물리 삭제된 대상도 남아 있는 조치 이력은 조회할 수 있다.

## 4. 제재된 계정의 로그인과 기존 세션

```mermaid
flowchart TD
    REQUEST["새 요청"] --> TYPE{"인증 경로"}
    TYPE -->|OAuth 로그인| OAUTH["기존 회원의 제재 상태 확인"]
    TYPE -->|Refresh 쿠키| REFRESH["토큰·회원의 제재 상태 확인"]
    TYPE -->|Bearer API| JWT["기존 JWT 인증 뒤 DB 제재 상태 확인"]
    TYPE -->|비로그인 공개 열람| PUBLIC["기존 공개 접근 규칙"]
    OAUTH --> BLOCK{"현재 제재 중인가?"}
    REFRESH --> BLOCK
    JWT --> BLOCK
    BLOCK -->|예| DENY["계정 제한 오류 · 토큰 신규 발급·API 실행 없음"]
    BLOCK -->|아니요| ALLOW["기존 인증·권한 규칙 계속 적용"]
```

발급은 사용자 공유 잠금을 먼저 잡고 세션·토큰 순서로 진행한다. 제재는 같은 사용자의 배타 잠금을 잡은 뒤 토큰을 폐기하므로, 앞서 진행하던 발급이 끝나면 새로 발급된 토큰까지 폐기한다. 잠금 대기 뒤에는 READ COMMITTED로 최신 제재 상태를 확인한다.

로그아웃·CSRF 토큰 발급은 세션 정리에 필요한 예외다. 제재 확인용 DB에 문제가 생기면 허용 상태로 간주하지 않고 요청을 실패시킨다. 제재 커밋 전에 이미 통과한 요청·진행 중 생성·이미 발급된 이미지 URL을 소급 취소하지 않는다.

## 5. 콘텐츠 숨김과 해제

```mermaid
flowchart TD
    REQUEST["공개 조회·접촉·알림·공유 요청"] --> TYPE{"대상"}
    TYPE -->|피드와 공개 공유| FEED{"같은 일기의 피드에 관리자 숨김이 남아 있는가?"}
    TYPE -->|댓글| COMMENT{"댓글 또는 부모 일기의 피드가 숨김인가?"}
    FEED -->|예| HIDE["목록에서 DB 단계 제외 · 직접 접근 불가"]
    COMMENT -->|예| HIDE
    FEED -->|아니요| CHECK["삭제·공개성·개인 차단 등 기존 조건 확인"]
    COMMENT -->|아니요| CHECK
    UNHIDE["관리자가 숨김 해제"] --> CHECK
```

같은 일기의 과거 피드에 숨김이 남아 있으면 재게시나 새 피드의 개별 숨김 해제로 우회할 수 없다. 조치와 접촉은 일기→피드→댓글 순서로 잠가 동시 변경을 확인한다. 숨김 해제는 작성자 삭제·비공개 상태를 되돌리지 않는다. 개인 차단은 별도 조건이다. 목록은 숨김 대상을 제외한 뒤 정렬·페이지 제한을 적용하며, 기존 반응 수는 소급 재집계하지 않는다.

<details>
<summary>API 계약</summary>

| 요청 | 역할 |
| --- | --- |
| `GET /api/v1/admin/reports?status=RECEIVED&page=0&size=20` | 상태별 신고 목록 |
| `GET /api/v1/admin/reports/{id}` | 신고와 현재 대상 상세 |
| `PUT /api/v1/admin/reports/{id}/review` | `{status:DISMISSED 또는 RESOLVED,reason}` |
| `GET /api/v1/admin/moderation/{USER 또는 FEED 또는 COMMENT}/{id}` | 현재 조치 상태·버전 |
| `PUT /api/v1/admin/moderation/{type}/{id}` | `{restricted,expectedVersion,reason}` |
| `GET /api/v1/admin/moderation/{type}/{id}/history?page=0&size=20` | 최신 버전부터 조치 이력 |

조치가 없는 대상의 초기 버전은 0이다. 변경 사유는 앞뒤 공백을 제거한 Unicode 코드 포인트 기준 1~500자이며 NUL과 잘못된 UTF-16 문자열은 거절한다. 모든 경로는 기존 관리자 DB 권한 확인을 사용하고 응답은 `Cache-Control: no-store`다.

</details>
