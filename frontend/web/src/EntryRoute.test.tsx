import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import EntryRoute from './EntryRoute';
import { HAS_SEEN_LANDING_KEY } from './shared/constants';

jest.mock('react-router', () => ({
  Outlet: () => <div>피드</div>,
  Navigate: ({ to }: { to: string }) => <div>이동: {to}</div>,
}));

afterEach(() => {
  jest.restoreAllMocks();
  localStorage.removeItem(HAS_SEEN_LANDING_KEY);
  localStorage.removeItem('harudle.has-completed-oauth');
});

describe('랜딩 방문 여부에 따른 진입', () => {
  it('랜딩을 보지 않은 첫 방문은 랜딩으로 이동한다', () => {
    localStorage.removeItem(HAS_SEEN_LANDING_KEY);
    render(<EntryRoute />);
    expect(screen.getByText('이동: /landing')).toBeInTheDocument();
    expect(screen.queryByText('피드')).not.toBeInTheDocument();
  });

  it('랜딩을 본 적이 있으면 피드를 표시한다', () => {
    localStorage.setItem(HAS_SEEN_LANDING_KEY, 'true');
    render(<EntryRoute />);
    expect(screen.getByText('피드')).toBeInTheDocument();
    expect(screen.queryByText(/이동:/)).not.toBeInTheDocument();
  });

  it('로그인 완료 후에는 랜딩 방문 기록이 없어도 피드에 진입한다', (): void => {
    localStorage.removeItem(HAS_SEEN_LANDING_KEY);
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    render(<EntryRoute />);
    expect(screen.getByText('피드')).toBeInTheDocument();
    expect(screen.queryByText(/이동:/)).not.toBeInTheDocument();
  });

  it('방문 기록을 읽지 못해도 공개 피드를 표시한다', () => {
    jest.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('저장소 읽기 실패');
    });
    render(<EntryRoute />);
    expect(screen.getByText('피드')).toBeInTheDocument();
  });
});
