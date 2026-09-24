import type { Policy } from '../../generated/entity/policy/policy';
import { buildFlow, LANE_TITLES, type FlowStep } from './policyFlow';

/**
 * The policy drawn as a flowchart.
 *
 * An alternative reading of the same document the form authors and the sentence
 * describes, offered beside them rather than instead of them. The three suit
 * different moments: the form is for writing, the sentence is for checking a
 * clause word by word, and this is for the moment somebody wants to know
 * whether the policy has the shape they intended before reading any of it
 * closely.
 *
 * <h2>The connectors are drawn, because the order is the point</h2>
 *
 * Boxes listed down a page read as a list, and a list of clauses is what the
 * sentence already is. What the chart adds is that each box is reached only by
 * passing the one above it, and that gates have a second way out — so the
 * arrows are real strokes with real arrowheads, and every gate's "no" branch is
 * drawn leaving to the side. Without the branch a reader assumes failing a gate
 * means denial, when it means this policy simply had nothing to say.
 *
 * <h2>Drawn in the layout engine, not on a canvas</h2>
 *
 * The boxes are boxes and only the connectors are SVG. A graph library would
 * add a bundle larger than this page, take the boxes out of the document —
 * costing text selection, keyboard focus and screen-reader order — and draw
 * them in its own colours, which is the one thing this console has been
 * consistent about not doing.
 *
 * <h2>Clicking a box</h2>
 *
 * When {@link PolicyFlowChartProps.onEdit} is given, each box is a button that takes
 * the author back to the step that writes it. That is what keeps the chart from
 * being a dead end: whoever spots the wrong thing is one click from the field
 * that fixes it, rather than working out which of six steps owns it. Without
 * the callback the boxes are plain text, which is what a reader with no right
 * to edit should get.
 */

const TONE: Record<string, { box: string; title: string }> = {
  set: { box: 'tw:border-secondary tw:bg-primary', title: 'tw:text-primary' },
  empty: {
    box: 'tw:border-secondary tw:border-dashed tw:bg-secondary',
    title: 'tw:text-tertiary',
  },
  open: {
    box: 'tw:border-warning tw:bg-warning-primary',
    title: 'tw:text-warning-primary',
  },
  deny: { box: 'tw:border-error tw:bg-error-primary', title: 'tw:text-error-primary' },
};

/** A rounded pill for the two ends of the chart. */
function Terminal({ children }: { children: string }) {
  return (
    <div className="tw:w-fit tw:rounded-full tw:border tw:border-secondary tw:bg-secondary tw:px-4 tw:py-1.5 tw:text-sm tw:text-secondary">
      {children}
    </div>
  );
}

/**
 * The stroke from one box to the next.
 *
 * Hidden from assistive technology on purpose: the reading order already
 * carries the sequence, and "downwards arrow" announced six times carries
 * nothing that the boxes do not.
 */
function Down({ label }: { label?: string }) {
  return (
    <div className="tw:flex tw:items-center tw:gap-2 tw:pl-6 tw:text-tertiary">
      <svg
        aria-hidden="true"
        className="tw:overflow-visible"
        height="30"
        viewBox="0 0 12 30"
        width="12">
        <line stroke="currentColor" strokeWidth="1.5" x1="6" x2="6" y1="0" y2="23" />
        <path d="M6 30 L2 22 L10 22 Z" fill="currentColor" />
      </svg>
      {label && <span className="tw:text-xs">{label}</span>}
    </div>
  );
}

/** The stroke a gate takes when the answer is no. */
function Aside({ text }: { text: string }) {
  return (
    <>
      <div
        aria-hidden="true"
        className="tw:hidden tw:items-center tw:text-tertiary tw:md:flex">
        <svg className="tw:overflow-visible" height="12" viewBox="0 0 34 12" width="34">
          <line stroke="currentColor" strokeWidth="1.5" x1="0" x2="27" y1="6" y2="6" />
          <path d="M34 6 L26 2 L26 10 Z" fill="currentColor" />
        </svg>
      </div>
      <div className="tw:self-center tw:rounded-lg tw:border tw:border-dashed tw:border-secondary tw:bg-secondary tw:px-3 tw:py-2 tw:text-xs tw:text-tertiary">
        <span className="tw:font-medium">If not · </span>
        {text}
      </div>
    </>
  );
}

