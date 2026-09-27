# 프론트엔드 릴리스

프론트엔드 릴리스는 **운영에 배포한 코드에 버전 이름과 변경 내역을 남기는 기록**입니다. `main`에 코드가 합쳐지면 기존 AWS 파이프라인이 배포합니다. GitHub의 **Frontend Release** 버튼은 AWS 배포가 끝난 뒤 **프론트엔드 개발자가 직접** 실행하며, AWS 배포를 시작하지 않습니다.

예를 들어 productiond으로 배포한 커밋이 `abc123…`이고 그 커밋의 프론트 버전이 `1.0.0`이라면, 릴리스 버튼을 눌렀을 때 그 커밋에 `frontend-v1.0.0` 태그를 붙이고 해당 태그 이름의 GitHub Release를 게시합니다.

| 용어           | 의미                                                    | 확인할 곳                                                                                                                                                            |
| -------------- | ------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 프론트 버전    | 프론트엔드 개발자가 정의한 유의적 버전                  | `frontend/web/package.json`의 `version`                                                                                                                              |
| 변경 내역      | 그 버전에 포함된 주요 변경                              | [CHANGELOG.md](../CHANGELOG.md)                                                                                                                                      |
| 커밋 SHA(40자) | AWS가 빌드, 배포한 정확한 소스 상태                     | [성공한 AWS 배포의 Source revision](https://ap-northeast-2.console.aws.amazon.com/codesuite/codepipeline/pipelines/harudle-prod-pipeline/view?region=ap-northeast-2) |
| Git 태그       | 버전 이름이 가리키는 커밋                               | GitHub의 Tags, `frontend-vX.Y.Z`                                                                                                                                     |
| GitHub Release | 버전, 커밋, 변경 내역, 배포 기록을 함께 보여주는 페이지 | [저장소 Releases](https://github.com/woowacourse-teams/2026-Harudle/releases)                                                                                        |

## 버전은 언제 올려야 하는가?

웹 프론트 버전의 기준이 되는 파일은 `frontend/web/package.json`입니다. PR마다 버전을 올리지 않고, 운영에 내보낼 변경을 모았을 때 올립니다. 첫 버전은 `1.0.0`입니다.

**버전을 수정하는 시점은 `develop-frontend`에 모인 변경을 하나의 릴리스 후보로 정하고 `dev`에서 QA하기 전입니다.** 개별 기능 브랜치나 PR에서는 버전을 올리지 않습니다. 릴리스 후보의 버전과 changelog를 함께 수정해 `dev` 배포에 포함시키고, QA를 통과한 버전을 `main`에 반영합니다. `dev` 배포만으로 Git 태그나 GitHub Release를 만들지는 않습니다.

예를 들어 `1.0.0` 배포 후 기능 브랜치 세 개와 버그 수정 브랜치 두 개를 모아 다음 릴리스를 준비한다면, 브랜치마다 버전을 올리지 않고 `dev` QA를 준비할 때 `1.1.0`으로 한 번 올립니다. `1.1.0`버전의 QA 중 수정사항이 필요하다면 `1.1.0`버전을 유지하면서 수정한 뒤 dev에 수정한 코드를 반영하면 됩니다.
운영 배포 후 태그가 만들어진 뒤에는 `1.1.0` 버전을 다른 코드에 다시 사용하지 않습니다.

| 버전              | 증가 기준                                                 | 하루들에서의 예시                                                         |
| ----------------- | --------------------------------------------------------- | ------------------------------------------------------------------------- |
| **Major** `2.0.0` | 유지하기로 한 기존 사용 방식, 데이터와 호환되지 않는 변경 | 기존 공유 URL 지원 중단, 기존 저장 데이터를 이전 없이 사용할 수 없게 변경 |
| **Minor** `1.1.0` | 기존 동작을 유지하면서 사용자 기능 추가                   | 새로운 필터, 설정 옵션, 새로운 공유 방식 추가                             |
| **Patch** `1.0.1` | 기존 기능의 오류 수정이나 작은 개선                       | 버튼 오류 수정, 모바일 레이아웃 보정, 문구, 임시 공지 수정                |

여러 종류가 섞이면 가장 높은 등급을 적용합니다. Minor를 올리면 Patch를 `0`으로, Major를 올리면 Minor와 Patch를 `0`으로 초기화합니다. 동작을 유지하는 리팩터링, 의존성, 빌드 변경을 릴리스할 때는 팀 규칙으로 Patch를 적용합니다. 문서만 바뀐다던가 백엔드만 바뀌어 프론트 결과물이 그대로라면 프론트 버전은 유지합니다.

**한 번 릴리스한 버전은 다른 코드에 재사용하지 않습니다. 이미 만든 태그를 지우거나 다른 커밋으로 옮기지도 않습니다.**

## 릴리스 담당자가 하는 일

1. 릴리스할 버전을 `frontend/web/package.json`에 적고, [CHANGELOG.md](../CHANGELOG.md)에 정확히 `## 1.0.0` 형식의 제목과 변경 내역을 불릿으로 작성합니다.
2. `frontend/web`에서 `pnpm check`를 통과시킵니다. 기존 브랜치 흐름대로 `develop-frontend → dev → main`에 변경을 반영합니다. `main`에 머지됐다는 사실만으로는 아직 릴리스 기록을 만들지는 않습니다.
3. AWS에서 **운영 배포가 성공했고 주요 동작이 정상인지** 확인합니다. 성공한 CodePipeline 또는 CodeDeploy 실행에서 Source revision의 **전체 커밋 SHA 40자리**와 실행 기록 URL을 가져옵니다. 보통 `dev → main` 머지 커밋이지만, 현재 `main`의 최신 커밋을 추측해서 입력하지 않습니다([이곳](https://ap-northeast-2.console.aws.amazon.com/codesuite/codepipeline/pipelines/harudle-prod-pipeline/view?region=ap-northeast-2)에서 직접 확인합니다).
4. GitHub **Actions → Frontend Release → Run workflow**를 열고 실행 브랜치로 `main`을 선택합니다. `commit`에 AWS의 SHA, `deployment_url`에 성공한 배포 실행 URL을 입력합니다. URL에는 비밀번호나 임시 인증 토큰을 넣지 않습니다. `deployment_verified`를 체크한 뒤 실행합니다.
5. 실행이 끝나면 Actions 로그와 [Releases](https://github.com/woowacourse-teams/2026-Harudle/releases)에서 `frontend-vX.Y.Z`가 입력한 커밋을 가리키고 변경 내역이 맞는지 확인합니다.

GitHub Actions에 수동 실행 버튼이 나타나려면 [워크플로](../../../.github/workflows/frontend-release.yml)가 기본 브랜치 `main`에 먼저 반영되어 있어야 합니다.

## 릴리스 버튼을 누르면 실행되는 일

`frontend-release.yml`이 [prepare-frontend-release.mjs](../../../.github/scripts/prepare-frontend-release.mjs)를 실행합니다. 스크립트는 **입력한 배포 커밋에 들어 있는** `package.json`과 `CHANGELOG.md`를 읽습니다. 버튼을 누르는 시점의 최신 파일을 대신 읽지 않습니다.

릴리스를 만들기 전에 다음을 검사합니다.

- SHA가 40자리 형식이고, 해당 커밋이 `main` 이력에 포함되는지
- 배포 기록 URL이 인증 정보가 없는 HTTPS 주소인지
- 버전이 `MAJOR.MINOR.PATCH` 형식이고, 이미 릴리스한 다른 버전보다 높은지
- 같은 버전 태그가 다른 커밋에 붙어 있지 않은지
- changelog에 해당 버전 제목이 정확히 하나 있고 변경 내역 불릿이 있는지

검사를 통과하면 워크플로가 입력한 커밋에 `frontend-vX.Y.Z` Git 태그를 만들고, 버전, 커밋, 배포 URL, 변경 내역을 담은 GitHub Release를 게시합니다. 이미 같은 커밋에 태그가 있으면 다시 사용하며, Release가 이미 있으면 덮어쓰지 않습니다.

스크립트가 확인하는 것은 **입력 형식과 Git 기록의 일치 여부**입니다. AWS 배포의 실제 성공 여부, AWS의 Source revision과 입력 SHA가 같은지, Major, Minor, Patch 판단이 옳은지는 담당자가 확인합니다. 버튼은 Docker 이미지를 저장하거나 서버를 이전 버전으로 되돌리지 않습니다.

## 릴리스 기록을 읽을 때

- 서비스 화면에서 설정 페이지의 버전은 **그 사용자가 지금 실행 중인 프론트 번들**의 버전입니다. 오래 열어 둔 화면은 이전 버전일 수 있습니다.
- GitHub Release는 **성공한 운영 배포에 대해 남긴 기록**입니다. 이후 롤백이 있었다면 가장 최근 Release가 지금 서버에서 제공하는 버전과 다를 수 있습니다. 현재 운영 상태는 배포 기록으로 확인합니다.
- Git 태그는 그 버전의 **소스 커밋**을 찾는 기준입니다. `git show frontend-v1.0.0:frontend/web/package.json`으로 해당 시점의 버전을 볼 수 있습니다. 실제 롤백에는 이전 배포 이미지를 찾고 다시 실행하는 절차가 별도로 필요합니다.
- 이 저장소는 프론트와 백엔드가 Releases 목록을 공유합니다. 이 워크플로는 저장소 전체의 `Latest` 표시는 붙이지 않으므로, `frontend-v*` 이름으로 프론트 릴리스를 구분합니다.

태그만 생성된 뒤 워크플로가 실패했다면 같은 입력으로 다시 실행할 수 있습니다. 기존 태그가 다른 커밋에 있거나 더 높은 버전이 이미 릴리스됐다면 새 버전을 정해야 합니다. 게시 후 문제가 발견돼도 태그는 유지하고, 릴리스 기록에 문제, 롤백 사실을 남긴 뒤 수정 버전을 새로 배포합니다.
