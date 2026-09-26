import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import type { GrantAccess } from '../../api/access';
import { apiErrorMessage } from '../../api/client';
import { fetchPoliciesForAsset } from '../../api/policies';
import {
  accessFlow,
  type DecisionStep,
  type DiagramEntry,
  type DiagramLayer,
} from './accessFlow';

/**
 * How a request for this table is decided, as a decision flowchart or as the
 * stack of layers it is decided from.
 *
 * Built only from what the server already says binds here, so the chart can
 * be wrong only in the ways the policy list above it would also be wrong.
 */

const STEP_TONE: Record<DecisionStep['tone'], string> = {
  check: 'tw:border-secondary tw:bg-primary',
  deny: 'tw:border-error tw:bg-error-primary',
  allow: 'tw:border-success tw:bg-success-primary',
  restrict: 'tw:border-warning tw:bg-warning-primary',
  empty: 'tw:border-secondary tw:border-dashed tw:bg-secondary',
};

const BRANCH_TONE: Record<string, string> = {
  deny: 'tw:border-error tw:text-error-primary',
  allow: 'tw:border-success tw:text-success-primary',
  restrict: 'tw:border-warning tw:text-warning-primary',
  skip: 'tw:border-secondary tw:text-tertiary',
};

const ENTRY_TONE: Record<DiagramEntry['kind'], { box: string; label: string }> = {
  deny: { box: 'tw:border-error tw:bg-error-primary', label: 'Deny' },
  allow: { box: 'tw:border-success tw:bg-success-primary', label: 'Allow' },
  grant: { box: 'tw:border-brand tw:bg-brand-primary', label: 'Grant' },
  data: { box: 'tw:border-warning tw:bg-warning-primary', label: 'Data' },
};

type View = 'flow' | 'layers';

export function AccessDecision({ fqn, grants }: { fqn: string; grants: GrantAccess[] }) {
  const [view, setView] = useState<View>('flow');
  const { data, isLoading, error } = useQuery({
    queryKey: ['asset-policies', fqn],
    queryFn: () => fetchPoliciesForAsset(fqn),
    enabled: Boolean(fqn),
    retry: false,
  });

  if (isLoading) {
    return <p className="tw:text-sm tw:text-tertiary">Loading…</p>;
  }
  if (error || !data) {
    return (
      <p className="tw:text-sm tw:text-error-primary">
        {apiErrorMessage(error, 'The policies on this table could not be read.')}
      </p>
    );
  }

  const flow = accessFlow(data, grants);

  return (
    <div className="tw:flex tw:flex-col tw:gap-4">
      <div
        aria-label="Decision view"
        className="tw:flex tw:w-fit tw:rounded-lg tw:border tw:border-secondary tw:p-0.5"
        role="group">
        {(
          [
            ['flow', 'Flowchart'],
            ['layers', 'Diagram'],
          ] as const
        ).map(([key, label]) => (
          <button
            aria-pressed={view === key}
            className={`tw:rounded-md tw:px-3 tw:py-1 tw:text-sm ${
              view === key
                ? 'tw:bg-secondary tw:font-semibold tw:text-primary'
                : 'tw:text-tertiary tw:hover:text-secondary'
            }`}
            key={key}
            onClick={() => setView(key)}
            type="button">
            {label}
          </button>
        ))}
      </div>

      {view === 'flow' ? (
        <div className="tw:flex tw:flex-col">
          <Terminal>Someone asks to read this table</Terminal>
          {flow.steps.map((step) => (
            <div className="tw:contents" key={step.id}>
              <Down />
              <div className="tw:grid tw:gap-2 tw:md:grid-cols-[minmax(0,1fr)_minmax(0,18rem)] tw:md:items-start">
                <StepCard step={step} />
                <div className="tw:flex tw:flex-col tw:gap-2 tw:md:self-center">
                  {step.yes && <Branch label="Yes" text={step.yes} tone={step.yesTone} />}
                  {step.no && <Branch label="No" text={step.no} tone={step.noTone} />}
                </div>
              </div>
            </div>
          ))}
          <Down />
          <Terminal tone={flow.exitTone}>{flow.exit}</Terminal>
        </div>
      ) : (
        <Layers layers={flow.layers} />
      )}
    </div>
  );
}

