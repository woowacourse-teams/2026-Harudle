# 2026-10-13 FID와 deviceId를 함께 쓰는 이유

- 작성일: 2026-10-13
- 작성자: 이산
- 결정 대상: 푸시 등록에서 `deviceId` 유지 여부

## 배경

FCM이 알림을 보낼 때 FID를 쓰므로, 하루들이 만든 `deviceId`는 없어도 되는지 검토했다.

쉽게 말해 FID는 **알림을 보내는 주소**, `deviceId`는 **전에 등록한 브라우저·PWA 환경을 다시 찾는 번호**다. 주소는 바뀔 수 있지만, 같은 환경의 등록을 찾아 새 주소로 고치거나 그 환경의 알림만 끄려면 찾는 번호가 필요하다.

## 결정

- 현재 설계의 `deviceId`를 유지한다. 브라우저·PWA 저장 환경마다 하루들이 만든 UUID를 보관한다. 물리적인 기기의 고유 번호는 아니다.
- 한 로그인 세션에 푸시 환경을 여러 개 등록할 수 있다. `session_id`는 **누가 로그인했는지**, `deviceId`는 **어느 환경의 등록인지**, FID는 **어디로 알림을 보낼지** 나타낸다.
- 같은 환경에서 FID가 바뀌면 `deviceId`로 기존 등록을 찾아 발송 주소를 갱신한다. 그 환경에서 푸시를 끄면 해당 등록을 해제한다. 로그아웃하면 세션에 연결된 등록을 정리한다.
- `deviceId`만 안다고 다른 사람의 등록을 바꿀 수는 없다. 서버는 요청자의 유효한 세션과 등록의 소유 관계를 함께 확인한다.

## 이유

예를 들어 맥에서 Safari 웹페이지와 설치한 PWA가 각각 푸시를 켜면, 같은 계정으로 사용하더라도 알림을 받는 환경은 둘이다. 설치 과정에서 로그인 쿠키는 복사될 수 있지만 이후 웹 저장 데이터는 따로 관리되므로, **세션 하나에 등록 하나**라고 가정할 수 없다. [Apple의 웹 푸시 지원 범위](https://developer.apple.com/documentation/usernotifications/sending-web-push-notifications-in-web-apps-and-browsers), [WebKit의 웹 앱 저장소 설명](https://webkit.org/blog/14787/webkit-features-in-safari-17-2/)

Safari의 등록이 `deviceId=D1, FID=F1`이라고 하자. Firebase가 이 환경의 FID를 `F2`로 바꾸면, 서버는 `D1`을 찾아 `F1`을 `F2`로 고친다. PWA의 `deviceId=D2` 등록은 그대로 둔다. FID가 바뀔 수 있다는 점과 변경 통지는 [Firebase 웹 등록 가이드](https://firebase.google.com/docs/cloud-messaging/web/get-started)에 설명되어 있다.

FID만 등록의 식별자로 삼으면 `F2`가 왔을 때 서버는 그것이 `F1`을 쓰던 환경의 새 주소인지 바로 알 수 없다. `session_id`만으로 찾으면 같은 세션에 속한 다른 환경의 등록까지 구분할 수 없다.

## 결과

- 기존 `push_registrations.device_id`와 활성 `device_id` 유일 제약을 유지한다. 이번 결정으로 DB 마이그레이션은 추가하지 않는다.
- 프론트엔드는 환경별 `deviceId`를 보관하고, FID 변경 시에도 같은 `deviceId`로 등록을 갱신한다.
- `deviceId` 저장 값만 사라지고 FID가 유지되는 경우는 [로그인 세션 연결 ADR](2026-10-11_푸시%20등록을%20로그인%20세션에%20연결_이산.md)의 동일 세션 등록 정리 규칙을 따른다.
