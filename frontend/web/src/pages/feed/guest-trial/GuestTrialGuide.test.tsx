import { beforeAll, describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef } from 'react';
import GuestTrialGuide from './GuestTrialGuide';

const renderGuide = (): {
  onStart: jest.Mock<() => void>;
  onClose: jest.Mock<() => void>;
} => {
  const onStart = jest.fn<() => void>();
  const onClose = jest.fn<() => void>();
  const anchorRef = createRef<HTMLButtonElement>();

  render(
    <>
      <button ref={anchorRef} type="button" aria-label="네컷만화 만들기" />
      <ul aria-label="카테고리">
        <li>전체</li>
      </ul>
      <GuestTrialGuide
        isOpen
        anchorRef={anchorRef}
        onStart={onStart}
        onClose={onClose}
      />
    </>,
  );

  return { onStart, onClose };
};

beforeAll((): void => {
  // jsdom은 dialog의 모달 API를 제공하지 않아 열림 속성만 바꾼다.
  Object.defineProperties(HTMLDialogElement.prototype, {
    showModal: {
      configurable: true,
      value(this: HTMLDialogElement): void {
        this.setAttribute('open', '');
      },
    },
    close: {
      configurable: true,
      value(this: HTMLDialogElement): void {
        this.removeAttribute('open');
      },
    },
  });
});

describe('첫 방문 가이드 단계', (): void => {
  const goToLastStep = async (
    user: ReturnType<typeof userEvent.setup>,
  ): Promise<void> => {
    await user.click(screen.getByRole('button', { name: '다음' }));
    await user.click(screen.getByRole('button', { name: '다음' }));
  };

  it('+ 안내, 카테고리 안내, 생성 권유 순서로 보여준다', async (): Promise<void> => {
    const user = userEvent.setup();
    const { onStart, onClose } = renderGuide();

    expect(screen.getByText('1 / 3')).toBeInTheDocument();
    expect(screen.queryByText('먼저 구경할게요')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '다음' }));

    expect(screen.getByText('2 / 3')).toBeInTheDocument();
    expect(
      screen.getByRole('heading', {
        name: /좌우로 넘겨\s*더 많은 카테고리를 구경해보세요/,
      }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '네컷만화 만들기 체험 열기' }),
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '다음' }));

    expect(screen.getByText('3 / 3')).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: '지금 바로 생성해볼까요?' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '네컷만화 만들기 체험 열기' }),
    ).toBeInTheDocument();
    expect(onStart).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('마지막 단계에서 먼저 구경하기를 고르면 체험 없이 닫는다', async (): Promise<void> => {
    const user = userEvent.setup();
    const { onStart, onClose } = renderGuide();

    await goToLastStep(user);
    await user.click(screen.getByRole('button', { name: '먼저 구경할게요' }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onStart).not.toHaveBeenCalled();
  });

  it('마지막 단계에서 만들어보기를 고르면 체험을 시작한다', async (): Promise<void> => {
    const user = userEvent.setup();
    const { onStart, onClose } = renderGuide();

    await goToLastStep(user);
    await user.click(screen.getByRole('button', { name: '만들어볼게요' }));

    expect(onStart).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('1단계에서 강조된 +를 누르면 바로 체험을 시작한다', async (): Promise<void> => {
    const user = userEvent.setup();
    const { onStart } = renderGuide();

    await user.click(
      screen.getByRole('button', { name: '네컷만화 만들기 체험 열기' }),
    );

    expect(onStart).toHaveBeenCalledTimes(1);
  });
});
