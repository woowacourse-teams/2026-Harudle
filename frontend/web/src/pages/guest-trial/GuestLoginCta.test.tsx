import { describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import GuestLoginCta, { KAKAO_OAUTH_PATH } from './GuestLoginCta';

jest.mock('../../assets/icons/kakao.svg', () => 'kakao.svg');

describe('게스트 로그인 CTA', () => {
  it('기존 Kakao OAuth 진입 경로로 연결한다', () => {
    render(<GuestLoginCta label="카카오로 로그인하기" />);

    expect(
      screen.getByRole('link', { name: '카카오로 로그인하기' }),
    ).toHaveAttribute('href', KAKAO_OAUTH_PATH);
  });
});
