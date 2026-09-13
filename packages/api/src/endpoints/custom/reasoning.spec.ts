import type { InitializeResultBase } from '~/types';
import { applyOpenCodeReasoningEffort } from './reasoning';

describe('applyOpenCodeReasoningEffort', () => {
  it('merges reasoning_effort into the existing invocationKwargs', () => {
    const result = {
      llmConfig: {
        model: 'minimax-m3',
        invocationKwargs: { metadata: { user_id: 'user-1' } },
      },
    } as unknown as InitializeResultBase;

    expect(applyOpenCodeReasoningEffort(result, 'high')).toBe(true);
    expect((result.llmConfig as Record<string, unknown>).invocationKwargs).toEqual({
      metadata: { user_id: 'user-1' },
      reasoning_effort: 'high',
    });
  });

  it('accepts every level the parameter supports', () => {
    for (const level of ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max']) {
      const result = { llmConfig: {} } as unknown as InitializeResultBase;
      expect(applyOpenCodeReasoningEffort(result, level)).toBe(true);
      expect(
        (result.llmConfig as { invocationKwargs?: Record<string, unknown> }).invocationKwargs
          ?.reasoning_effort,
      ).toBe(level);
    }
  });

  it('ignores unset and invalid values', () => {
    const result = { llmConfig: {} } as unknown as InitializeResultBase;

    expect(applyOpenCodeReasoningEffort(result, '')).toBe(false);
    expect(applyOpenCodeReasoningEffort(result, undefined)).toBe(false);
    expect(applyOpenCodeReasoningEffort(result, null)).toBe(false);
    expect(applyOpenCodeReasoningEffort(result, 'bogus')).toBe(false);
    expect(
      (result.llmConfig as { invocationKwargs?: Record<string, unknown> }).invocationKwargs,
    ).toBeUndefined();
  });

  it('returns false when the result has no llmConfig', () => {
    const result = {} as InitializeResultBase;

    expect(applyOpenCodeReasoningEffort(result, 'high')).toBe(false);
  });
});
