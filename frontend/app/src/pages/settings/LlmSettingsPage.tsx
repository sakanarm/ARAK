import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  CheckCircle,
  CpuChip01 as CpuChip,
  Key01,
  Send01,
  Server01,
  Zap,
} from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Chip as Badge } from '../../components/chips';
import { apiErrorMessage } from '../../api/client';
import {
  completeWithLlm,
  fetchLlmModels,
  fetchLlmProvider,
  fetchLlmUsers,
  fetchMyLlmSetting,
  probeLlmProvider,
  saveLlmProvider,
  saveLlmUserEnabled,
  saveMyLlmSetting,
  type LlmUserRow,
} from '../../api/llm';
import { useAuthStore } from '../../auth/authStore';

/**
 * The assistant: your own gateway first, a shared one as a fallback (M11).
 *
 * The order of the sections on this page is the feature's design. Each person
 * configures their own endpoint and their own key — that is the primary path,
 * so it is the first thing on the screen and the only section everybody sees.
 * A deployment may also run one shared gateway for anybody who has not
 * configured their own; that is administrator-owned and comes second, framed as
 * the fallback it is.
 *
 * The third section is an overview and not a control surface. An administrator
 * can see who has the assistant on and whether they are using a gateway of
 * their own, and can switch it off for somebody; they cannot write anyone
 * else's address, key or model. The server narrows the edit as well, so this is
 * a consistent screen rather than the only thing holding the line.
 *
 * Two different kinds of secret appear here, and the difference is deliberate:
 *
 * - The shared gateway's key is a *pointer* (`env:LLM_API_KEY`), because one
 *   operator sets one value and the environment is where it belongs.
 * - A personal key is a real secret, typed here and stored encrypted, because
 *   nobody is going to add an environment variable per analyst.
 *
 * Neither is ever sent back to the browser. What the page reports is whether
 * the pointer resolves, and whether a personal key exists — a boolean, not a
 * prefix and not a length.
 */
export default function LlmSettingsPage() {
  const isAdmin = useAuthStore((state) => state.hasRole('PLATFORM_ADMIN'));

  return (
    <>
      <header>
        <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">
          Assistant
        </h1>
        <p className="tw:mt-2 tw:max-w-3xl tw:text-pretty tw:text-md tw:text-tertiary">
          An LLM can draft SQL for the query console and draft policies for
          review. It is shown metadata you can already read — table names, column
          names, types and tags — and never rows of data. Anything it writes is a
          draft: it cannot activate a policy.
        </p>
      </header>

      <div className="tw:mt-8 tw:flex tw:flex-col tw:gap-8">
        <MySection />
        {isAdmin && <GatewaySection />}
        {isAdmin && <PeopleSection />}
      </div>
    </>
  );
}

/* --------------------------------------------------------------- my gateway */

