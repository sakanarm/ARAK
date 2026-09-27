import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import RequireAuth from './RequireAuth';
import { useAuthStore } from './authStore';

// Recorded rather than followed: what matters is not where the redirect goes
// but that the props handed to <Navigate> are the same objects on a second
// render. <Navigate> lists `state` in the dependency array of the effect that
// calls navigate(), so a fresh object each render means navigate() each
// render, which renders again -- and past twenty-five rounds React throws
// instead of warning, unmounts the tree, and leaves a blank page at /login.
const mockStates: unknown[] = [];
jest.mock('react-router-dom', () => ({
  ...jest.requireActual('react-router-dom'),
  Navigate: (props: { state?: unknown }) => {
    mockStates.push(props.state);
    return null;
  },
}));

describe('RequireAuth', () => {
  beforeEach(() => {
    mockStates.length = 0;
    useAuthStore.setState({
      token: null,
      user: null,
      initialising: false,
      mustChangePassword: false,
    });
  });

  function renderAt(path: string) {
    return render(
      <MemoryRouter initialEntries={[path]}>
        <RequireAuth>
          <p>guarded</p>
        </RequireAuth>
      </MemoryRouter>
    );
  }

  it('hands <Navigate> the same state object when it renders again', () => {
    const { rerender } = renderAt('/query');
    rerender(
      <MemoryRouter initialEntries={['/query']}>
        <RequireAuth>
          <p>guarded</p>
        </RequireAuth>
      </MemoryRouter>
    );

    expect(mockStates.length).toBeGreaterThan(1);
    for (const state of mockStates) {
      expect(state).toBe(mockStates[0]);
    }
  });

  it('remembers the query string and fragment, not only the path', () => {
    renderAt('/query?sql=select%201#results');

    expect(mockStates[0]).toEqual({
      from: { pathname: '/query', search: '?sql=select%201', hash: '#results' },
    });
  });

  it('renders what it guards once there is a token', () => {
    useAuthStore.setState({ token: 'token', initialising: false });
    const { getByText } = renderAt('/query');

    expect(getByText('guarded')).toBeInTheDocument();
    expect(mockStates).toHaveLength(0);
  });

  it('asks for a new password in place of the page while the old one was set by an administrator', () => {
    useAuthStore.setState({
      token: 'token',
      initialising: false,
      mustChangePassword: true,
      user: {
        id: '11111111-1111-1111-1111-111111111111',
        username: 'analyst_a',
        email: null,
        displayName: 'Analyst A',
        source: 'local',
        roles: [],
        scopes: [],
      },
    });
    const { getByRole, queryByText } = renderAt('/query');

    expect(getByRole('heading', { name: 'Choose a new password' })).toBeInTheDocument();
    expect(queryByText('guarded')).not.toBeInTheDocument();
    // In place, not a redirect: the address asked for is kept.
    expect(mockStates).toHaveLength(0);
  });
});
