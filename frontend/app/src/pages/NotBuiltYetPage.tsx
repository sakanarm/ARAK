import { useLocation } from 'react-router-dom';
import { Link } from 'react-router-dom';
import { ArrowLeft } from '@untitledui/icons';
import { findSection } from '../layout/navigation';

/**
 * Stands in for a section whose milestone has not landed.
 *
 * It exists so the navigation can show the shape of the product without any
 * link going nowhere, and so nobody mistakes an unbuilt screen for a broken
 * one.
 */
export default function NotBuiltYetPage() {
  const { pathname } = useLocation();
  const section = findSection(pathname);

  return (
    <div className="tw:mx-auto tw:max-w-lg tw:py-16 tw:text-center">
      <h1 className="tw:text-display-xs tw:font-semibold tw:text-primary">
        {section?.label ?? 'Not here yet'}
      </h1>
      <p className="tw:mt-3 tw:text-md tw:text-tertiary">
        {section?.description ??
          'This part of the console has not been built yet.'}
      </p>
      {section?.milestone && (
        <p className="tw:mt-3 tw:text-sm tw:text-quaternary">
          Planned for milestone {section.milestone}.
        </p>
      )}
      <Link
        className="tw:mt-8 tw:inline-flex tw:items-center tw:gap-2 tw:text-sm tw:font-medium tw:text-brand-secondary tw:hover:text-brand-secondary_hover"
        to="/">
        <ArrowLeft className="tw:size-4" />
        Back to home
      </Link>
    </div>
  );
}
