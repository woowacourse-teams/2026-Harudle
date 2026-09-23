# 저장된 스토리보드로 이미지 복구

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
운영 Nginx 설정에도 요청 본문 제한을 25m 이상으로 반영해야 한다. 저장소의 deploy/nginx 예시는 수정했지만 서버에 설치된 설정은 별도로 갱신해야 한다.
서버가 파일 내용을 디코딩해 형식을 확인하고 S3 Content-Type을 설정한다.
기존 키의 확장자와 형식이 다르면 409로 거절한다. 확장자만 바꾸지 말고 실제 이미지 형식을 변환해야 한다.
WebP 직접 업로드는 현재 지원하지 않는다. S3 설정의 최대 객체 크기가 더 작다면 해당 제한도 적용된다.
응답은 imageObjectKey와 status(RESTORED 또는 ALREADY_EXISTS)를 포함한다.
