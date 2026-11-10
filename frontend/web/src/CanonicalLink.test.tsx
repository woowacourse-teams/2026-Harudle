import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { render } from '@testing-library/react';

import CanonicalLink from './CanonicalLink';

let mockPathname = '/';
jest.mock('react-router', () => ({
  useLocation: () => ({ pathname: mockPathname }),
}));

const canonicalLinks = () =>
  document.head.querySelectorAll<HTMLLinkElement>('link[rel="canonical"]');

describe('CanonicalLink', () => {
  afterEach(() => {
    canonicalLinks().forEach((link) => link.remove());
  });

  it('현재 경로를 운영 대표 주소의 canonical로 지정한다', () => {
    mockPathname = '/landing';

    render(<CanonicalLink />);

    expect(canonicalLinks()[0]?.href).toBe('https://www.harudle.com/landing');
  });

  it('경로가 바뀌면 canonical 태그 하나의 주소만 갱신한다', () => {
    mockPathname = '/';
    const { rerender } = render(<CanonicalLink />);

    mockPathname = '/login';
    rerender(<CanonicalLink />);

    expect(canonicalLinks()).toHaveLength(1);
    expect(canonicalLinks()[0]?.href).toBe('https://www.harudle.com/login');
  });
});
