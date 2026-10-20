# CodeBuild 캐시 효과를 전체 빌드 시간으로 판단

- 작성일: 2026-10-21
- 작성자: 이산
- 결정 대상: CodeBuild 백엔드 캐시의 적용 범위와 효과 확인 방법

## 배경

CodeBuild는 백엔드 JAR를 만들고, 백엔드와 프론트엔드 Docker 이미지를 각각 만든다. 두 이미지를 압축해 CodeDeploy에 전달한다. 백엔드 컴파일이 빨라져도 캐시 복원과 저장에 더 오래 걸리면 전체 배포 준비 시간은 줄지 않는다.

[PR #400](https://github.com/woowacourse-teams/2026-Harudle/pull/400)은 백엔드 Gradle의 이전 컴파일 결과를 S3 캐시에 저장하도록 구현했고 `develop-backend`에 병합됐다. GitHub Actions의 PR 검사 캐시는 [PR #432](https://github.com/woowacourse-teams/2026-Harudle/pull/432)에서 따로 처리했다. AWS dev CodePipeline은 `stage`를 소스로 읽고, prod CodePipeline은 `main`을 읽는다. 2026-10-21 기준 `stage`의 `buildspec.yml`에는 #400의 백엔드 빌드 스크립트 호출이 없다. 따라서 아직 새 방식의 AWS 실행 결과는 확인하지 못했다.

변경 전 AWS 로그에서 백엔드 캐시 복원 메시지가 있는 실행 10건의 전체 시간 중앙값은 4분 46초다. 이 중 dev 6건의 중앙값은 4분 50초, prod 4건은 4분 46초다. 이 값은 **이전 BuildKit 방식의 기록**이다. 캐시가 없었던 실행 1건은 계산에서 뺐다.

## 결정

1. 이번 작업에서는 백엔드 Gradle의 이전 클래스와 컴파일 기록을 S3에 저장한다. 완성된 JAR, Docker 이미지, 프론트엔드 빌드 결과는 저장하지 않는다. CodeBuild는 배포할 JAR와 두 이미지를 실행할 때마다 새로 만든다.
2. AWS CodeBuild 실행 기록의 전체 시간과 CloudWatch 로그 시각으로 백엔드 구간, 프론트엔드 구간, `POST_BUILD` 구간을 기록한다. Docker 이미지 생성과 tar 내보내기 시간, 이미지 압축 시간은 [구간별 로그 ADR](2026-10-21_CodeBuild%20이미지%20생성과%20압축%20시간을%20build-metrics%20로그로%20기록_이산.md)에 따라 `[build-metrics]` 로그로 따로 남긴다. CodeBuild가 관리하는 S3 캐시 전송 시간도 전체 시간에 포함된다.
3. 새 방식이 실제 AWS에서 실행된 뒤, 캐시가 없는 첫 실행과 캐시를 복원한 실행을 구분한다. dev와 prod도 나눠서 비교한다. 같은 빌드 이미지와 컴퓨팅 크기에서 비슷한 변경 범위의 성공한 실행을 여러 건 모아 중앙값을 비교한다.
4. 백엔드 시간이 줄어도 CodeBuild 전체 시간이 줄지 않으면 성공으로 보지 않는다. 그때는 가장 긴 구간을 확인하고 다음 변경을 정한다. 프론트엔드 캐시 적용과 `pnpm check` 이동 여부도 그 결과를 보고 판단한다.

## 이유

백엔드와 프론트엔드는 별도로 빌드하지만, 지금 저장소의 지속 캐시 대상은 백엔드뿐이다. 먼저 이미 구현된 백엔드 캐시의 효과를 확인해야 프론트엔드에도 캐시를 추가할지 판단할 수 있다.

변경 전 로그의 `Restoring backend build cache.`는 이전 BuildKit 캐시를 가져오려 했다는 뜻이다. 새 Gradle 방식의 복원 여부는 `backend_incremental_state_restored`로 확인한다. 실제 컴파일을 생략했는지는 `compileJava UP-TO-DATE`와 `backend_gradle_bootjar_seconds`를 함께 본다. 한 실행만 비교하면 소스 변경량이나 캐시 상태의 차이를 캐시 효과로 잘못 볼 수 있다.

## 결과와 확인할 점

1. `develop-backend`의 #400 변경이 `stage`에 반영된 뒤 dev CodeBuild가 실행됐는지 소스 버전과 `[build-metrics]` 로그를 확인한다.
2. `backend_incremental_state_restored`, `backend_incremental_restore_seconds`, `backend_gradle_bootjar_seconds`, `backend_incremental_save_seconds`를 기록한다. 두 이미지의 `docker_export_seconds`와 `image_gzip_seconds`도 로그에서 확인한다. CloudWatch 시각으로 계산한 백엔드, 프론트엔드, `POST_BUILD` 구간은 각각의 작업 시간보다 넓은 범위다.
3. 같은 조건의 변경 전후 CodeBuild 전체 시간 중앙값을 초와 비율로 비교한다. 전체 시간이 줄지 않으면 백엔드, 프론트엔드, `POST_BUILD`, 그 밖의 준비 및 마무리 중 어디에서 시간이 늘었는지 적는다.

현재 AWS의 새 방식 실행 결과와 실제 절감 시간은 아직 없다. [이슈 #435](https://github.com/woowacourse-teams/2026-Harudle/issues/435)에서 적용 후 수치를 계속 기록한다.

## 알림 연결

현재 팀의 알림은 CloudWatch 경보를 SNS와 Lambda를 거쳐 Discord로 보낸다. CodeBuild 로그는 별도 로그 그룹 `/aws/codebuild/harudle-dev-bulid`에 있다. 새 시간 로그를 출력하는 것만으로 Discord 알림이 생기지는 않는다. 알림을 보내려면 CloudWatch 지표와 경보를 만들고, 기존 전달 Lambda의 허용 목록에도 경보 이름을 추가해야 한다.

dev와 prod가 같은 CodeBuild 프로젝트를 쓰므로 알림 대상 환경도 구분해야 한다. 캐시가 없는 첫 실행은 정상일 수 있어 `backend_incremental_state_restored=false`만으로 경보를 만들지 않는다. 새 방식의 실행 시간을 모은 뒤 느린 빌드의 기준을 정하고 알림을 연결한다.
