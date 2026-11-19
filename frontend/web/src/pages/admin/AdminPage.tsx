import { css } from '@emotion/react';
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
} from 'react';
import type { FormEvent, ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import logo from '../../assets/images/harudle-logo.webp';
import Modal, { type ModalHandle } from '../../shared/Modal';
import { theme } from '../../styles/theme';
import adminCharacterGenerationHistory from './assets/admin-character-generation-history.png';
import adminCharacterUserDetail from './assets/admin-character-user-detail.png';
import adminCharacterUserSearch from './assets/admin-character-user-search.png';
import adminDefaultDog from './assets/admin-character-default-admin-dog.png';
import adminHeroPersonAndDog from './assets/admin-hero-person-and-dog.png';
import operationAlertIcon from './assets/admin-operation-alert.png';
import navDashboardIcon from './assets/admin-nav-dashboard.png';
import navFailuresIcon from './assets/admin-nav-failures.png';
import navGenerationsIcon from './assets/admin-nav-generations.png';
import navUsersIcon from './assets/admin-nav-users.png';
import searchIcon from './assets/admin-search-icon.png';
import {
  ADMIN_USER_SORTS,
  SORT_DIRECTIONS,
  getAdminStats,
  getAdminUser,
  resetAdminUsage,
  restoreAdminUsage,
  searchAdminGenerations,
  searchAdminUsers,
  setAdminGenerationLimit,
  type AdminGenerationUsage,
  type AdminStats,
  type AdminUserDetail,
  type AdminUserSort,
  type AdminUserSummary,
  type GenerationHistory,
  type Page,
  type SortDirection,
} from './adminApi';
import AdminReportsPanel from './AdminReportsPanel';
import AdminModerationPanel from './AdminModerationPanel';
import AdminPager from './AdminPager';
import AdminPostcardModal, {
  type AdminPostcardModalHandle,
} from './postcard/AdminPostcardModal';
import {
  ADMIN_LIST_COPY,
  ADMIN_POSTCARD_COPY,
  ADMIN_STAT_COPY,
  ADMIN_USER_SORT_LABELS,
  SORT_DIRECTION_LABELS,
  memberCountValue,
} from './copy';
import {
  readAdminUrlState,
  today,
  writeAdminUrlState,
  type AdminUrlState,
  type AdminView,
} from './adminUrlState';
import { shownPage, usePageRequest, type PageRequest } from './usePageRequest';
import type { GenerationStatus } from '../../domain/diary/diaryGeneration';

type StatsRequest =
  | { readonly status: 'loading' }
  | { readonly status: 'success'; readonly stats: AdminStats }
  | { readonly status: 'error' };
type UserStatusFilter = 'ALL' | 'ACTIVE' | 'DELETED';
type UsageModalMode = 'restore' | 'reset' | 'limit';

const LIST_PAGE_SIZE = 20;

// 정렬 기준을 바꾸면 사전순은 가→하, 가입일은 최신순, 남은 횟수는 많이 남은 순부터 보여 준다.
const SORT_DEFAULT_DIRECTIONS = {
  NAME: 'ASC',
  CREATED_AT: 'DESC',
  REMAINING_COUNT: 'DESC',
} as const satisfies Record<AdminUserSort, SortDirection>;
// 이 너비 이하에서는 상세 열을 숨기므로 사용자 상세를 모달로 연다.
const COMPACT_LAYOUT_QUERY = '(max-width: 1320px)';

const shiftDate = (date: string, days: number): string => {
  const [year, month, day] = date.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, day + days))
    .toISOString()
    .slice(0, 10);
};

const subscribeCompactLayout = (onChange: () => void): (() => void) => {
  const media = window.matchMedia?.(COMPACT_LAYOUT_QUERY);
  media?.addEventListener('change', onChange);
  return (): void => media?.removeEventListener('change', onChange);
};

const isCompactLayout = (): boolean =>
  window.matchMedia?.(COMPACT_LAYOUT_QUERY).matches ?? false;

