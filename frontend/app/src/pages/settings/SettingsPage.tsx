import type { FC } from 'react';
import { Link } from 'react-router-dom';
import {
  ArrowRight,
  CpuChip01,
  Database01,
  Dataflow03,
  FileCheck02,
  FileShield02,
  Key01,
  LayoutAlt01,
  RefreshCcw01,
  Server01,
  Server02,
  ShieldTick,
  Tag01,
  Target04,
  User03,
  Users01,
} from '@untitledui/icons';
import { Chip as Badge } from '../../components/chips';
import { useAuthStore } from '../../auth/authStore';

/**
 * Where the platform is configured, grouped by what is being configured.
 *
 * OpenMetadata's settings page is a grid of titled cards under headings, and
 * this one follows it, because the two consoles are administered by the same
 * people and a second layout to learn is a cost with nothing on the other side
 * of it.
 *
 * The three headings are not cosmetic. They separate settings that answer
 * genuinely different questions, and the separation is the point: where the
 * metadata comes from, who may operate this platform, and which databases the
 * platform enforces policy in. Conflating the second and third is the mistake
 * worth designing against — "who may use ARAK" and "who may read a table" are
 * different grants with different blast radii, and a console that files them
 * together invites somebody to make one while thinking they made the other.
 */

interface SettingCard {
  title: string;
  description: string;
  to: string;
  icon: FC<{ className?: string }>;
  /** Shown when the destination is not built yet. */
  milestone?: string;
  /** Hidden from anyone who cannot act on it. */
  adminOnly?: boolean;
  /** Shown only to someone holding one of these roles. */
  roles?: string[];
}

interface SettingGroup {
  heading: string;
  blurb: string;
  cards: SettingCard[];
}

const GROUPS: SettingGroup[] = [
  {
    heading: 'Catalog & metadata',
    blurb:
      'Where the assets, tags, glossary terms and domains a policy is written against come from, and how fresh this copy of them is.',
    cards: [
      {
        title: 'OpenMetadata connection',
        description:
          'The instance this platform reads from, whether the bot token and webhook secret are present, and when the last crawl finished.',
        to: '/settings/openmetadata',
        icon: Key01,
        adminOnly: true,
      },
      {
        title: 'Governance vocabulary',
        description:
          'Classifications, tags, glossaries, domains and custom properties as they arrived, with how many assets each one covers.',
        to: '/governance',
        icon: Tag01,
      },
      {
        title: 'Catalog',
        description:
          'The synced assets themselves, down to the column, with the facets that a selector will match on.',
        to: '/catalog',
        icon: Database01,
      },
    ],
  },
  {
    heading: 'People & platform access',
    blurb:
      'Who may operate ARAK itself. This is not access to data: a platform role decides who may write a policy, never what any policy lets them read.',
    cards: [
      {
        title: 'Application roles',
        description:
          'The five roles this platform recognises, what each one may do, who holds them — and, for an administrator, where a role is granted or an account added.',
        to: '/settings/roles',
        icon: ShieldTick,
      },
      {
        title: 'Access workflows',
        description:
          'Who approves a request for a table, in what order -- in parallel or one step after another -- and who configures the access once it is approved.',
        to: '/settings/workflows',
        icon: Dataflow03,
        roles: ['PLATFORM_ADMIN', 'POLICY_AUTHOR', 'DATA_OWNER', 'AUDITOR'],
      },
      {
        title: 'Request templates',
        description:
          'What a request for a table must say -- a purpose from a list, a reference, how long a reason -- and for how long it may ask, chosen by scope or by the tags and terms the table carries.',
        to: '/settings/request-templates',
        icon: FileCheck02,
        roles: ['PLATFORM_ADMIN', 'POLICY_AUTHOR', 'DATA_OWNER', 'AUDITOR'],
      },
      {
        title: 'Purposes',
        description:
          'What data may be used for, with the legal basis each rests on, whether sensitive data may be used for it and how long access for it may last. Policies, templates, requests and queries pick from it.',
        to: '/settings/purposes',
        icon: Target04,
        roles: ['PLATFORM_ADMIN', 'POLICY_AUTHOR', 'DATA_OWNER', 'AUDITOR'],
      },
      {
        title: 'People & attributes',
        description:
          'The identity cache a subject rule is written against — users, groups and the attribute values they carry.',
        to: '/principals',
        icon: User03,
      },
      {
        title: 'Assistant',
        description:
          'Whether an LLM may help draft SQL and policies, which model it uses, and -- for an administrator -- the gateway it is reached through and who it is switched on for.',
        to: '/settings/assistant',
        icon: CpuChip01,
      },
      {
        title: 'Home page per role',
        description:
          'The page somebody lands on before they arrange their own, chosen by the role they hold. A starting point, never an override: anyone who arranges their own page keeps it.',
        to: '/settings/home',
        icon: LayoutAlt01,
        adminOnly: true,
      },
      {
        title: 'Local groups',
        description:
          'Groups defined here rather than in Entra, for service accounts, external users and tests. Make one under Application roles, then add its members from its page.',
        to: '/principals?type=GROUP',
        icon: Users01,
        adminOnly: true,
      },
    ],
  },
  {
    heading: 'Data source connections',
    blurb:
      'The databases policy is enforced in, the credential reference each one resolves through, and which of the three enforcement modes it carries.',
    cards: [
      {
        title: 'Registered sources',
        description:
          'Connection details, the enforcement mode per source, and the secure-object naming each one writes under.',
        to: '/sources',
        icon: Server02,
        adminOnly: true,
      },
      {
        // Moved here from the rail: a secure view is applied a few times per
        // table, not visited daily. Anybody who does can pin it back with
        // Customize rail.
        title: 'Enforcement',
        description:
          'Secure views on registered sources: a dry run of exactly what would change, the apply, and the rollback by the name each view was created under.',
        to: '/enforcement',
        icon: FileShield02,
        roles: ['PLATFORM_ADMIN', 'POLICY_AUTHOR', 'DATA_OWNER'],
      },
      {
        title: 'Service & build',
        description:
          'What is running, which OpenMetadata instance it is pinned to, and whether the background pollers are healthy.',
        to: '/settings/system',
        icon: Server01,
      },
      {
        title: 'Sync & reconcile',
        description:
          'Run a crawl on demand and see what the last one changed. Lives with the connection it acts on.',
        to: '/settings/openmetadata',
        icon: RefreshCcw01,
        adminOnly: true,
      },
    ],
  },
];