function MySection() {
  const client = useQueryClient();
  const { data: mine } = useQuery({
    queryKey: ['llm', 'me'],
    queryFn: fetchMyLlmSetting,
  });
  const { data: models } = useQuery({
    queryKey: ['llm', 'models'],
    queryFn: fetchLlmModels,
  });

  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [touched, setTouched] = useState(false);

  // Seeded from the server once, then left alone: re-seeding on every refetch
  // would wipe whatever is halfway through being typed. The key is never
  // seeded, because the server does not send it.
  useEffect(() => {
    if (mine && !touched) {
      setBaseUrl(mine.ownBaseUrl ?? '');
    }
  }, [mine, touched]);

  const save = useMutation({
    mutationFn: saveMyLlmSetting,
    onSuccess: () => {
      setApiKey('');
      setTouched(false);
      client.invalidateQueries({ queryKey: ['llm'] });
    },
  });

  const [prompt, setPrompt] = useState(
    'In one sentence, what is row-level security?'
  );
  const tryIt = useMutation({ mutationFn: () => completeWithLlm(prompt) });

  const choices = models?.models ?? [];
  const canUseShared = (mine?.platformEnabled ?? false) && (mine?.sharedConfigured ?? false);
  const personalAllowed = mine?.personalAllowed ?? true;

  return (
    <Card
      icon={CpuChip}
      subtitle="Point the assistant at your own gateway with your own key. It applies to you only, and it is off until you turn it on."
      title="Your assistant">
      {mine && !personalAllowed && (
        <Notice tone="warning">
          This platform does not allow personal gateways, so the fields below are
          not offered.{' '}
          {canUseShared
            ? 'Your calls go to the shared gateway.'
            : 'There is no shared gateway switched on either, so the assistant is unavailable.'}
        </Notice>
      )}

      <div className="tw:mt-5 tw:grid tw:gap-4 tw:lg:grid-cols-2">
        <Field
          hint="Nothing is sent anywhere while this is off."
          label="Use the assistant">
          <Toggle
            checked={mine?.enabled ?? false}
            label={mine?.enabled ? 'On' : 'Off'}
            onChange={(enabled) => save.mutate({ enabled })}
          />
        </Field>

        <Field
          hint={
            models?.available === false
              ? (models.problem ?? 'The model list is unavailable.')
              : models?.personal
                ? 'Listed from your own gateway.'
                : mine?.defaultModel
                  ? `Leave on the default to follow the shared gateway (${mine.defaultModel}).`
                  : 'Choose the model your gateway should answer with.'
          }
          label="Model">
          <select
            className={INPUT}
            onChange={(event) =>
              save.mutate({ model: event.target.value || null })
            }
            value={mine?.model ?? ''}>
            <option value="">
              {mine?.defaultModel
                ? `Default (${mine.defaultModel})`
                : 'Default (none set)'}
            </option>
            {choices.map((model) => (
              <option key={model} value={model}>
                {model}
              </option>
            ))}
            {/* A model chosen before it disappeared from the gateway would
                otherwise silently reset the select to the default and look
                like the setting had never been saved. */}
            {mine?.model && !choices.includes(mine.model) && (
              <option value={mine.model}>{mine.model} (not on the gateway)</option>
            )}
          </select>
        </Field>
      </div>

      {personalAllowed && (
        <div className="tw:mt-5 tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary_subtle tw:p-4">
          <h3 className="tw:text-sm tw:font-semibold tw:text-primary">
            Your gateway
          </h3>
          <p className="tw:mt-1 tw:max-w-3xl tw:text-pretty tw:text-xs tw:text-tertiary">
            An OpenAI-compatible endpoint you have a key for. Your key is stored
            encrypted and is never shown again — not to you, not to an
            administrator.
            {canUseShared
              ? ' Leave this empty to use the shared gateway instead.'
              : ' There is no shared gateway to fall back to, so the assistant needs one here.'}
          </p>

          <div className="tw:mt-4 tw:grid tw:gap-4 tw:lg:grid-cols-2">
            <Field
              hint="Without a trailing /v1 — that is added for you."
              label="Base URL">
              <input
                className={INPUT}
                onChange={(event) => {
                  setTouched(true);
                  setBaseUrl(event.target.value);
                }}
                placeholder="https://gateway.example.com/litellm"
                value={baseUrl}
              />
            </Field>

            <Field
              hint={
                mine?.hasOwnKey
                  ? 'A key is saved. Leave this empty to keep it; type a new one to replace it.'
                  : 'Stored encrypted. It leaves this page once, on its way in.'
              }
              label="API key">
              <input
                autoComplete="off"
                className={`${INPUT} tw:font-mono`}
                onChange={(event) => {
                  setTouched(true);
                  setApiKey(event.target.value);
                }}
                placeholder={mine?.hasOwnKey ? '•••••••••• (saved)' : 'sk-…'}
                type="password"
                value={apiKey}
              />
            </Field>
          </div>

          <div className="tw:mt-4 tw:flex tw:flex-wrap tw:gap-2">
            <Button
              color="primary"
              isDisabled={save.isPending}
              onPress={() =>
                save.mutate({
                  baseUrl: baseUrl.trim(),
                  // Blank means "leave the stored key alone", which is how a
                  // change of address does not require retyping the key.
                  apiKey: apiKey.trim() || undefined,
                })
              }
              size="sm">
              {save.isPending ? 'Saving…' : 'Save my gateway'}
            </Button>
            {(mine?.ownBaseUrl || mine?.hasOwnKey) && (
              <Button
                color="secondary"
                isDisabled={save.isPending}
                onPress={() => {
                  setBaseUrl('');
                  setApiKey('');
                  save.mutate({ clearOwnGateway: true });
                }}
                size="sm">
                Forget my gateway
              </Button>
            )}
          </div>
        </div>
      )}

      <p className="tw:mt-4 tw:text-xs tw:text-tertiary">
        Status:{' '}
        {mine?.available ? (
          <Badge color="success" size="sm" type="pill-color">
            ready · {mine.effectiveModel}
          </Badge>
        ) : (
          <Badge color="gray" size="sm" type="pill-color">
            not active
          </Badge>
        )}
        {mine?.available && (
          <span className="tw:ml-2 tw:text-quaternary">
            {mine.usingOwnGateway
              ? `via your gateway at ${mine.ownBaseUrl}`
              : 'via the shared gateway'}
          </span>
        )}
        {mine && !mine.available && mine.problem && (
          <span className="tw:ml-2 tw:text-quaternary">{mine.problem}</span>
        )}
      </p>

      {save.error && (
        <Notice tone="error">
          {apiErrorMessage(save.error, 'That setting could not be saved.')}
        </Notice>
      )}

      {/* The proof. A settings page that cannot demonstrate its own setting
          leaves "is it working?" to be answered by trying the feature it
          powers, which confuses two failures with one message. */}
      <div className="tw:mt-6 tw:rounded-lg tw:border tw:border-secondary tw:bg-secondary_subtle tw:p-4">
        <h3 className="tw:text-sm tw:font-semibold tw:text-primary">Try it</h3>
        <p className="tw:mt-1 tw:text-xs tw:text-tertiary">
          One prompt, sent as you, through whichever gateway you would actually
          reach.
        </p>
        <div className="tw:mt-3 tw:flex tw:flex-wrap tw:gap-2">
          <input
            className={`${INPUT} tw:min-w-64 tw:flex-1`}
            onChange={(event) => setPrompt(event.target.value)}
            placeholder="Ask something"
            value={prompt}
          />
          <Button
            color="secondary"
            iconLeading={Send01}
            isDisabled={tryIt.isPending || !mine?.available}
            onPress={() => tryIt.mutate()}
            size="sm">
            {tryIt.isPending ? 'Asking…' : 'Send'}
          </Button>
        </div>
        {tryIt.error && (
          <Notice tone="error">
            {apiErrorMessage(tryIt.error, 'The assistant could not be reached.')}
          </Notice>
        )}
        {tryIt.data && (
          <div className="tw:mt-3 tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:p-3">
            <p className="tw:text-sm tw:whitespace-pre-wrap tw:text-primary">
              {tryIt.data.text}
            </p>
            <p className="tw:mt-2 tw:text-xs tw:text-quaternary">
              {tryIt.data.model} · {tryIt.data.promptTokens} prompt +{' '}
              {tryIt.data.completionTokens} completion tokens
              {tryIt.data.personal === false && ' · shared gateway'}
            </p>
          </div>
        )}
      </div>
    </Card>
  );
}

