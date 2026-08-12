import { useNavigate } from 'react-router';
import ActionButton from '../../shared/ActionButton';

const DiaryEmptyState = () => {
  const navigate = useNavigate();
  return (
    <div>
      <div>아직 기록이 없어요.</div>
      <ActionButton
        onClick={() => {
          navigate('/diary-write');
        }}
      />
    </div>
  );
};

export default DiaryEmptyState;
