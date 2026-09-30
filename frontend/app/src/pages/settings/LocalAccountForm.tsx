import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../../api/client';
import { createLocalPrincipal, type PrincipalDetail } from '../../api/governance';
import { Field, Select, TextField } from '../policies/controls';
import { ROLES } from './appRoles';
import { ScopePicker } from './pickers';

/**
 * Creates a local account.
 *
 * Local only, and the form says why rather than offering a directory choice
 * that would be a lie: an Entra account created here would exist until the
 * next sync and then not.
 */
export function LocalAccountForm({
  onDone,
  onSaved,
}: {
  onDone: () => void;
  onSaved: (created: PrincipalDetail) => void;
}) {
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [principalType, setPrincipalType] = useState<'USER' | 'SERVICE' | 'GROUP'>('USER');
  const [password, setPassword] = useState('');
  const [appRole, setAppRole] = useState('');
  const [scopeFqn, setScopeFqn] = useState<string | null>(null);

  // A group is for policies and grants to point at. Nobody signs in as one,
  // and an app role on it would act on nobody, so it takes neither.
  const group = principalType === 'GROUP';
  const scoped = !group && appRole === 'DATA_OWNER';

  const create = useMutation({
    mutationFn: () =>
      createLocalPrincipal({
        username: username.trim(),
        displayName: displayName.trim(),
        email: email.trim() || null,
        principalType,
        password: group ? null : password,
        roles: !group && appRole ? [{ appRole, scopeFqn: scoped ? scopeFqn : null }] : [],
      }),
    onSuccess: (created) => {
      onSaved(created);
      onDone();
    },
  });

  // The display name is required by the server, and every screen in this
  // console lists people by it. Leaving it out of this check is how the form
  // came to offer a Create button that could only answer 400.
  const ready =
    username.trim().length >= 2 &&
    displayName.trim().length >= 2 &&
    (group || password.length >= 10) &&
    (!scoped || Boolean(scopeFqn));

  return (
    <div className="tw:border-b tw:border-secondary tw:bg-secondary tw:p-5">
      <h3 className="tw:text-sm tw:font-semibold tw:text-primary">
        Add a local account
      </h3>
      <p className="tw:mt-1 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">
        For service integrations, tests, and people no directory holds. The
        password below is a first one only: the holder is asked to choose their
        own the first time they sign in.
      </p>

      <div className="tw:mt-4 tw:grid tw:gap-4 tw:lg:grid-cols-2">
        <Field
          hint="Letters, digits, dot, dash, underscore or @. Case is ignored at sign-in."
          label="Username">
          <TextField onChange={setUsername} placeholder="analyst_a" value={username} />
        </Field>
        <Field
          hint="What the console shows instead of the username. Required."
          label="Display name">
          <TextField
            onChange={setDisplayName}
            placeholder="Analyst A"
            value={displayName}
          />
        </Field>
        <Field label="Kind">
          <Select
            onChange={(next) => setPrincipalType(next as 'USER' | 'SERVICE' | 'GROUP')}
            options={[
              { value: 'USER', label: 'Person', hint: 'Somebody who signs in.' },
              {
                value: 'SERVICE',
                label: 'Service account',
                hint: 'A job or integration that calls the API.',
              },
              {
                value: 'GROUP',
                label: 'Group',
                hint: 'People that policies and grants name together. No sign-in.',
              },
            ]}
            value={principalType}
          />
        </Field>
        <Field
          hint="Optional. Only used to recognise the same person elsewhere."
          label="Email">
          <TextField
            onChange={setEmail}
            placeholder="analyst_a@example.com"
            type="email"
            value={email}
          />
        </Field>
        {!group && (
        <Field hint="Ten characters at least, and not the username." label="First password">
          <TextField
            onChange={setPassword}
            placeholder="A passphrase they will replace"
            type="password"
            value={password}
          />
        </Field>
        )}
        {!group && (
        <Field hint="Optional — more can be granted afterwards." label="Role to start with">
          <Select
            onChange={(next) => {
              setAppRole(next);
              if (next !== 'DATA_OWNER') {
                setScopeFqn(null);
              }
            }}
            options={[
              { value: '', label: 'None', hint: 'Signs in and reads the catalog.' },
              ...ROLES.map((role) => ({
                value: role.id,
                label: role.title,
                hint: role.purpose,
              })),
            ]}
            placeholder="None"
            value={appRole}
          />
        </Field>
        )}
        {scoped && (
          <Field hint="The asset this owner owns." label="Scope">
            <ScopePicker onChange={setScopeFqn} value={scopeFqn} />
          </Field>
        )}
      </div>

      {create.error && (
        <p className="tw:mt-4 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-3 tw:text-sm tw:text-error-primary">
          {apiErrorMessage(create.error, 'Could not create that account.')}
        </p>
      )}

      <div className="tw:mt-4 tw:flex tw:gap-2">
        <Button
          color="primary"
          isDisabled={!ready || create.isPending}
          onPress={() => create.mutate()}
          size="sm">
          {create.isPending ? 'Creating…' : group ? 'Create group' : 'Create account'}
        </Button>
        <Button color="tertiary" onPress={onDone} size="sm">
          Cancel
        </Button>
      </div>
    </div>
  );
}
