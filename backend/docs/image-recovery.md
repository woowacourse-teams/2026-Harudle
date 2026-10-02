# 관리자 이미지 수동 복구

## R2 백업 원본으로 복구

R2 원본을 원래 S3 키에 복구한다. AI 생성과 프롬프트 조회를 수행하지 않는다.
DB 대표 키, 생성 기록 상태, 완료 시각, 토큰 사용량과 사용자 생성 횟수는 유지한다.
이 절차의 대상은 현재 환경의 **성공한 생성 기록 UUID 목록**이다. 임의 키, 참조 이미지와 다른 환경의 파일은 대상에 포함하지 않는다.

### 1. 장애 원인과 대상 범위 확인

삭제 원인과 영향 범위를 확인하고, 같은 버킷을 사용하는 이전 삭제 작업을 중단한 뒤 진행한다.
관리자 생성 이력이나 DB에서 `diary_generations.id`, `diary_id`, `image_object_key`, 상태를 확인한다.
화면 조회에 사용할 `diary_id`도 따로 기록한다. API에는 `diary_generations.id`를 입력한다.

서버에 `HARUDLE_GENERATION_ADAPTERS_ENABLED=true`, `R2_ENABLED=true`와 [R2 설정](r2-storage-configuration.md)이 필요하다.
S3와 R2의 `DEPLOY_ENV`는 같아야 하며 서버 시작 시 검증한다. dev 검증에는 dev 설정과 자격 증명을 사용한다.
운영 R2 자격 증명은 개발 서버에 제공하지 않는다. 운영 실행 시 요청 환경도 반드시 `prod`로 지정한다.

S3는 대상 버킷의 GetObject, PutObject와 누락 확인을 위한 HeadBucket/ListBucket 권한이 필요하다.
R2 복구는 대상 백업의 HEAD/GET만 사용한다. R2를 공개로 변경하지 않는다.
교육용 AWS 계정의 IAM 정책 변경은 이 작업에 포함하지 않는다. 콘솔/CLI의 직접 접근 격리는 보장하지 않는다.

### 2. 목록을 고정하고 dry-run

관리자 토큰과 CSRF 쿠키/토큰을 준비한다. 토큰과 서명 URL을 저장소에 커밋하지 않는다.

```sh
curl --fail-with-body -c recovery-cookies.txt "$BASE_URL/api/v1/auth/csrf"
# XSRF-TOKEN 쿠키 값을 CSRF_TOKEN에 설정한다.
```

`r2-recovery-plan.json`을 만든다. 요청은 환경, 중복 없는 UUID 1~100개와 명시적인 `dryRun` 값을 요구한다.
처음에는 dev에서 한 건으로 확인한다.

```json
{
  "environment": "dev",
  "generationIds": ["550e8400-e29b-41d4-a716-446655440000"],
  "dryRun": true
}
```

```sh
curl --fail-with-body -b recovery-cookies.txt \
  -X POST "$BASE_URL/api/v1/admin/generations/restore-image/r2" \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN" \
  -H "X-XSRF-TOKEN: $CSRF_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @r2-recovery-plan.json > r2-recovery-plan-result.json
```

dry-run은 저장소에 PUT/DELETE를 보내거나 이미지 변환을 실행하지 않는다.
R2 원본을 실제로 읽어 MIME, 크기와 SHA-256을 계산하고, S3 원본·상세·썸네일의 누락 여부를 확인한다.
기존 S3 원본이 있으면 R2와 MIME·크기·SHA-256을 비교한다. 다른 내용이면 덮어쓰지 않고 `ORIGINAL_CONFLICT`로 중단한다.
과거 백업 완료 로그의 SHA-256과도 대조한다. DB 백업 기록이나 별도 체크섬 파일을 추가하지 않았으므로, 현재 R2 해시만으로 사고 전 원본과의 일치까지 보장하지는 않는다.

`results`의 `status`, `failureCode`, `recovery.originalKey`, `mime`, `size`, `sha256`, `missingKeys`, 환경과 버킷을 검토한다.
원본 대표 키는 그대로 사용한다. `image-960.webp`는 같은 폴더의 PNG/JPG/WebP 원본을 찾는다.
원본 후보가 여러 개면 `AMBIGUOUS_BACKUP`으로 실패하므로 운영자가 백업 상태를 먼저 확인한다.
dry-run 통과는 PutObject 권한을 보장하지 않는다. dev의 별도 테스트 대상에서 실제 조건부 복구를 검증하고, 운영 자격 증명의 쓰기 가능 범위도 확인한다.

