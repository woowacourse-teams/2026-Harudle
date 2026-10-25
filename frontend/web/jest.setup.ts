import '@testing-library/jest-dom/jest-globals';
import { jest } from '@jest/globals';

// jsdom에서 제공하지 않는 요소 스크롤 API를 테스트에서 호출할 수 있게 한다.
if (typeof HTMLElement !== 'undefined') {
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
    configurable: true,
    writable: true,
    value: jest.fn<HTMLElement['scrollTo']>(),
  });
}
