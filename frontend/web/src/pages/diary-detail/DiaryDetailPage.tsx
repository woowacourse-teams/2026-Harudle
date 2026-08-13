import ActionButton from '../../shared/ActionButton';
import PageHeader from '../../shared/PageHeader';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { API_BASE_URL, type ApiRequest } from '../../shared/api';
import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import backIcon from '../../assets/icons/back.svg';
import hamburgerIcon from '../../assets/icons/more.svg';
import shareIcon from '../../assets/icons/share.svg';
import downloadIcon from '../../assets/icons/download.svg';
import deleteIcon from '../../assets/icons/delete.svg';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import { authFetch } from '../../shared/auth';

interface DiaryDetail {
  id: string;
  diaryDate: string;
  sourceText: string;
  createdAt: string;
  diary: {
    id: string;
    status: 'SUCCEEDED';
    title: string;
    imageUrl: string;
    imageUrlExpiresAt: string;
    completedAt: string;
  };
}

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

const isDiaryDetail = (value: unknown): value is DiaryDetail => {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    typeof value.diaryDate === 'string' &&
    typeof value.sourceText === 'string' &&
    typeof value.createdAt === 'string' &&
    isRecord(value.diary) &&
    typeof value.diary.id === 'string' &&
    value.diary.status === 'SUCCEEDED' &&
    typeof value.diary.title === 'string' &&
    typeof value.diary.imageUrl === 'string' &&
    typeof value.diary.imageUrlExpiresAt === 'string' &&
    typeof value.diary.completedAt === 'string'
  );
};

