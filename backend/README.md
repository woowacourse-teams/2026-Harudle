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

## 문서

- [API 명세](docs/api-spec.md)

## 로컬 실행

예시 환경 변수 파일을 복사하고 `DB_PASSWORD`에 추측하기 어려운 로컬 비밀번호를 설정합니다.

```shell
cp .env.example .env
```

Windows PowerShell에서는 다음 명령을 사용할 수 있습니다.

```powershell
Copy-Item .env.example .env
```

PostgreSQL을 실행합니다. Docker Compose는 `backend/.env`를 자동으로 읽으며 데이터베이스 포트는 로컬
인터페이스에만 바인딩됩니다.

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
| `DB_PASSWORD` | 기본값 없음, 필수 |

애플리케이션은 실행 디렉터리의 `.env` 파일을 선택적으로 읽습니다. IntelliJ에서 애플리케이션을 직접 실행할 때는 Working
directory를 `backend`로 지정하거나 Run Configuration에 환경 변수를 설정합니다. 운영 환경에서는 `.env` 파일 대신 배포
환경의 Secret 또는 환경 변수로 모든 데이터베이스 접속 정보를 주입합니다.

PostgreSQL 18부터 데이터 볼륨은 `/var/lib/postgresql`에 마운트됩니다. 이전 설정으로 만든 개발용 볼륨을 초기화해도 되는
경우 다음 명령으로 삭제한 뒤 컨테이너를 다시 생성할 수 있습니다.

```shell
docker compose down -v
docker compose up -d
```

이 명령은 기존 로컬 데이터베이스 데이터를 삭제하므로 필요한 데이터가 있다면 먼저 백업합니다.

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
