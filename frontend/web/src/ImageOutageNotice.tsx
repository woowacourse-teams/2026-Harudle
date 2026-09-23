import { css } from '@emotion/react';
import { useEffect, useRef, useState } from 'react';
import ActionButton from './shared/ActionButton';
import { theme } from './styles/theme';

// 공지 내용을 갱신할 때 키도 변경하면 사용자에게 새 안내를 다시 표시합니다.
const NOTICE_KEY = 'harudle:image-outage:2026-09-23:v2';

const ImageOutageNotice = () => {
  const [showPrevious, setShowPrevious] = useState(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const bannerRef = useRef<HTMLButtonElement>(null);
  const titleRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    let acknowledged = false;
    try {
      const stored: unknown = localStorage.getItem(NOTICE_KEY);
      acknowledged = stored === 'acknowledged';
    } catch {
      // 저장소 접근이 제한돼도 공지는 표시합니다.
    }
    if (!acknowledged && dialog && !dialog.open) dialog.showModal();
    return () => dialog?.close();
  }, []);

  useEffect(() => {
    if (dialogRef.current?.open) {
      titleRef.current?.focus({ preventScroll: true });
      dialogRef.current.scrollTop = 0;
    }
  }, [showPrevious]);

  const acknowledge = () => {
    try {
      localStorage.setItem(NOTICE_KEY, 'acknowledged');
    } catch {
      // 확인 상태를 저장하지 못해도 모달을 닫을 수 있습니다.
    }
    dialogRef.current?.close();
    bannerRef.current?.focus();
  };

  return (
    <>
      <button
        ref={bannerRef}
        type="button"
        css={bannerStyle}
        aria-haspopup="dialog"
        onClick={() => {
          setShowPrevious(false);
          dialogRef.current?.showModal();
        }}
      >
        <span>일기 이미지 복구 및 지원 안내</span>
        <span css={bannerLinkStyle}>자세히 보기 ›</span>
      </button>
      <dialog
        ref={dialogRef}
        css={dialogStyle}
        aria-labelledby="image-outage-title"
        aria-describedby="image-outage-description"
        onCancel={(event) => {
          event.preventDefault();
          acknowledge();
        }}
      >
        <span css={badgeStyle}>
          {showPrevious
            ? '이전 공지 · 9월 23일'
            : '최신 공지 · 복구 및 지원 안내'}
        </span>
        <h2
          ref={titleRef}
          id="image-outage-title"
          css={titleStyle}
          tabIndex={-1}
          autoFocus
        >
          {showPrevious
            ? '일기 이미지 조회 장애 안내'
            : '일기 이미지 복구 및 지원 안내'}
        </h2>
        <div id="image-outage-description" css={descriptionStyle}>
          {showPrevious ? (
            <>
              <p>
                현재 일기 이미지가 표시되지 않는 문제가 발생하고 있습니다.
                원인과 복구 가능 여부를 확인하고 있습니다.
              </p>
              <p>소중한 기록을 이용하는 데 불편과 걱정을 드려 죄송합니다.</p>
              <p>
                다음 안내
                <br />
                한국시간 기준{' '}
                <strong css={emphasisStyle}>9월 23일(수) 23시</strong>
              </p>
            </>
          ) : (
            <>
              <p>
                먼저 소중한 그림일기를 복구할 수 없는 점에 대해 진심으로
                사과드립니다.
              </p>
              <p>
                작성해주신 일기 내용은 남아 있지만, 이미지 파일은 완전히
                삭제되어 원본을 되살릴 수 없는 상황입니다.
              </p>
              <p>
                <strong css={emphasisStyle}>9월 27일(일)</strong>(한국시간
                기준)까지 순차적으로 복구하겠습니다.
              </p>
              <p>
                이번 복구는 저장된 일기 내용을 바탕으로 저희가 그림을 새로
                생성하는 작업입니다. 이전 그림과는 달라질 수 있는 점 양해
                부탁드립니다.
              </p>
              <p>
                새 그림이 마음에 들지 않는 부분도 있을 수 있어, 원하시는
                이야기를 다시 그림으로 남기실 수 있도록 다음 주 일요일인{' '}
                <strong css={emphasisStyle}>10월 4일(일)</strong>까지 한국시간
                기준 <strong css={emphasisStyle}>매일 10회</strong>의 일기 생성
                기회를 제공하겠습니다.
              </p>
              <p>
                앞으로는 주기적인 백업을 통해 같은 일이 다시 발생하지 않도록
                하겠습니다. 소중한 기록을 믿고 맡기실 수 있도록 더 책임 있게
                운영하고 발전하는 하루들이 되겠습니다.
              </p>
            </>
          )}
        </div>
        <ActionButton label="확인했어요" onClick={acknowledge} />
        <button
          type="button"
          css={historyButtonStyle}
          onClick={() => setShowPrevious((previous) => !previous)}
        >
          {showPrevious ? '최신 공지로 돌아가기' : '이전 공지 보기'}
        </button>
      </dialog>
    </>
  );
};

export default ImageOutageNotice;

const bannerStyle = css`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  min-height: 44px;
  padding: 10px 16px;
  border: none;
  border-bottom: 1px solid ${theme.colors.stroke.brandWeak};
  background: ${theme.colors.background.brandWeak};
  color: ${theme.colors.foreground.brand};
  font: inherit;
  font-size: 13px;
  line-height: 20px;
  text-align: left;
  cursor: pointer;
`;

const bannerLinkStyle = css`
  flex-shrink: 0;
  text-decoration: underline;
`;

const dialogStyle = css`
  box-sizing: border-box;
  width: calc(100% - 40px);
  max-width: 390px;
  max-height: calc(100dvh - 40px);
  margin: auto;
  padding: 28px 24px 24px;
  overflow-y: auto;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 24px;
  background: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
  box-shadow: 0 16px 48px rgb(0 0 0 / 18%);

  &::backdrop {
    background: rgb(17 17 24 / 55%);
  }
`;

const badgeStyle = css`
  display: inline-block;
  margin-bottom: 14px;
  padding: 5px 10px;
  border-radius: 8px;
  background: ${theme.colors.background.brandWeak};
  color: ${theme.colors.foreground.brand};
  font-size: 12px;
  font-weight: 700;
  line-height: 18px;
`;

const titleStyle = css`
  outline: none;
  margin-bottom: 16px;
  font-size: 22px;
  font-weight: 700;
  line-height: 32px;
  word-break: keep-all;
`;

const descriptionStyle = css`
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-bottom: 24px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 15px;
  line-height: 24px;
  word-break: keep-all;
`;

const emphasisStyle = css`
  color: ${theme.colors.foreground.neutral};
  font-weight: 700;
`;

const historyButtonStyle = css`
  display: block;
  width: 100%;
  min-height: 44px;
  margin-top: 8px;
  padding: 10px;
  border: none;
  background: transparent;
  color: ${theme.colors.foreground.neutralMuted};
  font: inherit;
  font-size: 13px;
  line-height: 20px;
  text-decoration: underline;
  cursor: pointer;
`;
