import { css } from '@emotion/react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useSearchParams } from 'react-router';
import logo from '../../assets/images/harudle-logo.png';
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
  getAdminUser,
  resetAdminUsage,
  restoreAdminUsage,
  searchAdminGenerations,
  searchAdminUsers,
  setAdminGenerationLimit,
  type AdminGenerationUsage,
  type AdminUserDetail,
  type AdminUserSummary,
  type GenerationHistory,
  type GenerationStatus,
} from './adminApi';

type View = 'dashboard' | 'users' | 'generations' | 'failed';
type UserStatusFilter = 'ALL' | 'ACTIVE' | 'DELETED';
type UsageModalMode = 'restore' | 'reset' | 'limit';

const today = () => {
  const date = new Date();
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 10);
};

const viewFromParam = (value: string | null): View => {
  if (value === 'users' || value === 'generations' || value === 'failed') {
    return value;
  }
  return 'dashboard';
};

const AdminPage = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const view = viewFromParam(searchParams.get('view'));
  const isHistoryView = view === 'generations' || view === 'failed';
  const [users, setUsers] = useState<AdminUserSummary[]>([]);
  const [generations, setGenerations] = useState<GenerationHistory[]>([]);
  const [failedGenerationCount, setFailedGenerationCount] = useState<
    number | null
  >(null);
  const [detail, setDetail] = useState<AdminUserDetail | null>(null);
  const [query, setQuery] = useState('');
  const [submittedQuery, setSubmittedQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<UserStatusFilter>('ALL');
  const [historyDate, setHistoryDate] = useState(today);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [usageModal, setUsageModal] = useState<UsageModalMode | null>(null);
  const [usageModalValue, setUsageModalValue] = useState('');
  const [usageModalError, setUsageModalError] = useState<string | null>(null);
  const requestSequenceRef = useRef(0);
  const usersRequestIdRef = useRef(0);
  const generationsRequestIdRef = useRef(0);
  const failedGenerationRequestIdRef = useRef(0);
  const detailRequestIdRef = useRef(0);

  const loadUsers = useCallback(async (nextQuery = '') => {
    const requestId = ++requestSequenceRef.current;
    usersRequestIdRef.current = requestId;
    setLoading(true);
    setError(null);
    try {
      const result = await searchAdminUsers(nextQuery);
      if (usersRequestIdRef.current === requestId) {
        setUsers(result.content);
      }
    } catch (cause) {
      if (requestSequenceRef.current !== requestId) return;
      setError(
        cause instanceof Error
          ? cause.message
          : '사용자를 불러오지 못했습니다.',
      );
    } finally {
      if (requestSequenceRef.current === requestId) setLoading(false);
    }
  }, []);

  const loadGenerations = useCallback(
    async (status?: GenerationStatus) => {
      const requestId = ++requestSequenceRef.current;
      generationsRequestIdRef.current = requestId;
      setLoading(true);
      setError(null);
      try {
        const result = await searchAdminGenerations({
          page: 0,
          size: 20,
          status,
          from: historyDate,
          to: historyDate,
        });
        if (generationsRequestIdRef.current === requestId) {
          setGenerations(result.content);
        }
      } catch (cause) {
        if (requestSequenceRef.current !== requestId) return;
        setError(
          cause instanceof Error
            ? cause.message
            : '생성 이력을 불러오지 못했습니다.',
        );
      } finally {
        if (requestSequenceRef.current === requestId) setLoading(false);
      }
    },
    [historyDate],
  );

  const loadDashboard = useCallback(async () => {
    const requestId = ++requestSequenceRef.current;
    usersRequestIdRef.current = requestId;
    generationsRequestIdRef.current = requestId;
    failedGenerationRequestIdRef.current = requestId;
    setLoading(true);
    setError(null);
    setFailedGenerationCount(null);
    try {
      const [userPage, generationPage, failedGenerationPage] =
        await Promise.all([
          searchAdminUsers('', 0, 20),
          searchAdminGenerations({
            page: 0,
            size: 20,
            from: historyDate,
            to: historyDate,
          }),
          searchAdminGenerations({
            page: 0,
            size: 1,
            status: 'FAILED',
            from: historyDate,
            to: historyDate,
          }),
        ]);
      if (usersRequestIdRef.current === requestId) {
        setUsers(userPage.content);
      }
      if (generationsRequestIdRef.current === requestId) {
        setGenerations(generationPage.content);
      }
      if (failedGenerationRequestIdRef.current === requestId) {
        setFailedGenerationCount(failedGenerationPage.totalElements);
      }
    } catch (cause) {
      if (requestSequenceRef.current !== requestId) return;
      setError(
        cause instanceof Error
          ? cause.message
          : '관리자 데이터를 불러오지 못했습니다.',
      );
    } finally {
      if (requestSequenceRef.current === requestId) setLoading(false);
    }
  }, [historyDate]);

  useEffect(() => {
    // URL 상태 변화에 맞춰 관리자 API를 조회한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (view === 'dashboard') void loadDashboard();
    else if (view !== 'users') {
      void loadGenerations(view === 'failed' ? 'FAILED' : undefined);
    }
  }, [loadDashboard, loadGenerations, view]);

  useEffect(() => {
    if (view !== 'users') return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadUsers(submittedQuery);
  }, [loadUsers, submittedQuery, view]);

  const navigate = (nextView: View) => {
    const invalidationId = ++requestSequenceRef.current;
    usersRequestIdRef.current = invalidationId;
    generationsRequestIdRef.current = invalidationId;
    failedGenerationRequestIdRef.current = invalidationId;
    detailRequestIdRef.current = invalidationId;
    setDetail(null);
    setUsageModal(null);
    setSearchParams(nextView === 'dashboard' ? {} : { view: nextView });
  };

  const selectUser = async (userId: string) => {
    const requestId = ++requestSequenceRef.current;
    detailRequestIdRef.current = requestId;
    setUsageModal(null);
    setLoading(true);
    setError(null);
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

  const visibleUsers = useMemo(
    () =>
      statusFilter === 'ALL'
        ? users
        : users.filter((user) => user.status === statusFilter),
    [statusFilter, users],
  );

  const updateDetail = async (
    action: () => Promise<AdminGenerationUsage>,
  ): Promise<boolean> => {
    if (!detail) return false;
    const requestId = ++requestSequenceRef.current;
    detailRequestIdRef.current = requestId;
    const usersRequestId = usersRequestIdRef.current;
    setLoading(true);
    setError(null);
    try {
      await action();
      const updated = await getAdminUser(detail.id);
      if (detailRequestIdRef.current !== requestId) return false;
      setDetail(updated);
      if (usersRequestIdRef.current === usersRequestId) {
        setUsers((current) =>
          current.map((user) =>
            user.id === updated.id ? { ...user, ...updated } : user,
          ),
        );
      }
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
    const usersRequestId = usersRequestIdRef.current;
    setLoading(true);
    setError(null);
    try {
      await action();
      const updated = await getAdminUser(detail.id);
      if (detailRequestIdRef.current !== requestId) return false;
      setDetail(updated);
      if (usersRequestIdRef.current === usersRequestId) {
        setUsers((current) =>
          current.map((user) =>
            user.id === updated.id ? { ...user, ...updated } : user,
          ),
        );
      }
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

  return (
    <div css={pageStyle(isHistoryView)}>
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
              view === 'users'
                ? '사용자 관리'
                : view === 'failed'
                  ? '실패 이력'
                  : '생성 이력'
            }
            onBack={() => navigate('dashboard')}
          />
        )}
        {view === 'dashboard' && (
          <OperationCard
            failedGenerationCount={failedGenerationCount}
            onClick={() => navigate('failed')}
          />
        )}
        {(view === 'dashboard' || view === 'users') && (
          <UserSearchCard
            users={visibleUsers}
            query={query}
            loading={loading}
            error={error}
            statusFilter={statusFilter}
            onQueryChange={setQuery}
            onStatusChange={setStatusFilter}
            onSearch={(event) => {
              event.preventDefault();
              setSubmittedQuery(query);
              if (view === 'dashboard') void loadUsers(query);
            }}
            onSelect={selectUser}
          />
        )}
        {(view === 'dashboard' ||
          view === 'generations' ||
          view === 'failed') && (
          <HistoryCard
            generations={generations}
            loading={loading}
            error={error}
            failedOnly={view === 'failed'}
            date={historyDate}
            onDateChange={(date) => {
              setHistoryDate(date);
            }}
            onFailedOnly={() => navigate('failed')}
            onAll={() => navigate('generations')}
          />
        )}
      </main>

      {!isHistoryView && (
        <DetailPanel
          detail={detail}
          loading={loading}
          error={error}
          showEmptyDetail={view === 'dashboard' || view === 'users'}
          onRestore={handleRestore}
          onReset={handleReset}
          onSetGenerationLimit={handleSetGenerationLimit}
        />
      )}
      {usageModal && detail && (
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

const UserSearchCard = ({
  users,
  query,
  loading,
  error,
  statusFilter,
  onQueryChange,
  onStatusChange,
  onSearch,
  onSelect,
}: {
  users: AdminUserSummary[];
  query: string;
  loading: boolean;
  error: string | null;
  statusFilter: UserStatusFilter;
  onQueryChange: (value: string) => void;
  onStatusChange: (value: UserStatusFilter) => void;
  onSearch: (event: FormEvent<HTMLFormElement>) => void;
  onSelect: (userId: string) => void;
}) => (
  <section css={sectionCardStyle}>
    <div css={userSearchHeadingStyle}>
      <img src={adminCharacterUserSearch} alt="" />
      <div css={searchHeadingContentStyle}>
        <h2>사용자 검색</h2>
        <form css={searchFormStyle} onSubmit={onSearch}>
          <img src={searchIcon} alt="" />
          <input
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder="이름 또는 사용자 ID 검색"
          />
          <button type="submit">검색</button>
        </form>
      </div>
    </div>
    <select
      css={statusSelectStyle}
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
    {error ? (
      <ErrorMessage message={error} />
    ) : loading && users.length === 0 ? (
      <LoadingMessage />
    ) : (
      <UserTable users={users} onSelect={onSelect} />
    )}
  </section>
);

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
          <th>계정 상태</th>
          <th>오늘 남은 생성 횟수</th>
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
            <td>
              <StatusPill
                status={user.status === 'ACTIVE' ? '활성' : '비활성'}
              />
            </td>
            <td>
              {user.generationUsage.remainingCount} /{' '}
              {user.generationUsage.limitCount}
            </td>
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
            <td colSpan={4} css={emptyCellStyle}>
              검색 결과가 없습니다.
            </td>
          </tr>
        )}
      </tbody>
    </table>
  </div>
);

const HistoryCard = ({
  generations,
  loading,
  error,
  failedOnly,
  date,
  onDateChange,
  onFailedOnly,
  onAll,
}: {
  generations: GenerationHistory[];
  loading: boolean;
  error: string | null;
  failedOnly: boolean;
  date: string;
  onDateChange: (value: string) => void;
  onFailedOnly: () => void;
  onAll: () => void;
}) => (
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
      <label css={dateLabelStyle}>
        조회 날짜
        <input
          type="date"
          value={date}
          onChange={(event) => onDateChange(event.target.value)}
        />
      </label>
    </div>
    {error ? (
      <ErrorMessage message={error} />
    ) : loading && generations.length === 0 ? (
      <LoadingMessage />
    ) : (
      <GenerationTable generations={generations} />
    )}
  </section>
);

const GenerationTable = ({
  generations,
}: {
  generations: GenerationHistory[];
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
          </tr>
        ))}
        {generations.length === 0 && (
          <tr>
            <td colSpan={5} css={emptyCellStyle}>
              생성 이력이 없습니다.
            </td>
          </tr>
        )}
      </tbody>
    </table>
  </div>
);

