import { useState } from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import SqlEditor, { type SqlCompletionSource } from './SqlEditor';

/**
 * The editor is two layers pretending to be one, and the tests below guard the
 * seams between them rather than the typing, which the browser does.
 */
describe('SqlEditor', () => {
  function renderEditor(value = 'SELECT * FROM sales.customer') {
    const onChange = jest.fn();
    const onRun = jest.fn();
    render(<SqlEditor onChange={onChange} onRun={onRun} value={value} />);
    return {
      onChange,
      onRun,
      textarea: screen.getByLabelText('SQL') as HTMLTextAreaElement,
    };
  }

  /**
   * Asserted on the class name because jsdom does not resolve Tailwind, and a
   * class name is where this bug lived: the text is transparent so the
   * highlighted layer below shows through, so a caret left at `currentColor`
   * is transparent as well and the editor looks like it has lost focus.
   */
  it('paints the caret in a colour of its own, not the transparent text colour', () => {
    const { textarea } = renderEditor();
    const className = textarea.className;

    expect(className).toContain('tw:text-transparent');
    expect(className).not.toContain('tw:caret-current');
    expect(className).toContain('tw:caret-text-primary');
  });

  it('gives the gutter one number per line and hides it from assistive tech', () => {
    renderEditor('SELECT 1\nFROM dual\nWHERE 1 = 1');

    // The gutter is aria-hidden, so it is queried out of the DOM directly --
    // a screen reader announcing "1 2 3" before the statement is noise.
    const gutter = document.querySelectorAll('[aria-hidden="true"]');
    expect(gutter.length).toBeGreaterThan(0);
    expect(document.body.textContent).toContain('3');
  });

  it('indents with Tab instead of leaving the field', () => {
    const { onChange, textarea } = renderEditor('SELECT 1');
    textarea.setSelectionRange(0, 0);

    fireEvent.keyDown(textarea, { key: 'Tab' });

    expect(onChange).toHaveBeenCalledWith('  SELECT 1');
  });

  it('runs on Ctrl+Enter without adding a newline', () => {
    const { onChange, onRun, textarea } = renderEditor();

    fireEvent.keyDown(textarea, { ctrlKey: true, key: 'Enter' });

    expect(onRun).toHaveBeenCalledTimes(1);
    expect(onChange).not.toHaveBeenCalled();
  });
});

/**
 * The rules for what to suggest are tested on their own in sqlCompletion; what
 * is tested here is the list: that it opens when typing and not when deleting,
 * that the keys it takes are given back when it is closed, and that it says
 * beside each table whether the reader will be let in.
 */
