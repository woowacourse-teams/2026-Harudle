import './styles/reset.css';
import './styles/global.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';

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

  createRoot(root).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
});
