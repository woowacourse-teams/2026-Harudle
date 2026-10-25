export const PROFILE_IMAGES = [
  '문어',
  '카피바라',
  '다람쥐',
  '펭귄',
  '상어',
  '오리',
  '거북이',
  '박쥐',
  '기니피그',
  '고래',
].map((name, index) => ({
  code: index + 1,
  name,
  url: `/profile-images/v1/${index + 1}.png`,
}));
