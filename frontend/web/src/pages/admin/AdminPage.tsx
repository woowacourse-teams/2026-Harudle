import { css } from '@emotion/react';
import { useCallback, useEffect, useMemo, useState } from 'react';
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
  setAdminUsage,
  type AdminUserDetail,
  type AdminUserSummary,
  type GenerationHistory,
  type GenerationStatus,
} from './adminApi';

type View = 'dashboard' | 'users' | 'generations' | 'failed';
type UserStatusFilter = 'ALL' | 'ACTIVE' | 'DELETED';

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
  const [detail, setDetail] = useState<AdminUserDetail | null>(null);
  const [query, setQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<UserStatusFilter>('ALL');
  const [historyDate, setHistoryDate] = useState(today);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadUsers = useCallback(
    async (nextQuery = query) => {
      setLoading(true);
      setError(null);
      try {
        const result = await searchAdminUsers(nextQuery);
        setUsers(result.content);
      } catch (cause) {
        setError(
          cause instanceof Error
            ? cause.message
            : '사용자를 불러오지 못했습니다.',
        );
      } finally {
        setLoading(false);
      }
    },
    [query],
  );

  const loadGenerations = useCallback(
    async (status?: GenerationStatus) => {
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
        setGenerations(result.content);
      } catch (cause) {
        setError(
          cause instanceof Error
            ? cause.message
            : '생성 이력을 불러오지 못했습니다.',
        );
      } finally {
        setLoading(false);
      }
    },
    [historyDate],
  );

  const loadDashboard = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [userPage, generationPage] = await Promise.all([
        searchAdminUsers('', 0, 20),
        searchAdminGenerations({
          page: 0,
          size: 20,
          from: historyDate,
          to: historyDate,
        }),
      ]);
      setUsers(userPage.content);
      setGenerations(generationPage.content);
      if (userPage.content.length > 0) {
        setDetail(await getAdminUser(userPage.content[0].id));
      }
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : '관리자 데이터를 불러오지 못했습니다.',
      );
    } finally {
      setLoading(false);
    }
  }, [historyDate]);

  useEffect(() => {
    // URL 상태 변화에 맞춰 관리자 API를 조회한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (view === 'dashboard') void loadDashboard();
    else if (view === 'users') void loadUsers();
    else void loadGenerations(view === 'failed' ? 'FAILED' : undefined);
  }, [loadDashboard, loadGenerations, loadUsers, view]);

  const navigate = (nextView: View) => {
    setDetail(null);
    setSearchParams(nextView === 'dashboard' ? {} : { view: nextView });
  };

  const selectUser = async (userId: string) => {
    setLoading(true);
    setError(null);
    try {
      setDetail(await getAdminUser(userId));
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : '사용자 정보를 불러오지 못했습니다.',
      );
    } finally {
      setLoading(false);
    }
  };

  const visibleUsers = useMemo(
    () =>
      statusFilter === 'ALL'
        ? users
        : users.filter((user) => user.status === statusFilter),
    [statusFilter, users],
  );

  const updateDetail = async (action: () => Promise<AdminUserDetail>) => {
    setLoading(true);
    setError(null);
    try {
      const updated = await action();
      setDetail(updated);
      setUsers((current) =>
        current.map((user) =>
          user.id === updated.id ? { ...user, ...updated } : user,
        ),
      );
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : '생성 사용량을 변경하지 못했습니다.',
      );
    } finally {
      setLoading(false);
    }
  };

  const updateGenerationLimit = async (action: () => Promise<void>) => {
    if (!detail) return;
    setLoading(true);
    setError(null);
    try {
      await action();
      const updated = await getAdminUser(detail.id);
      setDetail(updated);
      setUsers((current) =>
        current.map((user) =>
          user.id === updated.id ? { ...user, ...updated } : user,
        ),
      );
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : '생성 한도를 변경하지 못했습니다.',
      );
    } finally {
      setLoading(false);
    }
  };

  const handleRestore = () => {
    if (!detail || detail.status !== 'ACTIVE') return;
    const value = window.prompt('복구할 생성 횟수를 입력하세요.', '1');
    if (value === null) return;
    const count = Number(value);
    if (!Number.isInteger(count) || count < 1) {
      window.alert('1 이상의 정수를 입력해주세요.');
      return;
    }
    void updateDetail(() => restoreAdminUsage(detail.id, count));
  };

  const handleReset = () => {
    if (!detail || detail.status !== 'ACTIVE') return;
    if (window.confirm('오늘 사용량을 전체 초기화할까요?')) {
      void updateDetail(() => resetAdminUsage(detail.id));
    }
  };

  const handleSetUsage = () => {
    if (!detail || detail.status !== 'ACTIVE') return;
    const value = window.prompt(
      '오늘 사용한 생성 횟수를 입력하세요.',
      String(detail.usedGenerationCount),
    );
    if (value === null) return;
    const usedCount = Number(value);
    if (
      !Number.isInteger(usedCount) ||
      usedCount < 0 ||
      usedCount > detail.dailyGenerationLimit
    ) {
      window.alert(
        `0부터 ${detail.dailyGenerationLimit} 사이의 정수를 입력해주세요.`,
      );
      return;
    }
    void updateDetail(() => setAdminUsage(detail.id, usedCount));
  };

  const handleSetGenerationLimit = () => {
    if (!detail || detail.status !== 'ACTIVE') return;
    const value = window.prompt(
      '오늘 생성 한도를 입력하세요.',
      String(detail.dailyGenerationLimit),
    );
    if (value === null) return;
    const limitCount = Number(value);
    if (
      !Number.isInteger(limitCount) ||
      limitCount < detail.usedGenerationCount
    ) {
      window.alert(
        `현재 사용 횟수(${detail.usedGenerationCount}) 이상인 정수를 입력해주세요.`,
      );
      return;
    }
    void updateGenerationLimit(() =>
      setAdminGenerationLimit(detail.id, limitCount),
    );
  };

  return (
    <div css={pageStyle(isHistoryView)}>
      <aside css={sidebarStyle}>
        <img src={logo} alt="Harudle" css={logoStyle} />
        <nav css={menuStyle} aria-label="관리자 메뉴">
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
          <OperationCard onClick={() => navigate('failed')} />
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
              void loadUsers(query);
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
              void loadGenerations(view === 'failed' ? 'FAILED' : undefined);
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
          onSetUsage={handleSetUsage}
          onSetGenerationLimit={handleSetGenerationLimit}
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

const OperationCard = ({ onClick }: { onClick: () => void }) => (
  <section css={operationCardStyle}>
    <img src={operationAlertIcon} alt="" />
    <div>
      <h2>운영 확인 필요</h2>
      <button type="button" onClick={onClick}>
        <span>실패한 생성</span>
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
            <td>{user.remainingGenerationCount} / 오늘</td>
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
            <td>{generation.failureCode ?? '-'}</td>
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
  onSetUsage,
  onSetGenerationLimit,
}: {
  detail: AdminUserDetail | null;
  loading: boolean;
  error: string | null;
  showEmptyDetail: boolean;
  onRestore: () => void;
  onReset: () => void;
  onSetUsage: () => void;
  onSetGenerationLimit: () => void;
}) => (
  <aside css={detailAsideStyle}>
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
          <Metric label="사용 횟수" value={detail.usedGenerationCount} />
          <Metric label="생성 한도" value={detail.dailyGenerationLimit} />
          <Metric
            label="남은 횟수"
            value={detail.remainingGenerationCount}
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
          선택 횟수 복구
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
            onClick={onSetUsage}
          >
            사용 횟수 지정
          </button>
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
  min-height: 100vh;
  margin-left: calc(50% - 50vw);
  display: grid;
  grid-template-columns: ${
    wideContent ? '250px minmax(620px, 1fr)' : '250px minmax(620px, 1fr) 430px'
  };
  max-width: 1500px;
  margin-right: auto;
  overflow: hidden;
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
  @media (max-width: 1100px) {
    grid-template-columns: 210px minmax(0, 1fr);
    .detail-aside {
      display: none;
    }
  }
  @media (max-width: 760px) {
    display: block;
    min-height: 100vh;
    .sidebar {
      display: none;
    }
    .detail-aside {
      display: none;
    }
  }
`;
const sidebarStyle = css`
  display: flex;
  flex-direction: column;
  padding: 38px 20px 24px;
  border-right: 1px solid #ddd8d1;
  background: #fffefa;
`;
const logoStyle = css`
  width: 190px;
  height: 92px;
  object-fit: contain;
`;
const menuStyle = css`
  display: grid;
  gap: 10px;
  margin-top: 20px;
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
`;
const sectionCardStyle = css`
  margin-bottom: 14px;
  padding: 14px 10px 12px;
  border: 1px solid #ddd8d1;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.76);
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
`;
const statusSelectStyle = css`
  margin: 0 10px 12px;
  padding: 9px 12px;
  border: 1px solid #d5d0c9;
  border-radius: 8px;
  background: white;
`;
const tableScrollStyle = css`
  overflow-x: auto;
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
`;
const posthogStyle = css`
  display: flex;
  justify-content: flex-end;
  margin-bottom: 88px;
`;
const historyPosthogStyle = css`
  position: absolute;
  top: 34px;
  right: 38px;
`;
const posthogLinkStyle = css`
  display: inline-block;
  padding: 13px 18px;
  border: 2px solid ${theme.colors.bg.brand};
  border-radius: 8px;
  color: ${theme.colors.text.brand};
  font-weight: 800;
  text-decoration: none;
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
  padding: 10px 8px;
  border: 1px solid #d5ccef;
  border-radius: 8px;
  background: #faf8ff;
  color: ${theme.colors.text.brand};
  font-size: 13px;
  font-weight: 700;
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