const AdminPage = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const urlState = readAdminUrlState(searchParams);
  // 다시 그려지기 전에 이어진 변경이 앞선 변경을 덮어쓰지 않도록 마지막으로 쓴 주소를 기준으로 합친다.
  const latestSearchParamsRef = useRef(searchParams);
  useEffect((): void => {
    latestSearchParamsRef.current = searchParams;
  }, [searchParams]);
  const {
    view,
    userQuery,
    userSort,
    userDirection,
    usersPage,
    historyDate,
    historyPage,
  } = urlState;
  const isHistoryView = view === 'generations' || view === 'failed';
  const compactLayout = useSyncExternalStore(
    subscribeCompactLayout,
    isCompactLayout,
  );
  const [detail, setDetail] = useState<AdminUserDetail | null>(null);
  const [statusFilter, setStatusFilter] = useState<UserStatusFilter>('ALL');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [usageModal, setUsageModal] = useState<UsageModalMode | null>(null);
  const [usageModalValue, setUsageModalValue] = useState('');
  const [usageModalError, setUsageModalError] = useState<string | null>(null);
  const requestSequenceRef = useRef(0);
  const detailRequestIdRef = useRef(0);
  const postcardModalRef = useRef<AdminPostcardModalHandle>(null);
  const detailModalRef = useRef<ModalHandle>(null);

  // 메뉴로 화면을 옮길 때마다 다시 조회하도록 view를 조회 조건에 포함한다.
  const fetchUsers = useMemo(
    () =>
      view === 'dashboard' || view === 'users'
        ? () =>
            searchAdminUsers({
              query: userQuery,
              sort: userSort,
              direction: userDirection,
              page: usersPage,
              size: LIST_PAGE_SIZE,
            })
        : null,
    [view, userQuery, userSort, userDirection, usersPage],
  );
  const [usersRequest, reloadUsers] = usePageRequest(
    fetchUsers,
    '사용자를 불러오지 못했습니다.',
  );

  const fetchGenerations = useMemo(
    () =>
      view === 'dashboard' || view === 'generations' || view === 'failed'
        ? () =>
            searchAdminGenerations({
              page: historyPage,
              size: LIST_PAGE_SIZE,
              status: view === 'failed' ? 'FAILED' : undefined,
              from: historyDate,
              to: historyDate,
            })
        : null,
    [view, historyPage, historyDate],
  );
  const [generationsRequest, reloadGenerations] = usePageRequest(
    fetchGenerations,
    '생성 이력을 불러오지 못했습니다.',
  );

  const fetchFailedGenerations = useMemo(
    () =>
      view === 'dashboard'
        ? () =>
            searchAdminGenerations({
              page: 0,
              size: 1,
              status: 'FAILED',
              from: historyDate,
              to: historyDate,
            })
        : null,
    [view, historyDate],
  );
  const [failedGenerationsRequest] = usePageRequest(
    fetchFailedGenerations,
    '관리자 데이터를 불러오지 못했습니다.',
  );
  const failedGenerationCount =
    failedGenerationsRequest.status === 'success'
      ? failedGenerationsRequest.data.totalElements
      : null;

  const [statsRequest, setStatsRequest] = useState<StatsRequest>({
    status: 'loading',
  });
  useEffect((): (() => void) | void => {
    if (view !== 'dashboard') return;
    let ignore = false;
    getAdminStats().then(
      (stats): void => {
        if (!ignore) setStatsRequest({ status: 'success', stats });
      },
      (): void => {
        if (!ignore) setStatsRequest({ status: 'error' });
      },
    );
    return (): void => {
      ignore = true;
    };
  }, [view]);

  // 메뉴와 뒤로 가기 중 어느 쪽으로 화면을 옮겨도 이전 화면의 상세와 진행 중인 상세 조회를 버린다.
  useLayoutEffect((): void => {
    detailRequestIdRef.current = ++requestSequenceRef.current;
    detailModalRef.current?.close();
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 주소의 화면이 바뀐 시점에 진행 중인 상세 조회·모달과 함께 상세 상태를 비운다.
    setDetail(null);
    setUsageModal(null);
    setLoading(false);
    setError(null);
  }, [view]);

  // 넓은 화면으로 바뀌면 상세가 오른쪽 열로 옮겨 가므로 비어 버린 모달을 닫는다.
  useEffect((): (() => void) | void => {
    if (!compactLayout) return;
    const modal = detailModalRef.current;
    return (): void => modal?.close();
  }, [compactLayout]);

  /** 주소가 바뀌었으면 `true`를 돌려준다. */
  const updateUrl = (
    patch:
      | Partial<AdminUrlState>
      | ((current: AdminUrlState) => Partial<AdminUrlState>),
  ): boolean => {
    const current = latestSearchParamsRef.current;
    const currentState = readAdminUrlState(current);
    const next = writeAdminUrlState({
      ...currentState,
      ...(typeof patch === 'function' ? patch(currentState) : patch),
    });
    if (next.toString() === current.toString()) return false;
    latestSearchParamsRef.current = next;
    setSearchParams(next);
    return true;
  };

  // 조건이 그대로인 검색·쪽 이동은 주소가 바뀌지 않으므로 같은 조건으로 다시 불러온다.
  const showUsers = (patch: Partial<AdminUrlState>): void => {
    if (!updateUrl(patch)) reloadUsers();
  };
  const showGenerations = (patch: Partial<AdminUrlState>): void => {
    if (!updateUrl(patch)) reloadGenerations();
  };

  // 검색어·정렬·조회 날짜는 메뉴를 옮겨도 유지하고 쪽 번호만 처음으로 돌린다.
  const navigate = (nextView: AdminView) => {
    updateUrl({ view: nextView, usersPage: 0, historyPage: 0 });
  };

  const selectUser = async (userId: string) => {
    const requestId = ++requestSequenceRef.current;
    detailRequestIdRef.current = requestId;
    setDetail(null);
    setUsageModal(null);
    setLoading(true);
    setError(null);
    if (compactLayout) detailModalRef.current?.open();
    try {
      const result = await getAdminUser(userId);
      if (detailRequestIdRef.current === requestId) setDetail(result);
    } catch (cause) {
      if (requestSequenceRef.current !== requestId) return;
      setError(
        cause instanceof Error
          ? cause.message
          : '사용자 정보를 불러오지 못했습니다.',
      );
    } finally {
      if (requestSequenceRef.current === requestId) setLoading(false);
    }
  };

  const loadedUsers = shownPage(usersRequest);
  const visibleUsers = useMemo(() => {
    const users = loadedUsers?.content ?? [];
    return statusFilter === 'ALL'
      ? users
      : users.filter((user) => user.status === statusFilter);
  }, [statusFilter, loadedUsers]);

  const updateDetail = async (
    action: () => Promise<AdminGenerationUsage>,
  ): Promise<boolean> => {
    if (!detail) return false;
    const requestId = ++requestSequenceRef.current;
    detailRequestIdRef.current = requestId;
    setLoading(true);
    setError(null);
    try {
      await action();
      // 바뀐 사용량이 정렬과 쪽 구성에 반영되도록 목록은 서버에서 다시 받는다.
      reloadUsers();
      const updated = await getAdminUser(detail.id);
      if (detailRequestIdRef.current !== requestId) return false;
      setDetail(updated);
      return true;
    } catch (cause) {
      if (requestSequenceRef.current !== requestId) return false;
      const message =
        cause instanceof Error
          ? cause.message
          : '생성 사용량을 변경하지 못했습니다.';
      setError(message);
      setUsageModalError(message);
      return false;
    } finally {
      if (requestSequenceRef.current === requestId) setLoading(false);
    }
  };

  const updateGenerationLimit = async (
    action: () => Promise<void>,
  ): Promise<boolean> => {
    if (!detail) return false;
    const requestId = ++requestSequenceRef.current;
    detailRequestIdRef.current = requestId;
    setLoading(true);
    setError(null);
    try {
      await action();
      // 바뀐 사용량이 정렬과 쪽 구성에 반영되도록 목록은 서버에서 다시 받는다.
      reloadUsers();
      const updated = await getAdminUser(detail.id);
      if (detailRequestIdRef.current !== requestId) return false;
      setDetail(updated);
      return true;
    } catch (cause) {
      if (requestSequenceRef.current !== requestId) return false;
      const message =
        cause instanceof Error
          ? cause.message
          : '생성 한도를 변경하지 못했습니다.';
      setError(message);
      setUsageModalError(message);
      return false;
    } finally {
      if (requestSequenceRef.current === requestId) setLoading(false);
    }
  };

  const openUsageModal = (mode: UsageModalMode) => {
    if (!detail || detail.status !== 'ACTIVE') return;
    setError(null);
    setUsageModalError(null);
    setUsageModalValue(
      mode === 'restore'
        ? '1'
        : mode === 'limit'
          ? String(detail.generationUsage.limitCount)
          : '',
    );
    setUsageModal(mode);
  };

  const closeUsageModal = useCallback(() => {
    if (loading) return;
    setUsageModal(null);
    setUsageModalError(null);
  }, [loading]);

  const handleUsageModalSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!detail || !usageModal) return;

    setUsageModalError(null);
    if (usageModal === 'reset') {
      if (await updateDetail(() => resetAdminUsage(detail.id))) {
        closeUsageModal();
      }
      return;
    }

    const value = Number(usageModalValue);
    if (!Number.isInteger(value) || value < 1) {
      setUsageModalError('1 이상의 정수를 입력해주세요.');
      return;
    }

    if (usageModal === 'restore' && value > detail.generationUsage.usedCount) {
      setUsageModalError(
        `현재 사용 횟수(${detail.generationUsage.usedCount}회) 이하로 입력해주세요.`,
      );
      return;
    }

    const succeeded =
      usageModal === 'restore'
        ? await updateDetail(() => restoreAdminUsage(detail.id, value))
        : await updateGenerationLimit(() =>
            setAdminGenerationLimit(detail.id, value),
          );
    if (succeeded) closeUsageModal();
  };

  useEffect(() => {
    if (!usageModal || loading) return;
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeUsageModal();
    };
    window.addEventListener('keydown', handleEscape);
    return () => window.removeEventListener('keydown', handleEscape);
  }, [closeUsageModal, loading, usageModal]);

  const handleRestore = () => {
    openUsageModal('restore');
  };

  const handleReset = () => {
    openUsageModal('reset');
  };

  const handleSetGenerationLimit = () => {
    openUsageModal('limit');
  };

  // 사용량 변경 모달은 상세와 함께 두어야 좁은 화면의 상세 모달 위에서도 조작할 수 있다.
  const detailContent: ReactNode = detail ? (
    <>
      <UserDetailBody
        detail={detail}
        loading={loading}
        error={error}
        onRestore={handleRestore}
        onReset={handleReset}
        onSetGenerationLimit={handleSetGenerationLimit}
      />
      {usageModal && (
        <UsageManagementModal
          mode={usageModal}
          usage={detail.generationUsage}
          value={usageModalValue}
          error={usageModalError}
          loading={loading}
          onValueChange={(value) => {
            setUsageModalValue(value);
            setUsageModalError(null);
          }}
          onClose={closeUsageModal}
          onSubmit={handleUsageModalSubmit}
        />
      )}
    </>
  ) : error ? (
    <ErrorMessage message={error} />
  ) : loading ? (
    <LoadingMessage />
  ) : null;

  return (
    <div css={pageStyle(isHistoryView || view === 'reports')}>
      <aside className="sidebar" css={sidebarStyle}>
        <img src={logo} alt="Harudle" css={logoStyle} />
        <nav className="menu" css={menuStyle} aria-label="관리자 메뉴">
          <MenuButton
            active={view === 'dashboard'}
            icon={navDashboardIcon}
            label="대시보드"
            onClick={() => navigate('dashboard')}
          />
          <MenuButton
            active={view === 'users'}
            icon={navUsersIcon}
            label="사용자 관리"
            onClick={() => navigate('users')}
          />
          <MenuButton
            active={view === 'generations'}
            icon={navGenerationsIcon}
            label="생성 이력"
            onClick={() => navigate('generations')}
          />
          <MenuButton
            active={view === 'failed'}
            icon={navFailuresIcon}
            label="실패 이력"
            onClick={() => navigate('failed')}
          />
          <MenuButton
            active={view === 'reports'}
            icon={navFailuresIcon}
            label="신고 관리"
            onClick={() => navigate('reports')}
          />
        </nav>
        <div css={adminProfileStyle}>
          <div css={adminAvatarStyle}>
            <img src={adminDefaultDog} alt="" />
          </div>
          <div>
            <strong>관리자</strong>
            <small>@harudle-admin</small>
          </div>
        </div>
      </aside>

      <main css={mainStyle}>
        {isHistoryView && (
          <div css={historyPosthogStyle}>
            <PostHogLink />
          </div>
        )}
        {view === 'dashboard' ? (
          <DashboardHeader />
        ) : (
          <PageTitle
            title={
              view === 'reports'
                ? '신고 관리'
                : view === 'users'
                  ? '사용자 관리'
                  : view === 'failed'
                    ? '실패 이력'
                    : '생성 이력'
            }
            onBack={() => navigate('dashboard')}
          />
        )}
        {view === 'reports' && <AdminReportsPanel />}
        {view === 'dashboard' && (
          <div css={dashboardStatsStyle}>
            <OperationCard
              failedGenerationCount={failedGenerationCount}
              onClick={() => navigate('failed')}
            />
            <MemberStatCard stat="memberCount" request={statsRequest} />
            <MemberStatCard stat="todaySignupCount" request={statsRequest} />
          </div>
        )}
        {(view === 'dashboard' || view === 'users') && (
          <UserSearchCard
            request={usersRequest}
            users={visibleUsers}
            query={userQuery}
            sort={userSort}
            direction={userDirection}
            statusFilter={statusFilter}
            onStatusChange={setStatusFilter}
            onSearch={(query) => showUsers({ userQuery: query, usersPage: 0 })}
            onSortChange={(sort) =>
              updateUrl({
                userSort: sort,
                userDirection: SORT_DEFAULT_DIRECTIONS[sort],
                usersPage: 0,
              })
            }
            onDirectionChange={(direction) =>
              updateUrl({ userDirection: direction, usersPage: 0 })
            }
            onSelect={selectUser}
            onPage={(page) => showUsers({ usersPage: page })}
            onRetry={reloadUsers}
          />
        )}
        {(view === 'dashboard' ||
          view === 'generations' ||
          view === 'failed') && (
          <HistoryCard
            request={generationsRequest}
            failedOnly={view === 'failed'}
            date={historyDate}
            onDateChange={(date) =>
              updateUrl({ historyDate: date, historyPage: 0 })
            }
            onDateShift={(days) =>
              updateUrl((current) => {
                if (current.historyDate === '') return {};
                const shifted = shiftDate(current.historyDate, days);
                return {
                  historyDate: shifted > today() ? today() : shifted,
                  historyPage: 0,
                };
              })
            }
            onPage={(page) => showGenerations({ historyPage: page })}
            onRetry={reloadGenerations}
            onFailedOnly={() => navigate('failed')}
            onAll={() => navigate('generations')}
            onPostcard={(generationId) =>
              postcardModalRef.current?.open(generationId)
            }
          />
        )}
      </main>
      <AdminPostcardModal ref={postcardModalRef} />

      {!isHistoryView && view !== 'reports' && (
        <DetailPanel>{compactLayout ? null : detailContent}</DetailPanel>
      )}
      <Modal
        ref={detailModalRef}
        css={detailModalStyle}
        title={ADMIN_LIST_COPY.userDetail}
        dismissible={usageModal === null}
      >
        {compactLayout ? detailContent : null}
      </Modal>
    </div>
  );
};

