import type React from 'react';
import { RouterProvider } from 'react-aria-components';
import { useHref, useNavigate } from 'react-router-dom';

/**
 * The router's `useHref`, except for a link that leaves the app.
 *
 * react-router resolves every string it is given as a route, so an absolute
 * URL — "Open in OpenMetadata" — came out as `/catalog/http:/host/...` and
 * opened this app's own 404 instead of the catalog. A scheme (`https:`,
 * `mailto:`) or a protocol-relative `//host` is somewhere else; it is handed
 * back untouched.
 */
export function isExternalHref(href: string): boolean {
  return /^([a-z][a-z\d+.-]*:|\/\/)/i.test(href);
}

function useRouterHref(href: string): string {
  // Called unconditionally: a hook may not be skipped on some renders.
  const routed = useHref(href);
  return isExternalHref(href) ? href : routed;
}

/**
 * Teaches react-aria's links to go through the router.
 *
 * Every `href` in the design system renders a react-aria Link, which without
 * this does a full page load — losing the React tree and, with it, the signed-in
 * state we just restored.
 */
export function AriaRouter({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate();
  return (
    <RouterProvider navigate={navigate} useHref={useRouterHref}>
      {children}
    </RouterProvider>
  );
}
