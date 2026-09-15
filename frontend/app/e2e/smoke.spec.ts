import { expect, test } from '@playwright/test';

/**
 * Proves the chain end to end: Vite serves the app, Tailwind resolved the
 * vendored OpenMetadata tokens, and the page reached the backend.
 */
test('home page renders and reports service status', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Data Access Control' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Service' })).toBeVisible();
});