const MenuButton = ({
  active,
  icon,
  label,
  onClick,
}: {
  active: boolean;
  icon: string;
  label: string;
  onClick: () => void;
}) => (
  <button css={menuButtonStyle(active)} onClick={onClick} type="button">
    <img src={icon} alt="" />
    <span>{label}</span>
  </button>
);

const DashboardHeader = () => (
  <header css={headlineStyle}>
    <h1>오늘의 하루를 살펴볼까요?</h1>
    <p>
      {new Date().toLocaleDateString('ko-KR', {
        year: 'numeric',
        month: 'long',
        day: 'numeric',
        weekday: 'short',
      })}
    </p>
    <img
      src={adminHeroPersonAndDog}
      alt="하루들과 반려견"
      css={heroCharacterStyle}
    />
  </header>
);

const PageTitle = ({
  title,
  onBack,
}: {
  title: string;
  onBack: () => void;
}) => (
  <header css={pageTitleStyle}>
    <button type="button" onClick={onBack} aria-label="대시보드로 돌아가기">
      ←
    </button>
    <h1>{title}</h1>
  </header>
);

const OperationCard = ({
  failedGenerationCount,
  onClick,
}: {
  failedGenerationCount: number | null;
  onClick: () => void;
}) => (
  <section css={operationCardStyle}>
    <img src={operationAlertIcon} alt="" />
    <div>
      <h2>운영 확인 필요</h2>
      <button type="button" onClick={onClick}>
        <span>
          실패한 생성{' '}
          {failedGenerationCount === null ? '-' : failedGenerationCount}건
        </span>
        <strong>확인하기 ›</strong>
      </button>
    </div>
  </section>
);

const MemberStatCard = ({
  stat,
  request,
}: {
  stat: keyof AdminStats;
  request: StatsRequest;
}) => {
  const copy = ADMIN_STAT_COPY[stat];
  const headingId = `admin-stat-${stat}`;
  return (
    <section css={operationCardStyle} aria-labelledby={headingId}>
      <img src={navUsersIcon} alt="" />
      <div>
        <h2 id={headingId}>{copy.title}</h2>
        <p css={statValueStyle}>
          <span>{request.status === 'error' ? copy.error : copy.scope}</span>
          <strong>
            {request.status === 'success'
              ? memberCountValue(request.stats[stat])
              : '-'}
          </strong>
        </p>
      </div>
    </section>
  );
};

