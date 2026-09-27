import { render, screen } from '@testing-library/react';
import type { EligibilityBrief } from '../../api/accessRequests';
import type { AssetSummary } from '../../api/client';
import { AccessBadge, ReachBadge, reachSentence, standingOf } from './reach';

const FQN = 'demo-pg.salesdb.sales.customer';

function brief(overrides: Partial<EligibilityBrief> = {}): EligibilityBrief {
  return {
    assetFqn: FQN,
    queryable: true,
    readable: false,
    requestable: true,
    openRequestId: null,
    blockedKind: null,
    ...overrides,
  };
}

function table(overrides: Partial<AssetSummary> = {}): AssetSummary {
  return {
    id: 't',
    fqn: FQN,
    name: 'customer',
    displayName: null,
    assetType: 'TABLE',
    parentFqn: 'demo-pg.salesdb.sales',
    description: null,
    tier: null,
    certification: null,
    dataSource: 'demo-pg',
    columnCount: 3,
    childCount: 0,
    taggedColumnCount: 0,
    facets: [],
    owners: [],
    ...overrides,
  };
}

describe('standingOf', () => {
  it('says what a reader can do, most useful first', () => {
    expect(standingOf(brief({ readable: true, openRequestId: 'r' }))?.label).toBe('You can query');
    // An open request wins over "can request" and "no access": it is already asked.
    expect(standingOf(brief({ openRequestId: 'r' }))?.label).toBe('You requested access');
    expect(standingOf(brief({ openRequestId: 'r', requestable: false, blockedKind: 'DENIED' }))?.label).toBe(
      'You requested access'
    );
    expect(standingOf(brief())?.label).toBe('You can request');
    expect(standingOf(brief({ requestable: false, blockedKind: 'DENIED' }))?.label).toBe('You have no access');
  });

  it('says nothing about the reader of a table nobody can query, or while it is unknown', () => {
    expect(standingOf(brief({ queryable: false, requestable: false }))).toBeNull();
    expect(standingOf(undefined)).toBeNull();
    expect(standingOf(null)).toBeNull();
  });

  it('says what kind of rule is in the way, never which policy', () => {
    const denied = standingOf(brief({ requestable: false, blockedKind: 'DENIED' }))!;
    const gated = standingOf(brief({ requestable: false, blockedKind: 'NOT_ADMITTED' }))!;
    expect(denied.sentence).toMatch(/An organisation rule keeps you out of it/);
    expect(gated.sentence).toMatch(/only open to people an organisation rule lets in/);
    for (const s of [denied, gated]) {
      expect(s.sentence).toMatch(/You can still ask/);
      expect(s.title).toMatch(/You can still ask/);
    }
  });

  it('starts every badge about the reader with "You"', () => {
    for (const b of [
      brief({ readable: true }),
      brief({ openRequestId: 'r' }),
      brief(),
      brief({ requestable: false, blockedKind: 'NOT_ADMITTED' }),
    ]) {
      expect(standingOf(b)!.label).toMatch(/^You /);
    }
  });
});

describe('AccessBadge', () => {
  it('draws only for a table or a view', () => {
    const { rerender } = render(<AccessBadge asset={{ assetType: 'VIEW' }} brief={brief({ readable: true })} />);
    expect(screen.getByText('You can query').parentElement).toHaveAttribute(
      'title',
      expect.stringMatching(/allowed now/)
    );

    rerender(<AccessBadge asset={{ assetType: 'DATABASE_SCHEMA' }} brief={brief({ readable: true })} />);
    expect(screen.queryByText('You can query')).toBeNull();
  });
});

describe('ReachBadge', () => {
  it('says whether the table is connected, and never says "you" on the badge', () => {
    const { rerender } = render(<ReachBadge asset={table({ querySource: 'demo-pg' })} />);
    expect(screen.getByText('Connected · demo-pg')).toBeInTheDocument();

    rerender(<ReachBadge asset={table({ querySource: null })} />);
    const off = screen.getByText('Not connected');
    // The tooltip says outright that this is about the table, not the reader.
    expect(off.parentElement).toHaveAttribute('title', expect.stringMatching(/not about your access/));
  });

  it('ends the detail sentence by saying what connected means', () => {
    expect(reachSentence(table({ querySource: 'demo-pg' }))).toBe(
      'Catalogued in OpenMetadata, and connected: queries run on it through demo-pg.'
    );
    expect(reachSentence(table({ querySource: null }))).toMatch(
      /but it is not connected: no data source in ARAK maps it, so nobody can query it through ARAK yet — this is not a limit on your access\.$/
    );
  });
});
