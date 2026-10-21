# Docker deployment

이 구성은 EC2 호스트 Nginx가 HTTPS를 종료하고, Docker Compose의 프론트 Nginx로 요청을 전달하는 구조입니다.

```text
Internet -> host Nginx/Certbot -> 127.0.0.1:3000
                                 -> frontend Nginx
                                    -> React static files
                                    -> /api/* -> backend:8080 -> RDS/S3/Gemini
```

## EC2 최초 설정

Docker, Docker Compose 2.24.4 이상, `jq`를 설치합니다. 검증 컨테이너의 로그 설정을 완전히
교체하는 [`!override`는 Compose 2.24.4 이상](https://docs.docker.com/reference/compose-file/merge/#replace-value)이 필요합니다.
`BeforeInstall`은 도구가 없으면 기존 배포 파일을 정리하기 전에 중단합니다.

CodeDeploy가 사용하는 고정 경로에 운영 환경 파일을 만들고 실제 값으로 교체합니다. 이 파일은 Git에 커밋하거나 CodeBuild 아티팩트에 포함하지 않습니다.

```bash
sudo mkdir -p /opt/harudle
sudo nano /opt/harudle/.env
sudo chmod 600 /opt/harudle/.env
```

EC2에서는 `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`을 설정하지 않습니다. S3 접근에는 인스턴스의 `ec2-project` IAM Role을 사용합니다.

`.env.docker.example`은 운영 환경 예시입니다. 개발 서버에서는 다음 표의 dev 값을 사용합니다. 두 환경 모두 `SPRING_PROFILES_ACTIVE=prod`를 사용하더라도 이미지 환경은 `DEPLOY_ENV`로 구분합니다.

서버 `.env`의 기존 `SHARE_PUBLIC_BASE_URL`을 `FEED_PUBLIC_BASE_URL`로 교체하고, 해당 환경의 프론트 피드 페이지 주소를 설정합니다. 운영 환경 예시는 다음과 같습니다.

일기 원문 암호화 배포 전에는 각 환경의 `/opt/harudle/.env`에 독립적인 32바이트 Base64
`DIARY_SOURCE_TEXT_KEY_BASE64`와 신뢰할 수 있는 `/etc/harudle/diary-key-checks.json`을 준비해야 합니다.
후보 이미지가 기존 검증 자료를 복호화하고 HMAC을 확인한 뒤에만 컨테이너를 교체합니다.
같은 ID에 다른 32바이트 키를 넣어도 실패하며 기존 컨테이너는 계속 실행됩니다. 앱 자체도
`prod` 프로필에서 같은 검증을 요구합니다. 키와 검증 자료는 배포마다 재생성하지 않습니다.
키는 JWT 키와 재사용하지 않습니다. 암호문을 쓰기 시작한 뒤의 복구 대상은 먼저 성공시켜 둔
암호화 호환 `reader` 배포본입니다. 전환 순서와 키 교체·복구 조건은
[일기 원문 암호화 운영 안내](../backend/docs/diary-source-text-encryption.md)를 따릅니다.

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

CodeBuild는 백엔드와 프론트엔드 ARM64 이미지를 빌드한 뒤 이미지 압축본, `appspec.yml`, 운영 Compose 파일과 배포 스크립트를 하나의 CodePipeline 아티팩트로 만듭니다. CodeDeploy는 EC2에서 이미지를 로드하고 `/opt/harudle/compose.prod.yaml`로 컨테이너를 재기동합니다. EC2에서는 Gradle이나 Node 빌드를 수행하지 않습니다.

`DEPLOY_ENV=dev`인 인스턴스는 `compose.dev.yaml`도 적용합니다. 두 환경의 CloudWatch 로그·지표 수집과 배포 전 IAM 조건은 [모니터링 운영 안내](monitoring/README.md)에 정리했습니다. 모니터링 설정 파일은 아티팩트로 전달되지만 호스트 CloudWatch Agent에는 배포 후 환경별 설정을 별도로 추가해야 합니다.

CodeBuild 프로젝트는 다음 설정을 사용합니다.

```text
Source: CodePipeline
Environment: Amazon Linux 2023 / AArch64 Standard 3.0 or 4.0 / Small
Privileged mode: enabled
Service role: codebuild-project
Buildspec: buildspec.yml
CloudWatch log group: /aws/codebuild/project-2026
```

`buildspec.yml`은 빌드 호스트 아키텍처와 관계없이 최종 이미지를 `linux/arm64`로 생성하고 이미지 아키텍처를 검증합니다. x86 빌드 환경에서는 QEMU/binfmt를 등록하므로 Docker 교차 빌드를 위해 Privileged mode가 반드시 필요합니다.

### 원문 암호화 최초 적용과 복구 배포본

CodeBuild의 `DIARY_SOURCE_TEXT_RELEASE_MODE`는 기본값이 `reader`입니다. 빌드할 때
선택값을 `diary-source-text-release-mode` 파일로 묶고, 배포 훅은 해당 Compose override를
마지막에 적용합니다. 서버 `.env`를 바꿔도 복구 배포본의 정책이 writer로 바뀌지 않습니다.

| 배포본 | 신규 일기 작성 | 기존 상세·동일 요청 재조회 | 보관 목적 |
| --- | --- | --- | --- |
| `reader` A | 503으로 차단 | 평문 v0·암호문 v1·HMAC 지원 | 최초 호환 배포 및 복구 기준 |
| `writer` B | v1 암호문·HMAC으로 저장 | 동일하게 지원 | A 성공 이후 정상 운영 |

1. 키·검증 자료를 아래 절차로 준비하고 서버 밖의 암호화된 보관소에 함께 백업합니다.
2. `reader`로 빌드한 A를 dev, prod에 각각 배포합니다. 조회·이미지 접근과 작성 차단을 확인하고
   CodeDeploy가 `Succeeded`인지 확인합니다. 커밋, CodeDeploy revision의 S3 bucket/key/version,
   이미지 ID, 담당자와 확인 시각을 운영 기록에 남기고 아티팩트를 보존합니다.
3. 같은 검토된 코드에서 `DIARY_SOURCE_TEXT_RELEASE_MODE=writer`를 명시해 B를 빌드·배포합니다.
   최초 전환에서는 A 확인 없이 writer로 직행하지 않습니다. 이후 정상 빌드도 writer를 명시합니다.
4. 장애 시 신규 작성을 차단한 호환 A 또는 그 이후의 reader 배포본을 선택합니다.
   암호화 미지원 구버전 수동 배포는 금지합니다. 과거 배포본의 훅은 이 검사를 포함하지 않으므로
   새 코드만으로 모든 구버전 수동 배포를 차단할 수는 없습니다.

자동 롤백이 필요한 배포는 그룹 설정뿐 아니라 그 배포의 override도 확인합니다.
[CodeDeploy 롤백은 이전 revision을 새 배포로 재실행](https://docs.aws.amazon.com/codedeploy/latest/userguide/deployments-rollback-and-redeploy.html)합니다.
성공 후 `docker image prune`을 실행하므로 복구를 기존 `:local` 태그나 dangling 이미지에 의존하지 않습니다.
`HARUDLE_GENERATION_ADAPTERS_ENABLED=false`는 이미지 URL 조회에도 영향을 주므로 reader를 대신하지 않습니다.

### 키 검증 자료의 명시적 관리

이 파일은 환경·키 ID별 공개 검증용 암호문과 HMAC입니다. 사용자 원문·비밀 키는 포함하지
않지만 변조되면 잘못된 키를 신뢰할 수 있으므로 root가 관리합니다. 일반 배포는 읽기 전용으로
마운트한 파일에 `verify`만 실행합니다. `verify`와 `register`는 DB에 연결하지 않습니다.
`initialize`는 최초 준비 조건을 JDBC로 검사하고, `verify-data`는 실제 v1 원문을 JDBC로 검증합니다.
어느 명령도 Spring context·Flyway·AI 클라이언트를 시작하지 않습니다.

| 명령 | 용도 | 허용하지 않는 사용 |
| --- | --- | --- |
| `initialize` | 정상 기존 스키마와 현재 v0만 있는 최초 설치에서 canary 생성 | 운영 canary 분실·손상 복구, v1·미지원 버전이 있는 DB의 재초기화 |
| `verify` | 기존 canary의 모든 키·환경·ID와 암복호화/HMAC 확인 | 파일 생성·복구·DB 데이터 전환 |
| `register` | 기존 모든 canary를 검증한 뒤 새 키 ID만 추가 | 구키 삭제·동일 ID의 키 변경·누락 파일 재생성 |
| `verify-data` | 현재 DB의 v1 전체를 인증 복호화하고 checked/failed만 집계 | canary 생성·v0 변환·#423의 전체 지문 검증 대체 |

#### 최초 initialize 조건

먼저 운영자가 모든 실제 writer와 진행 중인 작성 요청·worker를 중지·배출합니다. 현재 EC2·Compose
구성에서 이를 확인할 수 없으면 backend를 정지하고 배포·재시작을 통제합니다. 후보 컨테이너에
reader 설정을 넣는 것만으로 기존 앱과 다른 writer가 멈췄다고 판단하지 않습니다.

`initialize`는 `public.diaries`의 SHARE 잠금을 얻은 뒤 schema와 저장 버전을 확인합니다.
현재 v1이 한 건이라도 있거나 버전이 null/미지원이면 최종 canary를 생성하지 않고 거절합니다.
DB 연결·권한·schema 상태가 불명확한 경우도 빈 DB로 취급하지 않습니다.

정상 V28 이전 첫 업그레이드는 지원합니다. 필수 열과 `source_text VARCHAR(300) NOT NULL`,
성공한 Flyway V1 이력, V28 이상 이력이 없는 알려진 기존 schema를 확인한 경우입니다.
테이블이나 Flyway 이력이 준비되지 않은 새 DB는 먼저 검토된 기존 schema 준비 절차를 마쳐야 합니다.
이 명령이 schema를 만들거나 Flyway를 대신 실행하지는 않습니다.

현재 v1 0건은 최초 설치의 충분한 증거가 아닙니다. 과거 v1이 삭제되었거나 보존 DB 백업에 남아
있을 수 있습니다. 운영 canary를 잃은 환경은 현재 v1 0건이어도 아래 복구 절차를 사용합니다.
초기화는 임시 파일을 준비하고 DB 검사 트랜잭션의 커밋 성공을 확인한 뒤 최종 canary를 기존 파일
덮어쓰기 없이 공개합니다. 실패·커밋 불명확 상태의 임시 파일을 최종 이름으로 바꿔 신뢰하지 않습니다.

검토한 후보 이미지와 배포 파일을 로드한 뒤 배포 묶음 루트에서 실행하는 예입니다. `.env`는 root 소유 600,
검증 파일은 root:10001 소유 640이며 컨테이너 UID/GID 10001이 부모 경로를 통과할 수 있어야 합니다.

```bash
sudo bash <<'SH'
set -euo pipefail
set +x
export DEPLOYMENT_GROUP_NAME=harudle-dev # 운영은 harudle-prod
source deploy/scripts/compose_environment.sh
configure_compose /opt/harudle
install -d -m 0750 -o root -g 10001 /etc/harudle
override="$(mktemp)"
trap 'rm -f -- "$override"' EXIT
cat > "$override" <<'YAML'
services:
  backend:
    volumes: !override
      - type: bind
        source: /etc/harudle
        target: /var/run/harudle
    logging: !override
      driver: none
YAML
operation=initialize # 위 최초 준비 조건을 충족할 때만 사용. 신규 키 추가는 register
if ! docker compose "${COMPOSE_ARGS[@]}" --file "$override" run --rm --no-deps -T \
  --user 0:0 --env LOADER_MAIN=com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain \
  --entrypoint sh backend -c \
  'exec java ${JAVA_OPTS:-} -Xmx128m -Dloader.main="$LOADER_MAIN" -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher "$1"' \
  sh "$operation" >/dev/null 2>&1; then
  echo '키 검증 자료 관리 실패: 기존 키와 백업을 확인하세요.' >&2
  exit 1
fi
chown root:10001 /etc/harudle/diary-key-checks.json
chmod 640 /etc/harudle/diary-key-checks.json
SH
```

#### 운영 canary 분실·손상 복구

canary 검사는 앱 시작과 배포 전에 실행합니다. 실행 중 앱의 파일 유실을 계속 감시해 자동으로
쓰기를 중단하는 기능은 아닙니다. 분실·손상·키 불일치를 발견하면 운영자가 신규 쓰기를 차단하고
진행 중 요청을 배출합니다. 기존 writer가 계속 실행되거나 안전하게 배출할 수 없으면 backend를
정지합니다. 파일 복원 전에는 reader 재배포도 canary 검사에서 실패할 수 있습니다.

1. 마지막으로 신뢰한 **같은 백업 세대의 키링과 canary를 함께 복원**합니다. 암호화 설정만 복원하며
   오래된 `.env`의 DB 주소 등 다른 설정까지 무심코 되돌리지 않습니다. 현재 DB와 보존 중인 과거
   DB 백업이 참조하는 모든 구키를 포함해야 합니다. 백업 위치·세대·키 ID 목록·검토자를 기록합니다.
2. 파일 소유권·권한을 확인하고 `verify`를 통과시킵니다. canary를 새로 만드는 것은 복구가 아닙니다.
3. 복원한 키로 `verify-data`를 수행해 현재 DB의 모든 v1 인증 복호화를 확인합니다. v0는 건너뛰므로
   checked=0 또는 failed=0만으로 최초 설치나 전체 DB 암호화 완료를 증명하지 않습니다.
4. reader로 키 검증·본인 상세·동일 요청 재조회를 확인한 뒤 writer를 엽니다. 실제 DB 백업도 격리된
   환경에서 복원해 그 백업이 참조하는 키로 같은 검증과 조회를 확인합니다.
5. 신뢰된 키링/canary 쌍을 확보하지 못하거나 실제 v1 검증에 실패하면 중단합니다. 파일 삭제 후
   initialize, 임시 파일 이름 변경, 잘못된 키로 canary 재생성으로 우회하지 않고 별도 복구를 진행합니다.

복원한 canary가 설치된 뒤 배포 묶음 루트에서 실행하는 예입니다. 원시 JVM/SQL 진단은 숨기고,
허용된 두 집계 필드만 다시 출력합니다. 첫 `verify` 실패 시 데이터 검증도 진행하지 않습니다.

```bash
sudo bash <<'SH'
set -euo pipefail
set +x
export DEPLOYMENT_GROUP_NAME=harudle-dev # 운영은 harudle-prod
source deploy/scripts/compose_environment.sh
configure_compose /opt/harudle
override="$(mktemp)"
trap 'rm -f -- "$override"' EXIT
cat > "$override" <<'YAML'
services:
  backend:
    logging: !override
      driver: none
YAML
run_key_command() {
  docker compose "${COMPOSE_ARGS[@]}" --file "$override" run --rm --no-deps -T \
    --env LOADER_MAIN=com.harudle.diary.infrastructure.crypto.DiarySourceTextKeyCheckMain \
    --entrypoint sh backend -c \
    'exec java ${JAVA_OPTS:-} -Xmx128m -Dloader.main="$LOADER_MAIN" -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher "$1"' \
    sh "$1"
}
if ! run_key_command verify >/dev/null 2>&1; then
  echo '복원한 키 검증 자료를 확인할 수 없습니다.' >&2
  exit 1
fi
status=0
result="$(run_key_command verify-data 2>/dev/null)" || status=$?
if ! counts="$(jq -cse '
  select(length == 1) | .[0] |
  select(type == "object" and (keys | sort) == ["checked", "failed"]) |
  select([.checked, .failed] | all(type == "number" and . >= 0 and floor == .)) |
  {checked, failed}
' <<<"$result" 2>/dev/null)"; then
  echo '실제 v1 데이터 검증에 실패했습니다. 비공개 진단과 복구 자료를 확인하세요.' >&2
  exit 1
fi
printf '%s\n' "$counts"
if [[ "$status" != 0 ]]; then
  echo '실제 v1 데이터 검증에 실패했습니다. 쓰기를 재개하지 마세요.' >&2
  exit 1
fi
SH
```

키 추가 때마다 기존 키·활성 키를 유지하고 `register`한 뒤 모든 호환 앱에 키링을 먼저
반영·재배포합니다. 키 검증과 조회 확인 후 활성 키를 바꿉니다. 실행 중 앱의 키링은 시작 시 메모리
상태이므로 호스트 `.env`만 수정한 것으로 반영 완료를 판단하지 않습니다.

구키는 현재 DB뿐 아니라 보존 중인 과거 DB 백업의 복원에 필요한 기간까지 보관합니다.
실행자·확인 시각·checked/failed·키 ID·키링/canary 백업 세대·DB 백업 식별자는 기록하되
원문·키 값·암호문·지문·SQL 진단은 기록하지 않습니다. **실제 dev/prod 복구·백업 복원은 이 저장소
작업에서 수행하지 않았습니다.** 기존 v0 전환과 과거 평문/SHA의 보존·폐기는
[후속 #423 / PR #450](https://github.com/woowacourse-teams/2026-Harudle/pull/450)의 계약을 따릅니다.

### Docker 빌드 최적화

CodeBuild 호스트와 EC2는 모두 ARM64입니다. 백엔드는 CodeBuild의 Corretto 21로
JAR를 만든 뒤 Dockerfile의 `codebuild` 타깃으로 실행 이미지만 포장합니다.
최종 이미지는 기존처럼 `--platform linux/arm64`로 생성합니다. 기본 Docker 타깃은
소스에서 빌드하므로 기존 `docker compose build`도 사용할 수 있습니다.

프론트엔드의 PostHog 빌드 인자는 의존성 설치 이후에 선언하므로 분석 설정만
바뀌었을 때 `pnpm install` 레이어까지 무효화하지 않습니다. `pnpm check`는 그대로
실행합니다. 백엔드는 이전 클래스와 분석 상태를 복원하고 `clean` 없이 빌드합니다.

이미지는 `docker-container` 빌더에서 Docker 형식 tar로 직접 내보냅니다.
CodeBuild Docker daemon에 이미지를 로드하고 다시 `docker save`하는 단계를 줄입니다.
압축하기 전에 tar의 manifest와 이미지 설정을 읽어 예상 태그 및 `linux/arm64`를
검증하고, `gzip -1`로 압축한 뒤 gzip 무결성과 체크섬을 확인합니다.
CodeBuild 이미지에는 Docker/buildx, Corretto 21, `jq`, `tar`, `gzip`과 GNU coreutils가 필요합니다.
`install.runtime-versions.java: corretto21`을 지원하는 ARM64 표준 이미지를 사용합니다.

아티팩트 이름은 기존과 같은 `backend-image.tar.gz`, `frontend-image.tar.gz`이므로
CodeDeploy의 이미지 로드 절차를 그대로 사용합니다. 개선 효과는 CodeBuild 빌드와
아티팩트 내보내기·압축 구간의 시간을 기존 실행과 비교해서 확인합니다.

이미지 내보내기 설정은 [Docker 공식 문서](https://docs.docker.com/build/exporters/oci-docker/)를 참고합니다.

### CodeBuild 빌드 캐시

백엔드는 CodeBuild의 Corretto 21로 빌드합니다. Docker는 빌드가 끝난 JAR를
실행 이미지에 넣습니다. 프론트엔드는 기존처럼 `pnpm check`를 실행합니다.

빌드는 다음 순서로 진행합니다.

1. 이번에 받은 백엔드 코드를 `/root/.cache/harudle-gradle/workspace`로 복사합니다.
2. 이전 빌드의 클래스 파일과 Gradle 기록을 가져옵니다.
3. `bootJar -x test`를 실행합니다. 바뀐 소스와 영향을 받는 클래스만 다시 컴파일합니다.
4. 새 JAR를 `backend/build/codebuild/app.jar`에 놓고 Docker의 `codebuild` 타깃으로 포장합니다.
5. 두 이미지의 태그, ARM64 여부, 압축 파일을 검사합니다. 성공하면 이전 빌드 기록을 새 기록으로 바꿉니다.

작업 폴더는 매번 같은 경로를 사용합니다. 이번 소스는 새로 복사하고, 이전 소스를
캐시에서 가져오지는 않습니다. 기본 Docker 타깃도 유지해서 `docker compose build`를
사용할 수 있습니다.

#### 저장하는 파일

| 대상 | 저장 위치와 내용 |
| --- | --- |
| Gradle 실행 파일 | `home/wrapper/dists` |
| 다운로드한 라이브러리 | `home/caches/modules-2` |
| Gradle 준비 파일 | 스크립트 실행 결과, 변환 결과, Java 분석, 파일 해시, 접근 기록, `home/native` 실행 파일 |
| 이전 컴파일 결과 | 프로젝트 `.gradle`, 운영 클래스, 리소스, 생성 소스, 헤더, 컴파일 분석 파일 |
| 빈 폴더 목록 | `incremental/directories`. 복원할 때 같은 출력 폴더를 다시 만듭니다. |

위 경로의 기준은 `/root/.cache/harudle-gradle`입니다. 이전 컴파일 결과는 최근에 성공한
한 세트만 보관합니다. Gradle 설정, Wrapper, Java 버전, OS, CPU 종류가 바뀌면 그 결과를
사용하지 않고 다시 빌드합니다. Gradle도 입력 파일의 변경을 확인합니다.

완성된 JAR, JDK, Docker 레이어, 프론트엔드, 테스트 결과, 보고서, Daemon 로그는 저장하지
않습니다. `build/libs`를 복원하지 않으므로 JAR는 매번 새로 만듭니다. 클래스 파일을 또
저장하는 Gradle Build Cache와 Configuration Cache도 켜지 않습니다.

Gradle의 프로젝트 기록과 빈 출력 폴더를 함께 복원해야 컴파일을 생략할 수 있습니다.
프로젝트의 잠금 메타데이터도 보존하고, 의존성 캐시의 `modules-2.lock`만 제거합니다.
컴파일이나 이미지 생성이 실패하면 이전 성공 기록을 유지합니다. 캐시 저장만 실패한
경우에는 검사를 마친 이미지로 계속 진행합니다.

#### CodeBuild 설정

1. Corretto 21을 지원하는 Amazon Linux 2023 AArch64 Standard 3.0 또는 4.0 이미지를 사용합니다.
2. **Edit > Artifacts > Additional configuration**에서 Cache type을 **Amazon S3**로 선택합니다.
3. 기존 캐시 버킷과 팀 폴더 경로를 사용합니다. 예시는 `harudle/build-cache`입니다.
4. 변경한 `buildspec.yml`이 들어 있는 커밋으로 빌드합니다.

CodeBuild 역할에는 해당 캐시 경로의 `s3:GetObject`, `s3:PutObject`와 버킷의
`s3:GetBucketLocation`, `s3:GetBucketAcl` 권한이 필요합니다. 고객 관리 KMS 키를 쓰면
복호화와 데이터 키 생성 권한도 필요합니다.

`cache.key`는 넣지 않아 매 빌드 후 같은 캐시를 갱신합니다. dev와 prod가 같은 CodeBuild
프로젝트를 쓰면 이 캐시도 공유합니다. 소스가 다르면 Gradle이 변경된 부분을 확인합니다.
새 저장 경로로 바꾸기 때문에 첫 빌드에서는 캐시가 없을 수 있습니다.

#### 시간과 용량 확인

로그에서 아래 값을 확인합니다.

| 로그 이름 | 뜻 |
| --- | --- |
| `backend_incremental_state_restored` | 이전 컴파일 결과를 가져왔는지 |
| `backend_incremental_restore_seconds` | 가져온 결과를 작업 폴더에 복사한 시간 |
| `backend_gradle_bootjar_seconds` | Gradle 시작부터 JAR 생성까지 걸린 시간 |
| `backend_incremental_save_seconds` | 새 컴파일 결과를 저장한 시간 |

`compileJava UP-TO-DATE`는 컴파일을 생략했다는 뜻입니다. 파일을 바꾼 경우에는 실제로
얼마나 다시 컴파일했는지도 확인합니다. 복원 성공 로그만으로 속도가 빨라졌다고 판단하지
않습니다.

Gradle의 자세한 시간은 아티팩트의 `build-artifacts/backend-gradle-profile`에서 봅니다.
시작, 설정, `compileJava`, `bootJar` 시간을 각각 확인할 수 있습니다. 저장 후 출력하는
`du -sh`는 압축 전 사용량입니다. 실제 저장 용량은 S3 캐시 파일 크기로 확인합니다.

같은 빌드 이미지와 컴퓨팅 크기에서 비교합니다. 첫 빌드, 소스 일부 변경, 리소스만 변경,
백엔드 변경 없음의 경우를 나눠 봅니다. S3에서 캐시를 받는 시간과 올리는 시간을 포함한
전체 CodeBuild 시간이 줄어야 효과가 있는 것입니다.

캐시 복원과 실패 처리는 다음 명령으로 확인할 수 있습니다.

```bash
bash deploy/scripts/test_codebuild_backend.sh
```

참고 문서는 [CodeBuild S3 캐시](https://docs.aws.amazon.com/codebuild/latest/userguide/caching-s3.html),
[지원 런타임](https://docs.aws.amazon.com/codebuild/latest/userguide/available-runtimes.html),
[Gradle 증분 컴파일](https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_compile),
[Gradle 캐시 폴더](https://docs.gradle.org/current/userguide/directory_layout.html),
[빌드 시간 보고서](https://docs.gradle.org/current/userguide/inspect.html#sec:profiling_build)입니다.

CodeDeploy 애플리케이션은 EC2/온프레미스 플랫폼과 현재 위치 배포를 사용합니다. 배포 그룹 서비스 역할은 `codedeploy-project`이며, 에이전트를 직접 설치한 EC2에서는 Systems Manager 에이전트 구성 값을 `Never`로 설정합니다.

배포 수명주기는 다음과 같습니다.

```text
BeforeInstall    -> Docker/Compose/jq와 .env 파일 확인 후 기존 배포 파일 정리
AfterInstall     -> 체크섬 검증 후 ARM64 Docker 이미지 로드
ApplicationStart -> 이미지 환경·배포 모드 검사, 후보 키 검증 후 docker compose up --no-build
ValidateService  -> 두 컨테이너 health 및 프론트 /health 확인
```

이미지 환경과 배포 모드는 `configure_compose`에서 검사하고, 이어서 후보 이미지로 키 검증을
수행합니다. 실패하면 컨테이너 재생성을 시작하지 않습니다. 이 검증은 S3나 DB를 수정하지 않습니다.

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
