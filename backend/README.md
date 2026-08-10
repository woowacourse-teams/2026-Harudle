# 하루들 백엔드

Java 21과 Spring Boot 4.1.0 기반의 하루들 백엔드 애플리케이션입니다.

## 기술 스택

- Java 21 (Amazon Corretto)
- Spring Boot 4.1.0
- Spring Web MVC
- Spring Data JPA
- Flyway
- Spring Security
- Spring Security OAuth2 Client & Resource Server
- PostgreSQL 18
- Gradle 9.5.1
- JUnit 6.0.3, AssertJ, Testcontainers

## 사전 준비

- Amazon Corretto 21
- Docker와 Docker Compose 2.2 이상

Gradle은 별도로 설치할 필요가 없습니다. 저장소에 포함된 Gradle Wrapper를 사용합니다.

## 로컬 실행

PostgreSQL을 먼저 실행합니다.

```shell
docker compose up -d
```

애플리케이션을 실행합니다.

```shell
./gradlew bootRun
```

Windows PowerShell에서는 다음 명령을 사용할 수 있습니다.

```powershell
.\gradlew.bat bootRun
```

기본 데이터베이스 접속 정보는 다음과 같습니다.

| 환경 변수 | 기본값 |
| --- | --- |
| `DB_HOST` | `localhost` |
| `DB_PORT` | `5432` |
| `DB_NAME` | `harudle` |
| `DB_USERNAME` | `harudle` |
| `DB_PASSWORD` | `harudle` |

실제 운영 환경에서는 모든 데이터베이스 접속 정보를 환경 변수로 주입합니다.

## 테스트

```shell
./gradlew test
```

통합 테스트는 Testcontainers가 PostgreSQL 컨테이너를 실행하므로 Docker가 필요합니다. Docker를 사용할 수 없는
환경에서는 컨테이너 기반 테스트가 자동으로 건너뛰어집니다.

Testcontainers의 PostgreSQL로 애플리케이션을 직접 실행하려면 다음 명령을 사용합니다.

```shell
./gradlew bootTestRun
```

## 기본 정책

- JPA의 Open Session in View는 비활성화되어 있습니다.
- Hibernate는 스키마를 자동 변경하지 않고 시작 시 스키마를 검증합니다.
- 인증 구현 전까지 Spring Security의 기본 보안 설정이 적용되어 모든 API가 보호됩니다.