| status | 의미 |
| --- | --- |
| `WOULD_RESTORE` | dry-run에서 누락 파일을 확인함 |
| `ALREADY_EXISTS` | 파일이 모두 존재하고 원본 내용이 R2와 같음. 복구 쓰기는 생략 |
| `RESTORED` | 실제 복구 후 원본 해시와 대상 파일 존재를 확인함 |
| `FAILED` | 해당 대상 실패. `failureCode` 확인 필요 |

대표 실패 코드는 `BACKUP_NOT_FOUND`, `AMBIGUOUS_BACKUP`, `BACKUP_CHANGED`, `INVALID_CONTENT`, `ORIGINAL_CONFLICT`, `VERIFICATION_FAILED`이다.
권한·인증·통신 오류는 `R2_*`, `S3_*`로 구분하고 파일 없음으로 취급하지 않는다.
기록 없음과 복구 불가능 상태는 각각 `GENERATION_NOT_FOUND`, `GENERATION_NOT_RECOVERABLE`이다.
R2 백업이 없으면 정상 S3 파일이 있더라도 이 절차의 검증에 실패하며 AI 생성으로 넘어가지 않는다.

### 3. 검토한 목록으로 실제 복구

확정한 목록을 유지하고 요청 파일의 `dryRun`을 `false`로 바꾼 뒤 같은 API를 실행한다.
서버는 실행 시점에 대상 상태를 다시 조회한다. dry-run 결과를 쓰기 권한이나 잠금으로 사용하지 않는다.
실제 실행은 한 건씩 보내고 응답을 저장하는 것을 권장한다. 여러 건을 보내면 동기로 순서대로 실행하며,
기존 수동 복구와 공통 잠금을 사용하고 각 작업 완료 후 기본 10초 간격을 둔다.
배포의 프록시/클라이언트 타임아웃을 고려한다. 인스턴스 간 공통 잠금은 없지만 객체별 조건부 PUT은 모든 인스턴스의 경합에 적용된다.

```sh
curl --fail-with-body -b recovery-cookies.txt \
  -X POST "$BASE_URL/api/v1/admin/generations/restore-image/r2" \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN" \
  -H "X-XSRF-TOKEN: $CSRF_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @r2-recovery-apply.json > r2-recovery-apply-result.json
```

모든 복구 PUT에 `If-None-Match: *`를 사용한다. 이미 존재하는 원본·상세·썸네일은 덮어쓰지 않는다.
PNG/JPEG는 원래 `.png`/`.jpg` 키에 올바른 MIME과 동일한 바이트로 복구한다.
DB 대표 키가 `image-960.webp`이면 누락 상세·썸네일을 cwebp로 만든다. 기존 상세가 있으면 누락 썸네일은 그 상세에서 만든다.
원본 WebP 대표 키는 동일한 `.webp` 경로에 그대로 복구한다. UUID 폴더 하나인 기존 구조와 두 개인 현재 구조를 모두 지원한다.
PNG/JPEG 바이트를 `.webp` 상세 키에 그대로 복사하지 않는다.

한 대상의 백업 없음이나 저장소 오류가 나도 나머지 대상 결과를 반환한다.
HTTP 200이어도 각 대상의 `FAILED`를 확인해야 한다. 전체 요청의 잘못된 목록은 400, 환경 불일치는 409, 복구 설정 없음은 503이다.
부분 실패 때 원본·정상 파생 파일을 삭제하지 않는다. 원인 해결 후 **같은 목록으로 dry-run부터 재실행**하면 누락된 파일만 복구한다.
응답 유실과 작업 중단 후에도 같은 절차를 사용한다. 클라이언트 연결 종료가 서버 실행을 취소했다는 보장은 없다.

### 4. 결과 기록과 화면 확인