/* --------------------------------------------------------- shared gateway */

function GatewaySection() {
  const client = useQueryClient();
  const { data, isLoading } = useQuery({
    queryKey: ['llm', 'provider'],
    queryFn: fetchLlmProvider,
  });

  const [baseUrl, setBaseUrl] = useState('');
  const [credentialRef, setCredentialRef] = useState('');
  const [defaultModel, setDefaultModel] = useState('');
  const [touched, setTouched] = useState(false);

  useEffect(() => {
    if (data && !touched) {
      setBaseUrl(data.baseUrl ?? '');
      setCredentialRef(data.credentialRef ?? '');
      setDefaultModel(data.defaultModel ?? '');
    }
  }, [data, touched]);

  const save = useMutation({
    mutationFn: saveLlmProvider,
    onSuccess: () => {
      setTouched(false);
      client.invalidateQueries({ queryKey: ['llm'] });
    },
  });

  const probe = useMutation({ mutationFn: probeLlmProvider });

  if (isLoading) {
    return <Card title="Shared gateway">Loading…</Card>;
  }

  return (
    <Card
      icon={Server01}
      subtitle="Optional. One gateway the platform pays for, used by anybody who has not configured their own. Platform administrators only."
      title="Shared gateway">
      <div className="tw:mt-5 tw:grid tw:gap-4 tw:lg:grid-cols-2">
        <Field
          hint="An OpenAI-compatible base, without a trailing /v1."
          label="Base URL">
          <input
            className={INPUT}
            onChange={(event) => {
              setTouched(true);
              setBaseUrl(event.target.value);
            }}
            placeholder="https://gateway.example.com/litellm"
            value={baseUrl}
          />
        </Field>

        <Field
          hint="A pointer to the key, never the key itself. env:NAME reads it from the service process."
          label="Credential reference">
          <input
            className={`${INPUT} tw:font-mono`}
            onChange={(event) => {
              setTouched(true);
              setCredentialRef(event.target.value);
            }}
            placeholder="env:LLM_API_KEY"
            value={credentialRef}
          />
        </Field>

        <Field
          hint="What somebody gets before they choose one of their own. Optional."
          label="Default model">
          <input
            className={INPUT}
            onChange={(event) => {
              setTouched(true);
              setDefaultModel(event.target.value);
            }}
            placeholder="gpt-5"
            value={defaultModel}
          />
        </Field>

        <div className="tw:flex tw:flex-col tw:gap-4">
          <Field
            hint="Off means nobody falls back to this gateway. Their own gateways keep working."
            label="Shared gateway available">
            <Toggle
              checked={data?.enabled ?? false}
              label={data?.enabled ? 'On' : 'Off'}
              onChange={(enabled) => save.mutate({ enabled })}
            />
          </Field>

          <Field
            hint="Off locks the assistant to this one vetted endpoint. Nobody's saved settings are lost."
            label="People may use their own gateway">
            <Toggle
              checked={data?.allowPersonal ?? true}
              label={data?.allowPersonal ?? true ? 'Allowed' : 'Not allowed'}
              onChange={(allowPersonal) => save.mutate({ allowPersonal })}
            />
          </Field>
        </div>
      </div>

      {data?.configured && (
        <p className="tw:mt-4 tw:text-xs tw:text-tertiary">
          {data.secretPresent ? (
            <span className="tw:inline-flex tw:items-center tw:gap-1.5 tw:text-utility-success-700">
              <CheckCircle className="tw:size-3.5" />
              The key behind {data.credentialRef} is present on the service.
            </span>
          ) : (
            <span className="tw:inline-flex tw:items-center tw:gap-1.5 tw:text-utility-error-700">
              <AlertTriangle className="tw:size-3.5" />
              {data.secretProblem}
            </span>
          )}
          {data.updatedBy && (
            <span className="tw:ml-2 tw:text-quaternary">
              · last changed by {data.updatedBy}
            </span>
          )}
        </p>
      )}

      <div className="tw:mt-5 tw:flex tw:flex-wrap tw:gap-2">
        <Button
          color="primary"
          isDisabled={save.isPending}
          onPress={() =>
            save.mutate({
              baseUrl: baseUrl.trim(),
              credentialRef: credentialRef.trim(),
              defaultModel: defaultModel.trim(),
            })
          }
          size="sm">
          {save.isPending ? 'Saving…' : 'Save gateway'}
        </Button>
        <Button
          color="secondary"
          iconLeading={Zap}
          isDisabled={probe.isPending || !data?.configured}
          onPress={() => probe.mutate()}
          size="sm">
          {probe.isPending ? 'Asking…' : 'Test and list models'}
        </Button>
      </div>

      {save.error && (
        <Notice tone="error">
          {apiErrorMessage(save.error, 'The gateway could not be saved.')}
        </Notice>
      )}

      {probe.data && (
        <Notice tone={probe.data.reachable ? 'success' : 'error'}>
          {probe.data.reachable ? (
            <>
              <strong>{probe.data.modelCount} models</strong> are available at{' '}
              {probe.data.baseUrl}.
              <div className="tw:mt-2 tw:flex tw:flex-wrap tw:gap-1.5">
                {probe.data.models.map((model) => (
                  <span
                    className="tw:rounded-md tw:bg-utility-brand-50 tw:px-2 tw:py-0.5 tw:font-mono tw:text-xs tw:text-utility-brand-700"
                    key={model}>
                    {model}
                  </span>
                ))}
              </div>
            </>
          ) : (
            probe.data.problem
          )}
        </Notice>
      )}
      {probe.error && (
        <Notice tone="error">
          {apiErrorMessage(probe.error, 'The gateway could not be reached.')}
        </Notice>
      )}
    </Card>
  );
}

