import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { Maximize01, Minimize01, ZoomIn, ZoomOut } from '@untitledui/icons';

/**
 * A left-to-right flow drawn on a dotted canvas, the way OpenMetadata's
 * workflow builder draws one: rounded nodes with connector dots, curved
 * strokes between them, and the two ways out of a decision coloured.
 *
 * <p>Shared by the access workflow builder and the policy diagram, so a reader
 * who learns one reads the other. The caller places each node on a grid --
 * a column for how far along the flow it is, a row for what runs beside it --
 * and this lays the grid out, draws the strokes and handles selection. Nothing
 * here knows what a stage or a policy is.
 *
 * <h2>Why not a graph library</h2>
 *
 * The same reason as {@link ../../pages/policies/PolicyFlowChart}: the nodes
 * stay HTML, so they keep text selection, keyboard focus and reading order,
 * and they draw in the console's own colours. The flows drawn here are a
 * handful of nodes on a grid the caller already knows; a force layout would
 * only move them somewhere less predictable.
 *
 * <h2>Two kinds of stroke</h2>
 *
 * An edge {@code across} leaves a node's right-hand dot for the next node's
 * left-hand one. An edge {@code down} leaves the bottom dot and turns right
 * along the lane below, which is how every "no" in a flow reaches its one end
 * without crossing the nodes between: the lane below the flow is kept for it.
 */

export type DiagramNodeKind = 'start' | 'end' | 'task' | 'join';

export type DiagramTone = 'default' | 'muted' | 'success' | 'warning' | 'error';

export interface DiagramLine {
  text: string;
  /** The line that makes this node wider or harsher than it looks. */
  caution?: boolean;
}

export interface DiagramNode {
  id: string;
  kind: DiagramNodeKind;
  /** How far along the flow: 0 is the start. */
  column: number;
  /** What runs beside it; may be fractional to centre a node between rows. */
  row: number;
  title: string;
  eyebrow?: string;
  lines?: DiagramLine[];
  tone?: DiagramTone;
  icon?: ReactNode;
  /** A short tag in the node's corner: "Off", "System". */
  badge?: string;
  /** False keeps a node plain text even when the diagram is selectable. */
  selectable?: boolean;
}

export type EdgeTone = 'neutral' | 'success' | 'error';

export interface DiagramEdge {
  from: string;
  to: string;
  label?: string;
  tone?: EdgeTone;
  route?: 'across' | 'down';
  dashed?: boolean;
}

export interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}

export const SIZE: Record<DiagramNodeKind, { w: number; h: number }> = {
  start: { w: 208, h: 44 },
  end: { w: 208, h: 44 },
  task: { w: 264, h: 118 },
  join: { w: 40, h: 40 },
};

const PAD = 40;
const COL_GAP = 88;
const ROW_H = SIZE.task.h;
const ROW_GAP = 52;
/** Lines a task shows before it says how many more there are. */
const MAX_LINES = 3;

/** Where every node sits, and how large the canvas is. Pure, so it is tested alone. */
export function layoutDiagram(nodes: DiagramNode[]): {
  boxes: Record<string, Box>;
  width: number;
  height: number;
} {
  const widths: number[] = [];
  for (const node of nodes) {
    widths[node.column] = Math.max(widths[node.column] ?? 0, SIZE[node.kind].w);
  }
  const lefts: number[] = [];
  let x = PAD;
  for (let column = 0; column < widths.length; column++) {
    lefts[column] = x;
    x += (widths[column] ?? 0) + (widths[column] ? COL_GAP : 0);
  }
  const boxes: Record<string, Box> = {};
  let right = 0;
  let bottom = 0;
  for (const node of nodes) {
    const { w, h } = SIZE[node.kind];
    const cx = lefts[node.column] + (widths[node.column] - w) / 2;
    const cy = PAD + node.row * (ROW_H + ROW_GAP) + (ROW_H - h) / 2;
    boxes[node.id] = { x: cx, y: cy, w, h };
    right = Math.max(right, cx + w);
    bottom = Math.max(bottom, cy + h);
  }
  return { boxes, width: right + PAD, height: bottom + PAD };
}

