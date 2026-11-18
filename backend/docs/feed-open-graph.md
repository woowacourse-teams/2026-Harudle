# 피드별 공유 미리보기

피드의 공유 버튼으로 복사한 링크에 해당 네컷만화가 표시되게 한다.
이미지 주소 자체에는 만료 시각을 넣지 않는다. 피드가 공개되어 있는 동안 같은 주소로 요청할 수 있다.
개인 일기와 일반 화면의 S3/R2 서명 URL 정책은 그대로 유지한다.

## 요청 흐름

1. 피드 게시·목록·상세 API의 기존 `shareUrl` 필드는 `/feeds/{feedId}` 주소를 유지한다.
   캠퍼스의 링크 붙여넣기 등 기존 연동도 같은 주소를 사용한다.
2. Frontend Nginx가 알려진 공유 수집기의 요청만 `/api/v1/public/feeds/{feedId}`로 내부 프록시한다.
   수집기는 같은 공유 주소에서 피드별 `og:title`, `og:description`, `og:image`가 담긴 HTML을 받는다.
   원문은 읽거나 HTML에 넣지 않는다. 공개 닉네임과 카테고리만 설명에 사용한다.
3. `og:image`는 기본적으로 같은 도메인의 `/api/v1/public/feeds/{feedId}/image`다.
   서버가 현재 익명 공개 여부를 확인한 다음 JPEG 본문을 반환한다.
4. 일반 브라우저와 카카오톡 WebView는 기존 React 화면을 받는다.
   HTML API에 직접 접속한 브라우저만 제한된 스크립트로 원래 피드 화면으로 이동한다.
   이때 쿼리와 해시는 유지한다. 이미 피드 경로이면 같은 주소로 다시 이동하지 않는다.

React 코드와 공유 주소 형식은 바꾸지 않는다. 전에 복사된 링크도 새로 수집하면 피드별 미리보기를 받는다.
카카오톡 스크랩, Facebook, X/Twitter, Slack, Discord 등 명시된 수집기를 지원한다.
알 수 없는 수집기는 기존 공통 OG 이미지로 표시될 수 있으므로 UA 목록을 점검한다.
피드 HTML 응답은 `Vary: User-Agent`로 구분한다. Google/Bing 검색 봇을 별도로 분기하지 않는다.

## 이미지 제공과 공개 범위

- 주소의 피드 ID로 기존 저장소의 익명 공개 조회 조건을 적용한다. 사용자가 임의의 S3 Object Key를 지정할 수 없다.
  OG 전용 조회는 닉네임, 카테고리, 이미지 키만 사용하며 프로필 사진 URL을 발급하거나 일기 원문을 복호화하지 않는다.
- 피드나 일기가 삭제되었거나, 작성자가 탈퇴했거나, 관리자에 의해 숨겨졌거나,
  생성이 완료되지 않은 경우 `404 FEED_NOT_FOUND`다. 개인별 차단은 로그인 정보가 없는 공개 공유 수집기에 적용할 수 없다.
- S3의 PNG/JPEG/WebP 원본을 우선 읽고, 읽기나 JPEG 변환에 실패하면 다음 원본 후보와 기존 상세 이미지를 시도한다.
  S3 후보로 이미지를 만들지 못하면 활성화된 R2 원본을 시도한다. 후보는 한 번씩만 확인하며 재업로드하지 않는다.
  기존 화면의 서명 URL 선택과 달리, OG는 S3 파일 부재뿐 아니라 읽기 및 변환 실패에도 다른 후보를 사용한다.
  정상 백업이 있는 공유를 최대한 제공하기 위한 별도 정책이며 기존 S3/R2 URL 선택 코드는 변경하지 않는다.
  그만큼 첫 요청은 S3 대기에 R2 대기까지 더해질 수 있다. 아래 동시 처리 제한과 짧은 내부 캐시를 함께 적용한다.
