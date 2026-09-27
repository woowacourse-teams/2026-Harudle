import { NOTICE_ACKNOWLEDGED_STORAGE_STATE } from './storageState';

export const AUTHENTICATED_STORAGE_STATE = {
  ...NOTICE_ACKNOWLEDGED_STORAGE_STATE,
  origins: NOTICE_ACKNOWLEDGED_STORAGE_STATE.origins.map(
    (origin): (typeof NOTICE_ACKNOWLEDGED_STORAGE_STATE.origins)[number] => ({
      ...origin,
      localStorage: [
        ...origin.localStorage,
        {
          name: 'harudle.has-completed-oauth',
          value: 'true',
        },
      ],
    }),
  ),
};