/** The stroke of one edge, and where its label sits. */
export function edgePath(a: Box, b: Box, route: 'across' | 'down'): { d: string; lx: number; ly: number } {
  if (route === 'down') {
    const x1 = a.x + a.w / 2;
    const y1 = a.y + a.h;
    const x2 = b.x;
    const y2 = b.y + b.h / 2;
    const r = Math.min(14, Math.max(0, (y2 - y1) / 2), Math.max(0, (x2 - x1) / 2));
    return {
      d: `M ${x1} ${y1} V ${y2 - r} Q ${x1} ${y2} ${x1 + r} ${y2} H ${x2 - 2}`,
      lx: x1,
      ly: y1 + Math.min(26, (y2 - y1) / 2),
    };
  }
  const x1 = a.x + a.w;
  const y1 = a.y + a.h / 2;
  const x2 = b.x;
  const y2 = b.y + b.h / 2;
  const dx = Math.max(28, (x2 - x1) / 2);
  return {
    d: `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2 - 2} ${y2}`,
    lx: (x1 + x2) / 2,
    ly: (y1 + y2) / 2,
  };
}

const STROKE: Record<EdgeTone, string> = {
  neutral: 'tw:text-fg-quaternary',
  success: 'tw:text-fg-success-primary',
  error: 'tw:text-fg-error-primary',
};

const LABEL: Record<EdgeTone, string> = {
  neutral: 'tw:bg-secondary tw:text-tertiary',
  success: 'tw:bg-utility-success-100 tw:text-utility-success-700',
  error: 'tw:bg-utility-error-100 tw:text-utility-error-700',
};

const BOX: Record<DiagramTone, string> = {
  default: 'tw:border-secondary tw:bg-primary',
  muted: 'tw:border-dashed tw:border-secondary tw:bg-secondary',
  success: 'tw:border-secondary tw:bg-primary',
  warning: 'tw:border-warning tw:bg-warning-primary',
  error: 'tw:border-error tw:bg-error-primary',
};

const CHIP: Record<DiagramTone, string> = {
  default: 'tw:bg-utility-brand-100 tw:text-utility-brand-700',
  muted: 'tw:bg-utility-gray-100 tw:text-utility-gray-700',
  success: 'tw:bg-utility-success-100 tw:text-utility-success-700',
  warning: 'tw:bg-utility-warning-100 tw:text-utility-warning-700',
  error: 'tw:bg-utility-error-100 tw:text-utility-error-700',
};

function NodeBody({ node }: { node: DiagramNode }) {
  const tone = node.tone ?? 'default';
  if (node.kind === 'join') {
    return (
      <span className={`tw:flex tw:size-full tw:items-center tw:justify-center ${CHIP[tone]} tw:rounded-full`}>
        {node.icon}
      </span>
    );
  }
  if (node.kind !== 'task') {
    return (
      <span className="tw:flex tw:min-w-0 tw:items-center tw:gap-2">
        {node.icon && (
          <span
            className={`tw:flex tw:size-6 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-full ${CHIP[tone]}`}>
            {node.icon}
          </span>
        )}
        <span className="tw:truncate tw:text-sm tw:font-semibold tw:text-primary">{node.title}</span>
      </span>
    );
  }
  const lines = node.lines ?? [];
  const shown = lines.slice(0, MAX_LINES);
  return (
    <span className="tw:flex tw:min-w-0 tw:gap-3">
      {node.icon && (
        <span
          className={`tw:flex tw:size-8 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg ${CHIP[tone]}`}>
          {node.icon}
        </span>
      )}
      <span className="tw:flex tw:min-w-0 tw:flex-1 tw:flex-col">
        {node.eyebrow && (
          <span className="tw:truncate tw:text-xs tw:font-medium tw:uppercase tw:tracking-wide tw:text-quaternary">
            {node.eyebrow}
          </span>
        )}
        <span
          className={`tw:truncate tw:text-sm tw:font-semibold ${
            tone === 'error' ? 'tw:text-error-primary' : tone === 'warning' ? 'tw:text-warning-primary' : 'tw:text-primary'
          }`}>
          {node.title}
        </span>
        {shown.map((line, index) => (
          <span
            className={`tw:truncate tw:text-xs ${line.caution ? 'tw:text-error-primary' : 'tw:text-tertiary'}`}
            key={index}
            title={line.text}>
            {line.text}
          </span>
        ))}
        {lines.length > MAX_LINES && (
          <span className="tw:text-xs tw:text-quaternary">+{lines.length - MAX_LINES} more</span>
        )}
      </span>
    </span>
  );
}