- 전체 네컷이 잘리지 않게 비율을 유지하고 흰 여백을 더해 1200×630 JPEG로 변환한다.
  원본은 20MiB, 3천만 픽셀 이내로 제한하며 WebP 변환은 기존 `dwebp` 실행 파일을 사용한다.
  다운로드와 변환 전체를 서버당 최대 2건으로 제한한다. 초과 시 `503 FEED_PREVIEW_CAPACITY_EXCEEDED`와
  `Retry-After: 5`를 반환한다. 변환 및 저장소 장애는 기존 `503 IMAGE_STORAGE_ERROR`다.
  WebP 변환의 5초 제한은 전체 HTTP 요청의 제한 시간이 아니다. 저장소 조회는 기존 S3/R2 타임아웃을 따르며,
  저장소가 느리면 두 처리 자리가 점유되는 동안 새 이미지 요청이 503을 받을 수 있다.
- 완성된 JPEG 바이트만 이미지 Object Key 기준으로 최대 1분 보관한다. 캐시의 JPEG 바이트 용량 기준은 16MiB다.
  HEAD 다음 GET 등 반복 요청에서 다운로드와 변환을 줄이며, 실패한 결과는 보관하지 않는다.
  공개 상태는 캐시 조회 **전에 매 요청 검사**한다. 숨김이나 삭제 이후에는 남아 있는 캐시도 제공하지 않는다.
  원본을 같은 키로 복구하면 새 내용이 반영되기까지 최대 1분 걸릴 수 있다.
- HTML, 이미지와 실패 응답은 `Cache-Control: no-store`다. Nginx 자체의 502/504에도 OG 경로만 이 정책을 적용한다.
  서버 내부 JPEG 캐시와 브라우저 및 CDN 캐시는 별개다.
  HTML의 공개 정보는 이스케이프하며, 이동 스크립트는 해시로 제한하는 CSP를 사용한다.

만료가 없는 주소는 영구 공개 약속이 아니다. 피드를 숨기면 이후 서버 요청은 거절한다.
공유 서비스가 이미 내려받아 자체적으로 보관한 카드나 파일까지 원격으로 지우는 것은 보장하지 않는다.

## 확장 구조와 장애 확인

기존 `FeedQueryService`, `FeedQueryRepository`, 프로필 이미지 URL 발급기와 S3/R2 어댑터의 동작은 바꾸지 않는다.
새 `FeedPreviewReader`가 기존 공개 조회 조건과 프로필 스냅샷을 재사용하고,
`FeedPreviewImageProvider`가 이미지 제공 책임을 분리한다.
저장소 구현은 후보별 읽기와 JPEG 변환 성공까지 담당하며, 캐시 구현이 이를 감싸 보관과 동시 실행 제한을 담당한다.
OG 전용 예외 처리기를 추가해 기존 공통 예외 처리기에도 분기를 넣지 않는다.
공용 `ErrorType`에는 새 오류 계약 한 항목을 등록한다. 설정, Nginx 경로와 CI 검사는 새 기능 연결에 필요한 변경이다.

`event=feed_preview_candidate_failed`는 고정된 `source`, `stage`, `reason` 값으로 후보 실패를 구분한다.
`event=feed_preview_image_failed`의 `reason`으로 크기 초과, 디코딩 실패와 WebP 실행 실패를 구분한다.
객체 키, 원본 주소, 예외 메시지는 기록하지 않는다. 처리 한도 초과 횟수는
`harudle.feed.preview.capacity.rejected`로 등록하며 Prometheus 이름은 `harudle_feed_preview_capacity_rejected_total`이다.
CloudWatch에서 사용하려면 별도 수집 선언과 대시보드 적용이 필요하다.

IP별 요청 제한은 이번 변경에 포함하지 않는다. 현재 Nginx 앞에 호스트 프록시가 있으므로
실제 사용자 IP를 신뢰할 범위부터 정한 뒤 호스트 Nginx 또는 WAF에서 적용해야 한다.
캐시와 동시 실행 제한이 반복 호출이나 모든 과부하를 막는 것은 아니다.

## 배포와 설정

새 필수 환경 변수나 DB 마이그레이션은 없다. 기존 `FEED_PUBLIC_BASE_URL`은 해당 환경의
프론트 상세 페이지 기준 주소로 유지한다(예: `https://www.harudle.com/feeds`).
공유·이미지 주소는 이 값의 도메인에서 만든다. 요청의 Host 헤더로 만들지 않는다.

