# CodeBuild 이미지 생성과 압축 시간을 build-metrics 로그로 기록

- 작성일: 2026-10-21
- 작성자: 이산
- 결정 대상: `buildspec.yml`에서 추가로 기록할 구간별 빌드 시간 로그

이 문서는 이미지 재사용 전 로그 도입 기록이다. 후속 구현안에서는 프론트엔드 이미지를 재사용할 때 해당 이미지의 생성과 압축 시간 로그를 출력하지 않는다. [프론트엔드 PR 검사와 이미지 재사용](2026-10-21_프론트엔드%20PR%20검사와%20이미지%20재사용_이산.md)을 함께 본다.

## 배경

기존에는 [codebuild_backend.sh](../../../../deploy/scripts/codebuild_backend.sh)에서 백엔드 Gradle과 관련된 4가지 값만 `[build-metrics]` 로그로 남겼다.

- `backend_incremental_state_restored`: 이전 컴파일 결과 복원 여부
- `backend_incremental_restore_seconds`: 복원한 파일을 작업 폴더에 복사한 시간
- `backend_gradle_bootjar_seconds`: Gradle이 실행 가능한 JAR를 만든 시간
- `backend_incremental_save_seconds`: 다음 빌드를 위해 컴파일 결과를 추려 저장한 시간

로그를 추가할 당시 CodeBuild의 배포 준비 시간에는 백엔드 Gradle 빌드 외에도 **두 Docker 이미지를 만들고 `.tar`로 내보내는 시간**, 그리고 **`POST_BUILD`에서 두 `.tar` 파일을 `gzip`으로 압축하는 시간**이 들어갔다.

이 로그가 없으면 구간별 시간을 알기 위해 매번 CloudWatch 로그의 줄별 시각을 수동으로 빼서 계산해야 한다. 또한 CloudWatch의 Metric Filter로 구간별 지표를 수집하거나 느린 빌드 알림을 연결하기도 어렵다.

## 결정

[buildspec.yml](../../../../buildspec.yml)의 `build` 단계와 `post_build` 단계에 아래 4개의 `[build-metrics]` 로그를 추가한다. 기존 백엔드 스크립트와 같은 `[build-metrics] 키=값` 형식을 사용한다. 후속 이미지 재사용 구현안에서는 프론트엔드 이미지를 새로 만들 때만 프론트엔드의 생성과 압축 시간 로그가 남는다.

1. `backend_docker_export_seconds`
2. `frontend_docker_export_seconds`
3. `backend_image_gzip_seconds`
4. `frontend_image_gzip_seconds`

## 4개 지표가 각각 측정하는 범위

| 지표 이름 | 실행 단계 | 실제로 포함되는 작업 |
| --- | --- | --- |
| `backend_docker_export_seconds` | `BUILD` | 베이스 이미지(`amazoncorretto:21-alpine3.22`) 가져오기를 포함한다. Alpine 패키지(`curl`, `tini`, `libwebp-tools`) 설치도 포함한다. 미리 만든 `app.jar`를 이미지에 복사하고 `backend-image.tar` 파일로 내보내는 시간까지 재는 값이다. **Gradle 컴파일 시간은 여기에 포함되지 않는다.** |
| `frontend_docker_export_seconds` | `BUILD` | 프론트엔드 이미지를 새로 만들 때 베이스 이미지(`node:24-bookworm-slim`, `nginx:1.29-alpine3.22`) 가져오기, `pnpm install --frozen-lockfile`, `pnpm build`, 결과물(`dist`)을 Nginx 이미지에 담아 `frontend-image.tar`로 내보내기를 포함한다. 이 로그를 처음 추가했을 때는 `pnpm build` 대신 `pnpm check`를 실행했다. 이미지를 재사용하면 이 값은 출력하지 않는다. |
| `backend_image_gzip_seconds` | `POST_BUILD` | `backend-image.tar`를 `gzip -1`로 압축해 `backend-image.tar.gz`를 만드는 시간이다. 백엔드 이미지는 JRE와 `app.jar`를 포함해 크기가 크므로 압축 시간을 따로 확인한다. |
| `frontend_image_gzip_seconds` | `POST_BUILD` | 새로 만든 `frontend-image.tar`를 `gzip -1`로 압축해 `frontend-image.tar.gz`를 만드는 시간이다. 이미지를 재사용하면 압축도 생략하므로 이 값은 출력하지 않는다. |

## 이유

1. **백엔드 컴파일 시간과 Docker 패키징 시간을 분리해 볼 수 있다.**
   백엔드는 `--target codebuild` 스테이지를 쓰므로 호스트에서 먼저 `app.jar`를 만든 뒤 Docker 이미지에는 파일만 넣는다. 만약 `backend_gradle_bootjar_seconds`는 줄었는데도 백엔드 구간이 느리다면, `backend_docker_export_seconds`를 보고 베이스 이미지 다운로드나 `.tar` 파일 생성이 느린지 바로 알 수 있다.

2. **새로 만드는 프론트엔드 이미지의 작업 시간을 한 줄로 확인할 수 있다.**
   로그 도입 당시에는 Docker 빌드 안에서 패키지 설치와 `pnpm check`를 모두 수행했다. 후속 구현안에서는 GitHub Actions가 PR에서 `pnpm check`를 실행한다. CodeBuild는 자체 S3 캐시에서 이미지를 재사용하거나, 새 이미지가 필요할 때 `pnpm build`를 실행한다. `frontend_docker_export_seconds`는 새 이미지를 만들 때만 기록하므로, 이미지 재사용 여부는 `frontend_image_reused` 로그와 함께 확인한다.

3. **`POST_BUILD` 구간의 시간을 압축과 캐시 저장으로 나눠 볼 수 있다.**
   `POST_BUILD`에서는 새로 만든 이미지의 `.tar` 압축(`gzip -1`)과 백엔드 증분 상태 저장(`backend_incremental_save_seconds`)이 일어난다. 프론트엔드 이미지를 재사용하면 해당 이미지의 압축과 로컬 캐시 교체를 생략한다. 재사용에 실패해 새 이미지를 만들고 빌드 전후 입력이 같다면 기존 캐시의 지문과 체크섬이 같아도 새 이미지를 로컬 캐시에 저장한다. 압축 시간과 캐시 저장 로그를 따로 보면 `POST_BUILD`에서 오래 걸린 작업을 찾을 수 있다.

4. **CloudWatch 검색과 알림 연결이 쉬워진다.**
   모든 시간 로그가 `[build-metrics] 이름=초` 형식으로 통일되어 CloudWatch Logs Insights나 검색창에서 `[build-metrics]` 하나로 한 번의 빌드에 걸린 주요 구간 시간을 모두 모아 볼 수 있다.
