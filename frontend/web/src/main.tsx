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
import { initializePostHog } from './shared/posthog';
import { DiaryGenerateProvider } from './pages/diary-generating/DiaryGenerateContext';
import { PwaInstallProvider } from './pages/setting/PwaInstallContext';
import { enableMocking } from './mocks/enableMocking';

const root = document.getElementById('root');

const isPostHogInitialized = initializePostHog();

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
      <PwaInstallProvider>
        <DiaryGenerateProvider>
          {isPostHogInitialized ? (
            <PostHogProvider client={posthog}>
              <PostHogErrorBoundary>{app}</PostHogErrorBoundary>
            </PostHogProvider>
          ) : (
            app
          )}
        </DiaryGenerateProvider>
      </PwaInstallProvider>
    </StrictMode>,
  );
});
