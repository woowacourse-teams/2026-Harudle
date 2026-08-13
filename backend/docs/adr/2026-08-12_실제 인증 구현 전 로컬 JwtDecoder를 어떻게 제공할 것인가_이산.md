# 2026-08-12 실제 인증 구현 전 local 프로필 전용 JwtDecoder 사용

- 작성일: 2026-08-12
- 작성자: 이산
- 결정 대상: 실제 OAuth 및 JWT 구현 전 로컬 실행과 통합 테스트의 인증 구성

## 배경

`ApiSecurityConfiguration`은 `/api/v1/**` 요청의 Bearer Token을 검증하기 위해 `JwtDecoder`를 주입받는다. 현재 OAuth 로그인,
Access Token 발급과 실제 JWT 검증 방식은 구현되지 않아 `JwtDecoder` Bean이 존재하지 않는다. 이 때문에 Spring
ApplicationContext가 생성되지 않고 애플리케이션 실행과 일부 통합 테스트가 실패한다.

공유 링크 API는 로그인한 사용자의 ID로 그림일기 소유권을 확인해야 한다. 실제 인증 구현 전까지도 SecurityFilterChain과 사용자
식별 흐름을 유지하면서 로컬 개발을 진행할 방법이 필요하다.

## 결정 요인

- 실제 인증 구현 전에도 로컬 애플리케이션이 실행되어야 한다.
- 공유 API에서 인증 사용자 ID와 소유권 검증 흐름을 확인할 수 있어야 한다.
- 실제 토큰 발급 및 서명 검증 정책을 공유 기능에서 임의로 결정하지 않아야 한다.
- 임시 인증 방식이 운영 환경에서 활성화되지 않아야 한다.
- 실제 인증 구현이 들어올 때 제거하거나 교체할 범위가 명확해야 한다.

## 고려한 대안

### 대안 1: 실제 JwtDecoder를 먼저 구현

장점은 다음과 같다.

- 운영 환경과 같은 JWT 검증 흐름을 사용할 수 있다.
- 임시 인증 설정이 필요하지 않다.

단점은 다음과 같다.

- 토큰 발급 주체, 서명 알고리즘과 키 관리 정책을 먼저 결정해야 한다.
- OAuth 및 인증 담당 범위를 공유 기능에서 임의로 구현하게 된다.
- 인증 구현 일정에 다른 API 개발이 종속된다.

### 대안 2: 로컬에서 Security를 비활성화하거나 JwtDecoder를 선택적 의존성으로 변경

장점은 다음과 같다.

- 별도 토큰 없이 애플리케이션을 실행할 수 있다.
- 임시 Decoder 구현이 필요하지 않다.

단점은 다음과 같다.

- 인증 사용자 ID와 소유권 검증 흐름을 확인하기 어렵다.
- 인증 설정이 불완전한 상태를 애플리케이션 코드가 묵인한다.
- 실제 SecurityFilterChain과 다른 환경에서 API를 개발하게 된다.

### 대안 3: local 프로필 전용 JwtDecoder와 테스트 Mock 사용

장점은 다음과 같다.

- SecurityFilterChain을 유지하면서 로컬 사용자 ID를 전달할 수 있다.
- `local` 프로필로 운영 환경과 격리할 수 있다.
- 통합 테스트는 필요한 클래스에서 `@MockitoBean`으로 의존성만 제공할 수 있다.

단점은 다음과 같다.

- 실제 JWT 서명과 만료 검증을 확인하지 못한다.
- 로컬 전용 토큰 사용법을 개발자가 알아야 한다.
- 실제 인증 구현 이후 임시 설정을 재검토해야 한다.

## 결정

대안 3을 선택한다.

실제 인증 구현이 완료되기 전까지 `local` 프로필에서만 동작하는 `LocalJwtConfiguration`을 사용한다.

```java
@Profile("local")
@Configuration(proxyBeanMethods = false)
public class LocalJwtConfiguration {
}
```

로컬 Decoder는 Bearer Token으로 전달된 UUID를 JWT의 `subject`로 설정한다. UUID가 아닌 토큰은 인증 실패로 처리한다.

```http
Authorization: Bearer 96c7cb9d-c451-4a98-a28b-eecf56c47482
```

로컬 실행 시 `SPRING_PROFILES_ACTIVE=local`을 IntelliJ Run Configuration 또는 실행 명령의 환경 변수로 지정한다.

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

통합 테스트는 로컬 프로필에 의존하지 않고 필요한 클래스에 다음 Bean을 제공한다.

```java
@MockitoBean
private JwtDecoder jwtDecoder;
```

실제 인증 구현을 연결할 때 인증 담당자에게 다음 내용을 전달한다.

- `ApiSecurityConfiguration`이 요구하는 실제 `JwtDecoder` Bean을 제공해야 한다.
- Access Token의 `subject`에 하루들 사용자 UUID를 저장하거나 `AuthenticatedUserIdResolver`의 사용자 식별 규칙을 함께 변경해야 한다.
- 서명 알고리즘, 발급자, 대상자, 만료 시간과 검증 키 관리 방식을 결정해야 한다.
- 실제 Decoder와 `LocalJwtConfiguration`의 Bean 충돌 여부를 확인해야 한다.
- 실제 인증으로 로컬 개발이 가능해지면 `LocalJwtConfiguration`의 제거 또는 유지 여부를 결정해야 한다.
- 인증 성공, 만료, 변조, 잘못된 발급자와 사용자 ID 형식에 대한 인증 전용 테스트를 추가해야 한다.

## 긍정적 결과

- 실제 인증 구현 전에도 애플리케이션과 전체 테스트를 실행할 수 있다.
- 공유 API의 인증 사용자 전달과 소유권 검증 흐름을 개발할 수 있다.
- 운영 환경의 실제 JWT 정책을 공유 기능에서 미리 결정하지 않는다.
- `local` 프로필과 테스트 Bean으로 임시 설정의 적용 범위를 분리한다.

## 부정적 결과와 트레이드오프

- 로컬 Decoder는 실제 JWT 서명, 만료, 발급자와 대상자를 검증하지 않는다.
- `local` 프로필이 운영 환경에서 활성화되면 임의의 UUID로 인증할 수 있으므로 보안상 위험하다.
- `.env.example`에 `SPRING_PROFILES_ACTIVE=local`을 기본값으로 추가할 수 없다.
- 실제 인증 구현 이후 임시 설정과 테스트 Mock이 인증 오류를 가리지 않는지 다시 검토해야 한다.