const UserSearchCard = ({
  request,
  users,
  query,
  sort,
  direction,
  statusFilter,
  onStatusChange,
  onSearch,
  onSortChange,
  onDirectionChange,
  onSelect,
  onPage,
  onRetry,
}: {
  request: PageRequest<AdminUserSummary>;
  users: AdminUserSummary[];
  query: string;
  sort: AdminUserSort;
  direction: SortDirection;
  statusFilter: UserStatusFilter;
  onStatusChange: (value: UserStatusFilter) => void;
  onSearch: (query: string) => void;
  onSortChange: (sort: AdminUserSort) => void;
  onDirectionChange: (direction: SortDirection) => void;
  onSelect: (userId: string) => void;
  onPage: (page: number) => void;
  onRetry: () => void;
}) => {
  const page = shownPage(request);
  const handleSearch = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    const value = new FormData(event.currentTarget).get('query');
    onSearch(typeof value === 'string' ? value.trim() : '');
  };
  return (
    <section css={sectionCardStyle}>
      <div css={userSearchHeadingStyle}>
        <img src={adminCharacterUserSearch} alt="" />
        <div css={searchHeadingContentStyle}>
          <h2>사용자 검색</h2>
          {/* 주소의 검색어가 바뀌면(뒤로 가기 등) 입력창을 그 값으로 다시 채운다. */}
          <form key={query} css={searchFormStyle} onSubmit={handleSearch}>
            <img src={searchIcon} alt="" />
            <input
              name="query"
              defaultValue={query}
              placeholder={ADMIN_LIST_COPY.searchPlaceholder}
            />
            <button type="submit">검색</button>
          </form>
        </div>
      </div>
      <div css={userListToolbarStyle}>
        <select
          css={listSelectStyle}
          aria-label="사용자 상태 필터"
          value={statusFilter}
          onChange={(event) =>
            onStatusChange(event.target.value as UserStatusFilter)
          }
        >
          <option value="ALL">전체 상태</option>
          <option value="ACTIVE">활성</option>
          <option value="DELETED">비활성</option>
        </select>
        <select
          css={listSelectStyle}
          aria-label={ADMIN_LIST_COPY.sortKey}
          value={sort}
          onChange={(event) => {
            const next = ADMIN_USER_SORTS.find(
              (value) => value === event.target.value,
            );
            if (next) onSortChange(next);
          }}
        >
          {ADMIN_USER_SORTS.map((value) => (
            <option key={value} value={value}>
              {ADMIN_USER_SORT_LABELS[value]}
            </option>
          ))}
        </select>
        <select
          css={listSelectStyle}
          aria-label={ADMIN_LIST_COPY.sortDirection}
          value={direction}
          onChange={(event) => {
            const next = SORT_DIRECTIONS.find(
              (value) => value === event.target.value,
            );
            if (next) onDirectionChange(next);
          }}
        >
          {SORT_DIRECTIONS.map((value) => (
            <option key={value} value={value}>
              {SORT_DIRECTION_LABELS[value]}
            </option>
          ))}
        </select>
      </div>
      {request.status === 'error' && (
        <ListError message={request.message} onRetry={onRetry} />
      )}
      {page === null ? (
        request.status === 'loading' && <LoadingMessage />
      ) : (
        <>
          <UserTable users={users} onSelect={onSelect} />
          <ListFooter
            total={ADMIN_LIST_COPY.userTotal(page.totalElements)}
            page={page}
            loading={request.status === 'loading'}
            onPage={onPage}
          />
        </>
      )}
    </section>
  );
};

const UserTable = ({
  users,
  onSelect,
}: {
  users: AdminUserSummary[];
  onSelect: (id: string) => void;
}) => (
  <div css={tableScrollStyle}>
    <table css={tableStyle}>
      <thead>
        <tr>
          <th>사용자</th>
          <th>{ADMIN_LIST_COPY.nickname}</th>
          <th>계정 상태</th>
          <th>{ADMIN_LIST_COPY.joinedAt}</th>
          <th>오늘 남은 생성 횟수</th>
          <th>{ADMIN_LIST_COPY.generatedDiaryCount}</th>
          <th>상세 보기</th>
        </tr>
      </thead>
      <tbody>
        {users.map((user) => (
          <tr key={user.id}>
            <td>
              <span css={miniUserStyle}>
                <img src={adminCharacterUserDetail} alt="" />
                {user.name}
              </span>
            </td>
            <td>{user.nickname ?? ADMIN_LIST_COPY.noNickname}</td>
            <td>
              <StatusPill
                status={user.status === 'ACTIVE' ? '활성' : '비활성'}
              />
            </td>
            <td>{formatDateTime(user.createdAt)}</td>
            <td>
              {user.generationUsage.remainingCount} /{' '}
              {user.generationUsage.limitCount}
            </td>
            <td>{user.generatedDiaryCount.toLocaleString('ko-KR')}</td>
            <td>
              <button
                css={detailButtonStyle}
                type="button"
                onClick={() => onSelect(user.id)}
                aria-label={`${user.name} 상세 보기`}
              >
                <img src={searchIcon} alt="" />
              </button>
            </td>
          </tr>
        ))}
        {users.length === 0 && (
          <tr>
            <td colSpan={7} css={emptyCellStyle}>
              검색 결과가 없습니다.
            </td>
          </tr>
        )}
      </tbody>
    </table>
  </div>
);

const ListFooter = ({
  total,
  page,
  loading,
  onPage,
}: {
  total: string;
  page: Page<unknown>;
  loading: boolean;
  onPage: (page: number) => void;
}) => (
  <div css={listFooterStyle}>
    <span>{total}</span>
    <AdminPager
      css={listPagerStyle}
      page={page.page}
      hasNext={page.hasNext}
      disabled={loading}
      onPage={onPage}
    />
  </div>
);

const HistoryCard = ({
  request,
  failedOnly,
  date,
  onDateChange,
  onDateShift,
  onPage,
  onRetry,
  onFailedOnly,
  onAll,
  onPostcard,
}: {
  request: PageRequest<GenerationHistory>;
  failedOnly: boolean;
  date: string;
  onDateChange: (value: string) => void;
  onDateShift: (days: number) => void;
  onPage: (page: number) => void;
  onRetry: () => void;
  onFailedOnly: () => void;
  onAll: () => void;
  onPostcard: (generationId: string) => void;
}) => {
  const page = shownPage(request);
  const todayDate = today();
  return (
    <section css={sectionCardStyle}>
      <div css={sectionHeadingStyle}>
        <img src={adminCharacterGenerationHistory} alt="" />
        <h2>최근 생성 이력</h2>
      </div>
      <div css={historyToolbarStyle}>
        <button
          css={filterButtonStyle(!failedOnly)}
          type="button"
          onClick={onAll}
        >
          전체
        </button>
        <button
          css={filterButtonStyle(failedOnly)}
          type="button"
          onClick={onFailedOnly}
        >
          실패 이력
        </button>
        <div css={dateNavStyle}>
          <button
            css={filterButtonStyle(false)}
            type="button"
            aria-label={ADMIN_LIST_COPY.previousDate}
            disabled={date === ''}
            onClick={() => onDateShift(-1)}
          >
            ◀
          </button>
          <label css={dateLabelStyle}>
            {ADMIN_LIST_COPY.historyDate}
            <input
              type="date"
              value={date}
              max={todayDate}
              onChange={(event) => onDateChange(event.target.value)}
            />
          </label>
          <button
            css={filterButtonStyle(false)}
            type="button"
            aria-label={ADMIN_LIST_COPY.nextDate}
            disabled={date === '' || date >= todayDate}
            onClick={() => onDateShift(1)}
          >
            ▶
          </button>
          <button
            css={filterButtonStyle(false)}
            type="button"
            disabled={date === todayDate}
            onClick={() => onDateChange(todayDate)}
          >
            {ADMIN_LIST_COPY.today}
          </button>
        </div>
      </div>
      {request.status === 'error' && (
        <ListError message={request.message} onRetry={onRetry} />
      )}
      {page === null ? (
        request.status === 'loading' && <LoadingMessage />
      ) : (
        <>
          <GenerationTable generations={page.content} onPostcard={onPostcard} />
          <ListFooter
            total={ADMIN_LIST_COPY.historyTotal(page.totalElements)}
            page={page}
            loading={request.status === 'loading'}
            onPage={onPage}
          />
        </>
      )}
    </section>
  );
};

