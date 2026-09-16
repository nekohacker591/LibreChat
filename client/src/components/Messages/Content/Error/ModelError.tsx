import {
  ErrorTypes,
  ViolationTypes,
  isCodeWorkspaceSelectionErrorReason,
} from 'librechat-data-provider';
import type { ErrorRendererProps } from './parts';
import { ErrorBody, getProviderName, readNumber, readString, useErrorEndpoint } from './parts';
import { codeWorkspaceErrorKeys } from '~/utils/errors';
import { useLocalize } from '~/hooks';

/**
 * Failures naming a provider, a model or a workspace selection. Each payload carries the identity
 * it is about (`info`, `reason`, `status`), so the only resolution done here is turning an
 * endpoint id into the name a reader recognizes.
 */
export default function ModelError({ json, message }: ErrorRendererProps) {
  const localize = useLocalize();
  const { provider: conversationProvider } = useErrorEndpoint(message);
  const errorKey = readString(json, 'code') ?? readString(json, 'type');
  const info = readString(json, 'info');
  /** `info` is an endpoint id on these payloads; the conversation's own provider is the fallback. */
  const provider = info != null ? getProviderName(info) : conversationProvider;

  if (errorKey === ErrorTypes.MISSING_MODEL) {
    return provider != null
      ? localize('com_error_missing_model', { 0: provider })
      : localize('com_error_models_not_loaded');
  }

  if (errorKey === ErrorTypes.ENDPOINT_MODELS_NOT_LOADED) {
    return provider != null
      ? localize('com_error_endpoint_models_not_loaded', { 0: provider })
      : localize('com_error_models_not_loaded');
  }

  if (errorKey === ViolationTypes.ILLEGAL_MODEL_REQUEST) {
    const [endpoint, model] = info?.split('|') ?? [];
    const requestedProvider =
      endpoint != null && endpoint !== '' ? getProviderName(endpoint) : conversationProvider;
    if (model == null || model === '' || requestedProvider == null) {
      return localize('com_error_model_not_found');
    }
    return localize('com_error_illegal_model_request', { 0: model, 1: requestedProvider });
  }

  if (errorKey === ErrorTypes.CODE_WORKSPACE_UNAVAILABLE) {
    const reason = readString(json, 'reason');
    return isCodeWorkspaceSelectionErrorReason(reason)
      ? localize(codeWorkspaceErrorKeys[reason])
      : localize('com_error_code_workspace_unavailable');
  }

  /** Provider-neutral, matching the sentence the server persists as the failure's own text. */
  const status = readNumber(json, 'status');
  if (status == null) {
    return localize('com_error_upstream_model');
  }

  const headline = localize('com_error_upstream_model_status', { 0: status });
  if (status < 500) {
    return headline;
  }

  /** A 5xx is the provider failing, not the request being refused, so the copy names the two
   *  things a reader can act on: retrying, and the attachment a model may not accept — an image
   *  sent to a text-only model surfaces as this same 500 on every attempt. */
  return (
    <ErrorBody>
      <div>{headline}</div>
      <div>{localize('com_error_upstream_model_server_hint')}</div>
    </ErrorBody>
  );
}
