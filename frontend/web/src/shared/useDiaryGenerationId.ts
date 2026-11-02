import { useSyncExternalStore } from 'react';
import {
  getGenerationId,
  subscribeGenerationId,
} from '../domain/diary/diaryGenerationStore';

const useDiaryGenerationId = (): string | null => {
  return useSyncExternalStore(subscribeGenerationId, getGenerationId);
};

export default useDiaryGenerationId;
