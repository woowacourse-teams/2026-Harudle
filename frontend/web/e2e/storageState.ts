// 일반 사용자 흐름은 현재 공지를 확인한 상태에서 시작한다.
export const NOTICE_ACKNOWLEDGED_STORAGE_STATE = {
  cookies: [],
  origins: [
    {
      origin: 'http://localhost:5173',
      localStorage: [
        {
          name: 'harudle:image-outage:2026-09-27:v3',
          value: 'acknowledged',
        },
      ],
    },
  ],
};
