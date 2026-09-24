import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Link } from 'react-aria-components';
import { AriaRouter, isExternalHref } from './AriaRouter';

function renderLinks(basename?: string) {
  return render(
    <MemoryRouter basename={basename} initialEntries={[`${basename ?? ''}/catalog/svc.db`]}>
      <AriaRouter>
        <Link href="http://om.example.test:8585/database/svc.db">Open in OpenMetadata</Link>
        <Link href="/policies">Policies</Link>
      </AriaRouter>
    </MemoryRouter>
  );
}

describe('AriaRouter', () => {
  it('leaves a link to another site as it was written', () => {
    renderLinks('/Arak');

    // Seen live: the router took this for a route and opened /catalog/http:/...
    expect(screen.getByRole('link', { name: 'Open in OpenMetadata' })).toHaveAttribute(
      'href',
      'http://om.example.test:8585/database/svc.db'
    );
  });

  it('still puts a route under the base path', () => {
    renderLinks('/Arak');

    expect(screen.getByRole('link', { name: 'Policies' })).toHaveAttribute('href', '/Arak/policies');
  });

  it.each([
    ['https://om.example.test/x', true],
    ['HTTP://om.example.test', true],
    ['mailto:owner@example.com', true],
    ['//om.example.test/x', true],
    ['/catalog/svc.db', false],
    ['catalog/svc.db', false],
    ['svc.db', false],
    ['', false],
  ])('knows whether %p leaves the app', (href, external) => {
    expect(isExternalHref(href)).toBe(external);
  });
});
