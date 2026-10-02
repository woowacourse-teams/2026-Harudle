import { useEffect, type RefObject } from 'react';

const useLandingReveal = (pageRef: RefObject<HTMLElement | null>): void => {
  useEffect(() => {
    const page = pageRef.current;
    if (!page || typeof IntersectionObserver === 'undefined') {
      return;
    }

    const observer = new IntersectionObserver(
      (entries): void => {
        entries.forEach((entry): void => {
          if (entry.isIntersecting && entry.target instanceof HTMLElement) {
            entry.target.dataset.revealed = 'true';
            observer.unobserve(entry.target);
          }
        });
      },
      { root: page, threshold: 0.3, rootMargin: '0px 0px -80px 0px' },
    );

    page
      .querySelectorAll<HTMLElement>('[data-reveal]')
      .forEach((element): void => {
        observer.observe(element);
      });
    page.dataset.motion = 'enabled';

    return (): void => {
      observer.disconnect();
      delete page.dataset.motion;
    };
  }, [pageRef]);
};

export default useLandingReveal;
