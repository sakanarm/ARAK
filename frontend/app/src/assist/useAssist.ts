import { useQuery } from '@tanstack/react-query';
import {
  fetchMyLlmSetting,
  fetchOfferedFeatures,
  type AssistFeature,
} from '../api/llm';

/**
 * Whether this account has an assistant at all and, given a job, whether its
 * role is offered that job (M28). Same keys and freshness as the dock, so the
 * console costs no second request.
 *
 * <p>Hiding a job is a courtesy; the server refuses it to a role not offered
 * it whatever the console draws.
 */
export function useAssistReady(feature?: AssistFeature): boolean {
  const { data } = useQuery({
    queryKey: ['llm-me'],
    queryFn: fetchMyLlmSetting,
    staleTime: 5 * 60_000,
    retry: false,
  });
  const available = data?.available === true;
  const offered = useOfferedFeatures(available && feature !== undefined);
  if (!available) {
    return false;
  }
  return feature === undefined || (offered?.includes(feature) ?? false);
}

/** The jobs this account is offered, or undefined until known. */
export function useOfferedFeatures(enabled = true): AssistFeature[] | undefined {
  const { data } = useQuery({
    queryKey: ['llm-offered'],
    queryFn: fetchOfferedFeatures,
    staleTime: 5 * 60_000,
    retry: false,
    enabled,
  });
  return data;
}
