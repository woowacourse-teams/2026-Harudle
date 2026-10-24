import { adminModerationHandlers } from './adminModerationHandlers';
import { setupWorker } from 'msw/browser';
import { guestTrialHandlers } from './guestTrialHandlers';
import { handlers } from './handlers';
import { feedHandlers } from './feedHandlers';

export const worker = setupWorker(
  ...adminModerationHandlers,
  ...handlers,
  ...guestTrialHandlers,
  ...feedHandlers,
);
