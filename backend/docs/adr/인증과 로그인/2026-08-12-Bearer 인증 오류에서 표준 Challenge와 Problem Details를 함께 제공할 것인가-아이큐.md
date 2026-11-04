# 2026-08-12 Bearer Challenge와 Problem Details 병행

- 작성일: 2026-08-12
- 작성자: 아이큐
- 결정 대상: Spring Security 인증 및 인가 실패 응답의 표준 헤더와 공통 오류 본문 구성

## 배경

보호된 API에 Bearer 토큰이 없거나 유효하지 않으면 Spring Security 필터에서 인증 실패가 발생한다. 인증된 사용자에게 필요한
권한이 없으면 인가 실패가 발생한다. 이 오류들은 MVC Controller에 도달하기 전에 처리되므로 일반적인 전역 예외 처리기만으로는
응답 형식을 통일할 수 없다.

OAuth 2.0 Bearer Token 표준은 인증 실패 응답에서 `WWW-Authenticate` 헤더로 Bearer 인증 방식과 필요한 오류 정보를 전달하도록
정의한다. 한편 서비스의 API 오류 계약은 RFC 9457 Problem Details에 `code`와 `traceId`를 포함한다.

서비스 공통 JSON 본문만 직접 작성하면 Spring Security가 생성하는 Bearer challenge 헤더를 빠뜨릴 수 있다. 반대로 Spring
Security 기본 응답만 사용하면 다른 API 오류와 본문 계약이 달라진다. 두 계약을 어떤 방식으로 함께 제공할지 결정해야 한다.

## 결정 요인

- 인증 실패 시 RFC 6750의 `WWW-Authenticate` Bearer challenge를 보존해야 한다.
- 인증 실패와 인가 실패의 HTTP 상태를 각각 401과 403으로 구분해야 한다.
- Security 필터에서 발생한 오류도 서비스의 RFC 9457 Problem Details 형식을 사용해야 한다.
- 오류 본문에 안정적인 `code`와 요청별 `traceId`를 제공해야 한다.
- Spring Security가 이미 구현한 표준 헤더 생성 규칙을 중복 구현하지 않아야 한다.
- 표준 처리기와 공통 응답 작성기 의존성이 명시적으로 드러나야 한다.

## 고려한 대안

### 대안 1: 서비스가 인증 오류 상태, 헤더와 JSON 본문을 모두 직접 작성

커스텀 `AuthenticationEntryPoint`와 `AccessDeniedHandler`가 모든 응답 요소를 직접 구성한다.

장점은 다음과 같다.

- 응답 생성 흐름이 한 코드에 모인다.
- 서비스 요구에 맞게 헤더와 본문을 자유롭게 변경할 수 있다.
- Spring Security 기본 구현의 동작을 알지 않아도 된다.

단점은 다음과 같다.

- RFC 6750의 challenge 문법과 오류 매핑을 직접 유지해야 한다.
- 프레임워크가 지원하는 표준 동작을 중복 구현한다.
- 토큰 오류 종류가 추가되면 커스텀 코드가 표준과 어긋날 수 있다.
- 401과 403의 헤더 정책을 빠뜨리기 쉽다.

### 대안 2: Spring Security의 기본 Bearer 오류 응답만 사용

`BearerTokenAuthenticationEntryPoint`와 `BearerTokenAccessDeniedHandler`에 전체 응답을 맡긴다.

장점은 다음과 같다.

- RFC 6750에 맞는 Bearer challenge를 프레임워크가 처리한다.
- 인증과 인가 오류에 필요한 커스텀 코드가 적다.
- Spring Security 업데이트의 표준 동작을 활용할 수 있다.

단점은 다음과 같다.

- 서비스의 다른 오류와 Problem Details 본문 형식이 달라진다.
- 프론트엔드가 인증 오류만 별도의 응답 구조로 처리해야 한다.
- 서비스 오류 `code`와 요청별 `traceId`가 보장되지 않는다.
- 공통 오류 문서와 실제 Security 응답이 불일치할 수 있다.

### 대안 3: 표준 Bearer 처리기에 위임한 뒤 Problem Details 작성

인증 및 인가 처리기가 먼저 Spring Security의 표준 Bearer 처리기에 위임한다. 그 다음 기존 응답 헤더를 지우지 않고 공통 응답
작성기가 Problem Details 본문을 기록한다.

장점은 다음과 같다.

- RFC 6750의 `WWW-Authenticate` 헤더 생성을 Spring Security에 맡긴다.
- RFC 9457 본문과 서비스의 `code`, `traceId` 계약을 함께 제공한다.
- 표준 처리와 서비스 표현 책임이 분리된다.
- 두 처리기를 주입받아 숨은 의존 없이 구성할 수 있다.

단점은 다음과 같다.

- 표준 처리기를 먼저 호출해야 하는 순서 제약이 생긴다.
- 공통 응답 작성기가 기존 헤더를 덮어쓰지 않아야 한다.
- Spring Security 처리기가 응답 본문까지 커밋하도록 바뀌면 동작을 다시 검토해야 한다.
- 인증 및 인가 경로를 실제 필터 체인 테스트로 검증해야 한다.

## 결정

대안 3을 선택한다.

`ApiAuthenticationEntryPoint`는 주입받은 `BearerTokenAuthenticationEntryPoint`에 먼저 위임해 401 상태와 Bearer challenge를
구성한다. 이후 `ApiProblemResponseWriter`가 `UNAUTHORIZED` Problem Details 본문을 작성한다.

`ApiAccessDeniedHandler`도 주입받은 `BearerTokenAccessDeniedHandler`에 먼저 위임한 뒤 `FORBIDDEN` Problem Details를 작성한다.

```text
인증 또는 인가 실패
→ Spring Security Bearer 처리기가 상태와 표준 헤더 구성
→ ApiProblemResponseWriter가 기존 헤더를 유지하며 Problem Details 작성
```

표준 Bearer 처리기와 공통 응답 작성기는 구성 클래스에서 Bean으로 만들고 커스텀 처리기에 생성자 주입한다. 커스텀 처리기는 구체
구현을 내부에서 직접 생성하지 않는다.

## 긍정적 결과

- 클라이언트는 `WWW-Authenticate` 헤더로 표준 Bearer 인증 요구를 확인할 수 있다.
- 인증 및 인가 오류도 다른 API 오류와 같은 Problem Details 구조를 사용한다.
- 프론트엔드는 문자열 메시지가 아니라 안정적인 `code`로 오류를 분기할 수 있다.
- 오류 응답의 `traceId`로 같은 요청의 서버 로그를 찾을 수 있다.
- RFC 6750 헤더 규칙을 서비스 코드에서 다시 구현하지 않는다.
- 표준 처리기 의존성이 구성과 생성자에 명시된다.

## 부정적 결과와 트레이드오프

- 하나의 오류 응답을 표준 처리기와 공통 응답 작성기가 순서대로 구성한다.
- 위임 순서를 바꾸거나 응답을 초기화하면 challenge 헤더가 사라질 수 있다.
- Spring Security 버전을 올릴 때 기본 처리기의 응답 커밋 여부를 확인해야 한다.
- `WWW-Authenticate`만으로 로그인 이동이나 토큰 재발급 정책을 결정할 수 없으므로 클라이언트의 인증 상태 정책은 별도로 필요하다.

Bearer challenge는 인증 프로토콜 계약이고 Problem Details는 서비스 오류 표현 계약이다. 둘 중 하나를 대체하지 않고 함께 제공하며,
필터 체인 테스트에서 상태, `WWW-Authenticate`, `code`와 `traceId`가 모두 유지되는지 검증한다.
