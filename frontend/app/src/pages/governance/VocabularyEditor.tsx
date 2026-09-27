import { createContext, useContext, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../../api/client';
import {
  createClassification,
  createTag,
  updateVocabulary,
  type GovernanceValue,
  type LocalVocabularyValue,
  type VocabularyKind,
} from '../../api/governance';
import { FIELD } from '../policies/controls';

/**
 * Making classifications and tags in ARAK, for vocabulary OpenMetadata does
 * not have yet.
 *
 * <p>What is made here is provenance 'local': no sync overwrites it and
 * nothing is sent back to OpenMetadata. A tag made here is attached to a table
 * the same way as one from OpenMetadata -- "Edit tags" on the table's Columns
 * tab -- and a policy cannot tell the two apart. Only a platform admin or a
 * policy author may make them, because a policy anywhere can name one.
 */

/** Which form is open, and for what. */
export type VocabularyForm =
  | { mode: 'classification' }
  | { mode: 'tag'; classification: GovernanceValue }
  | { mode: 'edit'; kind: VocabularyKind; value: GovernanceValue };

export interface Editing {
  canEdit: boolean;
  form: VocabularyForm | null;
  open: (form: VocabularyForm) => void;
  close: () => void;
  done: (value: LocalVocabularyValue, verb: 'made' | 'changed') => void;
}

export const EditingContext = createContext<Editing>({
  canEdit: false,
  form: null,
  open: () => undefined,
  close: () => undefined,
  done: () => undefined,
});

export function useEditing(): Editing {
  return useContext(EditingContext);
}

/** Whether the open form belongs under this row. */
export function formUnder(form: VocabularyForm | null, value: GovernanceValue): boolean {
  if (!form) return false;
  if (form.mode === 'tag') return form.classification.fqn === value.fqn;
  if (form.mode === 'edit') return form.value.fqn === value.fqn;
  return false;
}

/** A name is one FQN segment: a dot would make it two, and a quote is how one is escaped. */
function nameProblem(name: string): string | null {
  if (name.includes('.') || name.includes('"')) return 'A name cannot contain a dot or a double quote.';
  if (name.trim().length > 64) return 'Keep the name to 64 characters.';
  return null;
}

export function VocabularyFormPanel({ form }: { form: VocabularyForm }) {
  const editing = useEditing();
  const queryClient = useQueryClient();
  const current = form.mode === 'edit' ? form.value : null;
  const [name, setName] = useState('');
  const [displayName, setDisplayName] = useState(current?.displayName ?? '');
  const [description, setDescription] = useState(current?.description ?? '');
  const [exclusive, setExclusive] = useState(false);
  const [disabled, setDisabled] = useState(current?.disabled ?? false);

  const save = useMutation({
    mutationFn: (): Promise<LocalVocabularyValue> => {
      const words = {
        displayName: displayName.trim() || undefined,
        description: description.trim(),
      };
      if (form.mode === 'classification') {
        return createClassification({ name: name.trim(), ...words, mutuallyExclusive: exclusive });
      }
      if (form.mode === 'tag') {
        return createTag({ classificationFqn: form.classification.fqn, name: name.trim(), ...words });
      }
      return updateVocabulary({
        kind: form.kind,
        fqn: form.value.fqn,
        displayName: displayName.trim(),
        description: description.trim(),
        disabled,
      });
    },
    onSuccess: (value) => {
      // Both lists: this screen's, and the one "Edit tags" picks from.
      void queryClient.invalidateQueries({ queryKey: ['governance-vocabulary'] });
      void queryClient.invalidateQueries({ queryKey: ['vocabulary'] });
      editing.done(value, form.mode === 'edit' ? 'changed' : 'made');
    },
  });

  const problem = form.mode === 'edit' ? null : nameProblem(name);
  const ready =
    description.trim() !== '' && (form.mode === 'edit' || (name.trim() !== '' && problem === null));
  const title =
    form.mode === 'classification'
      ? 'New classification'
      : form.mode === 'tag'
        ? `New tag under ${form.classification.displayName || form.classification.name}`
        : `Edit ${form.value.displayName || form.value.name}`;
  const becomes =
    form.mode === 'tag' ? `${form.classification.fqn}.${name.trim() || '…'}` : name.trim() || '…';

  return (
    <form
      aria-label={title}
      className="tw:rounded-xl tw:border tw:border-brand tw:bg-primary tw:p-4 tw:shadow-xs"
      onSubmit={(event) => {
        event.preventDefault();
        if (ready && !save.isPending) save.mutate();
      }}>
      <h3 className="tw:text-sm tw:font-semibold tw:text-primary">{title}</h3>
      <p className="tw:mt-1 tw:text-xs tw:text-tertiary">
        {form.mode === 'edit'
          ? `${form.value.fqn} was made in ARAK, so it is changed here. Its name stays: policies and tagged columns refer to it by name.`
          : form.mode === 'tag'
            ? `It becomes ${becomes}, kept in ARAK only. Attach it to a column from the table's Columns tab, with Edit tags.`
            : 'Kept in ARAK only: OpenMetadata never sees it, and no sync overwrites it. Add its tags once it exists.'}
      </p>

      <div className="tw:mt-3 tw:grid tw:gap-3 tw:sm:grid-cols-2">
        {form.mode !== 'edit' && (
          <label className="tw:flex tw:flex-col tw:gap-1 tw:text-xs tw:font-medium tw:text-secondary">
            Name
            <input
              autoFocus
              className={FIELD}
              maxLength={64}
              onChange={(event) => setName(event.target.value)}
              placeholder={form.mode === 'tag' ? 'e.g. Payroll' : 'e.g. Retention'}
              value={name}
            />
          </label>
        )}
        <label className="tw:flex tw:flex-col tw:gap-1 tw:text-xs tw:font-medium tw:text-secondary">
          Display name (optional)
          <input
            className={FIELD}
            maxLength={128}
            onChange={(event) => setDisplayName(event.target.value)}
            value={displayName}
          />
        </label>
        <label className="tw:flex tw:flex-col tw:gap-1 tw:text-xs tw:font-medium tw:text-secondary tw:sm:col-span-2">
          Description
          <textarea
            className={`${FIELD} tw:resize-y`}
            maxLength={2000}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="What it means, so a steward knows when to attach it"
            rows={2}
            value={description}
          />
        </label>
      </div>
      {problem && <p className="tw:mt-2 tw:text-xs tw:text-error-primary">{problem}</p>}

      {form.mode === 'classification' && (
        <label className="tw:mt-3 tw:flex tw:items-center tw:gap-2 tw:text-sm tw:text-secondary">
          <input checked={exclusive} onChange={(event) => setExclusive(event.target.checked)} type="checkbox" />
          One tag per column or table (mutually exclusive), like a tier
        </label>
      )}
      {form.mode === 'edit' && (
        <label className="tw:mt-3 tw:flex tw:items-start tw:gap-2 tw:text-sm tw:text-secondary">
          <input
            checked={disabled}
            className="tw:mt-1"
            onChange={(event) => setDisabled(event.target.checked)}
            type="checkbox"
          />
          <span>
            Disabled: no longer offered when tagging.
            <span className="tw:block tw:text-xs tw:text-tertiary">
              Where it is already attached it stays, and so does any mask it brings, until the
              table's steward takes it off.
            </span>
          </span>
        </label>
      )}

      {save.isError && (
        <p
          className="tw:mt-3 tw:rounded-lg tw:border tw:border-error tw:bg-error-primary tw:p-2 tw:text-sm tw:text-error-primary"
          role="alert">
          {apiErrorMessage(save.error, 'Could not save it.')}
        </p>
      )}

      <div className="tw:mt-4 tw:flex tw:justify-end tw:gap-2">
        <Button color="secondary" isDisabled={save.isPending} onPress={editing.close} size="sm">
          Cancel
        </Button>
        <Button isDisabled={!ready} isLoading={save.isPending} size="sm" type="submit">
          {form.mode === 'edit' ? 'Save' : 'Create'}
        </Button>
      </div>
    </form>
  );
}
