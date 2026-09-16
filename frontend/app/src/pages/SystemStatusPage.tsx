import { useQuery } from '@tanstack/react-query';
import { fetchSystemVersion } from '../api/client';

/**
 * Placeholder home screen. It exists so the whole chain is provable on day one:
 * Vite serves it, Tailwind resolves the vendored tokens, the proxy reaches
 * Dropwizard, and TanStack Query renders what comes back.
 */
export default function SystemStatusPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['system-version'],
    queryFn: fetchSystemVersion,
  });

  return (
    <main className="tw:mx-auto tw:max-w-3xl tw:px-6 tw:py-12">
      <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">Data Access Control</h1>
      <p className="tw:mt-2 tw:text-md tw:text-tertiary">
        Subscription and data policies over OpenMetadata governance.
      </p>

      <section className="tw:mt-8 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-6">
        <h2 className="tw:text-lg tw:font-medium tw:text-primary">Service</h2>
        {isLoading && <p className="tw:mt-2 tw:text-sm tw:text-tertiary">Checking…</p>}
        {error && (
          <p className="tw:mt-2 tw:text-sm tw:text-error-primary">
            Backend unreachable. Start it with: java -jar backend/dac-service/target/dac-service.jar server conf/dac.yml
          </p>
        )}
        {data && (
          <dl className="tw:mt-4 tw:grid tw:grid-cols-[max-content_1fr] tw:gap-x-6 tw:gap-y-2 tw:text-sm">
            <dt className="tw:text-tertiary">Version</dt>
            <dd className="tw:text-primary">{data.version}</dd>
            <dt className="tw:text-tertiary">OpenMetadata</dt>
            <dd className="tw:text-primary">{data.openMetadataBaseUrl}</dd>
            <dt className="tw:text-tertiary">Expected OM version</dt>
            <dd className="tw:text-primary">{data.openMetadataExpectedVersion}</dd>
          </dl>
        )}
      </section>
    </main>
  );
}