/* ---------------------------------------------------------------- everybody */

function PeopleSection() {
  const client = useQueryClient();
  const { data: rows } = useQuery({
    queryKey: ['llm', 'users'],
    queryFn: fetchLlmUsers,
  });

  const save = useMutation({
    mutationFn: ({
      principalId,
      enabled,
    }: {
      principalId: string;
      enabled: boolean;
    }) => saveLlmUserEnabled(principalId, enabled),
    onSuccess: () => client.invalidateQueries({ queryKey: ['llm'] }),
  });

  const on = useMemo(
    () => (rows ?? []).filter((row) => row.enabled).length,
    [rows]
  );
  const own = useMemo(
    () => (rows ?? []).filter((row) => row.ownBaseUrl).length,
    [rows]
  );

  return (
    <Card
      icon={Key01}
      subtitle="Who has the assistant on, and whose gateway they are using. Everybody configures their own address, key and model — the only thing you can change here is whether the assistant is offered to them at all."
      title={`Who is using it${rows ? ` · ${on} of ${rows.length} on, ${own} with their own gateway` : ''}`}>
      <div className="tw:mt-4 tw:overflow-x-auto">
        <table className="tw:w-full tw:text-sm">
          <thead>
            <tr className="tw:border-b tw:border-secondary tw:text-left tw:text-xs tw:font-semibold tw:uppercase tw:tracking-wide tw:text-tertiary">
              <th className="tw:py-2 tw:pr-4">Account</th>
              <th className="tw:py-2 tw:pr-4">Assistant</th>
              <th className="tw:py-2 tw:pr-4">Gateway</th>
              <th className="tw:py-2 tw:pr-4">Model</th>
              <th className="tw:py-2">Last changed</th>
            </tr>
          </thead>
          <tbody>
            {(rows ?? []).map((row) => (
              <PersonRow
                key={row.principalId}
                onChange={(enabled) =>
                  save.mutate({ principalId: row.principalId, enabled })
                }
                row={row}
              />
            ))}
          </tbody>
        </table>
      </div>
      {save.error && (
        <Notice tone="error">
          {apiErrorMessage(save.error, 'That setting could not be saved.')}
        </Notice>
      )}
    </Card>
  );
}

