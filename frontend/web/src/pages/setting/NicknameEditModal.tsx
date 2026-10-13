import { css } from '@emotion/react';
import {
  useImperativeHandle,
  useRef,
  useState,
  type FormEvent,
  type Ref,
} from 'react';
import Modal, { type ModalHandle } from '../../shared/Modal';
import useNicknameUpdate from './useNicknameUpdate';
import { RequestError } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { theme } from '../../styles/theme';

interface NicknameEditModalProps {
  currentNickname: string;
  onSaved: (nickname: string) => void;
  ref: Ref<ModalHandle>;
}

const NicknameEditModal = ({
  currentNickname,
  onSaved,
  ref,
}: NicknameEditModalProps) => {
  const nicknameModalRef = useRef<ModalHandle>(null);
  const [nickname, setNickname] = useState('');
  const [nicknameError, setNicknameError] = useState('');
  const { request, execute, reset } = useNicknameUpdate();
  const isSaving = request.status === 'loading';

  useImperativeHandle(
    ref,
    () => ({
      open: () => {
        if (!isSaving) {
          setNickname(currentNickname);
          setNicknameError('');
          reset();
        }
        nicknameModalRef.current?.open();
      },
      close: () => {
        if (!isSaving) nicknameModalRef.current?.close();
      },
    }),
    [currentNickname, isSaving, reset],
  );

  const handleNicknameSubmit = async (
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> => {
    event.preventDefault();
    if (isSaving) return;

    if (!nickname.trim()) {
      setNicknameError('닉네임은 1자 이상 10자 이하로 입력해 주세요.');
      return;
    }

    setNicknameError('');
    const saved = await execute(nickname);
    if (!saved) return;

    onSaved(nickname);
    nicknameModalRef.current?.close();
  };

  let errorMessage = nicknameError;
  if (request.status === 'error') {
    const error = request.error;
    if (
      error instanceof RequestError &&
      error.problem.code === 'NICKNAME_ALREADY_IN_USE'
    ) {
      errorMessage = '이미 사용 중인 닉네임이에요.';
    } else if (
      error instanceof RequestError &&
      error.problem.code === 'VALIDATION_ERROR'
    ) {
      errorMessage = '닉네임은 1자 이상 10자 이하로 입력해 주세요.';
    } else {
      errorMessage = ERROR_MESSAGES.NICKNAME_UPDATE_FAILED;
    }
  }

  return (
    <Modal
      ref={nicknameModalRef}
      title="닉네임 수정"
      footer={
        <>
          <button
            type="button"
            css={cancelNicknameButtonStyle}
            disabled={isSaving}
            onClick={() => nicknameModalRef.current?.close()}
          >
            취소
          </button>
          <button
            type="submit"
            form="nickname-form"
            css={saveNicknameButtonStyle}
            disabled={isSaving}
          >
            {isSaving ? '저장 중...' : '저장'}
          </button>
        </>
      }
    >
      <form
        id="nickname-form"
        onSubmit={(event) => void handleNicknameSubmit(event)}
      >
        <label css={nicknameInputLabelStyle} htmlFor="nickname">
          닉네임
        </label>
        <input
          id="nickname"
          css={[nicknameInputStyle, errorMessage && nicknameInputErrorStyle]}
          value={nickname}
          disabled={isSaving}
          onChange={(event) => {
            setNickname(Array.from(event.target.value).slice(0, 10).join(''));
            setNicknameError('');
            reset();
          }}
        />
        <p css={nicknameHelpStyle}>{Array.from(nickname).length} / 10</p>
        {errorMessage && <p css={nicknameErrorStyle}>{errorMessage}</p>}
      </form>
    </Modal>
  );
};

export default NicknameEditModal;

const nicknameInputLabelStyle = css`
  display: block;
  margin: 12px 0 8px;
  color: ${theme.colors.foreground.neutral};
  font-weight: 700;
`;

const nicknameInputStyle = css`
  width: 100%;
  height: 44px;
  padding: 0 12px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 8px;
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
  font-size: 16px;
`;

const nicknameInputErrorStyle = css`
  border-color: ${theme.colors.stroke.critical};
`;

const nicknameHelpStyle = css`
  margin-top: 10px;
  font-size: 12px;
  line-height: 20px;
`;

const nicknameErrorStyle = css`
  ${nicknameHelpStyle};
  color: ${theme.colors.foreground.critical};
`;

const nicknameButtonStyle = css`
  flex: 1;
  min-width: 0;
  height: 44px;
  border: 0;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;

  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
`;

const cancelNicknameButtonStyle = css`
  ${nicknameButtonStyle};
  background-color: ${theme.colors.background.neutralSubtle};
  color: ${theme.colors.foreground.neutral};
`;

const saveNicknameButtonStyle = css`
  ${nicknameButtonStyle};
  background-color: ${theme.colors.background.brandSolid};
  color: ${theme.colors.foreground.onBrand};
`;