const GenerationTable = ({
  generations,
  onPostcard,
}: {
  generations: GenerationHistory[];
  onPostcard: (generationId: string) => void;
}) => (
  <div css={tableScrollStyle}>
    <table css={tableStyle}>
      <thead>
        <tr>
          <th>사용자</th>
          <th>생성 ID</th>
          <th>요청 시간</th>
          <th>상태</th>
          <th>실패 코드</th>
          <th>엽서</th>
        </tr>
      </thead>
      <tbody>
        {generations.map((generation) => (
          <tr key={generation.id}>
            <td>{generation.user.name}</td>
            <td css={idCellStyle}>{generation.id}</td>
            <td>{formatDateTime(generation.requestedAt)}</td>
            <td>
              <GenerationStatusPill status={generation.status} />
            </td>
            <td>{generation.errorCode ?? '-'}</td>
            <td>
              {generation.status === 'SUCCEEDED' ? (
                <button
                  css={filterButtonStyle(false)}
                  type="button"
                  onClick={() => onPostcard(generation.id)}
                >
                  {ADMIN_POSTCARD_COPY.open}
                </button>
              ) : (
                '-'
              )}
            </td>
          </tr>
        ))}
        {generations.length === 0 && (
          <tr>
            <td colSpan={6} css={emptyCellStyle}>
              생성 이력이 없습니다.
            </td>
          </tr>
        )}
      </tbody>
    </table>
  </div>
);

const DetailPanel = ({ children }: { children: ReactNode }) => (
  <aside className="detail-aside" css={detailAsideStyle}>
    <div css={posthogStyle}>
      <PostHogLink />
    </div>
    {children ? (
      <section css={detailCardStyle}>
        <div css={detailHeadingStyle}>
          <img src={adminCharacterUserDetail} alt="" />
          <h2>{ADMIN_LIST_COPY.userDetail}</h2>
        </div>
        {children}
      </section>
    ) : (
      <div css={emptyDetailStyle}>
        사용자를 선택하면
        <br />
        상세 정보를 확인할 수 있어요.
      </div>
    )}
  </aside>
);

const UserDetailBody = ({
  detail,
  loading,
  error,
  onRestore,
  onReset,
  onSetGenerationLimit,
}: {
  detail: AdminUserDetail;
  loading: boolean;
  error: string | null;
  onRestore: () => void;
  onReset: () => void;
  onSetGenerationLimit: () => void;
}) => (
  <div css={detailBodyStyle}>
    <dl css={detailInfoStyle}>
      <dt>이름</dt>
      <dd>{detail.name}</dd>
      <dt>{ADMIN_LIST_COPY.nickname}</dt>
      <dd>{detail.nickname ?? ADMIN_LIST_COPY.noNickname}</dd>
      <dt>사용자 ID</dt>
      <dd css={idCellStyle}>{detail.id}</dd>
      <dt>계정 상태</dt>
      <dd>
        <StatusPill status={detail.status === 'ACTIVE' ? '활성' : '비활성'} />
      </dd>
    </dl>
    <hr css={dividerStyle} />
    <h3>오늘 생성 사용량</h3>
    <div css={usageStyle}>
      <Metric label="사용 횟수" value={detail.generationUsage.usedCount} />
      <Metric label="생성 한도" value={detail.generationUsage.limitCount} />
      <Metric
        label="남은 횟수"
        value={detail.generationUsage.remainingCount}
        accent
      />
    </div>
    <h3>생성 사용량 관리 ⓘ</h3>
    <button
      css={actionButtonStyle}
      type="button"
      disabled={loading || detail.status !== 'ACTIVE'}
      onClick={onRestore}
    >
      사용 횟수 복구
    </button>
    <button
      css={resetActionButtonStyle}
      type="button"
      disabled={loading || detail.status !== 'ACTIVE'}
      onClick={onReset}
    >
      오늘 사용량 전체 초기화
    </button>
    <div css={secondaryActionGridStyle}>
      <button
        css={secondaryActionButtonStyle}
        type="button"
        disabled={loading || detail.status !== 'ACTIVE'}
        onClick={onSetGenerationLimit}
      >
        생성 한도 변경
      </button>
    </div>
    <AdminModerationPanel
      key={`USER-${detail.id}`}
      targetType="USER"
      targetId={detail.id}
      available={detail.status === 'ACTIVE'}
    />
    {error && <ErrorMessage message={error} />}
  </div>
);

