import { act, fireEvent, render, screen } from '@testing-library/react';
import BusyNote from './BusyNote';

const BUSY = {
  message: 'Too many of your queries are running right now: ARAK runs 2 at a time for one person.',
  retryAfterSeconds: 2,
};

describe('BusyNote', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('says nothing was run and why, without calling it a refusal', () => {
    render(<BusyNote busy={BUSY} />);

    expect(screen.getByRole('region', { name: 'Busy' })).toBeInTheDocument();
    expect(screen.getByText(BUSY.message)).toBeInTheDocument();
    expect(screen.getByText(/not a decision about your access/)).toBeInTheDocument();
    expect(screen.queryByText('Refused')).not.toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('offers the same statement again once the wait is over', () => {
    const onRetry = jest.fn();
    render(<BusyNote busy={BUSY} onRetry={onRetry} />);

    const button = screen.getByRole('button', { name: 'Try again in 2s' });
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(onRetry).not.toHaveBeenCalled();

    act(() => {
      jest.advanceTimersByTime(1_000);
    });
    expect(screen.getByRole('button', { name: 'Try again in 1s' })).toBeDisabled();

    act(() => {
      jest.advanceTimersByTime(1_000);
    });
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
