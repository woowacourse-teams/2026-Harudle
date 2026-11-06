import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import DiaryDetailError from './DiaryDetailError';

const mockNavigate = jest.fn();
jest.mock('react-router', () => ({ useNavigate: () => mockNavigate }));
jest.mock('../../assets/images/request-fail.png', () => 'request-fail.png');
jest.mock('../../assets/icons/back.svg', () => 'back.svg');
beforeEach((): void => {
  mockNavigate.mockReset();
});

describe('일기 상세 원문 조회 장애', (): void => {
  it('서버 행동 안내와 앨범 복귀를 제공하고 즉시 재조회 버튼을 표시하지 않는다', async (): Promise<void> => {
    const user = userEvent.setup();
    const action = '나중에 다시 이용해 주세요.';
    render(
      <DiaryDetailError
        errorMessage="일기 내용을 불러올 수 없어요."
        actionMessage={action}
        canReload={false}
      />,
    );
    expect(screen.getByText(action)).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '다시 불러오기' }),
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '앨범으로 돌아가기' }));
    expect(mockNavigate).toHaveBeenCalledWith('/album');
  });
  it('일반 조회 실패에서는 기존 다시 불러오기 버튼을 유지한다', (): void => {
    render(<DiaryDetailError errorMessage="네트워크 연결을 확인해주세요." />);
    expect(
      screen.getByRole('button', { name: '다시 불러오기' }),
    ).toBeInTheDocument();
  });
});