요청 파일과 두 응답 파일을 함께 보관한다. `batchId`로 `admin_r2_recovery_target`과 `admin_r2_recovery_batch` 로그를 찾는다.
단일 저장소 작업은 `image_r2_recovery_completed` 로그에 시도 ID, 환경, 버킷, 대표·원본 키, MIME, 크기, SHA-256,
누락 목록, 복구 단계, 원본/파생 복구 여부와 실패 코드를 기록한다. 부분 실패 때 이미 복구한 원본도 이 로그로 확인한다.
예외 원문, 비밀 키와 서명 URL은 복구 완료 로그에 기록하지 않는다.

1. 동일 목록의 dry-run을 다시 실행해 `ALREADY_EXISTS`, 빈 `missingKeys`와 원본 SHA-256 일치를 확인한다.
2. 대상 사용자의 서비스 화면을 새로고침한다. 목록 썸네일과 상세 그림을 각각 연다.
3. 브라우저 Network에서 새로 발급된 S3 이미지 URL의 GET이 200이며 올바른 Content-Type인지 확인한다.
4. 원본 WebP와 기존 단일 PNG/JPEG도 목록과 상세에서 각각 확인한다.
5. `diary_id`와 `image_object_key` 연결이 유지되고 다른 환경이나 R2 공개 URL을 참조하지 않는지 확인한다.

화면의 오래된 실패 응답이나 만료된 서명 URL이 보이면 일기 조회를 다시 실행해 새 URL을 받는다.
이 구현의 자동 테스트는 메모리 저장소와 실제 cwebp를 사용해 복구·변환·경합·부분 실패·재실행을 검증한다.
`backend/build/r2-recovery-preview/`에는 합성 이미지 복구 결과가 생성된다.
실제 dev/prod 파일의 복구와 서비스 목록·상세 화면의 확인은 배포 후 위 절차로 별도 수행하고 결과를 남긴다.

기존 `restore-image/upload`는 PNG/JPEG 직접 업로드만 지원하고 WebP는 415, 키/MIME 불일치는 409를 반환한다.
R2 API는 이 업로드 입력 경로를 사용하지 않으므로 원본 WebP도 처리한다.

## 저장된 스토리보드로 AI 이미지 재생성

관리자 인증으로 아래 API를 호출한다. 요청 본문은 없다.

POST /api/v1/admin/generations/{generationId}/restore-image

예시 (운영 주소 및 관리자 액세스 토큰은 실제 값으로 지정):

~~~sh
curl --fail-with-body -c recovery-cookies.txt "$BASE_URL/api/v1/auth/csrf"
# 발급된 XSRF-TOKEN 쿠키 값을 CSRF_TOKEN에 설정한다.
curl --fail-with-body -b recovery-cookies.txt -X POST "$BASE_URL/api/v1/admin/generations/$GENERATION_ID/restore-image" \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN" \
  -H "X-XSRF-TOKEN: $CSRF_TOKEN"
~~~

응답:

~~~json
{
  "generationId": "복구한 생성 기록 UUID",
  "imageObjectKey": "generated/diary-images/기존경로/image.png",
  "status": "RESTORED"
}
~~~

- diary_generations.id를 사용한다. diary_id가 아니다.
- SUCCEEDED 상태이며 storyboard와 image_object_key가 남아 있어야 한다.
- 복구 요청 시점에 ID가 가장 큰 최신 프롬프트의 스타일 프롬프트와 참조 이미지 키를 조회한다. 기존 기록의 prompt_id는 사용하지 않는다.
- 스토리보드 생성은 호출하지 않고 Gemini 이미지 생성만 호출한다.
- DB, 기존 이미지 키, 완료 시각, 토큰 사용량 및 사용자 일일 생성 횟수는 수정하지 않는다.
- 기존 이미지가 있으면 Gemini를 호출하지 않고 ALREADY_EXISTS를 반환한다.
- S3 PUT은 기존 키에 If-None-Match: * 조건으로 실행한다. 경합 시 이미 올라간 이미지를 유지한다.
- HEAD가 404인 경우에만 누락으로 판단한다. 403 등 조회 오류가 나면 생성을 중단한다.
- PUT 오류 시 삭제 보상을 실행하지 않는다. 결과가 불명확하면 같은 API를 다시 호출해 존재 여부를 확인한다.
- 기존 키의 확장자는 png/jpg/jpeg/webp를 지원한다. 생성 응답 MIME 형식이 확장자와 다르면 409로 중단한다. 자동 형식 변환은 하지 않는다.
- 대상 기록 없음은 404, 복구 불가능한 기록은 409, 생성 어댑터 비활성화는 503이다.