export interface FlowDiagramProps {
  nodes: DiagramNode[];
  edges: DiagramEdge[];
  /** Names the figure for assistive technology. */
  label: string;
  selected?: string | null;
  /** Given, the nodes become buttons; omitted, they are plain text. */
  onSelect?: (id: string) => void;
  /** Laid over the canvas's top-left corner: a legend, a toolbar. */
  overlay?: ReactNode;
}

const ZOOMS = [0.5, 0.6, 0.75, 0.9, 1, 1.15, 1.3];
const SMALLEST = ZOOMS[0];
const LARGEST = ZOOMS[ZOOMS.length - 1];

/**
 * The scale, up to actual size, at which a canvas this wide fits the room it
 * has. Not one of the steps: a flow a little too wide shrinks a little, not to
 * the next step down. Below the smallest step it scrolls instead.
 */
export function fitZoom(width: number, room: number): number {
  if (room <= 0 || width <= 0) return 1;
  return Math.max(SMALLEST, Math.min(1, Math.floor((room / width) * 100) / 100));
}

export default function FlowDiagram({ nodes, edges, label, selected, onSelect, overlay }: FlowDiagramProps) {
  const uid = useId().replace(/:/g, '');
  const [scale, setScale] = useState(1);
  const { boxes, width, height } = layoutDiagram(nodes);

  // Opens at the size that shows the whole flow, until the reader zooms: from
  // then on the size is theirs, and a resize does not take it back.
  const viewport = useRef<HTMLDivElement>(null);
  const [chosen, setChosen] = useState(false);
  useEffect(() => {
    const element = viewport.current;
    if (chosen || !element) return;
    const fit = () => {
      if (element.clientWidth > 0) setScale(fitZoom(width, element.clientWidth));
    };
    fit();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(fit);
    observer.observe(element);
    return () => observer.disconnect();
  }, [chosen, width]);
  // The buttons step from wherever the fit left it to the next step along.
  const zoomTo = (next: number) => {
    setChosen(true);
    setScale(next);
  };
  // Full screen lays the canvas over the page so a wide flow can be read
  // across the whole window; it fits again to the room it now has.
  const [full, setFull] = useState(false);
  const toggleFull = () => {
    setChosen(false);
    setFull((now) => !now);
  };
  useEffect(() => {
    if (!full) return;
    const leave = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setChosen(false);
        setFull(false);
      }
    };
    window.addEventListener('keydown', leave);
    return () => window.removeEventListener('keydown', leave);
  }, [full]);
  const smaller = [...ZOOMS].reverse().find((step) => step < scale - 0.001);
  const larger = ZOOMS.find((step) => step > scale + 0.001);

  const outgoing = new Set<string>();
  const downward = new Set<string>();
  const incoming = new Set<string>();
  for (const edge of edges) {
    (edge.route === 'down' ? downward : outgoing).add(edge.from);
    incoming.add(edge.to);
  }
  // Reading order follows the flow: along it first, then down each column.
  const ordered = [...nodes].sort((a, b) => a.column - b.column || a.row - b.row);

  const dots: { x: number; y: number; key: string }[] = [];
  for (const node of nodes) {
    const box = boxes[node.id];
    if (node.kind === 'join') continue;
    if (incoming.has(node.id)) dots.push({ x: box.x, y: box.y + box.h / 2, key: `${node.id}-in` });
    if (outgoing.has(node.id)) dots.push({ x: box.x + box.w, y: box.y + box.h / 2, key: `${node.id}-out` });
    if (downward.has(node.id)) dots.push({ x: box.x + box.w / 2, y: box.y + box.h, key: `${node.id}-down` });
  }

  return (
    <>
    {full && (
      <div
        aria-hidden="true"
        className="tw:fixed tw:inset-0 tw:z-40 tw:bg-black/40"
        onClick={toggleFull}
      />
    )}
    <div
      aria-label={full ? label : undefined}
      aria-modal={full || undefined}
      className={`tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary tw:bg-primary ${
        full ? 'tw:fixed tw:inset-4 tw:z-50 tw:flex tw:flex-col tw:shadow-2xl' : 'tw:relative'
      }`}
      data-testid="flow-diagram"
      role={full ? 'dialog' : undefined}>
      <div
        // The overlay sits over the canvas's corner; the band above the flow
        // keeps it off the first node however small the flow is drawn.
        className={`tw:overflow-auto ${overlay ? 'tw:pt-12' : ''} ${full ? 'tw:min-h-0 tw:flex-1 tw:pt-12' : ''}`}
        ref={viewport}
        style={{
          backgroundImage: 'radial-gradient(var(--color-border-primary) 1px, transparent 1px)',
          backgroundSize: '18px 18px',
          maxHeight: full ? undefined : '72vh',
        }}>
        <div
          aria-label={label}
          className="tw:relative tw:mx-auto"
          role="figure"
          style={{ width: width * scale, height: height * scale }}>
          <div
            className="tw:absolute tw:top-0 tw:left-0 tw:origin-top-left"
            style={{ width, height, transform: `scale(${scale})` }}>
            <svg aria-hidden="true" className="tw:absolute tw:inset-0" height={height} width={width}>
              <defs>
                {(Object.keys(STROKE) as EdgeTone[]).map((tone) => (
                  <marker
                    className={STROKE[tone]}
                    id={`${uid}-arrow-${tone}`}
                    key={tone}
                    markerHeight="8"
                    markerWidth="8"
                    orient="auto-start-reverse"
                    refX="7"
                    refY="4"
                    viewBox="0 0 8 8">
                    <path d="M0 0 L8 4 L0 8 Z" fill="currentColor" />
                  </marker>
                ))}
              </defs>
              {edges.map((edge, index) => {
                const a = boxes[edge.from];
                const b = boxes[edge.to];
                if (!a || !b) return null;
                const tone = edge.tone ?? 'neutral';
                const { d } = edgePath(a, b, edge.route ?? 'across');
                return (
                  <path
                    className={STROKE[tone]}
                    d={d}
                    fill="none"
                    key={index}
                    markerEnd={`url(#${uid}-arrow-${tone})`}
                    stroke="currentColor"
                    strokeDasharray={edge.dashed ? '5 5' : undefined}
                    strokeWidth={1.75}
                  />
                );
              })}
            </svg>

            {ordered.map((node) => {
              const box = boxes[node.id];
              const tone = node.tone ?? 'default';
              const pickable = Boolean(onSelect) && node.selectable !== false;
              const isSelected = selected === node.id;
              const shape =
                node.kind === 'task'
                  ? 'tw:rounded-2xl tw:px-3.5 tw:py-3'
                  : node.kind === 'join'
                    ? 'tw:rounded-full tw:p-1'
                    : 'tw:flex tw:items-center tw:rounded-full tw:px-3';
              const style = { left: box.x, top: box.y, width: box.w, height: box.h };
              const className = `tw:absolute tw:border tw:text-left tw:shadow-xs ${shape} ${BOX[tone]} ${
                isSelected ? 'tw:border-brand tw:outline-2 tw:outline-offset-2 tw:outline-focus-ring' : ''
              }`;
              const badge = node.badge && (
                <span className="tw:absolute tw:-top-2.5 tw:right-3 tw:rounded-full tw:bg-utility-gray-100 tw:px-2 tw:py-0.5 tw:text-xs tw:font-medium tw:text-utility-gray-700">
                  {node.badge}
                </span>
              );
              if (!pickable) {
                return (
                  <div
                    aria-label={node.kind === 'join' ? node.title : undefined}
                    className={className}
                    key={node.id}
                    role={node.kind === 'join' ? 'img' : undefined}
                    style={style}
                    title={node.kind === 'join' ? node.title : undefined}>
                    <NodeBody node={node} />
                    {badge}
                  </div>
                );
              }
              return (
                <button
                  aria-label={node.kind === 'join' ? node.title : undefined}
                  aria-pressed={isSelected}
                  className={`${className} tw:cursor-pointer tw:transition tw:hover:border-brand tw:hover:shadow-md tw:focus-visible:outline-2 tw:focus-visible:outline-offset-2 tw:focus-visible:outline-focus-ring`}
                  key={node.id}
                  onClick={() => onSelect?.(node.id)}
                  style={style}
                  title={node.kind === 'join' ? node.title : undefined}
                  type="button">
                  <NodeBody node={node} />
                  {badge}
                </button>
              );
            })}

            <svg
              aria-hidden="true"
              className="tw:pointer-events-none tw:absolute tw:inset-0"
              height={height}
              width={width}>
              {dots.map((dot) => (
                <circle
                  className="tw:fill-bg-primary tw:stroke-fg-quaternary"
                  cx={dot.x}
                  cy={dot.y}
                  key={dot.key}
                  r={4}
                  strokeWidth={1.5}
                />
              ))}
            </svg>

            {edges.map((edge, index) => {
              const a = boxes[edge.from];
              const b = boxes[edge.to];
              if (!edge.label || !a || !b) return null;
              const { lx, ly } = edgePath(a, b, edge.route ?? 'across');
              return (
                <span
                  className={`tw:absolute tw:-translate-x-1/2 tw:-translate-y-1/2 tw:whitespace-nowrap tw:rounded-full tw:px-2 tw:py-0.5 tw:text-xs tw:font-medium ${LABEL[edge.tone ?? 'neutral']}`}
                  key={`label-${index}`}
                  style={{ left: lx, top: ly }}>
                  {edge.label}
                </span>
              );
            })}
          </div>
        </div>
      </div>

      {overlay && <div className="tw:absolute tw:top-3 tw:left-3">{overlay}</div>}

      <div className="tw:absolute tw:right-3 tw:bottom-3 tw:flex tw:items-center tw:gap-0.5 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:p-0.5 tw:shadow-xs">
        <button
          aria-label="Zoom out"
          className="tw:flex tw:size-7 tw:cursor-pointer tw:items-center tw:justify-center tw:rounded-md tw:text-tertiary tw:hover:bg-secondary tw:disabled:cursor-default tw:disabled:opacity-40"
          disabled={smaller === undefined}
          onClick={() => zoomTo(smaller ?? SMALLEST)}
          type="button">
          <ZoomOut className="tw:size-4" />
        </button>
        <button
          aria-label="Reset zoom"
          className="tw:h-7 tw:min-w-12 tw:cursor-pointer tw:rounded-md tw:px-1 tw:text-xs tw:font-medium tw:tabular-nums tw:text-tertiary tw:hover:bg-secondary"
          onClick={() => zoomTo(1)}
          type="button">
          {Math.round(scale * 100)}%
        </button>
        <button
          aria-label="Zoom in"
          className="tw:flex tw:size-7 tw:cursor-pointer tw:items-center tw:justify-center tw:rounded-md tw:text-tertiary tw:hover:bg-secondary tw:disabled:cursor-default tw:disabled:opacity-40"
          disabled={larger === undefined}
          onClick={() => zoomTo(larger ?? LARGEST)}
          type="button">
          <ZoomIn className="tw:size-4" />
        </button>
        <span aria-hidden="true" className="tw:mx-0.5 tw:h-4 tw:w-px tw:bg-border-secondary" />
        <button
          aria-label={full ? 'Exit full screen' : 'Full screen'}
          className="tw:flex tw:size-7 tw:cursor-pointer tw:items-center tw:justify-center tw:rounded-md tw:text-tertiary tw:hover:bg-secondary"
          onClick={toggleFull}
          title={full ? 'Exit full screen (Esc)' : 'Full screen'}
          type="button">
          {full ? <Minimize01 className="tw:size-4" /> : <Maximize01 className="tw:size-4" />}
        </button>
      </div>
    </div>
    </>
  );
}
