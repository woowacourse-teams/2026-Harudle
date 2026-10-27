import { describe, expect, it } from '@jest/globals';
import { isRefreshTokenResponse } from './auth';

describe('isRefreshTokenResponse', () => {
  it('Access Token 재발급 응답 형식을 확인한다', () => {
    const response = {
      accessToken: 'access-token',
      tokenType: 'Bearer',
      expiresIn: 1800,
    };

    expect(isRefreshTokenResponse(response)).toBe(true);
  });
});
