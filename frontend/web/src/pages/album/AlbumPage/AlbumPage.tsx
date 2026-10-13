import { ALBUM_COPY } from '../copy';
import BottomNavigationLayout from '../../../shared/BottomNavigationLayout';
import DiaryItemList from '../DiaryItemList';
import useSelectedYearMonth from './useSelectedYearMonth';
import { css } from '@emotion/react';
import { theme } from '../../../styles/theme';
import { getToday, type Month } from '../../../shared/utils';
import { useDiaryGenerateContext } from '../../diary-generating/DiaryGenerateContext';
import { useEffect } from 'react';
import keyboardArrowDownIcon from '../../../assets/icons/keyboard_arrow_down.svg';
import useGenerationUsage from './useGenrationUsage';

const formatYearMonthToString = ({
  year,
  month,
}: {
  year: number;
  month: Month;
}): string => {
  return `${year}-${month.toString().padStart(2, '0')}`;
};

const AlbumPage = () => {
  const { selectedYearMonth, handleYearMonthChange } = useSelectedYearMonth(
    getToday().year,
    getToday().month,
  );

  return (
    <BottomNavigationLayout>
      <main css={albumPageContentStyle}>
        <div css={contentHeaderStyle}>
          <div css={monthPickerStyle}>
            <input
              css={monthInputStyle}
              type="month"
              aria-label="조회할 월"
              value={formatYearMonthToString(selectedYearMonth)}
              onChange={handleYearMonthChange}
            />
            <img
              css={monthPickerIconStyle}
              src={keyboardArrowDownIcon}
              alt=""
              aria-hidden="true"
            />
          </div>
          <div css={contentSummaryStyle}>
            <RemainingGenerationUsage />
          </div>
        </div>

        <section css={diaryContentStyle}>
          <DiaryItemList {...selectedYearMonth} />
        </section>
      </main>
    </BottomNavigationLayout>
  );
};

export default AlbumPage;

const RemainingGenerationUsage = () => {
  const { request, execute } = useGenerationUsage();

  const { request: diaryGenerateRequest } = useDiaryGenerateContext();

  useEffect(() => {
    if (diaryGenerateRequest.status === 'success') {
      void execute();
    }
  }, [diaryGenerateRequest.status, execute]);

  const remainingCount = request.status === 'success' ? request.data : null;
  const hasGenerationUsageError = request.status === 'error';

  return (
    <div css={remainingGenerationUsageStyle} aria-live="polite">
      {hasGenerationUsageError ? (
        <>
          <span>{ALBUM_COPY.usageError}</span>
          <button
            css={retryButtonStyle}
            type="button"
            onClick={() => void execute()}
          >
            {ALBUM_COPY.usageRetryAction}
          </button>
        </>
      ) : (
        <span>
          {ALBUM_COPY.remainingUsage.before}
          <strong css={generationUsageTextStyle(remainingCount)}>
            {remainingCount ?? ALBUM_COPY.usageLoadingCount}
          </strong>
          {ALBUM_COPY.remainingUsage.after}
        </span>
      )}
    </div>
  );
};

const albumPageContentStyle = css`
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0px;
  padding: 4px 20px 0 20px;
`;

const contentHeaderStyle = css`
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
  width: 100%;
`;

const contentSummaryStyle = css`
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  min-width: 0;
`;

const monthPickerStyle = css`
  position: relative;
  width: 135px;
  height: 28px;
`;

const monthInputStyle = css`
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  padding-right: 24px;
  border: none;
  outline: none;
  background-color: transparent;
  color: ${theme.colors.foreground.neutral};
  font-size: 18px;
  font-weight: 700;
  line-height: 26px;
  cursor: pointer;

  appearance: none;
  -webkit-appearance: none;

  &::-webkit-calendar-picker-indicator {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    margin: 0;
    opacity: 0;
    -webkit-appearance: none;
    cursor: pointer;
  }
`;

const monthPickerIconStyle = css`
  position: absolute;
  top: 50%;
  right: 0;
  width: 24px;
  height: 24px;
  transform: translateY(-50%);
  pointer-events: none;
`;

const diaryContentStyle = css`
  flex: 1;
  overflow-y: auto;
`;

const remainingGenerationUsageStyle = css`
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  color: ${theme.colors.foreground.neutral};
  font-size: 15px;
  font-weight: 500;
  line-height: 22px;
  word-break: keep-all;
`;

const generationUsageTextStyle = (remainingCount: number | null) => css`
  color: ${
    remainingCount === null
      ? theme.colors.foreground.neutralMuted
      : remainingCount > 0
        ? theme.colors.foreground.brand
        : theme.colors.foreground.critical
  };
  font-weight: 800;
`;

const retryButtonStyle = css`
  margin-left: 8px;
  padding: 0;
  border: none;
  background: none;
  color: ${theme.colors.foreground.brand};
  font: inherit;
  cursor: pointer;
`;
