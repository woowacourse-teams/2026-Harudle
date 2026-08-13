## 배경

하루들 백엔드는 서로 다른 두 가지 인증 흐름을 함께 처리한다.

- 카카오 OAuth 로그인은 브라우저를 카카오로 보냈다가 콜백으로 돌려받는 과정에서 OAuth 인가 요청과 `state`를
  임시로 보관해야 한다. Spring Security의 기본 `AuthorizationRequestRepository`는 이 정보를 `HttpSession`에
  저장하므로 OAuth 과정에서는 `JSESSIONID`가 필요하다.
- 로그인 완료 후 하루들 API는 서버가 자체 발급한 Access Token JWT를 `Authorization: Bearer` 헤더로 검증한다.
  OAuth 과정에서 생성된 `JSESSIONID`는 API 인증 수단으로 인정하지 않는다.

한 `SecurityFilterChain`에 OAuth 로그인과 API 보안을 모두 구성할 수도 있다. 그러나 하나의 `HttpSecurity`에는
기본적으로 하나의 세션 생성 정책과 하나의 `SecurityContextRepository` 정책이 적용된다. 

한 체인에서 `SessionCreationPolicy.IF_REQUIRED`를 사용하면 OAuth 세션의 인증 정보가 보호 API의 `authenticated()` 판단에
사용되지 않도록 별도 제어가 필요하다. 반대로 전역 `STATELESS`와 세션 기반 OAuth 인가 요청 저장소를 함께 사용하면
동작은 구성할 수 있지만, 코드만 보고 세션 사용 경계를 이해하기 어렵고 복잡해진다.

## 결정

하루들 백엔드는 두 개의 `SecurityFilterChain`을 사용한다.

### 1. OAuth 로그인 체인

대상 경로:

```text
/oauth2/**
/login/oauth2/**
```

정책:
- 가장 먼저 검사되도록 `@Order(1)`을 사용한다.
- `SessionCreationPolicy.IF_REQUIRED`를 사용한다.
- `oauth2Login()`을 구성한다.
- 로그인 시작과 콜백 요청을 허용한다.
- Spring Security의 기본 OAuth 인가 요청 저장소를 사용해 요청과 콜백 사이의 `state`를 검증한다.
- 로그인 성공 시 서비스 Refresh Token을 발급하고 사전에 설정된 프론트엔드 URL로 리다이렉트한다.
- OAuth 세션 인증을 하루들 서비스 로그인 유지 수단으로 사용하지 않는다.

### 2. API 및 fallback 체인

대상 경로:

```text
OAuth 체인에 매칭되지 않은 나머지 모든 경로
```

정책:

- `securityMatcher`를 지정하지 않아 모든 나머지 요청을 처리하는 기본 체인으로 사용한다.
- `SessionCreationPolicy.STATELESS`를 사용한다.
- 보호 API는 서비스 Access Token JWT를 Bearer Token으로 검증한다.
- `JSESSIONID`가 요청에 포함되어도 세션의 `SecurityContext`를 API 인증에 사용하지 않는다.
- `/api/v1/auth/refresh`와 `/api/v1/auth/logout`은 Access Token 없이 호출할 수 있지만, 각 엔드포인트가 Refresh
  Token Cookie를 직접 검증한다.
- 브라우저가 자동 전송하는 Cookie에 의존하는 Refresh 및 Logout 요청에는 별도의 CSRF 보호 정책을 적용한다.
- `/api/v1/public/**`처럼 명시된 공개 API와 오류 처리에 필요한 경로만 허용한다.
- 보호 API는 인증을 요구하고, 그 밖의 등록되지 않은 경로는 `denyAll()`로 차단한다.

두 번째 체인이 API와 fallback 역할을 함께 담당하므로 별도의 세 번째 차단 체인은 만들지 않는다.

## 검토한 대안

### 하나의 `SecurityFilterChain` 사용

Bean과 설정 수가 줄어든다는 장점이 있다. 그러나 OAuth의 임시 세션과 API의 JWT 인증이 같은 체인에 존재하므로,
세션 인증이 API의 `authenticated()` 조건을 만족하지 않도록 인증 타입 또는 `SecurityContextRepository`를 별도로
제어해야 한다. 세션 경계가 설정 구조만으로 드러나지 않고, 잘못된 변경으로 `JSESSIONID`가 API 인증에 사용될 위험이
있어 채택하지 않는다.

### OAuth, API, fallback의 세 체인 사용