const DiaryDetailPage = () => {
  const navigate = useNavigate();
  const { diaryId } = useParams();
  const [diaryDetail, setDiaryDetail] = useState<ApiRequest<DiaryDetail>>({
    status: 'idle',
  });
  const [isDeleteMenuOpen, setIsDeleteMenuOpen] = useState(false);
  const [isDeleteModalOpen, setIsDeleteModalOpen] = useState(false);
  const [isSharing, setIsSharing] = useState(false);
  const [diaryDeleteRequest, setDiaryDeleteRequest] = useState<
    ApiRequest<void>
  >({ status: 'idle' });

  useEffect(() => {
    const getDiaryDetail = async (): Promise<void> => {
      setDiaryDetail({ status: 'loading' });

      try {
        const response = await authFetch(`${API_BASE_URL}/diaries/${diaryId}`);

        if (!response.ok) {
          throw new Error('일기를 불러오지 못했습니다.');
        }

        const data: unknown = await response.json();

        if (!isDiaryDetail(data)) {
          throw new Error('일기 상세 응답 형식이 일치하지 않습니다.');
        }

        setDiaryDetail({ status: 'success', data });
      } catch (error: unknown) {
        setDiaryDetail({
          status: 'error',
          error:
            error instanceof Error
              ? error
              : new Error('알 수 없는 에러가 발생했습니다.'),
        });
      }
    };

    void getDiaryDetail();
  }, [diaryId]);

  const handleDiaryDelete = async (): Promise<void> => {
    setDiaryDeleteRequest({ status: 'loading' });

    try {
      const response = await authFetch(`${API_BASE_URL}/diaries/${diaryId}`, {
        method: 'DELETE',
      });

      if (!response.ok) {
        throw new Error('일기를 삭제하지 못했습니다.');
      }

      setDiaryDeleteRequest({ status: 'success', data: undefined });
      navigate('/', { replace: true });
    } catch (error: unknown) {
      const deleteError =
        error instanceof Error
          ? error
          : new Error('알 수 없는 에러가 발생했습니다.');

      setDiaryDeleteRequest({ status: 'error', error: deleteError });
      alert(deleteError.message);
    }
  };

  if (diaryDetail.status === 'idle' || diaryDetail.status === 'loading') {
    return (
      <div css={diaryDetailPageStyle}>
        <PageHeader
          leftButton={
            <button css={backButtonStyle} onClick={() => navigate(-1)}>
              <img src={backIcon} alt="뒤로가기" css={backIconStyle} />
            </button>
          }
          title={null}
          rightButton={null}
        />
        <div css={feedbackStyle}>
          <img src={loadingAnimation} alt="로딩 중" css={loadingImageStyle} />
        </div>
      </div>
    );
  }

  if (diaryDetail.status === 'error') {
    return (
      <div css={diaryDetailPageStyle}>
        <PageHeader
          leftButton={
            <button css={backButtonStyle} onClick={() => navigate(-1)}>
              <img src={backIcon} alt="뒤로가기" css={backIconStyle} />
            </button>
          }
          title={null}
          rightButton={null}
        />
        <div css={feedbackStyle}>
          <div>{diaryDetail.error.message}</div>
        </div>
      </div>
    );
  }

  const handleDiaryShare = async () => {
    if (isSharing) {
      return;
    }

    setIsSharing(true);

    try {
      const response = await authFetch(
        `${API_BASE_URL}/diaries/${diaryId}/share-link`,
        {
          method: 'PUT',
        },
      );

      if (!response.ok) {
        throw new Error('공유 링크를 만들지 못했습니다.');
      }

      const data: unknown = await response.json();

      if (!isRecord(data) || typeof data.shareUrl !== 'string') {
        throw new Error('공유 링크 응답 형식이 일치하지 않습니다.');
      }

      if (navigator.share) {
        await navigator.share({
          title: diaryDetail.data.diary.title,
          url: data.shareUrl,
        });
        return;
      }

      await navigator.clipboard.writeText(data.shareUrl);
      alert('공유 링크를 복사했습니다.');
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        return;
      }

      alert(error instanceof Error ? error.message : '공유에 실패했습니다.');
    } finally {
      setIsSharing(false);
    }
  };

  return (
    <div css={diaryDetailPageStyle}>
      <PageHeader
        leftButton={
          <button
            type="button"
            aria-label="뒤로 가기"
            css={backButtonStyle}
            onClick={() => navigate(-1)}
          >
            <img src={backIcon} alt="" css={backIconStyle} />
          </button>
        }
        title={diaryDetail.data.diaryDate}
        rightButton={
          <button
            type="button"
            aria-label="더보기"
            css={hamburgerButtonStyle}
            onClick={() => setIsDeleteMenuOpen((isOpen) => !isOpen)}
          >
            <img src={hamburgerIcon} alt="" css={hamburgerIconStyle} />
          </button>
        }
      />

      {isDeleteMenuOpen && (
        <button
          type="button"
          css={deleteMenuButtonStyle}
          onClick={() => {
            setIsDeleteMenuOpen(false);
            setIsDeleteModalOpen(true);
          }}
        >
          <img src={deleteIcon} alt="" css={deleteIconStyle} />
          삭제하기
        </button>
      )}

      <div css={diaryTitleStyle}>{diaryDetail.data.diary.title}</div>
      <img
        css={diaryImageStyle}
        src={diaryDetail.data.diary.imageUrl}
        alt={diaryDetail.data.diary.title}
      />
      <div css={storyTitleStyle}>오늘의 이야기</div>
      <div css={storyTextStyle}>{diaryDetail.data.sourceText}</div>
      <ActionButton
        icon={<img src={shareIcon} alt="공유하기 아이콘" />}
        label="공유하기"
        onClick={handleDiaryShare}
        disabled={isSharing}
      />
      <ActionButton
        icon={<img src={downloadIcon} alt="저장 아이콘" />}
        label="이미지 저장"
        variant="secondary"
        onClick={() => {
          window.open(diaryDetail.data.diary.imageUrl, '_blank');
        }}
      />

      {isDeleteModalOpen && (
        <>
          <div css={modalBackdropStyle} />
          <div css={deleteModalStyle}>
            <div css={deleteModalTitleStyle}>일기 관리</div>
            <div css={deleteModalDescriptionStyle}>
              삭제한 일기는 복구할 수 없어요.
            </div>
            <button
              type="button"
              css={deleteConfirmButtonStyle}
              disabled={diaryDeleteRequest.status === 'loading'}
              onClick={() => void handleDiaryDelete()}
            >
              삭제하기
            </button>
            <button
              type="button"
              css={deleteCancelButtonStyle}
              onClick={() => setIsDeleteModalOpen(false)}
            >
              취소
            </button>
          </div>
        </>
      )}
    </div>
  );
};

export default DiaryDetailPage;

const contentWidthStyle = css`
  width: calc(100% - 16px);
  max-width: 374px;
`;

