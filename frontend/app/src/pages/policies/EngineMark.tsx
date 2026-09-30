import databricks from '../../assets/engines/databricks.svg';
import mysql from '../../assets/engines/mysql.svg';
import postgresql from '../../assets/engines/postgresql.svg';
import sqlserver from '../../assets/engines/sqlserver.svg';

/**
 * The tile that stands for a database product: its logo, so an author finds
 * PostgreSQL among the cards without reading. Where the logos come from, and
 * on what terms, is in assets/engines/README.md.
 */
const LOGOS: Record<string, string> = {
  POSTGRES: postgresql,
  SQLSERVER: sqlserver,
  MYSQL: mysql,
  DATABRICKS: databricks,
};

export default function EngineMark({
  id,
  label,
  size = 'md',
}: {
  id: string;
  label: string;
  size?: 'sm' | 'md';
}) {
  const box = size === 'sm' ? 'tw:size-8' : 'tw:size-10';
  const logo = LOGOS[id];
  if (logo) {
    return (
      <span
        aria-hidden
        className={`tw:flex tw:flex-none tw:items-center tw:justify-center tw:rounded-lg tw:border tw:border-secondary tw:bg-white ${box}`}>
        <img
          alt=""
          className={size === 'sm' ? 'tw:size-5' : 'tw:size-7'}
          data-engine-logo={id}
          src={logo}
        />
      </span>
    );
  }
  // An engine with no logo on file still gets a tile: two letters of its name.
  return (
    <span
      aria-hidden
      className={`tw:flex tw:flex-none tw:items-center tw:justify-center tw:rounded-lg tw:bg-utility-gray-50 tw:font-semibold tw:tracking-tight tw:text-utility-gray-700 ${box} ${
        size === 'sm' ? 'tw:text-xs' : 'tw:text-sm'
      }`}>
      {label.replace(/[^A-Za-z]/g, '').slice(0, 2) || '?'}
    </span>
  );
}
