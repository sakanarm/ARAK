import { act, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { HeightSplitter, readSize, WidthSplitter } from './Splitters';

const KEY = 'arak.test.size';

/** A row or column of a given size, since jsdom lays nothing out. */
function sized(element: HTMLElement | null, size: { width?: number; height?: number }) {
  if (!element) {
    throw new Error('The grip has no parent to measure');
  }
  Object.defineProperty(element, 'clientWidth', { value: size.width ?? 0, configurable: true });
  Object.defineProperty(element, 'clientHeight', { value: size.height ?? 0, configurable: true });
}

/** jsdom has no PointerEvent; a mouse event of the pointer type carries clientX. */
function pointer(target: EventTarget, type: string, clientX: number) {
  act(() => {
    target.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, clientX }));
  });
}

function Widths({ panel, start = 300 }: { panel: 'left' | 'right'; start?: number }) {
  const [width, setWidth] = useState(start);
  return (
    <div data-testid="row">
      <WidthSplitter
        label="Resize the panel"
        max={(row) => (row ? row.clientWidth - 200 : width)}
        min={240}
        onChange={setWidth}
        panel={panel}
        storageKey={KEY}
        width={width}
      />
    </div>
  );
}

function Heights({ start = 200 }: { start?: number }) {
  const [height, setHeight] = useState(start);
  return (
    <div>
      <HeightSplitter
        height={height}
        label="Resize the editor"
        min={40}
        minBelow={96}
        onChange={setHeight}
        storageKey={KEY}
      />
    </div>
  );
}

beforeEach(() => window.localStorage.clear());

describe('readSize', () => {
  it('falls back on a first visit, and on anything below the floor', () => {
    expect(readSize(KEY, 200, 40)).toBe(200);
    window.localStorage.setItem(KEY, '12');
    expect(readSize(KEY, 200, 40)).toBe(200);
    window.localStorage.setItem(KEY, 'wide');
    expect(readSize(KEY, 200, 40)).toBe(200);
  });

  it('keeps what was saved, cut to the ceiling', () => {
    window.localStorage.setItem(KEY, '480');
    expect(readSize(KEY, 200, 40)).toBe(480);
    expect(readSize(KEY, 200, 40, 400)).toBe(400);
  });

  it('does not cost the page when storage throws', () => {
    const getItem = jest.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    try {
      expect(readSize(KEY, 200, 40)).toBe(200);
    } finally {
      getItem.mockRestore();
    }
  });
});

describe('WidthSplitter', () => {
  it('widens a panel on its left when moved right, and remembers it', () => {
    render(<Widths panel="left" />);
    const grip = screen.getByRole('separator', { name: 'Resize the panel' });
    sized(grip.parentElement, { width: 1000 });

    fireEvent.keyDown(grip, { key: 'ArrowRight' });
    expect(grip).toHaveAttribute('aria-valuenow', '316');
    fireEvent.keyDown(grip, { key: 'ArrowLeft', shiftKey: true });
    expect(grip).toHaveAttribute('aria-valuenow', '252');
    expect(window.localStorage.getItem(KEY)).toBe('252');
  });

  it('widens a panel on its right when moved left', () => {
    render(<Widths panel="right" />);
    const grip = screen.getByRole('separator', { name: 'Resize the panel' });
    sized(grip.parentElement, { width: 1000 });

    fireEvent.keyDown(grip, { key: 'ArrowLeft' });
    expect(grip).toHaveAttribute('aria-valuenow', '316');
    fireEvent.keyDown(grip, { key: 'ArrowRight' });
    expect(grip).toHaveAttribute('aria-valuenow', '300');
  });

  it('stops at the floor, and at what the row leaves for its neighbour', () => {
    render(<Widths panel="right" start={780} />);
    const grip = screen.getByRole('separator', { name: 'Resize the panel' });
    sized(grip.parentElement, { width: 1000 });

    fireEvent.keyDown(grip, { key: 'ArrowLeft', shiftKey: true });
    expect(grip).toHaveAttribute('aria-valuenow', '800');

    for (let i = 0; i < 20; i += 1) {
      fireEvent.keyDown(grip, { key: 'ArrowRight', shiftKey: true });
    }
    expect(grip).toHaveAttribute('aria-valuenow', '240');
  });

  it('follows the pointer, in the panel’s direction', () => {
    render(<Widths panel="right" />);
    const grip = screen.getByRole('separator', { name: 'Resize the panel' });
    sized(grip.parentElement, { width: 1000 });

    pointer(grip, 'pointerdown', 500);
    pointer(window, 'pointermove', 400);
    expect(grip).toHaveAttribute('aria-valuenow', '400');
    pointer(window, 'pointerup', 400);
    expect(window.localStorage.getItem(KEY)).toBe('400');

    // Let go means let go: a later move changes nothing.
    pointer(window, 'pointermove', 100);
    expect(grip).toHaveAttribute('aria-valuenow', '400');
  });

  it('leaves other keys alone', () => {
    render(<Widths panel="left" />);
    const grip = screen.getByRole('separator', { name: 'Resize the panel' });
    fireEvent.keyDown(grip, { key: 'ArrowUp' });
    expect(grip).toHaveAttribute('aria-valuenow', '300');
    expect(window.localStorage.getItem(KEY)).toBeNull();
  });
});

describe('HeightSplitter', () => {
  it('goes down until the rows keep only their floor', () => {
    render(<Heights />);
    const grip = screen.getByRole('separator', { name: 'Resize the editor' });
    sized(grip.parentElement, { height: 600 });

    for (let i = 0; i < 20; i += 1) {
      fireEvent.keyDown(grip, { key: 'ArrowDown', shiftKey: true });
    }
    expect(grip).toHaveAttribute('aria-valuenow', '504');
    expect(window.localStorage.getItem(KEY)).toBe('504');
  });

  it('goes up to about one line of editor', () => {
    render(<Heights />);
    const grip = screen.getByRole('separator', { name: 'Resize the editor' });
    sized(grip.parentElement, { height: 600 });

    for (let i = 0; i < 20; i += 1) {
      fireEvent.keyDown(grip, { key: 'ArrowUp', shiftKey: true });
    }
    expect(grip).toHaveAttribute('aria-valuenow', '40');
  });
});
