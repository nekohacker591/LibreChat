export interface TokenConfig {
  [key: string]: number | boolean | undefined;
  /** USD per 1M prompt tokens, when the catalog reports a rate. */
  prompt?: number;
  /** USD per 1M completion tokens, when the catalog reports a rate. */
  completion?: number;
  /** Context window in tokens. */
  context: number;
  /** Max output ceiling in tokens, when the catalog reports one. */
  output?: number;
  /** Whether the model accepts image input, from the catalog's modalities. */
  vision?: boolean;
  /** USD per 1M tokens for cache writes/reads, when configured. */
  cacheWrite?: number;
  cacheRead?: number;
}

/** An endpoint's config object mapping model keys to their respective prompt, completion rates, and context limit */
export type EndpointTokenConfig = Record<string, TokenConfig>;
