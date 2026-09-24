import type { FC } from 'react';
import { Link } from 'react-router-dom';
import {
  ArrowRight,
  CpuChip01,
  Database01,
  Key01,
  RefreshCcw01,
  Server01,
  Server02,
  ShieldTick,
  Tag01,
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
        title: 'Local groups',
        description:
          'Groups defined here rather than in Entra, for service accounts, external users and tests.',
        to: '/settings/groups',
        icon: Users01,
        milestone: 'M2',
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
        title: 'Service & build',
        description:
          'What is running, which OpenMetadata instance it is pinned to, and whether the background pollers are healthy.',
        to: '/system',
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
            (card) => !card.adminOnly || isAdmin
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
