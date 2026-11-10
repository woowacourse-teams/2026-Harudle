import { useEffect } from 'react';
import { useLocation } from 'react-router';

// 운영 대표 주소. harudle.com과 dev.harudle.com에서 열려도 검색엔진에는 이 주소를 알린다.
export const CANONICAL_ORIGIN = 'https://www.harudle.com';

// SPA는 모든 경로에 같은 index.html을 내려주므로, 경로마다 대표 URL을 지정해
// 검색엔진이 중복 페이지로 묶지 않게 한다. 쿼리와 해시는 대표 URL에서 제외한다.
const CanonicalLink = () => {
  const { pathname } = useLocation();

  useEffect(() => {
    let link = document.head.querySelector<HTMLLinkElement>(
      'link[rel="canonical"]',
    );
    if (!link) {
      link = document.createElement('link');
      link.rel = 'canonical';
      document.head.appendChild(link);
    }
    link.href = `${CANONICAL_ORIGIN}${pathname}`;
  }, [pathname]);

  return null;
};

export default CanonicalLink;