function Card({ step, onEdit }: { step: FlowStep; onEdit?: (form: number) => void }) {
  const tone = TONE[step.tone] ?? TONE.set;
  const clickable = Boolean(onEdit && step.step);

  const body = (
    <>
      <p className="tw:text-xs tw:font-semibold tw:uppercase tw:tracking-wide tw:text-quaternary">
        {LANE_TITLES[step.lane]}
      </p>
      <p className={`tw:mt-1 tw:text-sm tw:font-semibold ${tone.title}`}>{step.title}</p>
      {step.items.length > 0 && (
        <ul className="tw:mt-2 tw:flex tw:flex-col tw:gap-1">
          {step.items.map((item, index) => (
            <li
              className={`tw:text-sm ${
                item.caution ? 'tw:text-error-primary' : 'tw:text-secondary'
              }`}
              key={index}>
              {item.text}
            </li>
          ))}
        </ul>
      )}
      {step.note && <p className="tw:mt-2 tw:text-xs tw:text-tertiary">{step.note}</p>}
    </>
  );

  if (!clickable) {
    return <div className={`tw:rounded-xl tw:border tw:p-4 ${tone.box}`}>{body}</div>;
  }

  return (
    <button
      className={`tw:cursor-pointer tw:rounded-xl tw:border tw:p-4 tw:text-left tw:transition tw:hover:border-brand tw:focus-visible:border-brand tw:focus-visible:outline-none ${tone.box}`}
      onClick={() => onEdit?.(step.step!)}
      type="button">
      {body}
      <span className="tw:mt-2 tw:block tw:text-xs tw:text-brand-secondary">
        Edit in step {step.step}
      </span>
    </button>
  );
}

export interface PolicyFlowChartProps {
  policy: Policy;
  /** Given in the builder; omitted where the reader cannot edit. */
  onEdit?: (form: number) => void;
}

export default function PolicyFlowChart({ policy, onEdit }: PolicyFlowChartProps) {
  const flow = buildFlow(policy);

  return (
    <div className="tw:flex tw:flex-col tw:gap-4">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2 tw:text-sm tw:text-secondary">
        <span className="tw:rounded-full tw:border tw:border-secondary tw:px-2.5 tw:py-0.5 tw:text-xs tw:font-medium tw:text-tertiary">
          {flow.kind}
        </span>
        <span>{flow.scope}</span>
      </div>

      <div className="tw:flex tw:flex-col">
        <Terminal>{flow.entry}</Terminal>
        {flow.steps.map((step, index) => (
          <div className="tw:contents" key={step.id}>
            <Down label={index > 0 && flow.steps[index - 1].otherwise ? 'yes' : undefined} />
            <div className="tw:grid tw:gap-2 tw:md:grid-cols-[minmax(0,1fr)_auto_minmax(0,16rem)] tw:md:items-start">
              <Card onEdit={onEdit} step={step} />
              {step.otherwise && <Aside text={step.otherwise} />}
            </div>
          </div>
        ))}
        <Down
          label={
            flow.steps[flow.steps.length - 1]?.otherwise ? 'yes' : undefined
          }
        />
        <Terminal>{flow.exit}</Terminal>
      </div>

      <p
        className={`tw:rounded-xl tw:border tw:p-3 tw:text-sm ${
          flow.gateOpen
            ? 'tw:border-warning tw:bg-warning-primary tw:text-warning-primary'
            : 'tw:border-secondary tw:bg-secondary tw:text-secondary'
        }`}>
        {flow.gate}
      </p>
    </div>
  );
}
