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
    <div className="mx-auto max-w-lg py-16 text-center">
      <h1 className="text-display-xs font-semibold text-primary">
        {section?.label ?? 'Not here yet'}
      </h1>
      <p className="mt-3 text-md text-tertiary">
        {section?.description ??
          'This part of the console has not been built yet.'}
      </p>
      {section?.milestone && (
        <p className="mt-3 text-sm text-quaternary">
          Planned for milestone {section.milestone}.
        </p>
      )}
      <Link
        className="mt-8 inline-flex items-center gap-2 text-sm font-medium text-brand-secondary hover:text-brand-secondary_hover"
        to="/">
        <ArrowLeft className="size-4" />
        Back to home
      </Link>
    </div>
  );
}