describe('SqlEditor suggestions', () => {
  const CUSTOMER = { fqn: 'demo-pg.salesdb.sales.customer', schema: 'sales', name: 'customer' };
  const LEDGER = { fqn: 'demo-pg.salesdb.finance.ledger', schema: 'finance', name: 'ledger' };

  /** The page holds the text; the editor only reports changes to it. */
  function Harness({
    initial,
    completion,
    changes,
  }: {
    initial: string;
    completion?: SqlCompletionSource;
    changes?: (value: string) => void;
  }) {
    const [value, setValue] = useState(initial);
    return (
      <SqlEditor
        completion={completion}
        onChange={(next) => {
          changes?.(next);
          setValue(next);
        }}
        onRun={() => {}}
        value={value}
      />
    );
  }

  function source(overrides: Partial<SqlCompletionSource> = {}): SqlCompletionSource {
    return {
      tables: [CUSTOMER, LEDGER],
      columns: { [CUSTOMER.fqn]: ['id', 'email', 'citizen_id'] },
      readable: { [CUSTOMER.fqn]: false, [LEDGER.fqn]: true },
      ...overrides,
    };
  }

  function setup(completion?: SqlCompletionSource, initial = '') {
    const changes = jest.fn();
    render(<Harness changes={changes} completion={completion} initial={initial} />);
    const textarea = screen.getByLabelText('SQL') as HTMLTextAreaElement;
    // A change event carries no inputType in jsdom, which the editor reads as
    // typing -- the same as a browser's insertText.
    const type = (value: string) => fireEvent.change(textarea, { target: { value } });
    return { changes, textarea, type };
  }

  it('lists the tables after FROM, with whether the reader will be let in', () => {
    const { textarea, type } = setup(source());

    type('SELECT * FROM ');

    const list = screen.getByRole('listbox', { name: 'Suggestions' });
    const options = within(list).getAllByRole('option');
    expect(options).toHaveLength(2);
    expect(options[0]).toHaveTextContent('finance.ledger');
    expect(within(options[0]).getByText('Readable')).toBeInTheDocument();
    expect(options[1]).toHaveTextContent('sales.customer');
    expect(within(options[1]).getByText('Request needed')).toBeInTheDocument();
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
    expect(textarea).toHaveAttribute('aria-activedescendant', options[0].id);
    expect(textarea).toHaveAttribute('aria-controls', list.id);
  });

  it('draws no badge when readability is not known for the reader', () => {
    const { type } = setup(source({ readable: undefined }));

    type('SELECT * FROM cu');

    expect(screen.getByRole('option')).toHaveTextContent('sales.customer');
    expect(screen.queryByText('Request needed')).toBeNull();
    expect(screen.queryByText('Readable')).toBeNull();
  });

  it('takes the highlighted suggestion on Enter instead of adding a newline', () => {
    const { changes, textarea, type } = setup(source());
    type('SELECT * FROM cu');

    fireEvent.keyDown(textarea, { key: 'Enter' });

    expect(changes).toHaveBeenLastCalledWith('SELECT * FROM sales.customer ');
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('moves through the list with the arrow keys and takes a row with Tab', () => {
    const { changes, textarea, type } = setup(source());
    type('SELECT * FROM ');

    fireEvent.keyDown(textarea, { key: 'ArrowDown' });
    expect(screen.getAllByRole('option')[1]).toHaveAttribute('aria-selected', 'true');
    fireEvent.keyDown(textarea, { key: 'Tab' });

    expect(changes).toHaveBeenLastCalledWith('SELECT * FROM sales.customer ');
  });

  it('wraps round from the top of the list to the bottom', () => {
    const { textarea, type } = setup(source());
    type('SELECT * FROM ');

    fireEvent.keyDown(textarea, { key: 'ArrowUp' });

    expect(screen.getAllByRole('option')[1]).toHaveAttribute('aria-selected', 'true');
  });

  it('takes a row pressed with the mouse', () => {
    const { changes, type } = setup(source());
    type('SELECT * FROM le');

    fireEvent.mouseDown(screen.getByRole('option'));

    expect(changes).toHaveBeenLastCalledWith('SELECT * FROM finance.ledger ');
  });

  it('closes on Escape without the page seeing it, and Tab indents again', () => {
    // Full screen listens for Escape on the window; closing a list must not
    // also throw somebody out of full screen.
    const pageKeys = jest.fn();
    window.addEventListener('keydown', pageKeys);
    try {
      const { changes, textarea, type } = setup(source());
      type('SELECT * FROM ');

      fireEvent.keyDown(textarea, { key: 'Escape' });

      expect(screen.queryByRole('listbox')).toBeNull();
      expect(pageKeys).not.toHaveBeenCalled();

      textarea.setSelectionRange(0, 0);
      fireEvent.keyDown(textarea, { key: 'Tab' });
      expect(changes).toHaveBeenLastCalledWith('  SELECT * FROM ');
    } finally {
      window.removeEventListener('keydown', pageKeys);
    }
  });

  it('lets Escape through to the page when no list is open', () => {
    const pageKeys = jest.fn();
    window.addEventListener('keydown', pageKeys);
    try {
      const { textarea } = setup(source(), 'SELECT 1');
      fireEvent.keyDown(textarea, { key: 'Escape' });
      expect(pageKeys).toHaveBeenCalledTimes(1);
    } finally {
      window.removeEventListener('keydown', pageKeys);
    }
  });

  it('offers the columns of an aliased table, and asks for them when it needs them', () => {
    const onNeedColumns = jest.fn();
    const { type } = setup(source({ onNeedColumns }));

    type('SELECT * FROM sales.customer c WHERE c.');

    expect(onNeedColumns).toHaveBeenCalledWith([CUSTOMER.fqn]);
    const options = screen.getAllByRole('option');
    expect(options.map((o) => within(o).getByText(/^(citizen_id|email|id)$/).textContent)).toEqual(
      ['citizen_id', 'email', 'id']
    );
    // A column is not a table, so it carries no claim about who can read it.
    expect(screen.queryByText('Request needed')).toBeNull();
  });

  it('asks for the columns of every table the statement names, once per change of tables', () => {
    const onNeedColumns = jest.fn();
    const { type } = setup(source({ onNeedColumns }));

    type('SELECT * FROM sales.customer WHERE e');
    type('SELECT * FROM sales.customer WHERE em');

    expect(onNeedColumns).toHaveBeenCalledTimes(1);
    expect(onNeedColumns).toHaveBeenCalledWith([CUSTOMER.fqn]);
  });

  it('opens on Ctrl+Space where typing did not open it', () => {
    const { textarea } = setup(source(), 'SELECT * FROM ');
    textarea.setSelectionRange(14, 14);
    expect(screen.queryByRole('listbox')).toBeNull();

    fireEvent.keyDown(textarea, { ctrlKey: true, key: ' ' });

    expect(screen.getAllByRole('option')).toHaveLength(2);
  });

  it('stays closed while deleting, so backing out of a typo does not reopen it', () => {
    const { textarea, type } = setup(source());
    type('SELECT * FROM cu');
    fireEvent.keyDown(textarea, { key: 'Escape' });

    fireEvent.input(textarea, {
      target: { value: 'SELECT * FROM c' },
      inputType: 'deleteContentBackward',
    });

    expect(textarea.value).toBe('SELECT * FROM c');
    expect(screen.queryByRole('listbox')).toBeNull();

    // The same event as typing does open it, so the closed list above is the
    // editor reading inputType and not the event going unheard.
    fireEvent.input(textarea, {
      target: { value: 'SELECT * FROM cu' },
      inputType: 'insertText',
    });
    expect(screen.getByRole('option')).toHaveTextContent('sales.customer');
  });

  it('closes when the caret is moved away with the keyboard', () => {
    const { textarea, type } = setup(source());
    type('SELECT * FROM cu');

    fireEvent.keyDown(textarea, { key: 'ArrowLeft' });

    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('closes when the editor loses focus', () => {
    const { textarea, type } = setup(source());
    type('SELECT * FROM cu');

    fireEvent.blur(textarea);

    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('offers nothing inside a string', () => {
    const { type } = setup(source());
    type("SELECT * FROM sales.customer WHERE email = 'FROM ");
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('offers nothing without a completion source, and Tab still indents', () => {
    const { changes, textarea, type } = setup(undefined);
    type('SELECT * FROM ');
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(textarea).not.toHaveAttribute('aria-autocomplete');

    textarea.setSelectionRange(0, 0);
    fireEvent.keyDown(textarea, { key: 'Tab' });
    expect(changes).toHaveBeenLastCalledWith('  SELECT * FROM ');
  });
});