function PersonRow({
  onChange,
  row,
}: {
  onChange: (enabled: boolean) => void;
  row: LlmUserRow;
}) {
  const complete = Boolean(row.ownBaseUrl) && row.hasOwnKey;

  return (
    <tr className="tw:border-b tw:border-secondary tw:last:border-0">
      <td className="tw:py-2.5 tw:pr-4">
        <div className="tw:font-medium tw:text-primary">
          {row.displayName ?? row.username}
        </div>
        <div className="tw:font-mono tw:text-xs tw:text-tertiary">
          {row.username} · {row.source}
        </div>
      </td>
      <td className="tw:py-2.5 tw:pr-4">
        <Toggle
          checked={row.enabled}
          label={row.enabled ? 'On' : 'Off'}
          onChange={onChange}
        />
      </td>
      <td className="tw:py-2.5 tw:pr-4">
        {complete ? (
          <>
            <Badge color="brand" size="sm" type="pill-color">
              their own
            </Badge>
            <div className="tw:mt-1 tw:max-w-64 tw:truncate tw:font-mono tw:text-xs tw:text-tertiary">
              {row.ownBaseUrl}
            </div>
          </>
        ) : row.ownBaseUrl || row.hasOwnKey ? (
          // Half a gateway is a state somebody is stuck in, not a state they
          // chose. Naming it is how an administrator can help instead of
          // guessing why the assistant is quiet for one person.
          <Badge color="warning" size="sm" type="pill-color">
            {row.ownBaseUrl ? 'address, no key' : 'key, no address'}
          </Badge>
        ) : (
          <Badge color="gray" size="sm" type="pill-color">
            shared
          </Badge>
        )}
      </td>
      <td className="tw:py-2.5 tw:pr-4 tw:font-mono tw:text-xs tw:text-tertiary">
        {row.model ?? 'default'}
      </td>
      <td className="tw:py-2.5 tw:text-xs tw:text-tertiary">
        {row.updatedBy ? `${row.updatedBy}` : '—'}
      </td>
    </tr>
  );
}