function Terminal({
  children,
  tone,
}: {
  children: string;
  tone?: 'allow' | 'restrict' | 'deny';
}) {
  const colour =
    tone === 'deny'
      ? 'tw:border-error tw:bg-error-primary tw:text-error-primary'
      : tone === 'restrict'
        ? 'tw:border-warning tw:bg-warning-primary tw:text-warning-primary'
        : tone === 'allow'
          ? 'tw:border-success tw:bg-success-primary tw:text-success-primary'
          : 'tw:border-secondary tw:bg-secondary tw:text-secondary';
  return (
    <div className={`tw:w-fit tw:rounded-full tw:border tw:px-4 tw:py-1.5 tw:text-sm ${colour}`}>
      {children}
    </div>
  );
}

function Down() {
  return (
    <div aria-hidden="true" className="tw:pl-6 tw:text-tertiary">
      <svg className="tw:overflow-visible" height="26" viewBox="0 0 12 26" width="12">
        <line stroke="currentColor" strokeWidth="1.5" x1="6" x2="6" y1="0" y2="19" />
        <path d="M6 26 L2 18 L10 18 Z" fill="currentColor" />
      </svg>
    </div>
  );
}

function Branch({ label, text, tone }: { label: string; text: string; tone?: string }) {
  return (
    <div
      className={`tw:rounded-lg tw:border tw:border-dashed tw:bg-primary tw:px-3 tw:py-2 tw:text-xs ${
        BRANCH_TONE[tone ?? 'skip']
      }`}>
      <span className="tw:font-semibold">{label} → </span>
      {text}
    </div>
  );
}

function StepCard({ step }: { step: DecisionStep }) {
  return (
    <div className={`tw:rounded-xl tw:border tw:p-4 ${STEP_TONE[step.tone]}`}>
      <p className="tw:text-xs tw:font-semibold tw:uppercase tw:tracking-wide tw:text-quaternary">
        {step.layer}
      </p>
      <p className="tw:mt-1 tw:text-sm tw:font-semibold tw:text-primary">{step.title}</p>
      {step.items.length > 0 && (
        <ul className="tw:mt-2 tw:flex tw:flex-col tw:gap-1">
          {step.items.map((item, index) => (
            <li className="tw:text-sm tw:text-secondary" key={index}>
              {index === 0 && step.policyId ? (
                <Link className="tw:font-medium tw:text-brand-secondary tw:hover:underline" to={`/policies/${step.policyId}`}>
                  {item.text}
                </Link>
              ) : (
                item.text
              )}
              {item.detail && (
                <span className="tw:ml-2 tw:text-xs tw:text-tertiary">{item.detail}</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Outermost layer on top; each layer has to agree before the next is asked. */
function Layers({ layers }: { layers: DiagramLayer[] }) {
  if (!layers.length) {
    return (
      <p className="tw:text-sm tw:text-warning-primary">
        No policy and no grant reaches this table, so nobody can read it.
      </p>
    );
  }
  return (
    <ol className="tw:flex tw:flex-col tw:gap-2">
      {layers.map((layer, index) => (
        <li
          className="tw:rounded-xl tw:border tw:border-secondary tw:bg-secondary tw:p-3"
          key={layer.key}
          style={{ marginLeft: `${Math.min(index, 6) * 0.75}rem` }}>
          <p className="tw:text-xs tw:font-semibold tw:uppercase tw:tracking-wide tw:text-quaternary">
            {index + 1}. {layer.label}
          </p>
          <ul className="tw:mt-2 tw:flex tw:flex-wrap tw:gap-2">
            {layer.entries.map((entry) => {
              const tone = ENTRY_TONE[entry.kind];
              const body = (
                <>
                  <span className="tw:text-xs tw:font-semibold tw:uppercase">{tone.label}</span>
                  <span className="tw:text-sm tw:font-medium tw:text-primary">{entry.name}</span>
                  <span className="tw:text-xs tw:text-secondary">{entry.who}</span>
                  {entry.what && <span className="tw:text-xs tw:text-tertiary">{entry.what}</span>}
                </>
              );
              return (
                <li
                  className={`tw:flex tw:max-w-xs tw:flex-col tw:gap-0.5 tw:rounded-lg tw:border tw:px-3 tw:py-2 ${tone.box}`}
                  key={entry.id}>
                  {entry.policyId ? (
                    <Link className="tw:flex tw:flex-col tw:gap-0.5 tw:hover:underline" to={`/policies/${entry.policyId}`}>
                      {body}
                    </Link>
                  ) : (
                    body
                  )}
                </li>
              );
            })}
          </ul>
        </li>
      ))}
      <li className="tw:pt-1 tw:text-xs tw:text-tertiary">
        A deny anywhere wins. Every layer with an allow must admit the caller. Data
        policies then narrow what is read.
      </li>
    </ol>
  );
}
