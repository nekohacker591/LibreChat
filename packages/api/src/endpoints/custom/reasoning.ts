import type { InitializeResultBase } from '~/types';

/** Values the OpenAI-compatible `reasoning_effort` parameter accepts. */
const REASONING_EFFORTS = new Set(['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max']);

/**
 * Forwards a user-selected `reasoning_effort` to the native Anthropic client.
 *
 * OpenCode Go/Zen route models such as MiniMax and Qwen through the Messages
 * API, but their gateway expects OpenCode's own `reasoning_effort` convention
 * rather than Anthropic's `thinking`/`output_config.effort` controls — which
 * the native heuristics only apply to Claude model names anyway. Unknown
 * fields travel through the SDK's `invocationKwargs`, which are merged into the
 * outgoing JSON body.
 *
 * Returns `true` when the level was applied.
 */
export function applyOpenCodeReasoningEffort(
  result: InitializeResultBase,
  reasoningEffort: unknown,
): boolean {
  if (typeof reasoningEffort !== 'string' || !REASONING_EFFORTS.has(reasoningEffort)) {
    return false;
  }
  const llmConfig = result.llmConfig as
    | (Record<string, unknown> & { invocationKwargs?: Record<string, unknown> })
    | null
    | undefined;
  if (llmConfig == null) {
    return false;
  }
  llmConfig.invocationKwargs = {
    ...(llmConfig.invocationKwargs ?? {}),
    reasoning_effort: reasoningEffort,
  };
  return true;
}
