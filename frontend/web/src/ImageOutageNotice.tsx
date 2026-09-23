import { css } from '@emotion/react';
import { useEffect, useRef } from 'react';
import ActionButton from './shared/ActionButton';
import { theme } from './styles/theme';

// 공지 내용을 갱신할 때 키도 변경하면 사용자에게 새 안내를 다시 표시합니다.
const NOTICE_KEY = 'harudle:image-outage:2026-09-23:v1';

const ImageOutageNotice = () => {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const bannerRef = useRef<HTMLButtonElement>(null);

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
        onClick={() => dialogRef.current?.showModal()}
      >
        <span>이미지 조회 장애 안내</span>
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
        <span css={badgeStyle}>서비스 이용 안내</span>
        <h2 id="image-outage-title" css={titleStyle}>
          일기 이미지 조회 장애 안내
        </h2>
        <div id="image-outage-description" css={descriptionStyle}>
          <p>
            현재 일기 이미지가 표시되지 않는 문제가 발생하고 있습니다. 원인과
            복구 가능 여부를 확인하고 있습니다.
          </p>
          <p>소중한 기록을 이용하는 데 불편과 걱정을 드려 죄송합니다.</p>
          <p css={updateStyle}>
            다음 안내
            <span css={dateStyle}>한국시간 기준 9월 23일(수) 23시</span>
          </p>
        </div>
        <ActionButton label="확인했어요" onClick={acknowledge} />
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

const updateStyle = css`
  padding: 14px 16px;
  border-radius: 12px;
  background: ${theme.colors.background.brandWeak};
  color: ${theme.colors.foreground.brand};
  font-weight: 700;
`;

const dateStyle = css`
  display: block;
  margin-top: 4px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  font-weight: 400;
  line-height: 18px;
`;