## 실행 전 확인

같은 버킷과 prefix를 사용하는 모든 환경에서 고아 이미지 삭제 스케줄러 제거본을 배포해야 한다.
기존 참조 이미지도 삭제된 경우에는 그 이미지를 먼저 복구해야 한다.
실행 서버에는 Gemini 설정 및 S3 GetObject/PutObject 권한이 필요하다.
자동 생성 복구는 누락 객체를 HEAD로 확인하므로 버킷의 ListBucket 권한도 필요할 수 있다. 파일 직접 업로드는 조건부 PUT으로 검사한다.

API는 이미지 생성 완료까지 동기로 기다린다. 클라이언트와 프록시의 타임아웃을 고려해 한 건씩 실행한다.
동일 서버 인스턴스에서는 자동 생성 복구와 파일 업로드 복구가 공통 실행 잠금을 사용한다.
한 건이 성공하거나 실패한 시점부터 10초 후 다음 작업이 시작된다.
ADMIN_IMAGE_RECOVERY_INTERVAL로 간격을 조절한다. 일반 사용자 생성에는 적용하지 않는다.
대기 중인 요청도 HTTP 연결을 유지하므로 클라이언트에서 한 건 응답을 받은 뒤 다음 요청을 보내는 방식을 권장한다.
인스턴스가 여러 개면 잠금을 공유하지 않으므로 복구 요청은 한 인스턴스로 보내야 한다. 재시작 시 대기 요청과 마지막 실행 시각은 유지되지 않는다.
새 이미지 생성에는 공급자 비용이 발생하고 원본과 픽셀 단위로 동일한 이미지는 보장하지 않는다.
이 구현 작업에서는 실제 Gemini 호출과 S3 복구를 실행하지 않았다.

## 준비한 이미지 직접 업로드

POST /api/v1/admin/generations/restore-image/upload
Content-Type: multipart/form-data
텍스트 필드 이름: imageObjectKey (복구할 생성 이미지의 S3 키)
파일 필드 이름: image

imageObjectKey에 복구할 생성 이미지의 S3 키를 전달한다. DB의 image_object_key 값이 있다면 그대로 사용한다.
전체 URL이나 s3://버킷/ 경로가 아니라 버킷 내부 키만 입력한다.
DB 생성 기록 조회는 하지 않는다. UUID는 입력하지 않는다.
관리자 요청에 입력한 버킷 내부 키를 그대로 사용한다. DB의 존재 여부나 상태는 검사하지 않는다.
기존 생성 API와 달리 스토리보드, 프롬프트, Gemini 호출이 필요하지 않다.
SUCCEEDED 상태 검사는 하지 않는다. S3 조건부 PUT이 기존 객체를 확인한다. 이미 있으면 ALREADY_EXISTS를 반환하고 덮어쓰지 않는다.

위 절차로 관리자 토큰과 CSRF 쿠키/토큰을 준비한 뒤 호출한다:

~~~sh
curl --fail-with-body -b recovery-cookies.txt -X POST "$BASE_URL/api/v1/admin/generations/restore-image/upload" \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN" \
  -H "X-XSRF-TOKEN: $CSRF_TOKEN" \
  -F "imageObjectKey=generated/diary-images/기존경로/image.png" \
  -F "image=@recovered.png"
~~~

직접 업로드는 PNG/JPEG, 최대 20MiB 및 2500만 픽셀을 지원한다.
서버가 파일 내용을 디코딩해 형식을 확인하고 S3 Content-Type을 설정한다.
기존 키의 확장자와 형식이 다르면 409로 거절한다. 확장자만 바꾸지 말고 실제 이미지 형식을 변환해야 한다.
WebP 직접 업로드는 현재 지원하지 않는다. S3 설정의 최대 객체 크기가 더 작다면 해당 제한도 적용된다.
응답은 imageObjectKey와 status(RESTORED 또는 ALREADY_EXISTS)를 포함한다.
