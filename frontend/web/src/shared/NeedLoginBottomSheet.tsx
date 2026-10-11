import { css } from '@emotion/react';
import { useImperativeHandle, useRef, type Ref } from 'react';
import { useNavigate } from 'react-router';
import kakaoIcon from '../assets/icons/kakao.svg';
import BottomSheet, { type BottomSheetHandle } from './BottomSheet';
import { theme } from '../styles/theme';
import { NEED_LOGIN_COPY } from './needLoginCopy';

interface NeedLoginBottomSheetProps {
  readonly description: string;
  readonly ref: Ref<BottomSheetHandle>;
}

const NeedLoginBottomSheet = ({
  description,
  ref,
}: NeedLoginBottomSheetProps) => {
  const sheetRef = useRef<BottomSheetHandle>(null);
  const navigate = useNavigate();

  useImperativeHandle(
    ref,
    () => ({
      open: (): void => sheetRef.current?.open(),
      close: (): void => sheetRef.current?.close(),
    }),
    [],
  );

  const handleLogin = (): void => {
    sheetRef.current?.close();
    navigate('/login');
  };

  return (
    <BottomSheet title={NEED_LOGIN_COPY.title} ref={sheetRef}>
      <div css={contentStyle}>
        <p css={descriptionStyle}>{description}</p>
        <button type="button" css={loginButtonStyle} onClick={handleLogin}>
          <img src={kakaoIcon} alt="" width={24} height={24} />
          {NEED_LOGIN_COPY.loginAction}
        </button>
        <button
          type="button"
          css={browseButtonStyle}
          onClick={() => sheetRef.current?.close()}
        >
          {NEED_LOGIN_COPY.continueBrowsingAction}
        </button>
      </div>
    </BottomSheet>
  );
};

export default NeedLoginBottomSheet;

const contentStyle = css`
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px 0 calc(20px + env(safe-area-inset-bottom));
`;

const descriptionStyle = css`
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 15px;
  line-height: 22px;
  white-space: pre-line;
  word-break: keep-all;
`;

const loginButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  min-height: 56px;
  padding: 12px 20px;
  border: 0;
  border-radius: 16px;
  background: ${theme.colors.background.kakao};
  color: ${theme.colors.foreground.neutral};
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
  box-shadow: 0 4px 16px rgb(26 20 41 / 6%);
  cursor: pointer;
`;

const browseButtonStyle = css`
  min-height: 40px;
  padding: 8px;
  border: 0;
  background: transparent;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 15px;
  line-height: 24px;
  cursor: pointer;
`;