각 경로 영역이 가장 명시적으로 분리된다는 장점이 있다. 하지만 API 체인을 나머지 모든 요청을 처리하는 기본
체인으로 만들고 `anyRequest().denyAll()`을 적용하면 같은 보호 수준을 두 체인으로 달성할 수 있다. 체인 수와 공통
설정 중복을 줄이기 위해 채택하지 않는다.

### OAuth 인가 요청을 Cookie에 저장하고 전체 서버를 완전한 stateless로 구성

서버 세션을 완전히 제거할 수 있다. 반면 OAuth 인가 요청의 무결성과 기밀성을 보호하기 위한 Cookie 서명 또는
암호화, 크기 제한, 만료 및 삭제 정책을 직접 설계하고 검증해야 한다. 현재 MVP에서는 Spring Security의 기본 세션
기반 저장소를 사용하는 편이 단순하고 안전하므로 채택하지 않는다.

## 결과

- OAuth가 요구하는 임시 세션과 JWT API 인증의 경계가 코드 구조로 드러난다.
- OAuth 과정에서 발급된 `JSESSIONID`만으로 보호 API에 접근할 수 없다.
- API 요청마다 Access Token을 검증하므로 서버 세션을 서비스 로그인 유지 수단으로 사용하지 않는다.
- API와 fallback을 하나의 체인으로 구성해 미등록 경로가 Spring Security 바깥으로 빠지는 것을 방지한다.
- 향후 인증 방식을 변경할 때 어느 경로의 세션 및 인증 정책이 영향을 받는지 확인하기 쉽다.

## 트레이드오프

- `@Order`와 `securityMatcher` 순서가 보안 동작에 영향을 주므로 잘못된 순서나 경로 패턴을 주의해야 한다.
- Spring Security는 처음 매칭된 체인 하나만 실행하므로 OAuth 시작 및 콜백 경로가 반드시 OAuth 체인에 포함돼야
  한다.
- CORS, 보안 헤더, 예외 처리처럼 두 체인에 공통으로 필요한 설정이 중복될 수 있다. 공통 설정을 별도
  `Customizer`로 추출하더라도 각 체인에 실제로 적용됐는지 테스트해야 한다.
- OAuth 흐름 중에는 서버 세션이 생성되므로 완전히 무상태인 서버는 아니다. 다만 이 세션은 OAuth 요청 상관관계
  확인을 위한 임시 상태이며 API 인증에는 사용하지 않는다.
- 체인별 CSRF 적용 범위가 달라질 수 있으므로 Bearer JWT API와 Refresh Cookie API를 구분해 설정해야 한다.

## 구현 및 검증 조건

- [ ] OAuth 체인은 API 및 fallback 체인보다 높은 우선순위를 가진다.
- [ ] `/oauth2/authorization/kakao` 요청이 카카오 인증 화면으로 리다이렉트되고 OAuth 인가 요청용 세션이 생성된다.
- [ ] `/login/oauth2/code/kakao` 콜백에서 `state`가 없거나 일치하지 않으면 로그인이 실패한다.
- [ ] `JSESSIONID`만 포함한 `/api/v1/me` 요청은 `401 Unauthorized`를 반환한다.
- [ ] 유효한 서비스 Access Token을 포함한 `/api/v1/me` 요청은 인증된다.
- [ ] Refresh 및 Logout은 Access Token 대신 Refresh Token Cookie를 검증한다.
- [ ] 등록된 공개 API는 인증 없이 접근할 수 있다.
- [ ] OAuth와 API에 속하지 않는 미등록 경로는 접근이 거부된다.
- [ ] 각 요청이 의도한 체인 하나에 매칭되는지 통합 테스트로 검증한다.

## 재검토 조건

다음 조건 중 하나가 발생하면 체인 구조를 다시 검토한다.

- OAuth 인가 요청 저장소를 세션이 아닌 Cookie 또는 외부 저장소로 변경
- 모든 인증 흐름에서 서버 세션을 제거해야 하는 운영 요구 발생
- 서비스가 여러 독립 API 또는 관리자 보안 영역으로 분리됨
- OAuth와 API의 CORS, CSRF, 보안 헤더 설정 중복이 유지보수 문제로 커짐
- 세션 인증을 사용하는 별도의 웹 화면 또는 관리자 기능 도입

## 참고 자료

- [Spring Security Java Configuration](https://docs.spring.io/spring-security/reference/servlet/configuration/java.html)
- [Spring Security Session Management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- [Spring Security OAuth2 Authorization Grant Support](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html)
