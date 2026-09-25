import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import SettingsPage from './SettingsPage';

let roles: string[] = [];
const hasRole = (...wanted: string[]) =>
  roles.includes('PLATFORM_ADMIN') || wanted.some((role) => roles.includes(role));

jest.mock('../../auth/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) => selector({ hasRole }),
}));

function renderAs(...held: string[]) {
  roles = held;
  return render(
    <MemoryRouter>
      <SettingsPage />
    </MemoryRouter>
  );
}

function card(title: string) {
  return screen.queryByRole('link', { name: new RegExp(`^${title}`) });
}

describe('SettingsPage', () => {
  it('a policy author reaches Enforcement here, not the admin-only sources', () => {
    renderAs('POLICY_AUTHOR');
    expect(card('Enforcement')).toHaveAttribute('href', '/enforcement');
    expect(card('Service & build')).toHaveAttribute('href', '/settings/system');
    expect(card('Registered sources')).toBeNull();
    expect(card('Sync & reconcile')).toBeNull();
  });

  it('a data owner reaches it too', () => {
    renderAs('DATA_OWNER');
    expect(card('Enforcement')).toBeInTheDocument();
  });

  it('an auditor or requester does not', () => {
    renderAs('AUDITOR');
    expect(card('Enforcement')).toBeNull();
  });

  it('an administrator sees every card in the source group', () => {
    renderAs('PLATFORM_ADMIN');
    expect(card('Registered sources')).toBeInTheDocument();
    expect(card('Enforcement')).toBeInTheDocument();
    expect(card('Sync & reconcile')).toBeInTheDocument();
  });
});
