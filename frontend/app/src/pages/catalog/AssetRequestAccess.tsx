import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Dialog, Heading, Modal, ModalOverlay } from 'react-aria-components';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { CheckCircle, Clock, Lock01, Send01 } from '@untitledui/icons';
import { fetchEligibility, type AccessRequest, type Eligibility, type Refusal } from '../../api/accessRequests';
import type { AssetSummary } from '../../api/client';
import { BlockedNote, RequestAccessForm, RequestedNote } from '../query/RequestAccess';

/** Only these hold rows a grant can open; a schema or a service has none. */
const REQUESTABLE_TYPES = new Set(['TABLE', 'VIEW']);

/**
 * "Request access", in the header of a table the reader cannot read.
 *
 * <p>At the top right, beside the other things one does to an asset, because
 * that is where OpenMetadata keeps an asset's actions and where people look for
 * them. It opens the same form the Query page draws under a refusal, asked
 * before anything has run: somebody browsing the catalog should not have to
 * write a statement and be refused before they learn who to ask.
 *
 * <p>The server decides every state -- readable, whether a grant would help,
 * who decides, an open request -- so the header says only what it was told:
 * a quiet "You can read this", a link to the open request, the button, or a
 * locked button that explains which policy is in the way. Nothing while the
 * answer is still coming or could not be had.
 */
export function AssetAccessAction({ asset }: { asset: Pick<AssetSummary, 'fqn' | 'assetType'> }) {
  const applies = REQUESTABLE_TYPES.has(asset.assetType);
  const [open, setOpen] = useState(false);
  const [sent, setSent] = useState<AccessRequest | null>(null);
  const { data } = useQuery({
    // Under 'access-requests', so sending a request refreshes this answer too.
    queryKey: ['access-requests', 'eligibility', asset.fqn],
    queryFn: () => fetchEligibility(asset.fqn),
    enabled: applies,
    retry: false,
  });

  if (!applies || !data) {
    return null;
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
      ) : refusal.requestable ? (
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
      ) : (
        <Done onClose={() => setOpen(false)}>
          <BlockedNote className="" refusal={refusal} />
        </Done>
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

  if (!data.requestable && !data.blockedBy) {
    return null;
  }

  return (
    <>
      <Button
        color={data.requestable ? 'primary' : 'secondary'}
        iconLeading={data.requestable ? Send01 : Lock01}
        onPress={() => setOpen(true)}
        size="sm">
        Request access
      </Button>
      {dialog}
    </>
  );
}

function asRefusal(data: Eligibility): Refusal {
  return {
    message: `You cannot read ${data.assetFqn} yet.`,
    assetFqn: data.assetFqn,
    requestable: data.requestable,
    blockedBy: data.blockedBy,
    approvers: data.approvers,
    openRequestId: data.openRequestId,
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
