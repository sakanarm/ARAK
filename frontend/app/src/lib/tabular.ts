/**
 * Turning a result grid into a file somebody can open.
 *
 * <p>Two formats because they are asked for by different people for different
 * reasons: CSV is what a pipeline reads, Excel is what a reviewer opens. Both
 * are written here rather than by a library, because the only library-shaped
 * part is the ZIP container and that is sixty lines.
 *
 * <p>What leaves through here has already been through the policy engine — it
 * is the rows the principal was entitled to, masked as the decision said. The
 * file carries no second copy of anything hidden, because the grid never had
 * one. The export is still an egress event, and the caller is expected to have
 * audited it.
 */

/** A grid as the query API returns one. */
export interface Grid {
  columns: string[];
  rows: unknown[][];
}

/**
 * How a cell is written into a text format.
 *
 * <p>`null` becomes empty rather than the four letters "null", which is what
 * every spreadsheet means by an absent value and what a re-import will read
 * back. Everything else is stringified as it appears on screen, so the file
 * and the grid agree.
 */
function text(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

/**
 * Characters that make a spreadsheet treat a cell as a formula.
 *
 * <p>This matters more here than in most exporters. The rows come from a
 * customer's own tables, which is exactly where an attacker puts a string
 * beginning `=`: the file is then opened by a reviewer, on a trusted laptop,
 * because the platform handed it to them. Prefixing a quote defuses it and
 * Excel does not show the quote.
 */
const FORMULA_START = /^[=+\-@\t\r]/;

function defuse(cell: string): string {
  return FORMULA_START.test(cell) ? `'${cell}` : cell;
}

/** RFC 4180: quote when the value contains a delimiter, a quote or a newline. */
function csvCell(value: unknown): string {
  const cell = defuse(text(value));
  return /[",\r\n]/.test(cell) ? `"${cell.replace(/"/g, '""')}"` : cell;
}

/**
 * The grid as CSV, CRLF-terminated as the RFC says.
 *
 * <p>A BOM leads, because without one Excel on Windows reads UTF-8 as the
 * system codepage and every Thai column arrives as mojibake — which looks like
 * the platform corrupted the data rather than like an encoding default.
 */
export function toCsv(grid: Grid): Blob {
  const lines = [grid.columns.map(csvCell).join(',')];
  for (const row of grid.rows) {
    lines.push(row.map(csvCell).join(','));
  }
  return new Blob(['﻿', lines.join('\r\n'), '\r\n'], {
    type: 'text/csv;charset=utf-8',
  });
}

// --------------------------------------------------------------------- xlsx

function xmlEscape(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    // Control characters are not representable in XML 1.0 at all, and Excel
    // rejects the whole workbook rather than the one cell. A database column
    // can hold them, so they are dropped here instead -- which is the one
    // case the rule below exists to catch and exactly what is wanted here.
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '');
}

/** `0` to `A`, `26` to `AA`: the column names Excel uses in a cell reference. */
function columnRef(index: number): string {
  let ref = '';
  for (let n = index + 1; n > 0; n = Math.floor((n - 1) / 26)) {
    ref = String.fromCharCode(65 + ((n - 1) % 26)) + ref;
  }
  return ref;
}

/**
 * A number Excel should store as a number rather than as text.
 *
 * <p>Only genuine numbers qualify. A numeric-looking string is left as text
 * on purpose: a citizen id, a branch code and a masked `*******23456` are all
 * identifiers, and an identifier that Excel right-aligns, rounds to fifteen
 * digits or renders as `1.23E+12` has been damaged by the export.
 */
function numeric(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function sheetXml(grid: Grid): string {
  const rows: string[] = [];

  const header = grid.columns
    .map(
      (name, column) =>
        `<c r="${columnRef(column)}1" t="inlineStr" s="1"><is><t xml:space="preserve">${xmlEscape(
          name
        )}</t></is></c>`
    )
    .join('');
  rows.push(`<row r="1">${header}</row>`);

  grid.rows.forEach((row, index) => {
    const line = row.length
      ? row
          .map((value, column) => {
            const ref = `${columnRef(column)}${index + 2}`;
            const number = numeric(value);
            if (number !== null) {
              return `<c r="${ref}"><v>${number}</v></c>`;
            }
            const cell = defuse(text(value));
            if (cell === '') return `<c r="${ref}"/>`;
            return `<c r="${ref}" t="inlineStr"><is><t xml:space="preserve">${xmlEscape(
              cell
            )}</t></is></c>`;
          })
          .join('')
      : '';
    rows.push(`<row r="${index + 2}">${line}</row>`);
  });

  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${rows.join(
    ''
  )}</sheetData></worksheet>`;
}

/** Bold, for the one row that is not data. */
const STYLES = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`;

function contentTypes(sheets: number): string {
  const parts = Array.from(
    { length: sheets },
    (_, index) =>
      `<Override PartName="/xl/worksheets/sheet${index + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`
  ).join('');
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>${parts}<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>`;
}

const ROOT_RELS = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`;

function workbookRels(sheets: number): string {
  const parts = Array.from(
    { length: sheets },
    (_, index) =>
      `<Relationship Id="rId${index + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${index + 1}.xml"/>`
  ).join('');
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${parts}<Relationship Id="rId${sheets + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`;
}

/**
 * A sheet name Excel will open: at most 31 characters, none of `\ / ? * [ ] :`.
 * A name that breaks either rule makes Excel "repair" the whole workbook.
 */
function sheetName(name: string): string {
  return name.replace(/[\\/?*[\]:]/g, ' ').slice(0, 31) || 'Sheet';
}

function workbook(names: string[]): string {
  const sheets = names
    .map(
      (name, index) =>
        `<sheet name="${xmlEscape(sheetName(name))}" sheetId="${index + 1}" r:id="rId${index + 1}"/>`
    )
    .join('');
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${sheets}</sheets></workbook>`;
}

/** One tab of a workbook. */
export interface Sheet {
  name: string;
  grid: Grid;
}

/** Several grids as one `.xlsx`, a tab each, in the order given. */
export function toXlsxBook(sheets: Sheet[]): Blob {
  return zip([
    ['[Content_Types].xml', contentTypes(sheets.length)],
    ['_rels/.rels', ROOT_RELS],
    ['xl/workbook.xml', workbook(sheets.map((sheet) => sheet.name))],
    ['xl/_rels/workbook.xml.rels', workbookRels(sheets.length)],
    ['xl/styles.xml', STYLES],
    ...sheets.map(
      (sheet, index) =>
        [`xl/worksheets/sheet${index + 1}.xml`, sheetXml(sheet.grid)] as [string, string]
    ),
  ]);
}

/** The grid as a real `.xlsx`, which is a ZIP of five XML parts. */
export function toXlsx(grid: Grid): Blob {
  return toXlsxBook([{ name: 'Results', grid }]);
}

// ---------------------------------------------------------------------- zip

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[i] = c >>> 0;
  }
  return table;
})();

function crc32(bytes: Uint8Array): number {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) {
    c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

/**
 * A ZIP archive, stored rather than deflated.
 *
 * <p>Deflate would need an implementation of deflate. These parts are a few
 * hundred kilobytes of XML at the sizes this screen allows — the row cap is
 * five thousand — and the browser was going to hand the file straight to the
 * disk either way. Excel reads stored entries exactly as happily.
 */
function zip(files: [string, string][]): Blob {
  const utf8 = new TextEncoder();
  const locals: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset = 0;

  for (const [name, content] of files) {
    const nameBytes = utf8.encode(name);
    const body = utf8.encode(content);
    const crc = crc32(body);

    const local = new Uint8Array(30 + nameBytes.length);
    const lv = new DataView(local.buffer as ArrayBuffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true); // version needed
    lv.setUint16(6, 0x0800, true); // names and text are UTF-8
    lv.setUint16(8, 0, true); // stored
    lv.setUint32(10, 0, true); // a fixed DOS timestamp; the file has no mtime
    lv.setUint32(14, crc, true);
    lv.setUint32(18, body.length, true);
    lv.setUint32(22, body.length, true);
    lv.setUint16(26, nameBytes.length, true);
    local.set(nameBytes, 30);

    const entry = new Uint8Array(46 + nameBytes.length);
    const cv = new DataView(entry.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true); // version made by
    cv.setUint16(6, 20, true);
    cv.setUint16(8, 0x0800, true);
    cv.setUint16(10, 0, true);
    cv.setUint32(12, 0, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, body.length, true);
    cv.setUint32(24, body.length, true);
    cv.setUint16(28, nameBytes.length, true);
    cv.setUint32(42, offset, true);
    entry.set(nameBytes, 46);

    locals.push(local, body);
    central.push(entry);
    offset += local.length + body.length;
  }

  const centralSize = central.reduce((total, part) => total + part.length, 0);
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer as ArrayBuffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(8, files.length, true);
  ev.setUint16(10, files.length, true);
  ev.setUint32(12, centralSize, true);
  ev.setUint32(16, offset, true);

  // Flattened into one buffer rather than handed to Blob as a list of views:
  // a view over a possibly-shared buffer is not a `BlobPart`, and the copy is
  // the same few hundred kilobytes the encoder just produced.
  const parts = [...locals, ...central, end];
  const archive = new Uint8Array(offset + centralSize + end.length);
  let at = 0;
  for (const part of parts) {
    archive.set(part, at);
    at += part.length;
  }

  return new Blob([archive], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
}

// ------------------------------------------------------------------- saving

/**
 * A filename that every filesystem will accept.
 *
 * <p>Built from the asset the query touched, so a folder of exports is still
 * legible a month later — `results (3).csv` is not.
 */
export function exportName(assets: string[], extension: string): string {
  const stem = (assets[0] ?? 'query').replace(/[^A-Za-z0-9._-]+/g, '-');
  const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
  return `${stem}-${stamp}.${extension}`;
}

/** Hand the blob to the browser's downloader and let go of the object URL. */
export function download(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  // Not revoked synchronously: Firefox cancels a download whose URL is
  // released in the same tick as the click that started it.
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
