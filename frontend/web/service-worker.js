/**
 * 1. 서비스 워커 설치 (최초 1회 실행)
 */
self.addEventListener('install', (event) => {
  console.log('서비스 워커가 설치되었습니다!');
  // 대기 중인 서비스 워커를 즉시 활성화
  self.skipWaiting();
});

/**
 * 2. 서비스 워커 활성화
 */
self.addEventListener('activate', (event) => {
  console.log('서비스 워커가 활성화되었습니다!');
});

/**
 * 3. 네트워크 요청 가로채기 (PWA 오프라인 작동의 핵심)
 */
self.addEventListener('fetch', (event) => {
  // 현재는 통과시키지만, 나중에 캐싱 로직을 여기에 넣습니다.
});
