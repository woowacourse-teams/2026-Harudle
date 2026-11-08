import { adminModerationHandlers } from './adminModerationHandlers';
import { setupWorker } from 'msw/browser';
import { guestTrialHandlers } from './guestTrialHandlers';
import { guestFeedTrialHandlers } from './guestFeedTrialHandlers';
import { handlers } from './handlers';
import { feedHandlers } from './feedHandlers';
import { blockHandlers } from './blockHandlers';
import { notificationHandlers } from './notificationHandlers';

export const worker = setupWorker(
  ...adminModerationHandlers,
  ...guestFeedTrialHandlers,
  ...handlers,
  ...guestTrialHandlers,
  ...feedHandlers,
  ...blockHandlers,
  ...notificationHandlers,
);
