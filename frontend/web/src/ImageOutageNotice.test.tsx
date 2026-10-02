import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { cleanup, render, screen } from '@testing-library/react';
import ImageOutageNotice from './ImageOutageNotice';

const mockLocation = { pathname: '/' };
jest.mock('react-router', () => ({
  useLocation: () => mockLocation,
}));

beforeAll((): void => {
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value: jest.fn<() => void>(),
  });
});

beforeEach((): void => {
  localStorage.setItem('harudle:image-outage:2026-09-27:v3', 'acknowledged');
});

afterEach((): void => {
  cleanup();
  localStorage.clear();
});

const renderNotice = (path: string): void => {
  mockLocation.pathname = path;
  render(<ImageOutageNotice />);
};

describe('공지 노출 조건', () => {
  it('비로그인 상태에서는 숨기고 로그인 완료 후 표시하며 로그아웃하면 숨긴다', () => {
    mockLocation.pathname = '/';
    const view = render(<ImageOutageNotice />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();

    localStorage.setItem('harudle.has-completed-oauth', 'true');
    mockLocation.pathname = '/diary-write';
    view.rerender(<ImageOutageNotice />);
    expect(
      screen.getByRole('button', {
        name: /일기 이미지 재생성 완료 및 지원 안내/,
      }),
    ).toBeInTheDocument();

    localStorage.removeItem('harudle.has-completed-oauth');
    mockLocation.pathname = '/login';
    view.rerender(<ImageOutageNotice />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('dialog', { hidden: true }),
    ).not.toBeInTheDocument();
  });

  it.each([
    '/landing',
    '/landing/result/diary-id',
    '/login',
    '/auth/callback',
    '/admin',
    '/admin/diaries',
  ])('%s는 로그인했어도 배너와 팝업을 숨긴다', (path): void => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    renderNotice(path);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('dialog', { hidden: true }),
    ).not.toBeInTheDocument();
  });

  it.each(['/', '/setting', '/diary-write', '/diary/diary-id'])(
    '%s는 로그인 후 공지를 표시한다',
    (path): void => {
      localStorage.setItem('harudle.has-completed-oauth', 'true');
      renderNotice(path);
      expect(
        screen.getByRole('button', {
          name: /일기 이미지 재생성 완료 및 지원 안내/,
        }),
      ).toBeInTheDocument();
    },
  );
});
