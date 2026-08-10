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

## 도메인 패키지

백엔드는 기능과 데이터 소유권을 기준으로 다음 네 개의 최상위 도메인 패키지로 구성합니다.

```text
com.harudle
├── auth
├── diary
├── generation
└── share
```

| 패키지 | 역할 | 소유 데이터 |
| --- | --- | --- |
| `auth` | 사용자 조회, 카카오 OAuth 로그인, Access/Refresh Token 발급·갱신·폐기 | `users`, `oauth_accounts`, `refresh_tokens` |
| `diary` | 일기 저장, 월간 히스토리와 상세 조회, 소프트 삭제 | `diaries` |
| `generation` | 일일 생성 횟수, 멱등성, AI 생성 상태, 실패 및 고아 작업 복구 | `daily_generation_usage`, `generation_prompts`, `comic_generations` |
| `share` | 성공한 생성 결과의 공유 링크 생성과 인증 없는 공개 조회 | `share_links` |

각 도메인은 구현 규모에 따라 `domain`, `application`, `presentation`, `infrastructure` 하위 패키지로 확장합니다.
AI와 S3 같은 외부 시스템 연동은 독립 도메인으로 만들지 않고 `generation.infrastructure`의 어댑터로 둡니다. 보안 설정,
예외 처리와 같은 횡단 관심사는 도메인에 포함하지 않고 별도의 공통 영역에서 관리합니다.

현재 각 도메인의 `package-info.java`는 클래스가 없는 빈 디렉터리를 Git에 추적하고 역할을 표시하기 위한 임시 파일입니다.
해당 패키지에 실제 구현 클래스가 추가되면 대응하는 `package-info.java`를 삭제합니다.

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