/* ------------------------------------------------------------------- pieces */

const INPUT =
  'tw:w-full tw:rounded-md tw:border tw:border-secondary tw:bg-primary tw:px-2.5 tw:py-2 tw:text-sm tw:text-primary tw:placeholder:text-quaternary';

function Card({
  children,
  icon: Icon,
  subtitle,
  title,
}: {
  children: ReactNode;
  icon?: (props: { className?: string }) => ReactNode;
  subtitle?: string;
  title: string;
}) {
  return (
    <section className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5">
      <div className="tw:flex tw:items-start tw:gap-3">
        {Icon && (
          <span className="tw:flex tw:size-9 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-utility-brand-50">
            <Icon className="tw:size-4.5 tw:text-utility-brand-700" />
          </span>
        )}
        <div className="tw:min-w-0">
          <h2 className="tw:text-md tw:font-semibold tw:text-primary">
            {title}
          </h2>
          {subtitle && (
            <p className="tw:mt-1 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">
              {subtitle}
            </p>
          )}
        </div>
      </div>
      {children}
    </section>
  );
}

function Field({
  children,
  hint,
  label,
}: {
  children: ReactNode;
  hint?: string;
  label: string;
}) {
  return (
    <div>
      <label className="tw:text-sm tw:font-medium tw:text-secondary">
        {label}
      </label>
      <div className="tw:mt-1.5">{children}</div>
      {hint && <p className="tw:mt-1.5 tw:text-xs tw:text-tertiary">{hint}</p>}
    </div>
  );
}

/**
 * A switch.
 *
 * A button with `role="switch"` rather than a checkbox, because the change is
 * saved the moment it is pressed — there is no form to submit — and a switch is
 * what tells a screen reader that.
 */
function Toggle({
  checked,
  label,
  onChange,
}: {
  checked: boolean;
  label: string;
  onChange: (value: boolean) => void;
}) {
  return (
    <button
      aria-checked={checked}
      className="tw:flex tw:cursor-pointer tw:items-center tw:gap-2.5"
      onClick={() => onChange(!checked)}
      role="switch"
      type="button">
      <span
        className={`tw:relative tw:h-5 tw:w-9 tw:shrink-0 tw:rounded-full tw:transition ${
          checked ? 'tw:bg-brand-solid' : 'tw:bg-quaternary'
        }`}>
        <span
          className={`tw:absolute tw:top-0.5 tw:size-4 tw:rounded-full tw:bg-primary tw:transition ${
            checked ? 'tw:left-4.5' : 'tw:left-0.5'
          }`}
        />
      </span>
      <span className="tw:text-sm tw:text-secondary">{label}</span>
    </button>
  );
}

function Notice({
  children,
  tone,
}: {
  children: ReactNode;
  tone: 'error' | 'success' | 'warning';
}) {
  const tint = {
    error: 'tw:bg-utility-error-50 tw:text-utility-error-700',
    success: 'tw:bg-utility-success-50 tw:text-utility-success-700',
    warning: 'tw:bg-utility-warning-50 tw:text-utility-warning-700',
  }[tone];

  return (
    <div className={`tw:mt-4 tw:rounded-lg tw:px-3 tw:py-2.5 tw:text-sm ${tint}`}>
      {children}
    </div>
  );
}
