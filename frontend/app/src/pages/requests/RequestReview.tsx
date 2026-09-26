import { useState, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import {
  AlertTriangle,
  Database01,
  FilePlus02,
  InfoCircle,
  Lightbulb02,
  Stars02,
  User01,
  Users01,
  XCircle,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { relativeTime } from '../../components/widgets';
import { apiErrorMessage } from '../../api/client';
import {
  describePeople,
  describeTables,
  fetchAccessReview,
  type AccessReview,
  type PreauthCoverage,
  type ColumnFateKind,
  type ConflictSeverity,
  type ReviewAccess,
  type ReviewConflict,
  type ReviewSuggestion,
  type RiskLevel,
  type Recommendation,
  type Verdict,
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

const VERDICT: Record<Verdict, { label: string; colour: 'success' | 'warning' | 'error' | 'gray'; bar: string }> = {
  APPROVE: { label: 'Approve', colour: 'success', bar: 'tw:bg-fg-success-secondary' },
  REVIEW: { label: 'Look closer', colour: 'warning', bar: 'tw:bg-fg-warning-secondary' },
  REJECT: { label: 'Reject', colour: 'error', bar: 'tw:bg-fg-error-secondary' },
  DECLINE: { label: 'Nothing to approve', colour: 'gray', bar: 'tw:bg-fg-quaternary' },
};

/**
 * The review of one request, drawn between the request and the answer.
 *
 * <p>The suggestion waits behind a button rather than leading the review: a
 * reviewer who reads a number before the facts anchors on it. Asked for, it
 * comes first, with every point that made it.
 */
export function RequestReview({ requestId }: { requestId: string }) {
  const review = useAccessReview(requestId, null);
  const [suggesting, setSuggesting] = useState(false);

  return (
    <section
      aria-label="Review"
      className="tw:min-w-0 tw:overflow-hidden tw:rounded-xl tw:border tw:border-secondary">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-x-3 tw:gap-y-2 tw:border-b tw:border-secondary tw:bg-secondary tw:px-4 tw:py-3">
        <div className="tw:flex tw:min-w-0 tw:flex-1 tw:items-center tw:gap-2">
          <h3 className="tw:text-sm tw:font-semibold tw:text-primary">Before you answer</h3>
          {review.data && (
            <Badge color={RISK[review.data.risk.level].colour} size="sm" type="pill-color">
              {RISK[review.data.risk.level].label}
            </Badge>
          )}
        </div>
        {review.data && (
          <Button
            aria-expanded={suggesting}
            color={suggesting ? 'secondary' : 'primary'}
            iconLeading={Stars02}
            onPress={() => setSuggesting((on) => !on)}
            size="sm">
            {suggesting ? 'Hide suggestion' : 'Suggest'}
          </Button>
        )}
      </div>
      <div className="tw:flex tw:flex-col tw:gap-6 tw:p-4 tw:sm:p-5">
        {review.isLoading && <p className="tw:text-sm tw:text-tertiary">Reading the request…</p>}
        {review.isError && (
          <p className="tw:text-sm tw:text-error-primary" role="alert">
            {apiErrorMessage(review.error, 'The review could not be read.')}
          </p>
        )}
        {review.data && suggesting && <RecommendationCard recommendation={review.data.recommendation} />}
        {review.data && <ReviewBody review={review.data} />}
      </div>
    </section>
  );
}

/**
 * Whether ARAK would approve, how strongly, and every point that says so.
 *
 * <p>The score is shown as a lean, not a probability: it is a neutral 50 plus
 * the signals below it, and the list adds up to it.
 */
export function RecommendationCard({ recommendation }: { recommendation: Recommendation }) {
  const look = VERDICT[recommendation.verdict] ?? VERDICT.REVIEW;
  const weighed = recommendation.verdict !== 'DECLINE';
  return (
    <section
      aria-label="Suggestion"
      className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4 tw:shadow-xs">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-4">
        <div className="tw:flex tw:min-w-0 tw:flex-1 tw:items-center tw:gap-3">
          <span
            aria-hidden
            className="tw:flex tw:size-10 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-utility-brand-50">
            <Stars02 className="tw:size-5 tw:text-fg-brand-primary" />
          </span>
          <div className="tw:min-w-0">
            <p className="tw:text-xs tw:text-tertiary">ARAK suggests</p>
            <p className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
              <Badge color={look.colour} size="md" type="pill-color">
                {look.label}
              </Badge>
              {recommendation.suggestedDays !== null && (
                <span className="tw:text-sm tw:font-medium tw:text-secondary">
                  for {recommendation.suggestedDays} days
                </span>
              )}
            </p>
          </div>
        </div>
        {weighed && (
          <div className="tw:w-full tw:sm:w-56">
            <div className="tw:flex tw:items-baseline tw:justify-between tw:gap-2">
              <span className="tw:text-xs tw:text-tertiary">Leans to approve</span>
              <span
                aria-label={`${recommendation.score}% towards approving`}
                className="tw:text-display-xs tw:font-semibold tw:tabular-nums tw:text-primary">
                {recommendation.score}%
              </span>
            </div>
            <div
              aria-hidden
              className="tw:relative tw:mt-1.5 tw:h-2 tw:overflow-hidden tw:rounded-full tw:bg-quaternary">
              <div
                className={`tw:h-full tw:rounded-full ${look.bar}`}
                style={{ width: `${recommendation.score}%` }}
              />
            </div>
          </div>
        )}
      </div>

      <p className="tw:mt-3 tw:text-sm tw:text-secondary">{recommendation.summary}</p>

      {recommendation.signals.length > 0 && (
        <ul aria-label="Why" className="tw:mt-3 tw:flex tw:flex-col tw:divide-y tw:divide-secondary tw:rounded-lg tw:border tw:border-secondary">
          {weighed && (
            <li className="tw:flex tw:items-center tw:gap-3 tw:px-3 tw:py-2 tw:text-xs tw:text-tertiary">
              <span className="tw:w-10 tw:shrink-0 tw:text-right tw:font-semibold tw:tabular-nums">50</span>
              <span>Where every request starts</span>
            </li>
          )}
          {recommendation.signals.map((signal) => (
            <li className="tw:flex tw:items-start tw:gap-3 tw:px-3 tw:py-2 tw:text-sm" data-code={signal.code} key={signal.code}>
              {weighed ? (
                <span
                  className={`tw:w-10 tw:shrink-0 tw:text-right tw:font-semibold tw:tabular-nums ${
                    signal.points > 0 ? 'tw:text-success-primary' : signal.points < 0 ? 'tw:text-error-primary' : 'tw:text-tertiary'
                  }`}>
                  {signal.points > 0 ? `+${signal.points}` : signal.points < 0 ? `−${-signal.points}` : '0'}
                </span>
              ) : (
                <InfoCircle className="tw:mt-0.5 tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
              )}
              <span className="tw:min-w-0 tw:text-secondary">{signal.detail}</span>
            </li>
          ))}
        </ul>
      )}

      <p className="tw:mt-3 tw:text-xs tw:text-quaternary">
        A suggestion only, worked out from the facts below. You decide; nothing is approved until you answer.
      </p>
    </section>
  );
}

function ReviewBody({ review }: { review: AccessReview }) {
  const { requester, table, risk } = review;
  return (
    <>
      {review.conflicts.length > 0 && <ConflictList conflicts={review.conflicts} />}

      <div className="tw:grid tw:gap-4 tw:lg:grid-cols-2">
        <Panel icon={User01} title="Who is asking">
          <div className="tw:flex tw:min-w-0 tw:flex-wrap tw:items-center tw:gap-x-2 tw:gap-y-1">
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
          <dl className={FACTS}>
            {requester.email && (
              <Row label="Email">
                <span className="tw:block tw:truncate" title={requester.email}>
                  {requester.email}
                </span>
              </Row>
            )}
            {requester.source && <Row label="Directory">{requester.source}</Row>}
            <Row label="Groups">
              {requester.memberships.length === 0 ? (
                <None />
              ) : (
                <span className="tw:flex tw:min-w-0 tw:flex-wrap tw:gap-1">
                  {requester.memberships.map((m) => {
                    const label = `${m.kind === 'team' ? 'Team ' : ''}${m.displayName || m.name}`;
                    return (
                      <Link
                        aria-label={`Open ${label}: its members and attributes`}
                        className="tw:max-w-full tw:rounded-full tw:transition tw:hover:opacity-80 tw:focus-visible:outline-2 tw:focus-visible:outline-offset-1 tw:focus-visible:outline-brand"
                        key={m.id}
                        title={`${label} — open its members and attributes`}
                        to={`/principals/${encodeURIComponent(m.id)}`}>
                        <Badge className="tw:max-w-full tw:cursor-pointer" color="brand" size="sm" type="pill-color">
                          <span className="tw:truncate">{label}</span>
                        </Badge>
                      </Link>
                    );
                  })}
                </span>
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
            <ul
              aria-label="Earlier requests for this table"
              className="tw:mt-3 tw:flex tw:flex-col tw:gap-1 tw:border-t tw:border-secondary tw:pt-3 tw:text-xs tw:text-tertiary">
              {requester.earlier.map((past) => (
                <li className="tw:break-words" key={past.id}>
                  <b className="tw:font-semibold tw:text-secondary">{past.status.toLowerCase().replace('_', ' ')}</b>{' '}
                  <span title={past.createdAt}>{relativeTime(past.createdAt)}</span>
                  {past.decidedBy && ` by ${past.decidedBy}`}
                  {past.note && ` — “${past.note}”`}
                </li>
              ))}
            </ul>
          )}
        </Panel>

        {review.coverage && review.target ? (
          <Panel icon={Database01} title="What it covers">
            <dl className={`${FACTS} tw:mt-0`}>
              <Row label="Under">
                <span className="tw:block tw:truncate tw:font-mono tw:text-xs" title={review.assetFqn}>
                  {review.assetFqn}
                </span>
                <span className="tw:text-xs tw:text-tertiary">{review.coverage.scopeType.toLowerCase()}</span>
              </Row>
              <Row label="Tables where">{describeTables(review.target)}</Row>
              <Row label="For">{describePeople(review.target)}</Row>
              <Row label="Today">
                {count(review.coverage.tables, 'table')} ·{' '}
                {review.coverage.peopleAtLeast && 'at least '}
                {count(review.coverage.people, 'person', 'people')}
              </Row>
            </dl>
          </Panel>
        ) : (
        <Panel icon={Database01} title="The table">
          <dl className={`${FACTS} tw:mt-0`}>
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
            {review.now && (
              <Row label="Today">
                {review.now.allowed ? (
                  'They can already read it'
                ) : (
                  <span>
                    Refused{review.now.blockedBy && <span className="tw:text-tertiary"> — {review.now.blockedBy}</span>}
                  </span>
                )}
              </Row>
            )}
          </dl>
        </Panel>
        )}
      </div>

      {review.coverage ? (
        <Reaches coverage={review.coverage} />
      ) : (
        review.ifGranted && (
          <div>
            <Heading>If you grant it</Heading>
            <AccessColumns access={review.ifGranted} />
          </div>
        )
      )}

      {risk.factors.length > 0 && (
        <div>
          <Heading>Why this risk</Heading>
          <ul className="tw:mt-2 tw:flex tw:flex-col tw:gap-1.5 tw:text-sm tw:text-secondary">
            {risk.factors.map((f) => (
              <li className="tw:flex tw:items-start tw:gap-2" key={f.code}>
                <Badge className="tw:w-16 tw:shrink-0 tw:justify-center" color={RISK[f.level].colour} size="sm" type="pill-color">
                  {f.level.toLowerCase()}
                </Badge>
                <span className="tw:min-w-0">{f.detail}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {review.suggestions.length > 0 && <Suggestions review={review} />}
    </>
  );
}

/**
 * A pre-authorization's reach today: the tables and the people, named for the
 * people deciding it. It changes as tables are tagged and people join, which is
 * the point of it and the reason to read it.
 */
function Reaches({ coverage }: { coverage: PreauthCoverage }) {
  return (
    <div className="tw:grid tw:gap-4 tw:lg:grid-cols-2">
      <div className="tw:min-w-0">
        <Heading>Tables it reaches today</Heading>
        {coverage.tableSample.length === 0 ? (
          <p className="tw:mt-2 tw:text-sm tw:text-tertiary">None yet.</p>
        ) : (
          <ul aria-label="Tables it reaches" className="tw:mt-2 tw:flex tw:flex-col tw:gap-1 tw:text-sm">
            {coverage.tableSample.map((fqn) => (
              <li className="tw:truncate tw:font-mono tw:text-xs tw:text-secondary" key={fqn} title={fqn}>
                {fqn}
              </li>
            ))}
          </ul>
        )}
        <More shown={coverage.tableSample.length} total={coverage.tables} />
      </div>
      <div className="tw:min-w-0">
        <Heading>People it reaches today</Heading>
        {coverage.peopleSample.length === 0 ? (
          <p className="tw:mt-2 tw:text-sm tw:text-tertiary">
            {coverage.people === 0 ? 'Nobody yet.' : 'Not named here.'}
          </p>
        ) : (
          <ul aria-label="People it reaches" className="tw:mt-2 tw:flex tw:flex-wrap tw:gap-1">
            {coverage.peopleSample.map((name) => (
              <li key={name}>
                <Badge color="gray" size="sm" type="pill-color">
                  <Users01 aria-hidden className="tw:mr-1 tw:size-3" />
                  {name}
                </Badge>
              </li>
            ))}
          </ul>
        )}
        <More shown={coverage.peopleSample.length} total={coverage.people} atLeast={coverage.peopleAtLeast} />
      </div>
    </div>
  );
}

function More({ shown, total, atLeast = false }: { shown: number; total: number; atLeast?: boolean }) {
  if (shown === 0 || (shown >= total && !atLeast)) return null;
  return (
    <p className="tw:mt-1.5 tw:text-xs tw:text-quaternary">
      The first {shown} of {atLeast ? 'at least ' : ''}
      {total}.
    </p>
  );
}

function count(n: number, one: string, many = `${one}s`) {
  return `${n} ${n === 1 ? one : many}`;
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

/** The rows of a fact list: the labels line up, and a long value wraps inside its column. */
const FACTS =
  'tw:mt-3 tw:grid tw:grid-cols-[6.5rem_minmax(0,1fr)] tw:items-baseline tw:gap-x-3 tw:gap-y-2 tw:text-sm';

/** A titled box, one of a pair side by side. */
function Panel({ icon: Icon, title, children }: { icon: typeof User01; title: string; children: ReactNode }) {
  return (
    <div className="tw:min-w-0 tw:rounded-lg tw:border tw:border-secondary tw:p-4">
      <h4 className="tw:mb-3 tw:flex tw:items-center tw:gap-2 tw:text-xs tw:font-semibold tw:tracking-wide tw:text-quaternary tw:uppercase">
        <Icon aria-hidden className="tw:size-4 tw:text-fg-quaternary" />
        {title}
      </h4>
      {children}
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

/**
 * Values as chips that wrap, and truncate when one is wider than the column:
 * a domain FQN can be longer than the whole panel. The title keeps it readable.
 */
function Chips({ values }: { values: string[] }) {
  return (
    <span className="tw:flex tw:min-w-0 tw:flex-wrap tw:gap-1">
      {values.map((v) => (
        <Badge className="tw:max-w-full" color="gray" key={v} size="sm" type="pill-color">
          <span className="tw:truncate" title={v}>
            {v}
          </span>
        </Badge>
      ))}
    </span>
  );
}

function None({ text = 'None' }: { text?: string }) {
  return <span className="tw:text-quaternary">{text}</span>;
}
