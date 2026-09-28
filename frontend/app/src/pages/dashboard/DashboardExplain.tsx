import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import greet from '../../assets/mascot/greet.png';
import { apiErrorMessage } from '../../api/client';
import { assistExplainDashboard, type DashboardFocus } from '../../api/llm';
import { useAssistReady } from '../../assist/useAssist';
import { FIELD } from '../policies/controls';

const FOCUSES: { value: DashboardFocus; label: string }[] = [
  { value: 'ALL', label: 'Whole dashboard' },
  { value: 'COVERAGE', label: 'Coverage' },
  { value: 'ACTIVITY', label: 'Queries & refusals' },
  { value: 'ACCESS', label: 'Access & grants' },
  { value: 'REQUESTS', label: 'Requests' },
  { value: 'HEALTH', label: 'Platform health' },
];

/**
 * NokRak's reading of the dashboard: what stands out, what looks unusual and
 * where to look next, for the window and label on screen.
 *
 * <p>One panel with a choice of what to read rather than a button on every
 * card, which are too narrow to hold an answer. The server reads the dashboard
 * as the reader and sends the model its counts with every person numbered, and
 * puts the names back before the answer comes here. What comes back is a
 * reading aid and is labelled as NokRak's; the dashboard's numbers are what
 * count. Nothing here writes, and the page is whole without it: it is not drawn
 * unless this job is offered to the reader.
 */
export default function DashboardExplain({ days, label }: { days: number; label: string }) {
  const ready = useAssistReady('EXPLAIN_DASHBOARD');
  const [focus, setFocus] = useState<DashboardFocus>('ALL');
  const [language, setLanguage] = useState('English');
  const explain = useMutation({
    mutationFn: () => assistExplainDashboard({ days, label, focus, language }),
  });

  if (!ready) {
    return null;
  }

  return (
    <section
      aria-label="NokRak explains this dashboard"
      className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <h2 className="tw:mr-auto tw:text-sm tw:font-semibold tw:text-primary">Ask NokRak</h2>
        <select
          aria-label="About"
          className={`${FIELD} tw:py-1.5`}
          disabled={explain.isPending}
          onChange={(event) => setFocus(event.target.value as DashboardFocus)}
          value={focus}>
          {FOCUSES.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <select
          aria-label="Explain in"
          className={`${FIELD} tw:py-1.5`}
          disabled={explain.isPending}
          onChange={(event) => setLanguage(event.target.value)}
          value={language}>
          <option value="English">English</option>
          <option value="Thai">ไทย (Thai)</option>
        </select>
        <Button
          color="secondary"
          iconLeading={<img alt="" className="tw:size-5 tw:object-contain" src={greet} />}
          isLoading={explain.isPending}
          onPress={() => explain.mutate()}
          size="sm">
          {explain.data ? 'Explain again' : 'Explain with NokRak'}
        </Button>
      </div>

      {explain.isPending ? (
        <p aria-live="polite" className="tw:mt-3 tw:text-sm tw:text-secondary">
          NokRak is reading the dashboard…
        </p>
      ) : explain.error ? (
        <p className="tw:mt-3 tw:text-sm tw:text-error-primary" role="alert">
          {apiErrorMessage(explain.error, 'NokRak could not explain the dashboard.')}
        </p>
      ) : explain.data ? (
        <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
          <p className="tw:whitespace-pre-line tw:text-pretty tw:text-sm tw:leading-6 tw:text-secondary">
            {explain.data.text}
          </p>
          <p className="tw:text-xs tw:text-tertiary">
            NokRak wrote this from the counts on this page — it has not seen any data, and people
            were numbered for it rather than named. The numbers on the dashboard are what count.
            {explain.data.model ? ` · ${explain.data.model}` : ''}
          </p>
        </div>
      ) : (
        <p className="tw:mt-3 tw:text-xs tw:text-tertiary">
          NokRak reads this dashboard's counts for the window and label above and says what stands
          out and where to look next. It sees no data, and no one's name: people are numbered
          before anything leaves ARAK.
        </p>
      )}
    </section>
  );
}
