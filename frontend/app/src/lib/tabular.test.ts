import { exportName, toCsv, toXlsx } from './tabular';

/**
 * What the export writes.
 *
 * <p>These files leave the platform and are opened somewhere it has no say
 * over -- a laptop, a shared drive, a script that reconciles a report. Two
 * things therefore have to hold: the bytes must say exactly what the grid
 * said, and a cell a customer's own data supplied must not become something
 * Excel executes. Both are checked here rather than by opening the file,
 * because by the time somebody opens it the damage is done.
 */

async function bytes(blob: Blob): Promise<Uint8Array> {
  // jsdom's Blob has arrayBuffer(); this keeps the cast in one place.
  return new Uint8Array(await blob.arrayBuffer());
}

async function csvText(blob: Blob): Promise<string> {
  return new TextDecoder().decode(await bytes(blob));
}

/** Read a stored ZIP back out, so the test opens the file the way Excel does. */
async function unzip(blob: Blob): Promise<Record<string, string>> {
  const raw = await bytes(blob);
  const view = new DataView(raw.buffer);
  const out: Record<string, string> = {};

  let at = 0;
  while (at + 4 <= raw.length && view.getUint32(at, true) === 0x04034b50) {
    const size = view.getUint32(at + 18, true);
    const nameLength = view.getUint16(at + 26, true);
    const extraLength = view.getUint16(at + 28, true);
    const name = new TextDecoder().decode(raw.subarray(at + 30, at + 30 + nameLength));
    const start = at + 30 + nameLength + extraLength;
    out[name] = new TextDecoder().decode(raw.subarray(start, start + size));
    at = start + size;
  }
  return out;
}

describe('CSV', () => {
  it('leads with a BOM, because Excel reads UTF-8 without one as cp874', async () => {
    // A Thai name arriving as mojibake looks like the platform corrupted the
    // data, and the reader has no way to tell that it did not.
    const blob = toCsv({ columns: ['ชื่อ'], rows: [['สมชาย']] });

    // Checked as bytes, because TextDecoder swallows a leading BOM -- so a
    // file that had lost it would decode identically here and pass.
    expect([...(await bytes(blob)).slice(0, 3)]).toEqual([0xef, 0xbb, 0xbf]);
    expect(await csvText(blob)).toContain('สมชาย');
  });

  it('quotes a value holding a comma, a quote or a newline', async () => {
    const csv = await csvText(
      toCsv({
        columns: ['note'],
        rows: [['a,b'], ['he said "no"'], ['line\nbreak']],
      })
    );

    expect(csv).toContain('"a,b"');
    expect(csv).toContain('"he said ""no"""');
    expect(csv).toContain('"line\nbreak"');
  });

  it('writes an absent value as empty, not as the word null', async () => {
    const csv = await csvText(
      toCsv({ columns: ['a', 'b'], rows: [[null, undefined]] })
    );

    expect(csv.split('\r\n')[1]).toBe(',');
  });

  it('defuses a cell that a spreadsheet would run as a formula', async () => {
    // The rows come out of a customer's tables, which is exactly where an
    // attacker puts `=HYPERLINK(...)`: the file is then opened by a reviewer
    // on a trusted machine, because this platform handed it to them.
    const csv = await csvText(
      toCsv({
        columns: ['x'],
        rows: [['=1+1'], ['+ping'], ['-cmd'], ['@SUM(A1)']],
      })
    );

    expect(csv).toContain("'=1+1");
    expect(csv).toContain("'+ping");
    expect(csv).toContain("'-cmd");
    expect(csv).toContain("'@SUM(A1)");
  });

  it('defuses the header too, since a column name is data as well', async () => {
    const csv = await csvText(toCsv({ columns: ['=evil'], rows: [] }));

    expect(csv).toContain("'=evil");
  });
});

describe('Excel', () => {
  it('writes a workbook with the parts Excel requires', async () => {
    const parts = await unzip(toXlsx({ columns: ['id'], rows: [[1]] }));

    expect(Object.keys(parts).sort()).toEqual([
      '[Content_Types].xml',
      '_rels/.rels',
      'xl/_rels/workbook.xml.rels',
      'xl/styles.xml',
      'xl/workbook.xml',
      'xl/worksheets/sheet1.xml',
    ]);
  });

  it('puts the header in row 1 and the data under it', async () => {
    const parts = await unzip(
      toXlsx({ columns: ['id', 'email'], rows: [[7, '***@example.com']] })
    );
    const sheet = parts['xl/worksheets/sheet1.xml'];

    expect(sheet).toContain('<t xml:space="preserve">id</t>');
    expect(sheet).toContain('<c r="A2"><v>7</v></c>');
    expect(sheet).toContain('<t xml:space="preserve">***@example.com</t>');
  });

  it('keeps a numeric-looking string as text', async () => {
    // A citizen id stored as a number loses its leading zeros and, past
    // fifteen digits, its last digits -- the export would have damaged the
    // one column somebody exported it to check.
    const parts = await unzip(
      toXlsx({ columns: ['citizen_id'], rows: [['0123456789012']] })
    );

    expect(parts['xl/worksheets/sheet1.xml']).toContain(
      '<t xml:space="preserve">0123456789012</t>'
    );
  });

  it('escapes XML and drops control characters that void the workbook', async () => {
    // Excel rejects the whole file over one stray 0x01, not the one cell.
    const parts = await unzip(
      toXlsx({ columns: ['x'], rows: [['a & b <tag>\u0001']] })
    );

    expect(parts['xl/worksheets/sheet1.xml']).toContain('a &amp; b &lt;tag&gt;<');
  });

  it('defuses formulas here as well', async () => {
    const parts = await unzip(toXlsx({ columns: ['x'], rows: [['=1+1']] }));

    expect(parts['xl/worksheets/sheet1.xml']).toContain(
      '<t xml:space="preserve">\'=1+1</t>'
    );
  });

  it('names columns past Z the way Excel does', async () => {
    const columns = Array.from({ length: 28 }, (_, i) => `c${i}`);
    const parts = await unzip(toXlsx({ columns, rows: [] }));

    expect(parts['xl/worksheets/sheet1.xml']).toContain('r="Z1"');
    expect(parts['xl/worksheets/sheet1.xml']).toContain('r="AB1"');
  });
});

describe('the filename', () => {
  it('is built from the asset, so a folder of exports stays legible', () => {
    const name = exportName(['demo-pg.salesdb.sales.customer'], 'csv');

    expect(name).toMatch(/^demo-pg\.salesdb\.sales\.customer-[\d-]+\.csv$/);
  });

  it('falls back when the statement touched nothing nameable', () => {
    expect(exportName([], 'xlsx')).toMatch(/^query-[\d-]+\.xlsx$/);
  });

  it('strips anything a filesystem would refuse', () => {
    expect(exportName(['a/b:c*d'], 'csv')).toMatch(/^a-b-c-d-/);
  });
});