export default function SettingsPage() {
  const isAdmin = useAuthStore((state) => state.hasRole('PLATFORM_ADMIN'));
  const hasRole = useAuthStore((state) => state.hasRole);

  return (
    <>
      <header>
        <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">
          Settings
        </h1>
        <p className="tw:mt-2 tw:max-w-3xl tw:text-pretty tw:text-md tw:text-tertiary">
          Configure the platform: where its metadata comes from, who may operate
          it, and which databases it enforces policy in.
        </p>
      </header>

      <div className="tw:mt-8 tw:flex tw:flex-col tw:gap-10">
        {GROUPS.map((group) => {
          const cards = group.cards.filter(
            (card) =>
              (!card.adminOnly || isAdmin) &&
              (!card.roles || hasRole(...card.roles))
          );
          if (cards.length === 0) {
            return null;
          }
          return (
            <section key={group.heading}>
              <h2 className="tw:text-lg tw:font-semibold tw:text-primary">
                {group.heading}
              </h2>
              <p className="tw:mt-1 tw:max-w-3xl tw:text-pretty tw:text-sm tw:text-tertiary">
                {group.blurb}
              </p>
              <div className="tw:mt-4 tw:grid tw:gap-4 tw:sm:grid-cols-2 tw:xl:grid-cols-3">
                {cards.map((card) => (
                  <SettingTile card={card} key={card.title} />
                ))}
              </div>
            </section>
          );
        })}

        {/*
          Not a setting, and so not a tile: nothing here opens anything. It is
          at the foot of the page every role reaches, which is where a console
          says who made it.
        */}
        <section aria-labelledby="settings-credits">
          <h2 className="tw:text-lg tw:font-semibold tw:text-primary" id="settings-credits">
            Credits
          </h2>
          <div className="tw:mt-4 tw:max-w-xl tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5 tw:shadow-xs">
            <p className="tw:text-sm tw:font-semibold tw:text-primary">ARAK · Data Access Control Platform</p>
            <dl className="tw:mt-3 tw:grid tw:grid-cols-[auto_1fr] tw:gap-x-6 tw:gap-y-1.5 tw:text-sm">
              <dt className="tw:text-tertiary">Designed and built by</dt>
              <dd className="tw:text-secondary">Sakan Punyanon</dd>
              <dt className="tw:text-tertiary">For</dt>
              <dd className="tw:text-secondary">MFEC</dd>
            </dl>
            <p className="tw:mt-3 tw:text-xs tw:text-quaternary">&copy; 2026 MFEC. All rights reserved.</p>
          </div>
        </section>
      </div>
    </>
  );
}

function SettingTile({ card }: { card: SettingCard }) {
  const Icon = card.icon;

  return (
    <Link
      className="tw:group tw:flex tw:flex-col tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5 tw:shadow-xs tw:outline-focus-ring tw:transition tw:hover:border-brand tw:hover:shadow-md tw:focus-visible:outline-2"
      to={card.to}>
      <div className="tw:flex tw:items-start tw:gap-3">
        <span className="tw:flex tw:size-10 tw:shrink-0 tw:items-center tw:justify-center tw:rounded-lg tw:bg-secondary">
          <Icon className="tw:size-5 tw:text-fg-brand-primary" />
        </span>
        <div className="tw:min-w-0 tw:flex-1">
          <h3 className="tw:flex tw:items-center tw:gap-2 tw:text-sm tw:font-semibold tw:text-primary">
            {card.title}
            {card.milestone && (
              <Badge color="gray" size="sm" type="pill-color">
                {card.milestone}
              </Badge>
            )}
          </h3>
          <p className="tw:mt-1 tw:text-xs tw:text-tertiary">
            {card.description}
          </p>
        </div>
      </div>
      <span className="tw:mt-4 tw:flex tw:items-center tw:gap-1 tw:text-xs tw:font-semibold tw:text-brand-secondary">
        Open
        <ArrowRight className="tw:size-3.5 tw:transition group-hover:tw:translate-x-0.5" />
      </span>
    </Link>
  );
}
