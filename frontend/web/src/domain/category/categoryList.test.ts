import { describe, expect, it } from '@jest/globals';
import { isCategoryListResponse, type Category } from './categoryList';

const category: Category = {
  id: 1,
  name: '일상',
  sortOrder: 0,
  active: true,
};

describe('카테고리 목록 응답 검증', () => {
  it('카테고리 배열과 빈 배열을 허용한다', (): void => {
    expect(isCategoryListResponse([category])).toBe(true);
    expect(isCategoryListResponse([])).toBe(true);
  });

  it.each([
    null,
    { items: [category] },
    [null],
    [{ ...category, id: '1' }],
    [{ ...category, id: 0 }],
    [{ ...category, id: -1 }],
    [{ ...category, id: 1.5 }],
    [{ ...category, id: NaN }],
    [{ ...category, id: Number.MAX_SAFE_INTEGER + 1 }],
    [{ ...category, name: undefined }],
    [{ ...category, sortOrder: '0' }],
    [{ ...category, sortOrder: -1 }],
    [{ ...category, sortOrder: 1.5 }],
    [{ ...category, sortOrder: NaN }],
    [{ ...category, active: 'true' }],
  ])('잘못된 배열과 항목을 거부한다: %j', (value): void => {
    expect(isCategoryListResponse(value)).toBe(false);
  });
});
