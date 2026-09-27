import { fireEvent, render, screen } from '@testing-library/react';
import CachedNote from './CachedNote';

const READ_AT = '2026-09-27T08:00:00Z';

describe('CachedNote', () => {
  it('says the rows came from memory and how old the read is', () => {
    render(<CachedNote now={Date.parse(READ_AT) + 12_400} readAt={READ_AT} />);

    expect(screen.getByText('from cache · read 12s ago')).toBeInTheDocument();
    // Nothing to click without somewhere to send it.
    expect(screen.queryByRole('button', { name: 'Run fresh' })).not.toBeInTheDocument();
  });

  it('offers a fresh read of the same statement', () => {
    const onRunFresh = jest.fn();
    render(<CachedNote now={Date.parse(READ_AT)} onRunFresh={onRunFresh} readAt={READ_AT} />);

    fireEvent.click(screen.getByRole('button', { name: 'Run fresh' }));
    expect(onRunFresh).toHaveBeenCalledTimes(1);
    expect(screen.getByText('from cache · read 0s ago')).toBeInTheDocument();
  });

  it('still says it is from cache when the time is missing or ahead of the clock', () => {
    const { unmount } = render(<CachedNote readAt={null} />);
    expect(screen.getByText('from cache')).toBeInTheDocument();
    unmount();

    render(<CachedNote now={Date.parse(READ_AT) - 5_000} readAt={READ_AT} />);
    expect(screen.getByText('from cache · read 0s ago')).toBeInTheDocument();
  });
});
