import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Stars01 } from '@untitledui/icons';
import { useAssistStore } from './assistStore';
import { NOKRAK } from './nokrak';
import { useAssistReady } from './useAssist';

/**
 * Searching by what a table holds rather than what it is called (M28).
 *
 * <p>A name search finds `cust_mstr` only for somebody who already knows it is
 * called that. The chat's catalogue tool reads names, descriptions, columns and
 * tags -- the ones this person may see, and no others -- so "where are customer
 * phone numbers" finds it.
 */

/** Whether the search entry points are drawn: the chat and its search, both offered. */
export function useCatalogAssist(): boolean {
  const chat = useAssistReady('CHAT');
  const search = useAssistReady('CATALOG_SEARCH');
  return chat && search;
}

/** The question a search term becomes. */
export function catalogQuestion(term: string): string {
  const trimmed = term.trim();
  return trimmed
    ? `Find the tables in the catalogue that hold ${trimmed}. Tell me which I can query and which I would have to request.`
    : '';
}

export function AskArakButton({ term }: { term: string }) {
  const ready = useCatalogAssist();
  const askArak = useAssistStore((state) => state.askArak);
  const setOpen = useAssistStore((state) => state.setOpen);
  if (!ready) {
    return null;
  }
  return (
    <Button
      color="secondary"
      iconLeading={Stars01}
      onPress={() => {
        const question = catalogQuestion(term);
        if (question) {
          askArak(question);
        } else {
          setOpen(true);
        }
      }}
      size="md">
      {`Ask ${NOKRAK.name}`}
    </Button>
  );
}