이미지와 HTML API는 기존 Frontend Nginx의 `/api/**` 프록시를 사용한다.
기존 피드 링크에서 OG를 제공하려면 이 PR의 Nginx 설정이 포함된 **프론트 이미지도 함께 배포**해야 한다.
백엔드를 먼저 배포한 뒤 Nginx 설정을 배포한다. React 재작성이나 별도 프론트 기능 PR은 필요하지 않다.
이미지 변환에 필요한 `dwebp`는 기존 WebP 도구가 설치된 백엔드 이미지에 포함되어야 한다.

배포 후 다음을 확인한다.

1. 공개 피드의 `shareUrl`에 `User-Agent: kakaotalk-scrap/1.0`으로 요청해 해당 닉네임과 고정 `og:image`가 있는지 확인한다.
2. 이미지 주소가 `200 image/jpeg`인지, 15분 이상 지나서 다시 요청해도 같은 주소로 열리는지 확인한다.
3. 브라우저와 카카오톡 WebView에서 같은 링크로 기존 피드 화면이 열리고 캠퍼스 링크 붙여넣기도 유지되는지 확인한다.
4. 피드를 삭제하거나 관리자가 숨긴 뒤 수집기 공유 요청과 이미지 주소가 `404`인지 확인한다.
5. 비공개 일기의 ID로 요청하면 `404`이고 원문이나 이미지가 내려오지 않는지 확인한다.

## CloudFront를 연결하는 경우

이 PR은 고정 주소와 공개 상태 검사를 먼저 완성한다. AWS 리소스를 자동으로 만들거나
기존 S3 객체를 공개하지 않는다. CDN이 없어도 위 요청은 동작한다.

CloudFront를 연결하려면 **S3 직접 공개 대신 이 이미지 엔드포인트를 원본으로 사용**한다.
S3 직접 주소는 DB에서 피드가 삭제되거나 숨겨져도 공개 여부를 검사할 수 없기 때문이다.

- 원본: 해당 환경의 앱을 제공하는 HTTPS 원본. CloudFront 자체를 다시 가리켜 순환하지 않게 한다.
- 경로: `/api/v1/public/feeds/*/image`, Origin path는 비운다. GET·HEAD만 허용한다.
- 캐시 정책: AWS 관리형 `CachingDisabled`. 최소·기본·최대 TTL은 모두 0이다.
  403·404·5xx의 Error caching minimum TTL도 0으로 둔다.
- 쿠키, Authorization 헤더, 쿼리는 원본으로 전달하지 않는다. 피드 ID가 경로에 있고 익명 공개 조회만 사용한다.
- 원본 도메인은 원본 HTTPS 인증서와 일치해야 한다. 수집기가 보낸 viewer `Host`는 전달하지 않는다.
  캐시 정책과 Origin Request Policy는 별도 설정이다.
- HTTPS와 인증서를 준비한 뒤 아래 선택 설정을 적용하고 백엔드 컨테이너를 재생성한다.

```dotenv
# 예시이며 아직 생성된 도메인이 아니다. 경로를 포함한 기준 주소다.
FEED_PREVIEW_IMAGE_BASE_URL=https://images.harudle.com/api/v1/public/feeds
```

이 값은 `og:image`의 도메인만 바꾼다. 공유 페이지와 브라우저 이동 목적지는 바꾸지 않는다.
비우거나 생략하면 기존 앱 도메인을 사용한다.

현재 CDN 연결은 URL 안정성과 전송 경로를 제공하며 장기 이미지 캐싱으로 앱 부하를 줄이는 구성은 아니다.
나중에 TTL을 늘리려면 피드 삭제·숨김 시 CDN 캐시 회수와 실패 재시도까지 먼저 구현해야 한다.
단순히 S3 서명 주소나 302 응답을 오래 캐시해서는 만료와 노출 회수 문제가 해결되지 않는다.

관련 공식 문서: [CloudFront 캐시 정책](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/using-managed-cache-policies.html),
[캐시 유효 시간](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/Expiration.html),
[OG 메타데이터](https://ogp.me/).