const UsageManagementModal = ({
  mode,
  usage,
  value,
  error,
  loading,
  onValueChange,
  onClose,
  onSubmit,
}: {
  mode: UsageModalMode;
  usage: AdminGenerationUsage;
  value: string;
  error: string | null;
  loading: boolean;
  onValueChange: (value: string) => void;
  onClose: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) => {
  const isRestore = mode === 'restore';
  const isReset = mode === 'reset';
  const title = isRestore
    ? '사용 횟수 복구'
    : isReset
      ? '오늘 사용량 전체 초기화'
      : '생성 한도 변경';

  return (
    <div
      css={usageModalBackdropStyle}
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        css={usageModalStyle}
        role="dialog"
        aria-modal="true"
        aria-labelledby="usage-modal-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div css={usageModalHeaderStyle}>
          <div>
            <p css={usageModalEyebrowStyle}>생성 사용량 관리</p>
            <h2 id="usage-modal-title">{title}</h2>
          </div>
          <button
            css={usageModalCloseButtonStyle}
            type="button"
            aria-label="팝업 닫기"
            onClick={onClose}
            disabled={loading}
          >
            ×
          </button>
        </div>

        <form onSubmit={onSubmit}>
          {isRestore ? (
            <>
              <p css={usageModalDescriptionStyle}>
                현재 사용 횟수 <strong>{usage.usedCount}회</strong> 중 복구할
                횟수를 입력해주세요.
              </p>
              <label css={usageModalFieldStyle} htmlFor="usage-modal-value">
                복구할 횟수
                <span css={usageModalInputStyle}>
                  <input
                    id="usage-modal-value"
                    type="number"
                    inputMode="numeric"
                    min="1"
                    max={usage.usedCount}
                    step="1"
                    value={value}
                    onChange={(event) => onValueChange(event.target.value)}
                    autoFocus
                  />
                  <span>회</span>
                </span>
              </label>
              <p css={usageModalHintStyle}>
                1회부터 현재 사용 횟수까지 입력할 수 있어요.
              </p>
            </>
          ) : isReset ? (
            <div css={usageModalNoticeStyle}>
              <strong>오늘 사용량을 초기화할까요?</strong>
              <p>
                현재 사용 횟수와 남은 횟수가 오늘 생성 한도에 맞게 다시
                계산됩니다.
              </p>
            </div>
          ) : (
            <>
              <p css={usageModalDescriptionStyle}>
                오늘 사용 가능한 생성 한도를 입력해주세요.
              </p>
              <label css={usageModalFieldStyle} htmlFor="usage-modal-value">
                생성 한도
                <span css={usageModalInputStyle}>
                  <input
                    id="usage-modal-value"
                    type="number"
                    inputMode="numeric"
                    min="1"
                    step="1"
                    value={value}
                    onChange={(event) => onValueChange(event.target.value)}
                    autoFocus
                  />
                  <span>회</span>
                </span>
              </label>
              <p css={usageModalHintStyle}>1회 이상의 정수로 입력해주세요.</p>
            </>
          )}

          {error && (
            <p css={usageModalErrorStyle} role="alert">
              {error}
            </p>
          )}

          <div css={usageModalActionsStyle}>
            <button
              css={usageModalCancelButtonStyle}
              type="button"
              onClick={onClose}
              disabled={loading}
            >
              취소
            </button>
            <button
              css={usageModalConfirmButtonStyle(isReset)}
              type="submit"
              disabled={loading}
            >
              {loading ? '처리 중...' : isReset ? '전체 초기화' : '변경하기'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

const PostHogLink = () => (
  <a
    css={posthogLinkStyle}
    href="https://us.posthog.com/project/567105/web"
    target="_blank"
    rel="noreferrer"
  >
    PostHog 바로가기 ↗
  </a>
);

const Metric = ({
  label,
  value,
  accent = false,
}: {
  label: string;
  value: number;
  accent?: boolean;
}) => (
  <div css={metricStyle(accent)}>
    <span>{label}</span>
    <strong>{value}</strong>
  </div>
);

const StatusPill = ({ status }: { status: '활성' | '비활성' }) => (
  <span css={statusPillStyle(status === '활성')}>{status}</span>
);

const GenerationStatusPill = ({ status }: { status: GenerationStatus }) => (
  <span css={generationPillStyle(status)}>
    {status === 'SUCCEEDED' ? '성공' : status === 'FAILED' ? '실패' : '처리 중'}
  </span>
);

const ErrorMessage = ({ message }: { message: string }) => (
  <p css={errorStyle}>{message}</p>
);
const ListError = ({
  message,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}) => (
  <div css={[errorStyle, listErrorStyle]} role="alert">
    <p>{message}</p>
    <button css={filterButtonStyle(false)} type="button" onClick={onRetry}>
      {ADMIN_LIST_COPY.retry}
    </button>
  </div>
);
const LoadingMessage = () => <p css={messageStyle}>불러오는 중...</p>;

const formatDateTime = (value: string) =>
  new Date(value).toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });

const pageStyle = (wideContent: boolean) => css`
  width: 100vw;
  height: 100vh;
  min-height: 100vh;
  max-height: 100vh;
  margin-left: calc(50% - 50vw);
  display: grid;
  grid-template-columns: ${
    wideContent ? '250px minmax(620px, 1fr)' : '250px minmax(620px, 1fr) 430px'
  };
  grid-template-rows: minmax(0, 1fr);
  max-width: none;
  overflow: hidden;
  border: 1px solid #ddd8d1;
  border-radius: 8px;
  background: #fffefa;
  color: ${theme.colors.foreground.neutral};
  font-family: 'Avenir Next', 'Apple SD Gothic Neo', 'Noto Sans KR', sans-serif;
  letter-spacing: -0.02em;
  -webkit-font-smoothing: antialiased;
  h1,
  h2,
  h3,
  strong,
  button {
    font-family: inherit;
  }
  > aside,
  > main {
    overflow-y: auto;
  }
  @media ${COMPACT_LAYOUT_QUERY} {
    grid-template-columns: 210px minmax(0, 1fr);
    .detail-aside {
      display: none;
    }
  }
  @media (max-width: 760px) {
    display: block;
    width: 100%;
    min-height: 100vh;
    margin-left: 0;
    overflow-y: auto;
    > aside,
    > main {
      overflow: visible;
    }
    .sidebar {
      display: block;
    }
    .detail-aside {
      display: block;
    }
  }
`;
const sidebarStyle = css`
  display: flex;
  flex-direction: column;
  padding: 38px 20px 24px;
  border-right: 1px solid #ddd8d1;
  background: #fffefa;
  @media (max-width: 760px) {
    padding: 12px 16px 10px;
    border-right: 0;
    border-bottom: 1px solid #ddd8d1;
  }
`;
const logoStyle = css`
  width: 190px;
  height: 92px;
  object-fit: contain;
  @media (max-width: 760px) {
    width: 150px;
    height: 58px;
  }
`;
const menuStyle = css`
  display: grid;
  gap: 10px;
  margin-top: 20px;
  @media (max-width: 760px) {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 6px;
    margin-top: 8px;
  }
`;
const menuButtonStyle = (active: boolean) => css`
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 15px 18px;
  border: 0;
  border-radius: 10px;
  background: ${active ? 'linear-gradient(90deg,#eee8ff,#f3edff)' : 'transparent'};
  color: ${active ? theme.colors.foreground.brand : theme.colors.foreground.neutral};
  text-align: left;
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
  img {
    width: 28px;
    height: 28px;
    object-fit: contain;
  }
  @media (max-width: 760px) {
    gap: 6px;
    padding: 10px;
    font-size: 13px;
    img {
      width: 22px;
      height: 22px;
    }
  }
`;
const adminProfileStyle = css`
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: auto;
  padding-top: 24px;
  small {
    display: block;
    margin-top: 4px;
    color: ${theme.colors.foreground.neutralMuted};
  }
  @media (max-width: 760px) {
    display: none;
  }
`;
const adminAvatarStyle = css`
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  overflow: hidden;
  border: 1px solid #ddd8d1;
  border-radius: 50%;
  background: ${theme.colors.background.surface};
  img {
    width: 50px;
    height: 50px;
    object-fit: cover;
    object-position: left center;
  }
`;
const mainStyle = css`
  position: relative;
  min-width: 0;
  padding: 34px 38px 30px;
  background: #fffefa;
  @media (max-width: 760px) {
    padding: 24px 16px 24px;
  }
`;
const headlineStyle = css`
  position: relative;
  min-height: 145px;
  h1 {
    margin: 0 0 12px;
    font-size: 30px;
    font-weight: 800;
  }
  p {
    margin: 0;
    color: ${theme.colors.foreground.neutralMuted};
  }
  .hero {
  }
  @media (max-width: 760px) {
    min-height: 132px;
    h1 {
      max-width: 190px;
      font-size: 24px;
      line-height: 1.3;
    }
    p {
      font-size: 13px;
    }
  }
`;
const heroCharacterStyle = css`
  position: absolute;
  top: -45px;
  right: -8px;
  width: 310px;
  height: 190px;
  object-fit: contain;
  object-position: center;
  pointer-events: none;
  @media (max-width: 760px) {
    top: -20px;
    right: -8px;
    width: 180px;
    height: 120px;
  }
`;
const pageTitleStyle = css`
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 80px;
  margin-bottom: 14px;
  button {
    border: 0;
    background: transparent;
    font-size: 28px;
    cursor: pointer;
  }
  h1 {
    margin: 0;
    font-size: 26px;
  }
  @media (max-width: 760px) {
    min-height: 56px;
    gap: 8px;
    margin-bottom: 12px;
    button {
      font-size: 24px;
    }
    h1 {
      font-size: 22px;
    }
  }
`;
const operationCardStyle = css`
  display: grid;
  grid-template-columns: 90px 1fr;
  align-items: center;
  margin-bottom: 14px;
  padding: 12px 24px;
  border: 1px solid #ddd8d1;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.76);
  img {
    width: 58px;
    height: 58px;
    object-fit: contain;
    margin-left: 0;
    margin-right: auto;
  }
  h2 {
    margin: 0 0 10px;
    font-size: 18px;
    font-weight: 700;
  }
  button {
    display: flex;
    justify-content: space-between;
    width: 100%;
    padding: 12px 18px;
    border: 1px solid #e8e4de;
    border-radius: 10px;
    background: ${theme.colors.background.surface};
    color: ${theme.colors.foreground.neutral};
    cursor: pointer;
    strong {
      color: ${theme.colors.foreground.brand};
    }
  }
  @media (max-width: 760px) {
    grid-template-columns: 58px minmax(0, 1fr);
    padding: 10px 12px;
    img {
      width: 48px;
      height: 48px;
    }
    h2 {
      margin-bottom: 8px;
      font-size: 16px;
    }
    button {
      padding: 10px 12px;
      font-size: 13px;
    }
  }
`;
const dashboardStatsStyle = css`
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 14px;
  /* 상세 열이 보이는 너비에서 세 장을 한 줄에 두면 값이 줄바꿈되므로 운영 알림만 한 줄을 쓴다. */
  > section:first-of-type {
    grid-column: 1 / -1;
  }
  @media (max-width: 760px) {
    grid-template-columns: minmax(0, 1fr);
  }
`;
const statValueStyle = css`
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin: 0;
  padding: 12px 18px;
  border: 1px solid #e8e4de;
  border-radius: 10px;
  background: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
  font-size: 13px;
  strong {
    font-size: 16px;
  }
  @media (max-width: 760px) {
    padding: 10px 12px;
  }
`;
const sectionCardStyle = css`
  margin-bottom: 14px;
  padding: 14px 10px 12px;
  border: 1px solid #ddd8d1;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.76);
  @media (max-width: 760px) {
    padding: 12px 6px 10px;
  }
`;
const sectionHeadingStyle = css`
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 10px 10px;
  img {
    width: 72px;
    height: 66px;
    object-fit: contain;
  }
  h2 {
    margin: 0;
    font-size: 18px;
    font-weight: 700;
  }
  @media (max-width: 760px) {
    gap: 8px;
    padding: 0 6px 8px;
    img {
      width: 54px;
      height: 50px;
    }
    h2 {
      font-size: 16px;
    }
  }
`;
const userSearchHeadingStyle = css`
  ${sectionHeadingStyle};
  padding-right: 0;
`;
const searchHeadingContentStyle = css`
  flex: 1;
  min-width: 0;
  h2 {
    margin: 0 0 8px;
  }
`;
const searchFormStyle = css`
  display: flex;
  align-items: center;
  gap: 10px;
  width: calc(100% - 24px);
  margin-left: 10px;
  min-height: 40px;
  padding: 5px 8px 5px 14px;
  border: 1px solid #cfc9c1;
  border-radius: 10px;
  background: ${theme.colors.background.surface};
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
  &:focus-within {
    border-color: ${theme.colors.stroke.brandSolid};
    box-shadow: 0 0 0 3px rgb(139 112 232 / 12%);
  }
  img {
    width: 18px;
    height: 18px;
    object-fit: contain;
  }
  input {
    flex: 1;
    width: auto;
    min-width: 0;
    border: 0;
    outline: 0;
    background: transparent;
    color: ${theme.colors.foreground.neutral};
    font: inherit;
    font-size: 14px;
  }
  input::placeholder {
    color: #aaa4af;
  }
  button {
    flex: 0 0 auto;
    min-width: 44px;
    padding: 7px 10px;
    border: 0;
    border-radius: 7px;
    background: ${theme.colors.background.brandSolid};
    color: ${theme.colors.foreground.onBrand};
    font-weight: 700;
    line-height: 1.2;
    white-space: nowrap;
    cursor: pointer;
  }
  @media (max-width: 760px) {
    width: 100%;
    margin-left: 0;
    gap: 6px;
    min-height: 38px;
    padding: 4px 6px 4px 10px;
    input {
      font-size: 13px;
    }
  }
`;
const userListToolbarStyle = css`
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 10px 12px;
  @media (max-width: 760px) {
    margin: 0 6px 10px;
  }
`;
const listSelectStyle = css`
  padding: 9px 12px;
  border: 1px solid #d5d0c9;
  border-radius: 8px;
  background: ${theme.colors.background.surface};
  @media (max-width: 760px) {
    flex: 1 1 140px;
  }
`;
const tableScrollStyle = css`
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
`;
const tableStyle = css`
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  overflow: hidden;
  border: 1px solid #ddd8d1;
  border-radius: 10px;
  font-size: 13px;
  th,
  td {
    padding: 10px 12px;
    border-bottom: 1px solid #ece8e2;
    text-align: left;
    white-space: nowrap;
  }
  th {
    background: #faf8f4;
    font-weight: 700;
  }
  tr:last-child td {
    border-bottom: 0;
  }
  th:nth-child(n + 3),
  td:nth-child(n + 3) {
    text-align: center;
  }
  @media (max-width: 760px) {
    min-width: 560px;
    font-size: 12px;
    th,
    td {
      padding: 9px 10px;
    }
  }
`;
const miniUserStyle = css`
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  img {
    width: 28px;
    height: 28px;
    padding: 2px;
    border: 1px solid #cfc3f4;
    border-radius: 50%;
    background: ${theme.colors.background.brandWeak};
    object-fit: contain;
  }
`;
const statusPillStyle = (active: boolean) => css`
  display: inline-block;
  min-width: 54px;
  padding: 5px 10px;
  border-radius: 7px;
  background: ${active ? '#e9e3ff' : '#eceaec'};
  color: ${active ? '#7459d6' : '#77727a'};
  text-align: center;
  font-weight: 700;
`;
const detailButtonStyle = css`
  display: inline-grid;
  place-items: center;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  img {
    width: 20px;
    height: 20px;
    object-fit: contain;
  }
`;
const emptyCellStyle = css`
  padding: 32px !important;
  color: ${theme.colors.foreground.neutralMuted}!important;
  text-align: center !important;
`;
const historyToolbarStyle = css`
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0 10px 14px;
  padding: 10px;
  border-radius: 10px;
  background: #faf8f4;
`;
const filterButtonStyle = (active: boolean) => css`
  padding: 9px 14px;
  border: 1px solid ${active ? theme.colors.stroke.brandSolid : '#d5d0c9'};
  border-radius: 8px;
  background: ${active ? '#eee9ff' : theme.colors.background.surface};
  color: ${active ? theme.colors.foreground.brand : theme.colors.foreground.neutral};
  font-weight: ${active ? 700 : 400};
  cursor: pointer;
  &:disabled {
    cursor: not-allowed;
    opacity: 0.45;
  }
`;
const dateNavStyle = css`
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-left: auto;
`;
const dateLabelStyle = css`
  display: flex;
  align-items: center;
  gap: 8px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 13px;
  input {
    padding: 8px 10px;
    border: 1px solid #d5d0c9;
    border-radius: 8px;
    background: ${theme.colors.background.surface};
    color: ${theme.colors.foreground.neutral};
  }
`;
const listFooterStyle = css`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 12px 10px 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 13px;
`;
const listPagerStyle = css`
  display: flex;
  align-items: center;
  gap: 8px;
  button {
    width: auto;
    height: 36px;
    padding: 6px 12px;
    border-radius: 10px;
    font-size: 13px;
  }
  span {
    flex-shrink: 0;
    color: ${theme.colors.foreground.neutral};
    white-space: nowrap;
  }
`;
const idCellStyle = css`
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
`;
const generationPillStyle = (status: GenerationStatus) => css`
  display: inline-block;
  min-width: 54px;
  padding: 5px 10px;
  border-radius: 7px;
  background: ${status === 'FAILED' ? '#ffdfe6' : status === 'SUCCEEDED' ? '#e8f5e4' : '#eee9ff'};
  color: ${status === 'FAILED' ? '#e7526b' : status === 'SUCCEEDED' ? '#4d8b48' : theme.colors.foreground.brand};
  text-align: center;
  font-weight: 700;
`;
const detailAsideStyle = css`
  min-width: 0;
  padding: 42px 36px 26px 28px;
  background: #fffefa;
  @media (max-width: 760px) {
    padding: 0 16px 24px;
  }
`;
const posthogStyle = css`
  display: flex;
  justify-content: flex-end;
  margin-bottom: 88px;
  @media (max-width: 760px) {
    margin-bottom: 16px;
  }
`;
const historyPosthogStyle = css`
  position: absolute;
  top: 34px;
  right: 38px;
  @media (max-width: 760px) {
    top: 16px;
    right: 16px;
  }
`;
const posthogLinkStyle = css`
  display: inline-block;
  padding: 13px 18px;
  border: 2px solid ${theme.colors.stroke.brandSolid};
  border-radius: 8px;
  color: ${theme.colors.foreground.brand};
  font-weight: 800;
  text-decoration: none;
  @media (max-width: 760px) {
    padding: 9px 12px;
    font-size: 12px;
  }
`;
const detailCardStyle = css`
  padding: 24px;
  border: 1px solid #ddd8d1;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.76);
  @media (max-width: 760px) {
    padding: 16px;
  }
`;
const detailBodyStyle = css`
  h3 {
    margin: 20px 0 12px;
    font-size: 16px;
  }
`;
const detailModalStyle = css`
  width: min(440px, calc(100% - 32px));
`;
const detailHeadingStyle = css`
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 24px;
  img {
    width: 55px;
    height: 55px;
    object-fit: contain;
  }
  h2 {
    margin: 0;
    font-size: 21px;
    font-weight: 700;
  }
  @media (max-width: 760px) {
    gap: 10px;
    margin-bottom: 18px;
    img {
      width: 48px;
      height: 48px;
    }
    h2 {
      font-size: 19px;
    }
  }
`;
const detailInfoStyle = css`
  display: grid;
  grid-template-columns: 105px 1fr;
  gap: 19px 16px;
  margin: 0;
  color: ${theme.colors.foreground.neutralMuted};
  dt,
  dd {
    margin: 0;
  }
  dd {
    color: ${theme.colors.foreground.neutral};
    font-weight: 500;
    overflow-wrap: anywhere;
  }
  @media (max-width: 760px) {
    grid-template-columns: 80px minmax(0, 1fr);
    gap: 13px 10px;
  }
`;
const dividerStyle = css`
  margin: 24px 0 20px;
  border: 0;
  border-top: 1px dashed #cfc8e5;
`;
const usageStyle = css`
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  margin: 12px 0 18px;
  border: 1px solid ${theme.colors.stroke.brandSolid};
  border-radius: 12px;
  @media (max-width: 760px) {
    margin-top: 10px;
  }
`;
const metricStyle = (accent: boolean) => css`
  padding: 14px;
  border-right: 1px dashed #d9d1ef;
  text-align: center;
  &:last-child {
    border: 0;
  }
  span {
    display: block;
    font-size: 13px;
  }
  strong {
    display: block;
    margin-top: 8px;
    color: ${accent ? theme.colors.foreground.brand : theme.colors.foreground.neutral};
    font-size: 27px;
  }
  @media (max-width: 760px) {
    padding: 10px 6px;
    span {
      font-size: 12px;
    }
    strong {
      margin-top: 6px;
      font-size: 22px;
    }
  }
`;
const actionButtonStyle = css`
  width: 100%;
  margin-top: 10px;
  padding: 14px;
  border: 0;
  border-radius: 8px;
  background: #a990eb;
  color: ${theme.colors.foreground.onBrand};
  font-weight: 800;
  cursor: pointer;
  &:disabled {
    cursor: not-allowed;
    opacity: 0.45;
  }
`;
const resetActionButtonStyle = css`
  ${actionButtonStyle.styles};
  background: #ffe29a;
  color: #3e3520;
`;
const secondaryActionGridStyle = css`
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 8px;
`;
const secondaryActionButtonStyle = css`
  grid-column: 1 / -1;
  width: 100%;
  padding: 14px;
  border: 1px solid #d5ccef;
  border-radius: 8px;
  background: #faf8ff;
  color: ${theme.colors.foreground.brand};
  font-weight: 800;
  cursor: pointer;
  &:disabled {
    cursor: not-allowed;
    opacity: 0.45;
  }
`;
const usageModalBackdropStyle = css`
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  padding: 16px;
  background: rgb(42 32 58 / 32%);
  backdrop-filter: blur(2px);
`;
const usageModalStyle = css`
  width: min(100%, 420px);
  max-height: calc(100vh - 32px);
  overflow-y: auto;
  padding: 24px;
  border: 1px solid #ddd8d1;
  border-radius: 16px;
  background: #fffefa;
  box-shadow: 0 20px 50px rgb(46 36 61 / 20%);
`;
const usageModalHeaderStyle = css`
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  h2 {
    margin: 4px 0 0;
    font-size: 21px;
    font-weight: 800;
  }
`;
const usageModalEyebrowStyle = css`
  margin: 0;
  color: ${theme.colors.foreground.brand};
  font-size: 12px;
  font-weight: 700;
`;
const usageModalCloseButtonStyle = css`
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: #f3f0ec;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
  &:disabled {
    cursor: not-allowed;
    opacity: 0.45;
  }
`;
const usageModalDescriptionStyle = css`
  margin: 22px 0 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  line-height: 1.6;
  strong {
    color: ${theme.colors.foreground.neutral};
  }
`;
const usageModalFieldStyle = css`
  display: grid;
  gap: 8px;
  margin-top: 18px;
  color: ${theme.colors.foreground.neutral};
  font-size: 13px;
  font-weight: 700;
`;
const usageModalInputStyle = css`
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 14px;
  border: 1px solid #cfc9c1;
  border-radius: 10px;
  background: ${theme.colors.background.surface};
  &:focus-within {
    border-color: ${theme.colors.stroke.brandSolid};
    box-shadow: 0 0 0 3px rgb(139 112 232 / 12%);
  }
  input {
    width: 100%;
    min-width: 0;
    padding: 12px 0;
    border: 0;
    outline: 0;
    background: transparent;
    color: ${theme.colors.foreground.neutral};
    font: inherit;
    font-size: 18px;
    font-weight: 700;
  }
  > span {
    flex: 0 0 auto;
    color: ${theme.colors.foreground.neutralMuted};
    font-size: 14px;
    font-weight: 500;
  }
`;
const usageModalHintStyle = css`
  margin: 8px 0 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
`;
const usageModalNoticeStyle = css`
  margin-top: 22px;
  padding: 16px;
  border-radius: 10px;
  background: #faf8f4;
  line-height: 1.6;
  strong {
    display: block;
    font-size: 15px;
  }
  p {
    margin: 8px 0 0;
    color: ${theme.colors.foreground.neutralMuted};
    font-size: 13px;
  }
`;
const usageModalErrorStyle = css`
  margin: 12px 0 0;
  color: ${theme.colors.foreground.critical};
  font-size: 12px;
  line-height: 1.5;
`;
const usageModalActionsStyle = css`
  display: grid;
  grid-template-columns: 1fr 1.35fr;
  gap: 8px;
  margin-top: 24px;
`;
const usageModalCancelButtonStyle = css`
  padding: 12px;
  border: 1px solid #d5d0c9;
  border-radius: 8px;
  background: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
  font-weight: 700;
  cursor: pointer;
  &:disabled {
    cursor: not-allowed;
    opacity: 0.45;
  }
`;
const usageModalConfirmButtonStyle = (reset: boolean) => css`
  padding: 12px;
  border: 0;
  border-radius: 8px;
  background: ${reset ? '#ffe29a' : '#a990eb'};
  color: ${reset ? '#3e3520' : theme.colors.foreground.onBrand};
  font-weight: 800;
  cursor: pointer;
  &:disabled {
    cursor: not-allowed;
    opacity: 0.45;
  }
`;
const emptyDetailStyle = css`
  padding: 48px 24px;
  border: 1px dashed #d5d0c9;
  border-radius: 12px;
  color: ${theme.colors.foreground.neutralMuted};
  text-align: center;
  line-height: 1.7;
`;
const errorStyle = css`
  margin: 12px 0;
  padding: 12px;
  border-radius: 8px;
  background: #fff0f0;
  color: ${theme.colors.foreground.critical};
  font-size: 13px;
`;
const listErrorStyle = css`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  button {
    flex-shrink: 0;
  }
`;
const messageStyle = css`
  padding: 32px 0;
  color: ${theme.colors.foreground.neutralMuted};
  text-align: center;
`;

export default AdminPage;
