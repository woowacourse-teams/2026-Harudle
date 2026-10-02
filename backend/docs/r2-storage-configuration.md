# R2 저장소 설정

R2 설정, 전용 SDK 클라이언트와 원본 접근 어댑터를 구성한다. 백업 실행, 스케줄, 수동 복구와 조회 재시도는 이후 단계에서 연결한다.

## 환경 변수

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `R2_ENABLED` | `false` | R2 클라이언트 활성화 여부 |
| `DEPLOY_ENV` | 없음 | 실행 환경. 개발 테스트는 `dev`, 운영은 `prod`를 사용한다. Spring 프로필과 별개다 |
| `R2_ENDPOINT` | 없음 | Cloudflare가 제공하는 HTTPS S3 API endpoint. 버킷 경로를 붙이지 않는다 |
| `R2_BUCKET` | 없음 | 백업 버킷 이름. 운영 예정 버킷은 `harudle-backup` |
| `R2_ACCESS_KEY_ID` | 없음 | 해당 환경의 R2 Access Key ID |
| `R2_SECRET_ACCESS_KEY` | 없음 | 해당 환경의 R2 Secret Access Key |
| `R2_ACCESS_URL_TTL` | `15m` | GET 서명 URL 유효기간. 1초 이상 7일 이하 |
| `R2_MAX_OBJECT_SIZE` | `20MB` | 업로드·다운로드할 원본의 최대 크기. 2GiB 미만의 양수 |

비활성 상태에서는 R2 설정을 바인딩하거나 클라이언트를 생성하지 않으므로 자격 증명이 없어도 시작할 수 있다. 활성화하면 필수 설정, endpoint와 URL 유효기간을 서버 시작 단계에서 검증한다. 개발 환경에서도 `R2_ENABLED=true`로 개발 이미지 백업을 조회하는 테스트를 할 수 있다. 개발 서버에는 개발용 자격 증명을 사용하며 운영 R2 자격 증명을 제공하지 않는다.

## 클라이언트 구분

