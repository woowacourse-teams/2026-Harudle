import { css } from '@emotion/react';
import { useNavigate } from 'react-router';
import ActionButton from '../../shared/ActionButton';
import emptyPersonAndDog from '../../assets/images/empty-person-and-dog.png';
import { theme } from '../../styles/theme';

const DiaryEmptyState = () => {
  const navigate = useNavigate();

  return (
    <div css={emptyStateStyle}>
      <div css={emptyStateContentStyle}>
        <img
          src={emptyPersonAndDog}
          alt="사람과 강아지 일러스트"
          css={illustrationStyle}
        />

        <div css={emptyStateDescriptionContentStyle}>
          <div css={emptyStateTitleStyle}>아직 기록이 없어요</div>
          <div css={emptyStateDescriptionStyle}>
            오늘의 이야기를 네컷 만화로 남겨보세요!
          </div>
        </div>
      </div>

      <ActionButton
        onClick={() => {
          navigate('/diary-write');
        }}
        label="새 일기 쓰기"
      />
    </div>
  );
};

export default DiaryEmptyState;

const emptyStateStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  width: 100%;
`;

const emptyStateContentStyle = css`
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  align-items: center;
  width: 100%;
`;

const illustrationStyle = css`
  width: 300px;
  height: 230px;
  object-fit: cover;
`;

const emptyStateDescriptionContentStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  width: 100%;
  margin-bottom: 30px;
  text-align: center;
`;

const emptyStateTitleStyle = css`
  width: 100%;
  color: ${theme.colors.accent};
  font-size: 22px;
  font-weight: 700;
  line-height: 32px;
`;

const emptyStateDescriptionStyle = css`
  width: 100%;
  color: ${theme.colors.textSecondary};
  font-size: 15px;
  font-weight: 400;
`;
