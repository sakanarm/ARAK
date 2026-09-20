/**
 * Descriptions as written in OpenMetadata, rendered as text.
 *
 * OpenMetadata 2.0's description editor stores its output as HTML, so a field
 * that reads "Premium Service Delivery Domain" in the catalog arrives here as
 * `<p>Premium Service Delivery Domain</p>`. React escapes it — correctly — and
 * the tags end up on screen.
 *
 * The fix is to strip the markup rather than to trust it: these strings are
 * typed by anybody who can edit the catalog, so injecting them as HTML would
 * make every description a place to put a script. Nothing here is rendered as
 * markup; the tags are removed, the entities decoded, and the result is plain
 * text that a `line-clamp` can truncate honestly.
 */

const BLOCK_END = /<\/(p|div|li|h[1-6]|tr|blockquote)\s*>/gi;
const LINE_BREAK = /<br\s*\/?>/gi;
const ANY_TAG = /<[^>]*>/g;

const ENTITIES: Record<string, string> = {
  amp: '&',
  lt: '<',
  gt: '>',
  quot: '"',
  apos: "'",
  nbsp: ' ',
  ndash: '–',
  mdash: '—',
  hellip: '…',
};

export function plainText(value: string | null | undefined): string {
  if (!value) {
    return '';
  }

  const withoutMarkup = value
    // A paragraph or list item that ends becomes a space, not nothing, so two
    // sentences do not run together into one word.
    .replace(BLOCK_END, ' ')
    .replace(LINE_BREAK, ' ')
    .replace(ANY_TAG, '');

  return decodeEntities(withoutMarkup).replace(/\s+/g, ' ').trim();
}

function decodeEntities(value: string): string {
  return value.replace(/&(#x?[0-9a-f]+|[a-z]+);/gi, (match, body: string) => {
    if (body.startsWith('#')) {
      const code = body.startsWith('#x') || body.startsWith('#X')
        ? Number.parseInt(body.slice(2), 16)
        : Number.parseInt(body.slice(1), 10);
      return Number.isFinite(code) && code > 0 ? String.fromCodePoint(code) : match;
    }
    return ENTITIES[body.toLowerCase()] ?? match;
  });
}
