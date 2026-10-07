# 이미지 저장소 전환 안내

dev와 prod가 각자의 이미지 경로만 사용하도록 설정한다. **이미지 복사와 검증을 마친 뒤 DB key와 서버 설정을 함께 바꾸고 배포한다.** 코드만 먼저 배포하면 기존 공용 key의 이미지가 차단된다.

## 현재까지 완료한 작업

2026-10-01 01:09 KST 확인 기록 기준이다. 전환 직전에 최신 상태를 다시 대조한다.

- dev: 일기 이미지 265개와 기준 이미지 2개를 dev 경로로 복사했고, 내용과 이미지 디코딩 검증을 마쳤다. 기존 파일은 보존했다.
- 미완료: 양쪽 DB key와 서버 설정 변경, 새 코드 배포, prod 이미지 복사. 당시 서비스는 기존 공용 경로를 사용하고 있었다.
- prod 누락 84건은 정상 복사와 분리해 보류했다. 아래 처리 방침을 확정해야 운영 전환이 가능하다.

## 서버에 설정할 값

전환 시점에 각 서버의 `/opt/harudle/.env`에서 아래 값을 추가하거나 교체한다. 같은 변수를 중복해서 넣지 않고 기존 DB 연결 정보와 비밀값은 유지한다.

개발 서버:

```dotenv
DEPLOY_ENV=dev
S3_BUCKET=techcourse-project-2026
S3_GENERATED_PREFIX=harudle/generated/diary-images/dev
S3_REFERENCE_PREFIX=harudle/references/generation/dev
HARUDLE_GENERATION_PROMPT_BOOTSTRAP_IMAGE_ASSET_OBJECT_KEY=harudle/references/generation/dev/05-reference-style-asset.png
```

운영 서버:

```dotenv
DEPLOY_ENV=prod
S3_BUCKET=techcourse-project-2026
S3_GENERATED_PREFIX=harudle/generated/diary-images/prod
S3_REFERENCE_PREFIX=harudle/references/generation/prod
HARUDLE_GENERATION_PROMPT_BOOTSTRAP_IMAGE_ASSET_OBJECT_KEY=harudle/references/generation/prod/05-reference-style-asset.png
```

- prefix 끝에는 `/`를 붙이지 않는다. 환경명, 버킷, 두 prefix에는 변수 치환이나 줄 끝 주석을 쓰지 않는다. 이미지 설정은 이 `.env`에서만 관리한다.
- 두 서버 모두 `AWS_REGION=ap-northeast-2`와 `SPRING_PROFILES_ACTIVE=prod`를 유지한다. 이미지 환경은 `DEPLOY_ENV`로 구분한다.
- CodeDeploy 그룹은 각각 `harudle-dev`, `harudle-prod`다. `DEPLOYMENT_GROUP_NAME`은 CodeDeploy가 제공하므로 `.env`에 넣지 않는다. dev에는 `/opt/harudle/compose.dev.yaml`도 필요하다.
- bootstrap key를 바꿔도 기존 DB 프롬프트는 바뀌지 않는다. 기준 이미지 05번과 06번을 모두 환경별로 복사하고, DB의 각 참조는 같은 파일의 새 key로 바꾼다.

## 적용 순서: dev 검증 후 prod

1. **최신 대상을 확인한다.** 대상 DB가 `harudle_dev` 또는 `harudle_prod`인지 확인한다. DB와 S3를 대조해 기록 ID, 기존 key, 새 key, 관련 파일과 검증 결과를 명세로 남긴다. 누락 파일은 정상 대상과 구분한다.
2. **파일을 복사하고 검증한다.** 기존 상대 경로를 유지해 해당 환경의 prefix 아래로 복사한다. 실제 존재하는 원본, 상세 이미지, 썸네일과 기준 이미지를 함께 옮긴다. 목적지 파일은 덮어쓰지 않고 동일 여부를 확인한다. 전체 파일의 SHA-256, 크기, MIME와 디코딩을 검증한다. 소유 불명 파일과 복구 임시 파일은 보존한다.
3. **이미지 쓰기를 멈추고 최종 대조한다.** 신규 생성, 관리자 복구와 업로드를 중단하고 진행 중인 작업이 끝났는지 확인한다. 추가된 파일도 복사하고 검증한다. DB, 서버 설정, 배포 버전과 기존 key를 백업한다. `.env` 백업은 서버에 제한된 권한으로 보관한다.
4. **DB와 설정을 바꾸고 배포한다.** 쓰기 중단 상태에서 `diary_generations.image_object_key`와 `generation_prompts.image_asset_object_key`를 변경한다. 트랜잭션 안에서 ID와 기존 key가 명세와 일치하는 행만 갱신하고 예상 건수와 다르면 되돌린다. 같은 전환 구간에서 `.env`를 적용하고 배포한다. 일기 내용이나 생성 상태는 바꾸지 않는다.
5. **실제 동작을 확인한다.** 아래 항목을 확인한 뒤 쓰기를 재개한다. dev에서는 되돌리기도 검증하고, prod는 운영 DB를 기준으로 별도 복사한 뒤 같은 절차를 적용한다.

배포 후 확인할 항목:

- 기존 이미지의 목록, 상세, 공유와 다운로드. 정상 이미지가 있는 게스트 기록도 포함한다.
- 기준 이미지 읽기, 새 이미지 생성, DB key와 파일이 해당 환경 경로에 저장되는지 여부.
- 반대 환경의 조회, 저장, 삭제, 복구와 URL 발급이 S3 요청 또는 서명 전에 차단되는지 여부. 쓰기와 삭제 차단은 실제 사용자 파일 대신 검증용 key로 확인한다.

설정 검사 방법은 [배포 안내](../deploy/README.md#codepipeline-배포)를 참고한다. 설정 검사와 health 검사만으로 이미지 이전 완료를 판단하지 않는다. 콘솔이나 CLI의 IAM 권한 분리는 이번 변경에 포함하지 않는다.

## 누락 이미지와 문제 발생 시 처리

누락 84건은 당시 삭제된 비게스트 일기 50건과 삭제되지 않은 게스트 일기 34건이다. 자동 재생성하지 않고 일기 기록과 스토리보드를 보존한다. 공용 key를 남기면 새 검증이 거절하므로 운영 전환 전에 key 처리 방침을 확정한다. **파일 없이 key만 변경하면 `MISSING_NOT_RESTORED`로 별도 기록하며 복구 완료로 처리하지 않는다.**

문제가 생기면 쓰기 중단을 유지한다. 이전 코드가 새 key를 지원하는지 확인해 코드만 되돌릴지 DB와 설정도 되돌릴지 결정한다. DB는 명세의 ID와 현재 새 key가 일치하는 행만 복원하고, 전환 이후 생성된 기록을 일괄 변경하지 않는다. 고아 이미지 정리 작업이 남아 있는 과거 버전으로 되돌리지 않는다.

**기존 파일과 복사본은 이번 전환에서 삭제하지 않는다.** 기존 경로 삭제는 양쪽 DB, 프롬프트, 유효한 URL과 복구 참조가 남지 않았는지 확인하고 보존 기간을 정한 뒤 별도로 진행한다.
