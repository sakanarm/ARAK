import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { FEATURE_ROLES, FeatureAccessSection, toggleRole } from './LlmSettingsPage';

const fetchFeatureAccess = jest.fn();
const saveFeatureAccess = jest.fn();

jest.mock('../../api/llm', () => ({
  fetchFeatureAccess: () => fetchFeatureAccess(),
  saveFeatureAccess: (...args: unknown[]) => saveFeatureAccess(...args),
}));

jest.mock('../../auth/authStore', () => ({
  useAuthStore: (selector: (state: { hasRole: () => boolean }) => unknown) =>
    selector({ hasRole: () => true }),
}));

const ROWS = [
  {
    feature: 'CHAT',
    label: 'Chat with ARAK',
    description: 'The conversation in the corner.',
    roles: ['EVERYONE'],
    configured: false,
    updatedAt: null,
    updatedBy: null,
  },
  {
    feature: 'INSIGHTS',
    label: 'Answer from the logs',
    description: 'Query log and dashboard figures.',
    roles: ['PLATFORM_ADMIN', 'AUDITOR'],
    configured: true,
    updatedAt: '2026-09-20T00:00:00Z',
    updatedBy: 'admin',
  },
  {
    feature: 'DRAFT_POLICY',
    label: 'Draft policies',
    description: 'A sentence becomes a draft.',
    roles: [],
    configured: true,
    updatedAt: '2026-09-20T00:00:00Z',
    updatedBy: 'admin',
  },
];

function renderMatrix() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <FeatureAccessSection />
    </QueryClientProvider>
  );
}

function boxesOf(label: string) {
  return screen
    .getAllByRole('checkbox')
    .filter((box) => box.getAttribute('aria-label')?.startsWith(`${label}: `));
}

beforeEach(() => {
  fetchFeatureAccess.mockReset().mockResolvedValue(ROWS);
  saveFeatureAccess.mockReset();
});

describe('toggleRole', () => {
  it('ticking everyone replaces the list', () => {
    expect(toggleRole(['AUDITOR'], 'EVERYONE', true)).toEqual(['EVERYONE']);
  });

  it('unticking everyone ticks every role instead of switching the job off', () => {
    expect(toggleRole(['EVERYONE'], 'EVERYONE', false)).toEqual([...FEATURE_ROLES]);
  });

  it('adds and removes a role, in the server order', () => {
    expect(toggleRole(['REQUESTER'], 'PLATFORM_ADMIN', true)).toEqual([
      'PLATFORM_ADMIN',
      'REQUESTER',
    ]);
    expect(toggleRole(['PLATFORM_ADMIN', 'AUDITOR'], 'AUDITOR', false)).toEqual([
      'PLATFORM_ADMIN',
    ]);
    expect(toggleRole(['AUDITOR'], 'AUDITOR', true)).toEqual(['AUDITOR']);
  });

  it('takes one role off everyone by listing the rest', () => {
    expect(toggleRole(['EVERYONE'], 'REQUESTER', false)).toEqual([
      'PLATFORM_ADMIN',
      'POLICY_AUTHOR',
      'DATA_OWNER',
      'AUDITOR',
    ]);
  });
});

describe('FeatureAccessSection', () => {
  it('draws each job with its roles ticked', async () => {
    renderMatrix();

    const chatEveryone = await screen.findByLabelText('Chat with ARAK: everyone');
    expect(chatEveryone).toBeChecked();
    // Under everyone the role boxes show ticked and cannot be changed one by one.
    const chatRoles = boxesOf('Chat with ARAK').filter((box) => box !== chatEveryone);
    expect(chatRoles).toHaveLength(FEATURE_ROLES.length);
    chatRoles.forEach((box) => {
      expect(box).toBeChecked();
      expect(box).toBeDisabled();
    });

    expect(screen.getByLabelText('Answer from the logs: everyone')).not.toBeChecked();
    expect(screen.getByText('off for everybody')).toBeInTheDocument();
  });

  it('saves the new list when a box is ticked', async () => {
    saveFeatureAccess.mockResolvedValue({ ...ROWS[2], roles: ['POLICY_AUTHOR'] });
    renderMatrix();

    await screen.findByLabelText('Draft policies: everyone');
    // everyone, platform admin, policy author, ...
    fireEvent.click(boxesOf('Draft policies')[2]);

    await waitFor(() =>
      expect(saveFeatureAccess).toHaveBeenCalledWith('DRAFT_POLICY', ['POLICY_AUTHOR'])
    );
    await waitFor(() => expect(screen.queryByText('off for everybody')).not.toBeInTheDocument());
  });

  it('says so when a change is refused', async () => {
    saveFeatureAccess.mockRejectedValue(new Error('nope'));
    renderMatrix();

    fireEvent.click(await screen.findByLabelText('Answer from the logs: everyone'));

    expect(await screen.findByText('That change could not be saved.')).toBeInTheDocument();
    expect(saveFeatureAccess).toHaveBeenCalledWith('INSIGHTS', ['EVERYONE']);
  });
});
