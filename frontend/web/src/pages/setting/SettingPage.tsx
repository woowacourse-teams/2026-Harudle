import BottomNavigation from '../../shared/BottomNavigation';
import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import harudleLogo from '../../assets/images/harudle-logo.png';
import { useNavigate } from 'react-router';
import { useEffect, useState } from 'react';
import { API_BASE_URL, type ApiRequest } from '../../shared/api';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import { authFetch, logout } from '../../shared/auth';
import { throwIfResponseFailed, toUserError } from '../../shared/apiError';

interface ProfileResponse {
  id: string;
  name: string;
  email?: string;
  oauthProviders: string[];
  createdAt: string;
}

const isProfileResponse = (value: unknown): value is ProfileResponse => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'id' in value &&
    typeof value.id === 'string' &&
    'name' in value &&
    typeof value.name === 'string' &&
    'email' in value &&
    'oauthProviders' in value &&
    Array.isArray(value.oauthProviders) &&
    value.oauthProviders.every((provider) => typeof provider === 'string') &&
    'createdAt' in value &&
    typeof value.createdAt === 'string'
  );
};

const SettingPage = () => {
  const navigate = useNavigate();
  const [profile, setProfile] = useState<ApiRequest<ProfileResponse>>({
    status: 'idle',
  });
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const [logoutError, setLogoutError] = useState<string | null>(null);

  useEffect(() => {
    const getProfile = async (): Promise<void> => {
      setProfile({ status: 'loading' });

      try {
        const response = await authFetch(`${API_BASE_URL}/me`);

        await throwIfResponseFailed(
          response,
          '사용자 정보를 불러오지 못했습니다.',
        );

        const data: unknown = await response.json();

        if (!isProfileResponse(data)) {
          throw new Error('사용자 정보를 불러오지 못했습니다.');
        }

        setProfile({ status: 'success', data });
      } catch (error: unknown) {
        setProfile({
          status: 'error',
          error: toUserError(error, '사용자 정보를 불러오지 못했습니다.'),
        });
      }
    };

    void getProfile();
  }, []);

  const handleLogout = async () => {
    setIsLoggingOut(true);
    setLogoutError(null);

    try {
      await logout();
      navigate('/login', { replace: true });
    } catch (error: unknown) {
      setLogoutError(toUserError(error, '로그아웃하지 못했습니다.').message);
      setIsLoggingOut(false);
    }
  };

  return (
    <div css={settingPageStyle}>
      <header css={pageHeaderStyle}>
        <button css={logoButtonStyle} onClick={() => navigate('/')}>
          <img src={harudleLogo} alt="하루들" css={logoStyle} />
        </button>
      </header>

      <main css={settingContentStyle}>
        {profile.status === 'idle' || profile.status === 'loading' ? (
          <div css={feedbackStyle}>
            <img src={loadingAnimation} alt="로딩 중" css={loadingImageStyle} />
          </div>
        ) : profile.status === 'error' ? (
          <div css={feedbackStyle}>{profile.error.message}</div>
        ) : (
          <>
            <div css={pageTitleStyle}>설정</div>

            <div css={accountCardStyle}>
              <div css={settingRowStyle}>
                <span css={settingLabelStyle}>이름</span>
                <span css={settingValueStyle}>
                  <span>{profile.data.name}</span>
                </span>
              </div>

              <div css={settingRowStyle}>
                <span css={settingLabelStyle}>소셜 계정</span>
                <span css={settingValueStyle}>
                  <span>{profile.data.oauthProviders.join(', ')}</span>
                </span>
              </div>
            </div>

            <button
              type="button"
              css={logoutButtonStyle}
              disabled={isLoggingOut}
              onClick={() => void handleLogout()}
            >
              로그아웃
            </button>
            {logoutError && <div css={logoutErrorStyle}>{logoutError}</div>}
          </>
        )}
      </main>

      <BottomNavigation />
    </div>
  );
};

export default SettingPage;

const settingPageStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background-color: ${theme.colors.background};
`;

const pageHeaderStyle = css`
  width: 100%;
  height: 71px;
  padding: 0 20px;
  box-sizing: border-box;
  overflow: hidden;
`;

const logoButtonStyle = css`
  display: flex;
  align-items: center;
  width: 100%;
  height: 71px;
  border: none;
  background-color: transparent;
  cursor: pointer;
`;

const logoStyle = css`
  width: 106px;
  height: 71px;
`;

const settingContentStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  width: 342px;
  overflow: hidden;
`;

const pageTitleStyle = css`
  color: ${theme.colors.textPrimary};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 22px;
  font-weight: 700;
  line-height: 32px;
`;

const accountCardStyle = css`
  width: 100%;
  height: 120px;
  overflow: hidden;
  border: 1px solid ${theme.colors.border};
  border-radius: 16px;
  background-color: ${theme.colors.background};
  box-sizing: border-box;
`;

const settingRowStyle = css`
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  height: 60px;
  padding: 0 12px 0 16px;
  box-sizing: border-box;

  &:first-of-type {
    border-bottom: 1px solid ${theme.colors.border};
  }
`;

const settingLabelStyle = css`
  color: ${theme.colors.textPrimary};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 15px;
  font-weight: 400;
  line-height: 24px;
`;

const settingValueStyle = css`
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  width: 150px;
  height: 32px;
  color: ${theme.colors.textSecondary};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 15px;
  font-weight: 400;
  line-height: 24px;
  text-align: right;
  text-transform: capitalize;
`;

const logoutButtonStyle = css`
  width: 100%;
  height: 56px;
  padding: 0 16px;
  border: 1px solid ${theme.colors.border};
  border-radius: 16px;
  background-color: ${theme.colors.background};
  color: ${theme.colors.danger};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 15px;
  font-weight: 500;
  line-height: 24px;
  text-align: left;
  box-sizing: border-box;
  cursor: pointer;

  &:active {
    background-color: #fff7f7;
  }

  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
`;

const logoutErrorStyle = css`
  color: ${theme.colors.danger};
  font-size: 13px;
  line-height: 20px;
`;

const feedbackStyle = css`
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  color: ${theme.colors.textPrimary};
  font-size: 16px;
  line-height: 26px;
  background-color: ${theme.colors.background};
`;

const loadingImageStyle = css`
  width: 160px;
  height: 160px;
`;
