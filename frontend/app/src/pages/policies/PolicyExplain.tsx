import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import greet from '../../assets/mascot/greet.png';
import { apiErrorMessage } from '../../api/client';
import { assistExplainPolicy } from '../../api/llm';
import { useAssistReady } from '../../assist/useAssist';
import { FIELD } from './controls';

/**
 * NokRak's reading of a policy, for somebody who would rather be told in words
 * what it does, what it lands on and how it meets the other policies there.
 *
 * <p>The server loads the policy itself and sends the model the document, where
 * it is bound and the verdicts the Conflicts tab works out -- no rows, and no
 * one's name from its exemptions or approvers. What comes back is a reading
 * aid and is labelled as NokRak's; the Simulator, which runs the engine, is
 * what decides. Nothing here writes, and the page is whole without it: it is
 * not drawn unless this job is offered to the reader.
 */
export default function PolicyExplain({ policyId }: { policyId: string }) {
  const ready = useAssistReady('EXPLAIN_POLICY');
  const [language, setLanguage] = useState('English');
  const explain = useMutation({
    mutationFn: () => assistExplainPolicy({ policyId, language }),
  });

  if (!ready) {
    return null;
  }

  return (
    <section
      aria-label="NokRak explains this policy"
      className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-4">
      <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
        <h2 className="tw:mr-auto tw:text-sm tw:font-semibold tw:text-primary">Ask NokRak</h2>
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
          NokRak is reading the policy and the others it meets…
        </p>
      ) : explain.error ? (
        <p className="tw:mt-3 tw:text-sm tw:text-error-primary" role="alert">
          {apiErrorMessage(explain.error, 'NokRak could not explain this policy.')}
        </p>
      ) : explain.data ? (
        <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-2">
          <p className="tw:whitespace-pre-line tw:text-pretty tw:text-sm tw:leading-6 tw:text-secondary">
            {explain.data.text}
          </p>
          <p className="tw:text-xs tw:text-tertiary">
            NokRak wrote this from the policy and where it is bound — it has not seen any data. What
            the engine decides is what the{' '}
            <Link className="tw:font-medium tw:text-brand-secondary tw:underline" to="/simulator">
              Simulator
            </Link>{' '}
            shows.
            {explain.data.model ? ` · ${explain.data.model}` : ''}
          </p>
        </div>
      ) : (
        <p className="tw:mt-3 tw:text-xs tw:text-tertiary">
          NokRak reads this policy, where it is bound and the other policies it meets there, and
          says in words what it does. It sees no data, and nobody named in its exemptions or
          approvers.
        </p>
      )}
    </section>
  );
}
