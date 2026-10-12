# Docker deployment

이 구성은 EC2 호스트 Nginx가 HTTPS를 종료하고, Docker Compose의 프론트 Nginx로 요청을 전달하는 구조입니다.

```text
Internet -> host Nginx/Certbot -> 127.0.0.1:3000
                                 -> frontend Nginx
                                    -> React static files
                                    -> /api/* -> backend:8080 -> RDS/S3/Gemini
```

## EC2 최초 설정

Docker, Docker Compose v2, `jq`를 설치합니다. `BeforeInstall`은 이 도구들이 없으면 기존 배포 파일을 정리하기 전에 중단합니다.

CodeDeploy가 사용하는 고정 경로에 운영 환경 파일을 만들고 실제 값으로 교체합니다. 이 파일은 Git에 커밋하거나 CodeBuild 아티팩트에 포함하지 않습니다.

```bash
sudo mkdir -p /opt/harudle
sudo nano /opt/harudle/.env
sudo chmod 600 /opt/harudle/.env
```

EC2에서는 `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`을 설정하지 않습니다. S3 접근에는 인스턴스의 `ec2-project` IAM Role을 사용합니다.

`.env.docker.example`은 운영 환경 예시입니다. 개발 서버에서는 다음 표의 dev 값을 사용합니다. 두 환경 모두 `SPRING_PROFILES_ACTIVE=prod`를 사용하더라도 이미지 환경은 `DEPLOY_ENV`로 구분합니다.

서버 `.env`의 기존 `SHARE_PUBLIC_BASE_URL`을 `FEED_PUBLIC_BASE_URL`로 교체하고, 해당 환경의 프론트 피드 페이지 주소를 설정합니다. 운영 환경 예시는 다음과 같습니다.

```dotenv
FEED_PUBLIC_BASE_URL=https://www.harudle.com/feeds
```

`prod` 프로필은 이 값에 로컬 기본값을 적용하지 않습니다. 누락·빈 값·잘못된 URL이면 백엔드 애플리케이션 시작이 실패합니다. 개발 서버도 `prod` 프로필을 사용하므로 개발 프론트의 `/feeds` 주소를 명시해야 합니다.

| 항목 | dev | prod |
| --- | --- | --- |
| CodeDeploy 배포 그룹 | `harudle-dev` | `harudle-prod` |
| `DEPLOY_ENV` | `dev` | `prod` |
| `S3_BUCKET` | `techcourse-project-2026` | `techcourse-project-2026` |
| `S3_GENERATED_PREFIX` | `harudle/generated/diary-images/dev` | `harudle/generated/diary-images/prod` |
| `S3_REFERENCE_PREFIX` | `harudle/references/generation/dev` | `harudle/references/generation/prod` |

`HARUDLE_GENERATION_PROMPT_BOOTSTRAP_IMAGE_ASSET_OBJECT_KEY`에는 해당 환경의 기준 이미지 prefix 아래에 복사하고 검증한 **실제 파일 key**를 설정합니다. 예제의 실제 파일명은 `05-reference-style-asset.png`이며 DB 프롬프트가 참조하는 06 기준 이미지도 함께 이전해야 합니다. 이 설정만 바꿔도 기존 DB의 프롬프트나 일기 이미지 key가 변경되지는 않습니다. 기존 이미지·기준 이미지 복사 검증과 DB 참조 전환을 완료한 뒤 새 설정으로 배포합니다. 자세한 전환 순서는 [이미지 저장소 분리 절차](../docs/image-storage-isolation.md)를 따릅니다.

위 네 변수는 `.env`에 `이름=값`으로 각각 한 번씩 적습니다. 따옴표와 CRLF는 지원하지만 `export 이름=값`, `이름: 값`, 값 없는 선언은 거절합니다. 변수 치환이나 줄 끝 주석은 사용하지 않습니다. prefix 끝에는 `/`를 붙이지 않습니다. 초기화용 기준 이미지 key는 배포 검사의 필수값이 아니며, 프롬프트 초기화 기능을 사용할 때 설정합니다.

`DEPLOYMENT_GROUP_NAME`은 CodeDeploy가 제공합니다. `.env`에 넣지 않습니다. 배포 대상과 `.env`의 네 값이 다르거나 값이 빠지면 컨테이너 실행 전에 중단합니다.

이미지 설정은 서버 `.env`에서 관리합니다. 검사는 `.env` 선언과 Docker Compose가 해석한 최종 `backend.environment`의 네 값을 모두 배포 대상과 비교하므로, Compose의 `environment`나 추가 `env_file`이 값을 바꾸어도 컨테이너 실행 전에 중단합니다. Compose JSON과 파싱 오류 원문은 출력하지 않습니다. 별도 Spring·Java 옵션으로 덮어쓴 값은 추적하지 않으므로 다른 위치에 이미지 설정을 중복해서 넣지 않습니다.

Docker bridge 네트워크 안의 백엔드가 EC2 Instance Metadata Service(IMDSv2)에서 IAM Role 자격 증명을 받을 수 있도록, EC2 인스턴스의 `Metadata response hop limit`을 `2`로 설정해야 합니다. AWS 콘솔에서 인스턴스를 선택하고 `Actions > Instance settings > Modify instance metadata options`에서 변경합니다. `Http tokens`는 `required`로 유지합니다.

EC2의 이미지 로드와 컨테이너 실행은 아래 CodePipeline 배포 절차를 사용합니다.

## CodePipeline 배포

