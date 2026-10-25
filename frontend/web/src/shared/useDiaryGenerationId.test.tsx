import { afterEach, describe, expect, it } from '@jest/globals';
import { act, cleanup, renderHook } from '@testing-library/react';
import {
  removeGenerationId,
  setGenerationId,
} from '../domain/diary/diaryGenerationStore';
import { DIARY_GENERATION_ID_KEY } from './constants';
import useDiaryGenerationId from './useDiaryGenerationId';

const generationId = '00000000-0000-4000-8000-000000010001';

afterEach((): void => {
  cleanup();
  localStorage.clear();
});

describe('useDiaryGenerationId', (): void => {
  it('저장된 생성 ID를 초기값으로 읽는다', (): void => {
    localStorage.setItem(DIARY_GENERATION_ID_KEY, generationId);

    const { result } = renderHook(useDiaryGenerationId);

    expect(result.current).toBe(generationId);
  });

  it('같은 탭에서 ID를 저장하거나 삭제하면 마운트된 구독자들이 갱신된다', (): void => {
    const first = renderHook(useDiaryGenerationId);
    const second = renderHook(useDiaryGenerationId);

    expect(first.result.current).toBeNull();
    expect(second.result.current).toBeNull();

    act((): void => {
      setGenerationId(generationId);
    });

    expect(first.result.current).toBe(generationId);
    expect(second.result.current).toBe(generationId);

    first.unmount();
    act((): void => {
      removeGenerationId();
    });

    expect(second.result.current).toBeNull();
    expect(localStorage.getItem(DIARY_GENERATION_ID_KEY)).toBeNull();
  });
});