const DetailPanel = ({
  detail,
  loading,
  error,
  showEmptyDetail,
  onRestore,
  onReset,
  onSetGenerationLimit,
}: {
  detail: AdminUserDetail | null;
  loading: boolean;
  error: string | null;
  showEmptyDetail: boolean;
  onRestore: () => void;
  onReset: () => void;
  onSetGenerationLimit: () => void;
}) => (
  <aside className="detail-aside" css={detailAsideStyle}>
    <div css={posthogStyle}>
      <PostHogLink />
    </div>
    {detail ? (
      <section css={detailCardStyle}>
        <div css={detailHeadingStyle}>
          <img src={adminCharacterUserDetail} alt="" />
          <h2>사용자 상세</h2>
        </div>
        <dl css={detailInfoStyle}>
          <dt>이름</dt>
          <dd>{detail.name}</dd>
          <dt>사용자 ID</dt>
          <dd css={idCellStyle}>{detail.id}</dd>
          <dt>계정 상태</dt>
          <dd>
            <StatusPill
              status={detail.status === 'ACTIVE' ? '활성' : '비활성'}
            />
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
        {error && <ErrorMessage message={error} />}
      </section>
    ) : showEmptyDetail ? (
      <div css={emptyDetailStyle}>
        사용자를 선택하면
        <br />
        상세 정보를 확인할 수 있어요.
      </div>
    ) : null}
  </aside>
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
  max-width: none;
  overflow-x: hidden;
  overflow-y: auto;
  border: 1px solid #ddd8d1;
  border-radius: 8px;
  background: #fffefa;
  color: ${theme.colors.text.primary};
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
  @media (max-width: 1320px) {
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
  color: ${active ? theme.colors.text.brand : theme.colors.text.primary};
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
    color: ${theme.colors.text.secondary};
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
  background: white;
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
    color: ${theme.colors.text.secondary};
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
    background: white;
    color: ${theme.colors.text.primary};
    cursor: pointer;
    strong {
      color: ${theme.colors.text.brand};
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
  background: white;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
  &:focus-within {
    border-color: ${theme.colors.bg.brand};
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
    color: ${theme.colors.text.primary};
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
    background: ${theme.colors.bg.brand};
    color: white;
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
const statusSelectStyle = css`
  margin: 0 10px 12px;
  padding: 9px 12px;
  border: 1px solid #d5d0c9;
  border-radius: 8px;
  background: white;
  @media (max-width: 760px) {
    width: calc(100% - 12px);
    margin: 0 6px 10px;
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
    background: #f7f4ff;
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
  color: ${theme.colors.text.secondary}!important;
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
  border: 1px solid ${active ? theme.colors.bg.brand : '#d5d0c9'};
  border-radius: 8px;
  background: ${active ? '#eee9ff' : 'white'};
  color: ${active ? theme.colors.text.brand : theme.colors.text.primary};
  font-weight: ${active ? 700 : 400};
  cursor: pointer;
`;
const dateLabelStyle = css`
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  color: ${theme.colors.text.secondary};
  font-size: 13px;
  input {
    padding: 8px 10px;
    border: 1px solid #d5d0c9;
    border-radius: 8px;
    background: white;
    color: ${theme.colors.text.primary};
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
  color: ${status === 'FAILED' ? '#e7526b' : status === 'SUCCEEDED' ? '#4d8b48' : '#7355da'};
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
  border: 2px solid ${theme.colors.bg.brand};
  border-radius: 8px;
  color: ${theme.colors.text.brand};
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
  h3 {
    margin: 20px 0 12px;
    font-size: 16px;
  }
  @media (max-width: 760px) {
    padding: 16px;
  }
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
  color: ${theme.colors.text.secondary};
  dt,
  dd {
    margin: 0;
  }
  dd {
    color: ${theme.colors.text.primary};
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
  border: 1px solid ${theme.colors.bg.brand};
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
    color: ${accent ? theme.colors.text.brand : theme.colors.text.primary};
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
  color: white;
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
  color: ${theme.colors.text.brand};
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
  color: ${theme.colors.text.brand};
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
  color: ${theme.colors.text.secondary};
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
  color: ${theme.colors.text.secondary};
  font-size: 14px;
  line-height: 1.6;
  strong {
    color: ${theme.colors.text.primary};
  }
`;
const usageModalFieldStyle = css`
  display: grid;
  gap: 8px;
  margin-top: 18px;
  color: ${theme.colors.text.primary};
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
  background: white;
  &:focus-within {
    border-color: ${theme.colors.bg.brand};
    box-shadow: 0 0 0 3px rgb(139 112 232 / 12%);
  }
  input {
    width: 100%;
    min-width: 0;
    padding: 12px 0;
    border: 0;
    outline: 0;
    background: transparent;
    color: ${theme.colors.text.primary};
    font: inherit;
    font-size: 18px;
    font-weight: 700;
  }
  > span {
    flex: 0 0 auto;
    color: ${theme.colors.text.secondary};
    font-size: 14px;
    font-weight: 500;
  }
`;
const usageModalHintStyle = css`
  margin: 8px 0 0;
  color: ${theme.colors.text.secondary};
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
    color: ${theme.colors.text.secondary};
    font-size: 13px;
  }
`;
const usageModalErrorStyle = css`
  margin: 12px 0 0;
  color: ${theme.colors.text.danger};
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
  background: white;
  color: ${theme.colors.text.primary};
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
  color: ${reset ? '#3e3520' : 'white'};
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
  color: ${theme.colors.text.secondary};
  text-align: center;
  line-height: 1.7;
`;
const errorStyle = css`
  margin: 12px 0;
  padding: 12px;
  border-radius: 8px;
  background: #fff0f0;
  color: ${theme.colors.text.danger};
  font-size: 13px;
`;
const messageStyle = css`
  padding: 32px 0;
  color: ${theme.colors.text.secondary};
  text-align: center;
`;

export default AdminPage;
