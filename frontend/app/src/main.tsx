import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-aria-components';
import { BrowserRouter, useHref, useNavigate } from 'react-router-dom';
import App from './App';
import './styles/index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Catalog metadata changes through webhooks, not through polling; a stale
      // screen is refreshed by an event, not by a timer.
      refetchOnWindowFocus: false,
      retry: 1,
      staleTime: 30_000,
    },
  },
});

/**
 * Teaches react-aria's links to go through the router.
 *
 * Every `href` in the design system renders a react-aria Link, which without
 * this does a full page load — losing the React tree and, with it, the signed-in
 * state we just restored.
 */
function AriaRouter({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate();
  return (
    <RouterProvider navigate={navigate} useHref={useHref}>
      {children}
    </RouterProvider>
  );
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AriaRouter>
          <App />
        </AriaRouter>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>
);
