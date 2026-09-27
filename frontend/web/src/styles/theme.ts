// 팔레트는 서비스에서 사용되는 색상들을 관리하고, 컴포넌트에서는 아래의 역할에 맞는 색상 토큰을 사용합니다.
const palette = {
  white: '#FFFFFF',
  purple: {
    500: '#8B6FE8',
    600: '#7355DA',
  },
  gray: {
    100: '#E8E6EB',
    600: '#6F6B79',
    900: '#111118',
  },
  red: '#F15B5B',
} as const;

export const theme = {
  colors: {
    background: {
      surface: palette.white,
      neutralSolid: palette.gray[900],
      neutralWeak: palette.gray[100],
      brandSolid: palette.purple[500],
      brandStrong: palette.purple[600],
      brandWeak: '#F8F6FF',
      criticalWeak: '#FFF7F7',
      kakao: '#FFD66B',
    },
    // 글자와 아이콘의 색상. 마스크 아이콘도 CSS 속성과 관계없이 전경입니다.
    foreground: {
      neutral: palette.gray[900],
      neutralMuted: palette.gray[600],
      brand: palette.purple[600],
      onBrand: palette.white,
      critical: palette.red,
      placeholder: '#8B8793',
    },
    stroke: {
      // 같은 값이어도 외곽선과 구분선은 독립적으로 변경할 수 있습니다.
      outline: palette.gray[100],
      divider: palette.gray[100],
      brandSolid: palette.purple[500],
      brandStrong: palette.purple[600],
      brandWeak: '#EFEBFA',
      critical: palette.red,
      focusRing: 'rgb(115 85 218 / 35%)',
    },
  },
} as const;