- 기본 S3: `s3Client`, `s3Presigner`. 기존 AWS 자격 증명 체계를 사용한다.
- R2: `r2S3Client`, `r2S3Presigner`. R2 endpoint와 명시적인 R2 자격 증명을 사용한다.
- 소비하는 코드에서 `@Qualifier`로 클라이언트를 선택한다. 기본 이미지 저장과 URL 발급은 기존 S3를 사용한다.
- R2는 `auto` region, path style 접근과 업로드 chunked encoding 비활성화를 적용한다. [Cloudflare Java SDK 가이드](https://developers.cloudflare.com/r2/examples/aws/aws-sdk-java/)
- R2 클라이언트의 선택적 요청 체크섬 계산과 응답 체크섬 검증을 `WHEN_REQUIRED`로 설정한다. SDK가 기본으로 추가하는 전체 객체 CRC32 등을 보내지 않는다. 원본의 SHA-256 비교는 이후 백업 실행기에서 수행한다. [R2 S3 호환 목록](https://developers.cloudflare.com/r2/api/s3/api/)
- R2 SDK 호출 전체 제한은 60초, 개별 시도 제한은 20초다.
- 두 종류의 클라이언트는 애플리케이션 컨텍스트 종료 시 닫힌다.

클라이언트와 어댑터를 만드는 과정에서는 R2에 요청하지 않는다. 실제 작업을 호출하면 버킷·권한·통신 오류를 전달한다. 테스트에서는 가짜 자격 증명과 HTTP 전송 대역을 사용하며 실제 계정의 접근 권한은 배포 환경에서 별도로 확인해야 한다. R2 버킷은 비공개로 유지하며, 공개 접근이나 IAM 정책을 변경하는 기능은 포함하지 않는다.

## 원본 접근

`BackupObjectStorage`를 주입해 사용한다. `R2_ENABLED=true`일 때 `R2BackupObjectStorage`가 등록되며, R2 전용 `r2S3Client`와 `r2S3Presigner`를 사용한다. 기존 `ImageStorage`와 `ImageUrlProvider`는 계속 S3를 사용한다.

| 메서드 | 동작 |
| --- | --- |
| `findMetadata(key)` | HEAD로 키·MIME·크기·ETag를 반환한다. 파일 없음만 `Optional.empty()` |
| `download(key)` | 원본 바이트와 MIME을 반환한다. 파일 없음만 `Optional.empty()` |
| `uploadIfAbsent(key, original)` | 키·MIME·바이트를 유지해 조건부 PUT. `UPLOADED` 또는 `ALREADY_EXISTS` |
| `createAccessUrl(key)` | 설정한 유효기간의 GET 서명 URL과 만료 시각을 반환한다. 존재 확인 요청은 하지 않는다 |

`DEPLOY_ENV=dev`이면 `harudle/generated/diary-images/dev/`, `prod`이면 `harudle/generated/diary-images/prod/` 아래 원본만 허용한다. 폴더 구조는 변경하지 않는다. 파일명은 기존 키 규칙의 `image.png`, `image.jpg`, `image.webp`를 허용하며 MIME이 확장자와 일치해야 한다. 참조 이미지, `image-960.webp`, `image-240.webp`, 다른 환경의 키와 잘못된 경로는 SDK 호출 전에 거절한다.

업로드는 존재 확인 후 일반 PUT을 보내는 대신 `If-None-Match: *`를 포함한 PUT 한 번으로 시작한다. 동시에 같은 키를 저장해도 기존 객체를 덮어쓰지 않는다. 412는 `ALREADY_EXISTS`로 처리한다. 409, 권한 오류와 통신 실패는 예외로 전달하며 실패 후 삭제하지 않는다. SDK가 첫 PUT의 응답을 잃고 재시도에서 412를 받았을 수도 있으므로, `ALREADY_EXISTS`를 내용 일치 또는 검증 성공으로 취급하면 안 된다. 원본과 백업의 SHA-256 비교는 백업 실행기의 책임이다. ETag도 SHA-256으로 취급하지 않는다.

본문 없는 HEAD 404는 HeadBucket으로 버킷 존재를 한 번 확인한다. 버킷도 없으면 설정 오류, 확인 권한이 없으면 권한 오류로 전달한다. 자격 증명에는 대상 버킷의 객체 읽기·쓰기와 HeadBucket에 필요한 권한을 부여한다. 버킷이나 객체를 공개로 만드는 요청은 보내지 않는다.

다운로드는 Content-Length와 실제 읽은 크기 모두 제한하고 잘못된 응답 스트림을 중단한다. 업로드도 실제 읽은 바이트를 제한한다. 원본을 재인코딩하거나 파생 이미지를 생성하지 않는다.

## 백업용 원본 키 변환

`ImageVariantKeys.originalImageKeyCandidatesForBackup(imageObjectKey)`로 DB의 이미지 키에서 원본 후보 목록을 만든다. 원본과 백업의 폴더 경로와 파일명이 같다는 전제로 마지막 파일명만 처리한다.

| 입력 파일명 | 반환 후보 |
| --- | --- |
| `image.png`, `image.jpg`, `image.webp` | 입력 키 하나를 그대로 반환 |
| `image-960.webp` | 같은 폴더의 `image.png`, `image.jpg`, `image.webp` 순서로 반환 |

UUID 폴더가 하나인 기존 경로와 두 개인 현재 경로를 모두 유지한다. 키가 비어 있거나 파일명이 지원 대상이 아니면 `IllegalArgumentException`을 발생시킨다. 이 함수의 입력은 DB 대표 이미지 키이므로 썸네일 `image-240.webp`는 지원하지 않는다.

변환 함수는 키 후보만 반환한다. 환경·경로 검증은 저장소에서 수행하고, 실제 후보 중 존재하는 파일 확인은 이후 백업·R2 조회 로직에서 처리한다. `image-960.webp`만으로 원본 확장자를 확정하거나 원본 존재를 보장하지 않는다.

## 오류와 로그

오류는 `BackupStorageException.failureType()`으로 구분한다. 요청 검증·준비, 응답 처리, 인증, 권한, 설정, 제공자, 클라이언트 오류를 별도로 전달한다. 파일 없음은 예외가 아닌 조회 결과다.

`event=r2_object_operation` 로그에 작업·버킷·키·결과·크기·실패 유형을 기록한다. 검증되지 않은 키는 `invalid`로 남기고, 예외는 기존 `ExternalApiLogger`를 통해 메시지를 제거한 진단 정보만 기록한다. 자격 증명과 서명 URL은 로그에 남기지 않는다. 이 단계에서는 DB 백업 기록 테이블이나 서명 URL 저장을 추가하지 않는다.
