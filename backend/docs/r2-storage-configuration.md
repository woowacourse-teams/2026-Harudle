# R2 저장소 설정

R2 설정과 전용 SDK 클라이언트를 구성한다. 백업 실행, 스케줄, 수동 복구와 조회 재시도는 이후 단계에서 연결한다.

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

비활성 상태에서는 R2 설정을 바인딩하거나 클라이언트를 생성하지 않으므로 자격 증명이 없어도 시작할 수 있다. 활성화하면 필수 설정, endpoint와 URL 유효기간을 서버 시작 단계에서 검증한다. 개발 환경에서도 `R2_ENABLED=true`로 개발 이미지 백업을 조회하는 테스트를 할 수 있다. 개발 서버에는 개발용 자격 증명을 사용하며 운영 R2 자격 증명을 제공하지 않는다.

## 클라이언트 구분

- 기본 S3: `s3Client`, `s3Presigner`. 기존 AWS 자격 증명 체계를 사용한다.
- R2: `r2S3Client`, `r2S3Presigner`. R2 endpoint와 명시적인 R2 자격 증명을 사용한다.
- 소비하는 코드에서 `@Qualifier`로 클라이언트를 선택한다. 기본 이미지 저장과 URL 발급은 기존 S3를 사용한다.
- R2는 `auto` region, path style 접근과 업로드 chunked encoding 비활성화를 적용한다. [Cloudflare Java SDK 가이드](https://developers.cloudflare.com/r2/examples/aws/aws-sdk-java/)
- 두 종류의 클라이언트는 애플리케이션 컨텍스트 종료 시 닫힌다.

클라이언트를 만드는 과정에서는 R2에 요청하지 않는다. 버킷 존재 여부, 실제 권한과 네트워크 연결 검증은 어댑터 구현 단계에서 수행한다. R2 버킷은 비공개로 유지하며, 공개 접근이나 IAM 정책을 변경하는 기능은 포함하지 않는다.
