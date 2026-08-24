import './styles/reset.css';
import './styles/global.css';
import './assets/images/harudle-intro.jpg';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { BrowserRouter } from 'react-router';
import posthog from 'posthog-js';
import { PostHogProvider } from '@posthog/react';
import { PostHogErrorBoundary } from '@posthog/react';
import { isPostHogEnabled } from './shared/posthog';

const posthogKey = process.env.REACT_APP_POSTHOG_KEY;

const initializePostHog = (): boolean => {
  if (!isPostHogEnabled) {
    return false;
  }

  if (!posthogKey) {
    console.error('PostHog is enabled, but REACT_APP_POSTHOG_KEY is missing.');

    return false;
  }

  posthog.init(posthogKey, {
    api_host: 'https://e.harudle.com',
    ui_host: 'https://us.posthog.com',
    defaults: '2026-01-30',
  });

  return true;
};

const isPostHogInitialized = initializePostHog();

const root = document.getElementById('root');

const enableMocking = async () => {
  if (process.env.NODE_ENV !== 'development') {
    return;
  }

  const { worker } = await import('./mocks/browser');
  await worker.start({ onUnhandledRequest: 'bypass' });
};

void enableMocking().then(() => {
  if (!root) {
    return;
  }

  const app = (
    <BrowserRouter>
      <App />
    </BrowserRouter>
  );

  createRoot(root).render(
    <StrictMode>
      {isPostHogInitialized ? (
        <PostHogProvider client={posthog}>
          <PostHogErrorBoundary>{app}</PostHogErrorBoundary>
        </PostHogProvider>
      ) : (
        app
      )}
    </StrictMode>,
  );
});
