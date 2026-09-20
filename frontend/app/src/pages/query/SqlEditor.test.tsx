import { fireEvent, render, screen } from '@testing-library/react';
import SqlEditor from './SqlEditor';

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
