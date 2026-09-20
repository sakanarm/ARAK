import { act } from '@testing-library/react';
import { AUTH_SPLASH_MS, useAuthSplash } from './AuthSplash';

describe('useAuthSplash', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    useAuthSplash.getState().hide();
  });
  afterEach(() => jest.useRealTimers());

  // The login form is unmounted by its own redirect the moment the token
  // lands, long before the curtain is due to come down. If lowering it were
  // the caller's job, the caller would already be gone -- and the curtain is
  // an opaque full-screen layer, so one that never lowers is a blank page.
  it('lowers itself after the delay, with no help from the caller', () => {
    act(() => {
      useAuthSplash.getState().show('signing-in');
      useAuthSplash.getState().hideAfter(AUTH_SPLASH_MS);
    });
    expect(useAuthSplash.getState().phase).toBe('signing-in');

    act(() => void jest.advanceTimersByTime(AUTH_SPLASH_MS - 1));
    expect(useAuthSplash.getState().phase).toBe('signing-in');

    act(() => void jest.advanceTimersByTime(1));
    expect(useAuthSplash.getState().phase).toBe('idle');
  });

  it('does not let an old timer take down a curtain raised since', () => {
    act(() => useAuthSplash.getState().hideAfter(AUTH_SPLASH_MS));
    act(() => useAuthSplash.getState().show('signing-out'));
    act(() => void jest.advanceTimersByTime(AUTH_SPLASH_MS * 3));

    expect(useAuthSplash.getState().phase).toBe('signing-out');
  });

  it('comes down at once when asked directly, for a rejected password', () => {
    act(() => {
      useAuthSplash.getState().show('signing-in');
      useAuthSplash.getState().hideAfter(AUTH_SPLASH_MS);
      useAuthSplash.getState().hide();
    });

    expect(useAuthSplash.getState().phase).toBe('idle');
  });
});
