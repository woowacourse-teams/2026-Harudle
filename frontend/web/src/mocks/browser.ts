import { setupWorker } from 'msw/browser';
import { guestTrialHandlers } from './guestTrialHandlers';
import { handlers } from './handlers';

export const worker = setupWorker(...handlers, ...guestTrialHandlers);
