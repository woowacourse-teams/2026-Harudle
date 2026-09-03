import { isMswEnabled } from '../shared/environment';

export const enableMocking = async () => {
  if (!isMswEnabled) {
    return;
  }

  const { worker } = await import('./browser');
  await worker.start({ onUnhandledRequest: 'bypass' });
};
