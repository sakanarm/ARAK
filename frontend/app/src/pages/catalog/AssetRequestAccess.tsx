import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Dialog, Heading, Modal, ModalOverlay } from 'react-aria-components';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { CheckCircle, Clock, Send01 } from '@untitledui/icons';
import {
  fetchEligibility,
  type AccessRequest,
  type Eligibility,
  type EligibilityBrief,
  type Refusal,
} from '../../api/accessRequests';
import type { AssetSummary } from '../../api/client';
import { canAsk, RequestAccessForm, RequestedNote } from '../query/RequestAccess';
import { AccessBadge } from './reach';

/** Only these hold rows a grant can open; a schema or a service has none. */
const REQUESTABLE_TYPES = new Set(['TABLE', 'VIEW']);

type Asset = Pick<AssetSummary, 'fqn' | 'assetType'>;

/**
 * Where the caller stands on this table, asked once for the header and the
 * "Your access" row alike. Under 'access-requests', so sending a request
 * refreshes this answer too.
 */
function useEligibility(asset: Asset) {
  const applies = REQUESTABLE_TYPES.has(asset.assetType);
  const query = useQuery({
    queryKey: ['access-requests', 'eligibility', asset.fqn],
    queryFn: () => fetchEligibility(asset.fqn),
    enabled: applies,
    retry: false,
  });
  return { applies, ...query };
}

/**
 * "Request access", in the header of a table the reader cannot read.
 *
 * <p>At the top right, beside the other things one does to an asset, because
 * that is where OpenMetadata keeps an asset's actions and where people look for
 * them. It opens the same form the Query page draws under a refusal, asked
 * before anything has run: somebody browsing the catalog should not have to
 * write a statement and be refused before they learn who to ask.
 *
 * <p>The server decides every state -- connected, readable, whether a grant
 * would be enough, who decides, an open request -- so the header says only
 * what it was told: a grey "Not connected" note, a quiet "You can read this",
 * a link to the open request, or the button. The button is there even when a
 * policy would still refuse after a grant: the request is sent all the same,
 * and the form says a policy may have to change too. Nothing while the answer
 * is still coming or could not be had.
 */
export function AssetAccessAction({ asset }: { asset: Asset }) {
  const [open, setOpen] = useState(false);
  const [sent, setSent] = useState<AccessRequest | null>(null);
  const { applies, data } = useEligibility(asset);

  if (!applies || !data) {
    return null;
  }

  // No source ARAK knows maps the table: there is nothing a grant could open,
  // so there is no request to make either.
  if (data.queryable === false) {
    return (
      <span
        className="tw:inline-flex tw:items-center tw:gap-1.5 tw:rounded-md tw:border tw:border-dashed tw:border-primary tw:px-2.5 tw:py-1.5 tw:text-sm tw:font-medium tw:text-quaternary"
        title="No data source registered in ARAK maps this table, so nobody can query it through ARAK yet. An admin can register its source to connect it.">
        <span aria-hidden className="tw:size-2 tw:shrink-0 tw:rounded-full tw:border tw:border-current" />
        Not connected — nothing to query yet
      </span>
    );
  }

  if (data.readable) {
    return (
      <span className="tw:inline-flex tw:items-center tw:gap-1.5 tw:rounded-md tw:bg-utility-success-50 tw:px-2.5 tw:py-1.5 tw:text-sm tw:font-medium tw:text-utility-success-700">
        <CheckCircle aria-hidden className="tw:size-4" />
        You can read this
      </span>
    );
  }

  const refusal = asRefusal(data);
  // The dialog stays up after a send to say it went; the header behind it has
  // already turned into "Access requested" by the time it is closed.
  const dialog = (
    <RequestDialog isOpen={open} onClose={() => { setOpen(false); setSent(null); }}>
      {sent ? (
        <Done onClose={() => { setOpen(false); setSent(null); }}>
          <RequestedNote className="" refusal={refusal} sent={sent} />
        </Done>
      ) : (
        <RequestAccessForm
          className="tw:shadow-xl"
          from="catalog"
          onCancel={() => setOpen(false)}
          onSent={setSent}
          purpose={null}
          refusal={refusal}
          sourceId={null}
          sql=""
        />
      )}
    </RequestDialog>
  );

  if (data.openRequestId && !sent) {
    return (
      <Button color="secondary" href="/requests" iconLeading={Clock} size="sm">
        Access requested
      </Button>
    );
  }

  if (!sent && !canAsk(refusal)) {
    return null;
  }

  return (
    <>
      <Button color="primary" iconLeading={Send01} onPress={() => setOpen(true)} size="sm">
        Request access
      </Button>
      {dialog}
    </>
  );
}

/**
 * Where the reader stands, on a table's page: the same "You …" badge the
 * catalog rows carry, its tooltip saying what it means. Nothing until the
 * answer comes, for a table that is not connected ("Connection" says so), or
 * for a schema or a service.
 */
export function AssetStanding({ asset }: { asset: Asset }) {
  const { applies, data, isError } = useEligibility(asset);
  if (!applies) {
    return null;
  }
  if (!data) {
    return isError ? <span className="tw:text-sm tw:text-quaternary">Access could not be checked</span> : null;
  }
  return <AccessBadge asset={asset} brief={briefOf(data)} size="md" />;
}

/** The single answer, cut down to what a catalog row is told. */
export function briefOf(data: Eligibility): EligibilityBrief {
  return {
    assetFqn: data.assetFqn,
    queryable: data.queryable !== false,
    readable: data.readable,
    requestable: data.requestable,
    openRequestId: data.openRequestId,
    blockedKind: data.blockedKind ?? null,
  };
}

function asRefusal(data: Eligibility): Refusal {
  return {
    message: `You cannot read ${data.assetFqn} yet.`,
    assetFqn: data.assetFqn,
    requestable: data.requestable,
    queryable: data.queryable,
    blockedKind: data.blockedKind,
    blockedBy: data.blockedBy,
    blockedByPolicyId: data.blockedByPolicyId,
    blockedByPolicy: data.blockedByPolicy,
    blockedByReason: data.blockedByReason,
    approvers: data.approvers,
    openRequestId: data.openRequestId,
    stranded: data.stranded,
    route: data.route,
  };
}

function RequestDialog({
  isOpen,
  onClose,
  children,
}: {
  isOpen: boolean;
  onClose: () => void;
  children: React.ReactNode;
}) {
  return (
    <ModalOverlay
      className="tw:fixed tw:inset-0 tw:z-50 tw:flex tw:items-center tw:justify-center tw:bg-overlay/70 tw:p-4"
      isDismissable
      isOpen={isOpen}
      onOpenChange={(next) => {
        if (!next) onClose();
      }}>
      <Modal className="tw:w-full tw:max-w-lg">
        <Dialog aria-label="Request access" className="tw:max-h-[85vh] tw:overflow-y-auto tw:outline-none">
          {children}
        </Dialog>
      </Modal>
    </ModalOverlay>
  );
}

/** A message in the dialog's frame, with the one way out of it. */
function Done({ children, onClose }: { children: React.ReactNode; onClose: () => void }) {
  return (
    <div className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xl">
      <div className="tw:px-4 tw:pt-4">
        <Heading className="tw:text-sm tw:font-semibold tw:text-primary" slot="title">
          Request access
        </Heading>
      </div>
      <div className="tw:px-4 tw:py-3">{children}</div>
      <div className="tw:flex tw:justify-end tw:border-t tw:border-secondary tw:px-4 tw:py-3">
        <Button color="secondary" onPress={onClose} size="sm">
          Close
        </Button>
      </div>
    </div>
  );
}