CodeBuild는 ARM64 환경에서 백엔드와 프론트엔드 이미지를 빌드한 뒤 이미지 압축본, `appspec.yml`, 운영 Compose 파일과 배포 스크립트를 하나의 CodePipeline 아티팩트로 만듭니다. CodeDeploy는 EC2에서 이미지를 로드하고 `/opt/harudle/compose.prod.yaml`로 컨테이너를 재기동합니다. EC2에서는 Gradle이나 Node 빌드를 수행하지 않습니다.

`DEPLOY_ENV=dev`인 인스턴스는 `compose.dev.yaml`도 적용합니다. 두 환경의 CloudWatch 로그·지표 수집과 배포 전 IAM 조건은 [모니터링 운영 안내](monitoring/README.md)에 정리했습니다. 모니터링 설정 파일은 아티팩트로 전달되지만 호스트 CloudWatch Agent에는 배포 후 환경별 설정을 별도로 추가해야 합니다.

CodeBuild 프로젝트는 다음 설정을 사용합니다.

```text
Source: CodePipeline
Environment: Amazon Linux / Standard / Small
Privileged mode: enabled
Service role: codebuild-project
Buildspec: buildspec.yml
CloudWatch log group: /aws/codebuild/project-2026
```

`buildspec.yml`은 빌드 호스트 아키텍처와 관계없이 최종 이미지를 `linux/arm64`로 생성하고 이미지 아키텍처를 검증합니다. x86 빌드 환경에서는 QEMU/binfmt를 등록하므로 Docker 교차 빌드를 위해 Privileged mode가 반드시 필요합니다.

CodeDeploy 애플리케이션은 EC2/온프레미스 플랫폼과 현재 위치 배포를 사용합니다. 배포 그룹 서비스 역할은 `codedeploy-project`이며, 에이전트를 직접 설치한 EC2에서는 Systems Manager 에이전트 구성 값을 `Never`로 설정합니다.

배포 수명주기는 다음과 같습니다.

```text
BeforeInstall    -> Docker/Compose/jq와 .env 파일 확인 후 기존 배포 파일 정리
AfterInstall     -> 체크섬 검증 후 ARM64 Docker 이미지 로드
ApplicationStart -> 이미지 환경 검사 후 docker compose up --no-build
ValidateService  -> 두 컨테이너 health 및 프론트 /health 확인
```

이미지 환경 검사는 기존 `configure_compose` 함수에서 수행합니다. `ApplicationStart`에서 검사에 실패하면 컨테이너 재생성을 시작하지 않습니다. 검사는 S3나 DB를 수정하지 않으며 파일 존재 여부와 이전 완료 여부까지 확인하지는 않습니다.

배포 묶음 루트에서 서버의 `.env`를 확인하는 명령입니다. 대상 서버에 맞게 `harudle-dev` 또는 `harudle-prod`를 지정합니다. 개발 서버에는 `compose.dev.yaml`도 설치되어 있어야 합니다.

```bash
DEPLOYMENT_GROUP_NAME=harudle-dev bash -c '
  source deploy/scripts/compose_environment.sh
  configure_compose /opt/harudle
'
```

핵심 테스트는 임시 `.env`와 Compose 파일로 정상 설정, 환경 불일치, 필수값 누락·중복, 지원하지 않는 선언, Compose 최종 환경 덮어쓰기와 오류 메시지의 값 비노출을 확인합니다. Bash, Docker Compose v2와 `jq`가 필요하며, `docker compose config`만 사용하므로 컨테이너 실행, Docker daemon, Python, AWS 인증은 필요하지 않습니다.

```bash
bash deploy/scripts/test_image_environment.sh
```

호스트 Nginx 설정 예시를 적용합니다.

```bash
sudo cp deploy/nginx/harudle.conf.example /etc/nginx/sites-available/harudle
sudo ln -s /etc/nginx/sites-available/harudle /etc/nginx/sites-enabled/harudle
sudo nginx -t
sudo systemctl reload nginx
```

HTTP 연결 확인 후 Certbot으로 인증서를 설정합니다.

```bash
sudo certbot --nginx -d harudle.com -d www.harudle.com
```

## 운영 명령

배포 묶음 루트에서 대상 그룹을 명시하고 설치된 Compose 파일로 조회합니다. 개발 서버에서는 `harudle-dev`를 사용합니다.

```bash
DEPLOYMENT_GROUP_NAME=harudle-prod bash -c '
  source deploy/scripts/compose_environment.sh
  configure_compose /opt/harudle
  docker compose "${COMPOSE_ARGS[@]}" ps
  docker compose "${COMPOSE_ARGS[@]}" logs --tail=100 backend frontend
'
```

컨테이너 재기동이 필요하면 환경 검증이 포함된 `application_start.sh` 훅을 사용합니다. 이 검증은 배포 스크립트의 실수를 방지하며, 사람이 직접 실행하는 Docker/AWS 명령의 권한을 제한하지는 않습니다.

## Kakao OAuth 설정

이 구성은 `feat/kakao-oauth`의 운영 `JwtEncoder`와 `JwtDecoder`를 사용합니다. `.env`의 카카오 키와 JWT Secret을 설정하고, 카카오 개발자 콘솔의 Redirect URI에 다음 값을 등록합니다.

```text
https://www.harudle.com/login/oauth2/code/kakao
```

JWT HMAC Secret은 최소 32바이트 난수를 Base64로 인코딩해서 설정합니다.

```bash
openssl rand -base64 32
```

운영 환경에서는 `SESSION_COOKIE_SECURE=true`, `REFRESH_COOKIE_SECURE=true`를 유지합니다. 컨테이너 Nginx는 `/api/**`, `/oauth2/**`, `/login/oauth2/**` 요청을 백엔드로 전달합니다.
