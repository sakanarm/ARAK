import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import FlowDiagram, { edgePath, fitZoom, layoutDiagram, SIZE, type DiagramEdge, type DiagramNode } from './FlowDiagram';
import TabStrip from '../TabStrip';

const NODES: DiagramNode[] = [
  { id: 'start', kind: 'start', column: 0, row: 0, title: 'Request submitted' },
  {
    id: 'review',
    kind: 'task',
    column: 1,
    row: 0,
    eyebrow: 'Step 1',
    title: 'Owner review',
    lines: [{ text: 'a' }, { text: 'b' }, { text: 'c', caution: true }, { text: 'd' }, { text: 'e' }],
  },
  { id: 'end', kind: 'end', column: 2, row: 0, title: 'Access in place', tone: 'success' },
  { id: 'rejected', kind: 'end', column: 2, row: 1, title: 'Rejected', tone: 'error', selectable: false },
];

const EDGES: DiagramEdge[] = [
  { from: 'start', to: 'review' },
  { from: 'review', to: 'end', label: 'Approve', tone: 'success' },
  { from: 'review', to: 'rejected', label: 'Reject', tone: 'error', route: 'down' },
];

describe('layoutDiagram', () => {
  it('centres each node in its column and trims the canvas to what is drawn', () => {
    const { boxes, width, height } = layoutDiagram(NODES);
    // The start sits in a column no wider than itself.
    expect(boxes.start.x).toBe(40);
    // The task's column follows, a gap later.
    expect(boxes.review.x).toBe(40 + SIZE.start.w + 88);
    // Row 0 nodes share a centre line.
    expect(boxes.start.y + boxes.start.h / 2).toBe(boxes.review.y + boxes.review.h / 2);
    // Row 1 sits a row height and a gap lower.
    expect(boxes.rejected.y - boxes.end.y).toBe(SIZE.task.h + 52);
    expect(width).toBe(boxes.end.x + SIZE.end.w + 40);
    expect(height).toBe(boxes.rejected.y + SIZE.end.h + 40);
  });

  it('draws across from right to left, and down then along', () => {
    const a = { x: 0, y: 0, w: 100, h: 40 };
    const b = { x: 200, y: 0, w: 100, h: 40 };
    expect(edgePath(a, b, 'across').d).toMatch(/^M 100 20 C /);
    const c = { x: 200, y: 200, w: 100, h: 40 };
    const down = edgePath(a, c, 'down');
    expect(down.d).toBe('M 50 40 V 206 Q 50 220 64 220 H 198');
    expect(down.lx).toBe(50);
  });
});

describe('FlowDiagram', () => {
  it('reads as text when nothing can be chosen', () => {
    render(<FlowDiagram edges={EDGES} label="Workflow" nodes={NODES} />);
    expect(screen.getByRole('figure', { name: 'Workflow' })).toBeInTheDocument();
    expect(screen.getByText('Owner review')).toBeInTheDocument();
    expect(screen.getByText('Approve')).toBeInTheDocument();
    expect(screen.getByText('Reject')).toBeInTheDocument();
    // Three lines, then a count of the rest.
    expect(screen.getByText('c')).toBeInTheDocument();
    expect(screen.queryByText('d')).not.toBeInTheDocument();
    expect(screen.getByText('+2 more')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Owner review/ })).not.toBeInTheDocument();
  });

  it('lets a node be chosen, but not one marked plain', () => {
    function Harness() {
      const [selected, setSelected] = useState<string | null>(null);
      return <FlowDiagram edges={EDGES} label="Workflow" nodes={NODES} onSelect={setSelected} selected={selected} />;
    }
    render(<Harness />);
    const review = screen.getByRole('button', { name: /Owner review/ });
    expect(review).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(review);
    expect(review).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByRole('button', { name: /Rejected/ })).not.toBeInTheDocument();
  });

  it('zooms within its bounds and back', () => {
    render(<FlowDiagram edges={EDGES} label="Workflow" nodes={NODES} />);
    expect(screen.getByRole('button', { name: 'Reset zoom' })).toHaveTextContent('100%');
    fireEvent.click(screen.getByRole('button', { name: 'Zoom in' }));
    fireEvent.click(screen.getByRole('button', { name: 'Zoom in' }));
    expect(screen.getByRole('button', { name: 'Reset zoom' })).toHaveTextContent('130%');
    expect(screen.getByRole('button', { name: 'Zoom in' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Reset zoom' }));
    expect(screen.getByRole('button', { name: 'Reset zoom' })).toHaveTextContent('100%');
  });

  it('opens over the page and closes by its button or Escape', () => {
    render(<FlowDiagram edges={EDGES} label="Workflow" nodes={NODES} />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Full screen' }));
    expect(screen.getByRole('dialog')).toHaveAttribute('aria-modal', 'true');
    fireEvent.click(screen.getByRole('button', { name: 'Exit full screen' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Full screen' }));
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('TabStrip', () => {
  it('chooses by click and by arrow keys', () => {
    function Harness() {
      const [tab, setTab] = useState<'a' | 'b' | 'c'>('a');
      return (
        <TabStrip
          idPrefix="t"
          label="Views"
          onChange={setTab}
          tabs={[
            { id: 'a', label: 'Builder' },
            { id: 'b', label: 'History', count: 4 },
            { id: 'c', label: 'Other' },
          ]}
          value={tab}
        />
      );
    }
    render(<Harness />);
    const builder = screen.getByRole('tab', { name: 'Builder' });
    expect(builder).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'History 4' })).toHaveAttribute('tabindex', '-1');

    fireEvent.keyDown(builder, { key: 'ArrowLeft' });
    expect(screen.getByRole('tab', { name: 'Other' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Other' })).toHaveFocus();

    fireEvent.click(screen.getByRole('tab', { name: 'History 4' }));
    expect(screen.getByRole('tab', { name: 'History 4' })).toHaveAttribute('aria-selected', 'true');
  });
});

describe('fitZoom', () => {
  it('opens a flow at actual size when it fits, smaller when it does not, and never below the floor', () => {
    expect(fitZoom(900, 1200)).toBe(1);
    expect(fitZoom(1640, 1098)).toBe(0.66);
    expect(1640 * fitZoom(1640, 1098)).toBeLessThanOrEqual(1098);
    expect(fitZoom(4000, 800)).toBe(0.5); // and it scrolls
    // Not laid out yet (a test DOM, a hidden tab): leave it at actual size.
    expect(fitZoom(1200, 0)).toBe(1);
  });
});
