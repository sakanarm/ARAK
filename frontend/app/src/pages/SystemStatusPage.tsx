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
    <main className="mx-auto max-w-3xl px-6 py-12">
      <h1 className="text-display-sm font-semibold text-primary">Data Access Control</h1>
      <p className="mt-2 text-md text-tertiary">
        Subscription and data policies over OpenMetadata governance.
      </p>

      <section className="mt-8 rounded-xl border border-secondary bg-primary p-6">
        <h2 className="text-lg font-medium text-primary">Service</h2>
        {isLoading && <p className="mt-2 text-sm text-tertiary">Checking…</p>}
        {error && (
          <p className="mt-2 text-sm text-error-primary">
            Backend unreachable. Start it with: java -jar backend/dac-service/target/dac-service.jar server conf/dac.yml
          </p>
        )}
        {data && (
          <dl className="mt-4 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
            <dt className="text-tertiary">Version</dt>
            <dd className="text-primary">{data.version}</dd>
            <dt className="text-tertiary">OpenMetadata</dt>
            <dd className="text-primary">{data.openMetadataBaseUrl}</dd>
            <dt className="text-tertiary">Expected OM version</dt>
            <dd className="text-primary">{data.openMetadataExpectedVersion}</dd>
          </dl>
        )}
      </section>
    </main>
  );
}
