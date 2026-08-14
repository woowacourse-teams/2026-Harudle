import BottomNavigation from '../../shared/BottomNavigation';

const SettingPage = () => {
  return (
    <div>
      <div>설정</div>
      <div>
        <div>계정</div>
        <div>
          <span>이름</span>
          <span>정이현</span>
        </div>
        <div>
          <span>소셜 계정</span>
          <span>Kakao</span>
        </div>
      </div>

      <button>로그아웃</button>

      <BottomNavigation />
    </div>
  );
};

export default SettingPage;