const diaryDetailPageStyle = css`
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  width: 100%;
  height: 100%;
  padding: 12px 20px 10px;
  overflow-y: auto;
  background-color: ${theme.colors.background};
  box-sizing: border-box;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
`;

const backButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  background-color: transparent;
  cursor: pointer;
`;

const backIconStyle = css`
  width: 24px;
  height: 24px;
`;

const diaryTitleStyle = css`
  ${contentWidthStyle};
  flex-shrink: 0;
  color: ${theme.colors.textPrimary};
  font-size: 26px;
  font-weight: 700;
  line-height: 36px;
  text-align: center;
  overflow-wrap: break-word;
`;

const hamburgerButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  background-color: transparent;
  cursor: pointer;
`;

const hamburgerIconStyle = css`
  width: 24px;
  height: 24px;
`;

const deleteMenuButtonStyle = css`
  position: absolute;
  top: 68px;
  right: 20px;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 115px;
  height: 56px;
  padding: 0 8px;
  border: none;
  border-radius: 12px;
  background-color: ${theme.colors.background};
  box-shadow: 0 8px 24px rgba(51, 36, 89, 0.16);
  color: ${theme.colors.danger};
  font-size: 16px;
  font-weight: 500;
  line-height: 24px;
  cursor: pointer;
`;

const deleteIconStyle = css`
  width: 24px;
  height: 24px;
`;

const diaryImageStyle = css`
  ${contentWidthStyle};
  flex-shrink: 0;
  aspect-ratio: 1;
  padding: 2px;
  border: 0;
  border-radius: 16px;
  outline: 1px solid ${theme.colors.border};
  outline-offset: -1px;
  background-color: ${theme.colors.background};
  object-fit: cover;
  box-sizing: border-box;
`;

const storyTitleStyle = css`
  ${contentWidthStyle};
  flex-shrink: 0;
  color: ${theme.colors.textPrimary};
  font-size: 18px;
  font-weight: 700;
  line-height: 28px;
`;

const storyTextStyle = css`
  ${contentWidthStyle};
  flex-shrink: 0;
  margin-top: -2px;
  color: ${theme.colors.textPrimary};
  font-size: 16px;
  font-weight: 400;
  line-height: 26px;
  white-space: pre-wrap;
  overflow-wrap: break-word;
`;

const feedbackStyle = css`
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  width: 100%;
  color: ${theme.colors.textPrimary};
  font-size: 16px;
  line-height: 26px;
`;

const loadingImageStyle = css`
  width: 160px;
  height: 160px;
`;

const modalBackdropStyle = css`
  position: fixed;
  inset: 0;
  z-index: 20;
  width: 100%;
  max-width: 430px;
  margin: 0 auto;
  background-color: rgba(17, 17, 24, 0.32);
`;

const deleteModalStyle = css`
  position: fixed;
  top: 50%;
  left: 50%;
  z-index: 30;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  width: calc(100% - 40px);
  max-width: 390px;
  height: 256px;
  padding: 31px 20px 20px;
  border-radius: 24px;
  background-color: ${theme.colors.background};
  box-sizing: border-box;
  transform: translate(-50%, -50%);
`;

const deleteModalTitleStyle = css`
  color: ${theme.colors.textPrimary};
  font-size: 18px;
  font-weight: 700;
  line-height: 28px;
`;

const deleteModalDescriptionStyle = css`
  color: ${theme.colors.textSecondary};
  font-size: 14px;
  font-weight: 400;
  line-height: 22px;
`;

const deleteConfirmButtonStyle = css`
  width: 100%;
  height: 52px;
  padding: 14px 16px;
  border: none;
  border-radius: 12px;
  background-color: #fafafb;
  color: ${theme.colors.danger};
  font-size: 16px;
  font-weight: 500;
  line-height: 24px;
  cursor: pointer;
`;

const deleteCancelButtonStyle = css`
  width: 100%;
  height: 56px;
  padding: 16px 20px;
  border: 1px solid ${theme.colors.border};
  border-radius: 9999px;
  background-color: ${theme.colors.background};
  color: ${theme.colors.textPrimary};
  font-size: 16px;
  font-weight: 500;
  line-height: 24px;
  cursor: pointer;
`;
