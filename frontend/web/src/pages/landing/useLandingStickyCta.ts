import { useEffect, useState, type RefObject } from 'react';

// 내부 스크롤을 기준으로 첫 버튼을 지난 뒤부터 체험 섹션 전까지만 안내한다.
const useLandingStickyCta = (
  pageRef: RefObject<HTMLElement | null>,
  heroActionRef: RefObject<HTMLButtonElement | null>,
  finalRef: RefObject<HTMLElement | null>,
): boolean => {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const page = pageRef.current;
    const heroAction = heroActionRef.current;
    const finalSection = finalRef.current;
    if (
      !page ||
      !heroAction ||
      !finalSection ||
      typeof IntersectionObserver === 'undefined'
    ) {
      return;
    }
    const updateVisibility = (): void => {
      const viewport = page.getBoundingClientRect();
      const hero = heroAction.getBoundingClientRect();
      const final = finalSection.getBoundingClientRect();
      setVisible(hero.bottom <= viewport.top && final.top >= viewport.bottom);
    };
    const observer = new IntersectionObserver(updateVisibility, { root: page });
    observer.observe(heroAction);
    observer.observe(finalSection);
    return (): void => observer.disconnect();
  }, [pageRef, heroActionRef, finalRef]);

  return visible;
};

export default useLandingStickyCta;
