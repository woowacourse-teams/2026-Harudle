import ActionButton from '../../shared/ActionButton';
import { ADMIN_LIST_COPY as COPY } from './copy';

interface AdminPagerProps {
  readonly page: number;
  readonly hasNext: boolean;
  readonly disabled?: boolean;
  readonly previousLabel?: string;
  readonly nextLabel?: string;
  readonly onPage: (page: number) => void;
  readonly className?: string;
}

/** 관리자 목록의 이전·다음 쪽 이동. 배치는 호출하는 화면이 `css`로 정한다. */
const AdminPager = ({
  page,
  hasNext,
  disabled = false,
  previousLabel = COPY.previousPage,
  nextLabel = COPY.nextPage,
  onPage,
  className,
}: AdminPagerProps) => (
  <div className={className}>
    <ActionButton
      label={previousLabel}
      variant="secondary"
      disabled={disabled || page === 0}
      onClick={(): void => onPage(page - 1)}
    />
    <span>{COPY.pageNumber(page)}</span>
    <ActionButton
      label={nextLabel}
      variant="secondary"
      disabled={disabled || !hasNext}
      onClick={(): void => onPage(page + 1)}
    />
  </div>
);

export default AdminPager;
