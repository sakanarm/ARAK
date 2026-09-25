import type { ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import {
  AlertTriangle,
  FilePlus02,
  InfoCircle,
  Lightbulb02,
  User01,
  XCircle,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { relativeTime } from '../../components/widgets';
import { apiErrorMessage } from '../../api/client';
import {
  fetchAccessReview,
  type AccessReview,
  type ColumnFateKind,
  type ConflictSeverity,
  type ReviewAccess,
  type ReviewConflict,
  type ReviewSuggestion,
  type RiskLevel,
} from '../../api/accessRequests';

/**
 * What the people answering a request read before they answer (M9 slice 2b).
 *
 * <p>Everything here is read. The server works out what a grant would open by
 * deciding the table as if it existed, what stands in its way, and a few ways
 * to answer; none of them is taken. A policy suggested for a group opens in the
 * policy builder as an unsaved draft, and only the policy lifecycle ever
 * activates one.
 */
export function useAccessReview(id: string, policyId: string | null, enabled = true) {
  return useQuery({
    // Under 'access-requests' so answering a request refreshes its review.
    queryKey: ['access-requests', 'review', id, policyId],
    queryFn: () => fetchAccessReview(id, policyId),
    enabled,
    retry: false,
  });
}

const RISK: Record<RiskLevel, { label: string; colour: 'success' | 'warning' | 'error' }> = {
  LOW: { label: 'Low risk', colour: 'success' },
  MEDIUM: { label: 'Medium risk', colour: 'warning' },
  HIGH: { label: 'High risk', colour: 'error' },
};

const SEVERITY: Record<ConflictSeverity, { icon: typeof InfoCircle; box: string; icon_: string }> = {
  BLOCKER: {
    icon: XCircle,
    box: 'tw:bg-utility-error-50',
    icon_: 'tw:text-fg-error-primary',
  },
  WARNING: {
    icon: AlertTriangle,
    box: 'tw:bg-utility-warning-50',
    icon_: 'tw:text-fg-warning-primary',
  },
  INFO: {
    icon: InfoCircle,
    box: 'tw:bg-secondary',
    icon_: 'tw:text-fg-quaternary',
  },
};

const FATE: Record<ColumnFateKind, { label: string; colour: 'success' | 'warning' | 'gray' }> = {
  VISIBLE: { label: 'In clear', colour: 'success' },
  MASKED: { label: 'Masked', colour: 'warning' },
  HIDDEN: { label: 'Hidden', colour: 'gray' },
};

/** The review of one request, drawn between the request and the answer. */
export function RequestReview({ requestId }: { requestId: string }) {
  const review = useAccessReview(requestId, null);

  return (
    <section
      aria-label="Review"
      className="tw:overflow-hidden tw:rounded-lg tw:border tw:border-secondary">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:justify-between tw:gap-2 tw:border-b tw:border-secondary tw:bg-secondary tw:px-4 tw:py-2.5">
        <h3 className="tw:text-sm tw:font-semibold tw:text-primary">Before you answer</h3>
        {review.data && (
          <Badge color={RISK[review.data.risk.level].colour} size="sm" type="pill-color">
            {RISK[review.data.risk.level].label}
          </Badge>
        )}
      </div>
      <div className="tw:flex tw:flex-col tw:gap-5 tw:px-4 tw:py-4">
        {review.isLoading && <p className="tw:text-sm tw:text-tertiary">Reading the request…</p>}
        {review.isError && (
          <p className="tw:text-sm tw:text-error-primary" role="alert">
            {apiErrorMessage(review.error, 'The review could not be read.')}
          </p>
        )}
        {review.data && <ReviewBody review={review.data} />}
      </div>
    </section>
  );
}

function ReviewBody({ review }: { review: AccessReview }) {
  const { requester, table, risk } = review;
  return (
    <>
      {review.conflicts.length > 0 && <ConflictList conflicts={review.conflicts} />}

      <div className="tw:grid tw:gap-5 tw:lg:grid-cols-2">
        <div className="tw:min-w-0">
          <Heading>Who is asking</Heading>
          <div className="tw:mt-2 tw:flex tw:items-center tw:gap-2">
            <User01 className="tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
            <span className="tw:truncate tw:text-sm tw:font-medium tw:text-primary">
              {requester.displayName || requester.username}
            </span>
            {requester.displayName && (
              <span className="tw:truncate tw:text-xs tw:text-tertiary">{requester.username}</span>
            )}
            {!requester.known ? (
              <Badge color="error" size="sm" type="pill-color">
                Removed
              </Badge>
            ) : (
              !requester.enabled && (
                <Badge color="error" size="sm" type="pill-color">
                  Disabled
                </Badge>
              )
            )}
          </div>
          <dl className="tw:mt-3 tw:grid tw:grid-cols-[auto_1fr] tw:gap-x-4 tw:gap-y-1.5 tw:text-sm">
            {requester.email && <Row label="Email">{requester.email}</Row>}
            {requester.source && <Row label="Directory">{requester.source}</Row>}
            <Row label="Groups">
              {requester.memberships.length === 0 ? (
                <None />
              ) : (
                <Chips
                  values={requester.memberships.map(
                    (m) => `${m.kind === 'team' ? 'Team ' : ''}${m.displayName || m.name}`
                  )}
                />
              )}
            </Row>
            <Row label="Attributes">
              {requester.attributes.length === 0 ? (
                <None />
              ) : (
                <Chips values={requester.attributes.map((a) => `${a.key} = ${a.value}`)} />
              )}
            </Row>
            <Row label="Grants">
              {requester.grantsHere.length > 0
                ? `Already holds ${requester.grantsHere.length === 1 ? 'a grant' : `${requester.grantsHere.length} grants`} here`
                : 'None on this table'}
              {requester.grantsElsewhere > 0 &&
                ` · ${requester.grantsElsewhere} other ${requester.grantsElsewhere === 1 ? 'table' : 'tables'}`}
            </Row>
            <Row label="Last 90 days">
              {requester.recentRequests === 0
                ? 'No other requests'
                : `${requester.recentRequests} other ${requester.recentRequests === 1 ? 'request' : 'requests'}, ${requester.recentRejected} rejected`}
            </Row>
          </dl>
          {requester.earlier.length > 0 && (
            <ul aria-label="Earlier requests for this table" className="tw:mt-3 tw:flex tw:flex-col tw:gap-1 tw:text-xs tw:text-tertiary">
              {requester.earlier.map((past) => (
                <li key={past.id}>
                  <b className="tw:font-semibold tw:text-secondary">{past.status.toLowerCase().replace('_', ' ')}</b>{' '}
                  <span title={past.createdAt}>{relativeTime(past.createdAt)}</span>
                  {past.decidedBy && ` by ${past.decidedBy}`}
                  {past.note && ` — “${past.note}”`}
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="tw:min-w-0">
          <Heading>The table</Heading>
          <dl className="tw:mt-2 tw:grid tw:grid-cols-[auto_1fr] tw:gap-x-4 tw:gap-y-1.5 tw:text-sm">
            <Row label="Owners">
              {table.owners.length === 0 ? (
                <None text="None recorded" />
              ) : (
                <Chips values={table.owners.map((o) => (o.type === 'team' ? `Team ${o.name}` : o.name))} />
              )}
            </Row>
            <Row label="Tier">{table.tiers.length === 0 ? <None /> : <Chips values={table.tiers} />}</Row>
            <Row label="Domain">{table.domains.length === 0 ? <None /> : <Chips values={table.domains} />}</Row>
            <Row label="Columns">
              {table.columns}
              {table.sensitiveColumns > 0 && ` · ${table.sensitiveColumns} sensitive`}
            </Row>
            <Row label="Today">
              {review.now.allowed ? (
                'They can already read it'
              ) : (
                <span>
                  Refused{review.now.blockedBy && <span className="tw:text-tertiary"> — {review.now.blockedBy}</span>}
                </span>
              )}
            </Row>
          </dl>
        </div>
      </div>

      <div>
        <Heading>If you grant it</Heading>
        <AccessColumns access={review.ifGranted} />
      </div>

      {risk.factors.length > 0 && (
        <div>
          <Heading>Why this risk</Heading>
          <ul className="tw:mt-2 tw:flex tw:flex-col tw:gap-1 tw:text-sm tw:text-secondary">
            {risk.factors.map((f) => (
              <li className="tw:flex tw:items-start tw:gap-2" key={f.code}>
                <Badge color={RISK[f.level].colour} size="sm" type="pill-color">
                  {f.level.toLowerCase()}
                </Badge>
                <span>{f.detail}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {review.suggestions.length > 0 && <Suggestions review={review} />}
    </>
  );
}

/** Column by column, what a reading of the table would show. */
export function AccessColumns({ access }: { access: ReviewAccess }) {
  if (!access.allowed) {
    return (
      <p className="tw:mt-2 tw:text-sm tw:text-secondary">
        They still could not read it
        {access.blockedBy && <span className="tw:text-tertiary"> — {access.blockedBy}</span>}.
      </p>
    );
  }
  return (
    <div className="tw:mt-2 tw:flex tw:flex-col tw:gap-2">
      {access.columns.length === 0 ? (
        <p className="tw:text-sm tw:text-tertiary">They could read it. ARAK has no columns recorded for it.</p>
      ) : (
        <div className="tw:overflow-x-auto tw:rounded-lg tw:border tw:border-secondary">
          <table className="tw:w-full tw:text-left tw:text-sm">
            <thead className="tw:bg-secondary tw:text-xs tw:text-tertiary">
              <tr>
                <th className="tw:px-3 tw:py-2 tw:font-medium">Column</th>
                <th className="tw:px-3 tw:py-2 tw:font-medium">They see</th>
                <th className="tw:px-3 tw:py-2 tw:font-medium">Because of</th>
              </tr>
            </thead>
            <tbody>
              {access.columns.map((c) => (
                <tr className="tw:border-t tw:border-secondary" key={c.name}>
                  <td className="tw:px-3 tw:py-2">
                    <span className="tw:font-mono tw:text-xs tw:text-primary">{c.name}</span>
                    {c.dataType && <span className="tw:ml-2 tw:text-xs tw:text-quaternary">{c.dataType}</span>}
                    {c.sensitiveTags.length > 0 && (
                      <span className="tw:mt-1 tw:flex tw:flex-wrap tw:gap-1">
                        {c.sensitiveTags.map((t) => (
                          <Badge color="error" key={t} size="sm" type="pill-color">
                            {t}
                          </Badge>
                        ))}
                      </span>
                    )}
                  </td>
                  <td className="tw:px-3 tw:py-2">
                    <Badge color={FATE[c.fate].colour} size="sm" type="pill-color">
                      {FATE[c.fate].label}
                      {c.fate === 'MASKED' && c.masking ? ` · ${c.masking.toLowerCase()}` : ''}
                    </Badge>
                    {c.conditional && <span className="tw:ml-2 tw:text-xs tw:text-tertiary">on some rows</span>}
                  </td>
                  <td className="tw:px-3 tw:py-2 tw:text-xs tw:text-tertiary">{c.policy ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {access.rowFilters.length > 0 && (
        <ul aria-label="Row filters" className="tw:flex tw:flex-col tw:gap-1 tw:text-xs tw:text-tertiary">
          {access.rowFilters.map((f, i) => (
            <li key={i}>
              Rows limited to <span className="tw:font-mono tw:text-secondary">{f.description}</span>
              {f.policy && ` by ${f.policy}`}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function ConflictList({ conflicts }: { conflicts: ReviewConflict[] }) {
  return (
    <ul aria-label="Conflicts" className="tw:flex tw:flex-col tw:gap-2">
      {conflicts.map((c) => {
        const look = SEVERITY[c.severity];
        const Icon = look.icon;
        return (
          <li
            className={`tw:flex tw:items-start tw:gap-2 tw:rounded-lg tw:px-3 tw:py-2 tw:text-sm tw:text-secondary ${look.box}`}
            data-code={c.code}
            key={c.code}>
            <Icon className={`tw:mt-0.5 tw:size-4 tw:shrink-0 ${look.icon_}`} />
            <span className="tw:min-w-0">
              {c.detail}
              {c.policyId && (
                <>
                  {' '}
                  <Link
                    className="tw:font-medium tw:text-brand-secondary tw:hover:underline"
                    to={`/policies/${encodeURIComponent(c.policyId)}`}>
                    Open {c.policyName ?? 'the policy'}
                  </Link>
                </>
              )}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

function Suggestions({ review }: { review: AccessReview }) {
  const navigate = useNavigate();
  return (
    <div>
      <Heading>Ways to answer</Heading>
      <ul className="tw:mt-2 tw:flex tw:flex-col tw:gap-2">
        {review.suggestions.map((s: ReviewSuggestion, i) => (
          <li
            className="tw:flex tw:flex-wrap tw:items-start tw:gap-3 tw:rounded-lg tw:border tw:border-secondary tw:px-3 tw:py-2.5"
            key={`${s.kind}-${i}`}>
            <Lightbulb02 className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
            <div className="tw:min-w-0 tw:flex-1">
              <p className="tw:text-sm tw:font-medium tw:text-primary">{s.title}</p>
              <p className="tw:mt-0.5 tw:text-xs tw:text-tertiary">{s.detail}</p>
            </div>
            {s.kind === 'CREATE_POLICY_DRAFT' && s.draft && (
              <Button
                color="secondary"
                iconLeading={FilePlus02}
                onPress={() =>
                  navigate('/policies/new', {
                    state: { draft: s.draft, from: { requestId: review.requestId, assetFqn: review.assetFqn } },
                  })
                }
                size="sm">
                Open as draft policy
              </Button>
            )}
            {s.kind === 'UPDATE_POLICY' && s.policyId && (
              <Link
                className="tw:self-center tw:text-sm tw:font-medium tw:text-brand-secondary tw:hover:underline"
                to={`/policies/${encodeURIComponent(s.policyId)}`}>
                Open the policy
              </Link>
            )}
          </li>
        ))}
      </ul>
      <p className="tw:mt-2 tw:text-xs tw:text-quaternary">
        Suggestions only. Nothing here saves or activates a policy.
      </p>
    </div>
  );
}

function Heading({ children }: { children: ReactNode }) {
  return (
    <h4 className="tw:text-xs tw:font-semibold tw:tracking-wide tw:text-quaternary tw:uppercase">
      {children}
    </h4>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="tw:text-tertiary">{label}</dt>
      <dd className="tw:min-w-0 tw:text-primary">{children}</dd>
    </>
  );
}

function Chips({ values }: { values: string[] }) {
  return (
    <span className="tw:flex tw:flex-wrap tw:gap-1">
      {values.map((v) => (
        <Badge color="gray" key={v} size="sm" type="pill-color">
          {v}
        </Badge>
      ))}
    </span>
  );
}

function None({ text = 'None' }: { text?: string }) {
  return <span className="tw:text-quaternary">{text}</span>;
}
