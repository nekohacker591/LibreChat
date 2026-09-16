import { EModelEndpoint, getEndpointField } from 'librechat-data-provider';
import type { TConfig, TModelSpec, TConversation, TEndpointsConfig } from 'librechat-data-provider';
import {
  getAvailableEndpoints,
  getEndpointsFilter,
  getPriorConvoSetup,
  getSpecAgentAvatarURL,
  mapEndpoints,
  normalizeModelSpecs,
} from './endpoints';

const mockEndpointsConfig: TEndpointsConfig = {
  [EModelEndpoint.openAI]: { type: undefined, iconURL: 'openAI_icon.png', order: 0 },
  [EModelEndpoint.google]: { type: undefined, iconURL: 'google_icon.png', order: 1 },
  Mistral: { type: EModelEndpoint.custom, iconURL: 'custom_icon.png', order: 2 },
};

describe('getEndpointField', () => {
  it('returns undefined if endpointsConfig is undefined', () => {
    expect(getEndpointField(undefined, EModelEndpoint.openAI, 'type')).toBeUndefined();
  });

  it('returns undefined if endpoint is null', () => {
    expect(getEndpointField(mockEndpointsConfig, null, 'type')).toBeUndefined();
  });

  it('returns undefined if endpoint is undefined', () => {
    expect(getEndpointField(mockEndpointsConfig, undefined, 'type')).toBeUndefined();
  });

  it('returns the correct value for a valid endpoint and property', () => {
    expect(getEndpointField(mockEndpointsConfig, EModelEndpoint.openAI, 'order')).toEqual(0);
    expect(getEndpointField(mockEndpointsConfig, EModelEndpoint.google, 'iconURL')).toEqual(
      'google_icon.png',
    );
  });

  it('returns undefined for a valid endpoint but an invalid property', () => {
    /* Type assertion as 'nonexistentProperty' is intentionally not a valid property of TConfig */
    expect(
      getEndpointField(
        mockEndpointsConfig,
        EModelEndpoint.openAI,
        'nonexistentProperty' as keyof TConfig,
      ),
    ).toBeUndefined();
  });

  it('returns the correct value for a non-enum endpoint and valid property', () => {
    expect(getEndpointField(mockEndpointsConfig, 'Mistral', 'type')).toEqual(EModelEndpoint.custom);
  });

  it('returns undefined for a non-enum endpoint with an invalid property', () => {
    expect(
      getEndpointField(mockEndpointsConfig, 'Mistral', 'nonexistentProperty' as keyof TConfig),
    ).toBeUndefined();
  });
});

describe('getEndpointsFilter', () => {
  it('returns an empty object if endpointsConfig is undefined', () => {
    expect(getEndpointsFilter(undefined)).toEqual({});
  });

  it('returns a filter object based on endpointsConfig', () => {
    const expectedFilter = {
      [EModelEndpoint.openAI]: true,
      [EModelEndpoint.google]: true,
      Mistral: true,
    };
    expect(getEndpointsFilter(mockEndpointsConfig)).toEqual(expectedFilter);
  });
});

describe('getAvailableEndpoints', () => {
  it('returns available endpoints based on filter and config', () => {
    const filter = {
      [EModelEndpoint.openAI]: true,
      [EModelEndpoint.google]: false,
      Mistral: true,
    };
    const expectedEndpoints = [EModelEndpoint.openAI, 'Mistral'];
    expect(getAvailableEndpoints(filter, mockEndpointsConfig)).toEqual(expectedEndpoints);
  });
});

describe('mapEndpoints', () => {
  it('returns sorted available endpoints', () => {
    const expectedOrder = [EModelEndpoint.openAI, EModelEndpoint.google, 'Mistral'];
    expect(mapEndpoints(mockEndpointsConfig)).toEqual(expectedOrder);
  });
});

describe('normalizeModelSpecs', () => {
  const spec = (overrides: Partial<TModelSpec>): TModelSpec =>
    ({ name: 'spec-name', label: 'Spec Label', preset: {}, ...overrides }) as TModelSpec;

  it('returns the same array reference when every spec has a label', () => {
    const specs = [spec({}), spec({ name: 'other', label: 'Other' })];
    expect(normalizeModelSpecs(specs)).toBe(specs);
  });

  it('fills a missing label from the name', () => {
    const specs = [spec({ label: undefined as unknown as string })];
    expect(normalizeModelSpecs(specs)[0].label).toBe('spec-name');
  });

  it('fills an empty label from the name', () => {
    expect(normalizeModelSpecs([spec({ label: '' })])[0].label).toBe('spec-name');
  });

  /** Memoized consumers must not see a new identity for specs that were already valid. */
  it('preserves the identity of specs that already have a label', () => {
    const valid = spec({});
    const invalid = spec({ name: 'blank', label: '' });
    const result = normalizeModelSpecs([valid, invalid]);
    expect(result[0]).toBe(valid);
    expect(result[1]).not.toBe(invalid);
  });
});

describe('getSpecAgentAvatarURL', () => {
  const agentsMap = {
    agent_obj: { id: 'agent_obj', avatar: { filepath: '/images/obj.png', source: 'local' } },
    agent_str: { id: 'agent_str', avatar: '/images/legacy.png' },
  } as unknown as Parameters<typeof getSpecAgentAvatarURL>[1];

  const agentSpec = (agent_id?: string, endpoint: string = EModelEndpoint.agents): TModelSpec =>
    ({ name: 'n', label: 'l', preset: { endpoint, agent_id } }) as TModelSpec;

  it('resolves an object avatar', () => {
    expect(getSpecAgentAvatarURL(agentSpec('agent_obj'), agentsMap)).toBe('/images/obj.png');
  });

  /** Agents persisted before the object format still store the URL as a string. */
  it('resolves a legacy string avatar', () => {
    expect(getSpecAgentAvatarURL(agentSpec('agent_str'), agentsMap)).toBe('/images/legacy.png');
  });

  /** A leftover agent_id must not surface an unrelated agent on a non-agent spec. */
  it('ignores agent_id when the spec targets another endpoint', () => {
    expect(
      getSpecAgentAvatarURL(agentSpec('agent_obj', EModelEndpoint.openAI), agentsMap),
    ).toBeUndefined();
  });

  it('returns undefined for an unknown or absent agent', () => {
    expect(getSpecAgentAvatarURL(agentSpec('missing'), agentsMap)).toBeUndefined();
    expect(getSpecAgentAvatarURL(agentSpec(undefined), agentsMap)).toBeUndefined();
  });
});

describe('getPriorConvoSetup', () => {
  const storedSetup = {
    conversationId: 'convo-1',
    endpoint: 'OpenCode Go',
    endpointType: EModelEndpoint.custom,
    model: 'mimo-v2.5-pro',
    reasoning_effort: 'high',
    temperature: 0.7,
    max_tokens: 2000,
    spec: 'some-spec',
    iconURL: 'some-icon.png',
    chatProjectId: 'project-1',
    agent_id: 'convo-1::web_search',
    assistant_id: 'asst_1',
    disableParams: true,
  } as unknown as Partial<TConversation>;

  it('prefers an active preset over the stored setup', () => {
    const preset = { endpoint: 'OpenCode Go', model: 'preset-model' } as unknown as TConversation;

    expect(getPriorConvoSetup({ activePreset: preset, storedSetup, endpoint: 'OpenCode Go' })).toBe(
      preset,
    );
  });

  it('carries the params of a stored setup on the same endpoint', () => {
    const setup = getPriorConvoSetup({ storedSetup, endpoint: 'OpenCode Go' });

    expect(setup).toMatchObject({
      endpoint: 'OpenCode Go',
      model: 'mimo-v2.5-pro',
      reasoning_effort: 'high',
      temperature: 0.7,
      max_tokens: 2000,
    });
  });

  /** Selection and scope fields belong to the conversation being left, not to its params. */
  it('drops identity and spec fields from the carried setup', () => {
    const setup = getPriorConvoSetup({ storedSetup, endpoint: 'OpenCode Go' }) ?? {};

    expect(setup).not.toHaveProperty('spec');
    expect(setup).not.toHaveProperty('iconURL');
    expect(setup).not.toHaveProperty('chatProjectId');
    expect(setup).not.toHaveProperty('conversationId');
    expect(setup).not.toHaveProperty('agent_id');
    expect(setup).not.toHaveProperty('assistant_id');
    expect(setup).not.toHaveProperty('disableParams');
  });

  it('ignores a stored setup left behind by another endpoint', () => {
    expect(getPriorConvoSetup({ storedSetup, endpoint: EModelEndpoint.openAI })).toBeNull();
  });

  it('returns null without a stored setup, endpoint, or setup endpoint', () => {
    expect(getPriorConvoSetup({ endpoint: 'OpenCode Go' })).toBeNull();
    expect(getPriorConvoSetup({ storedSetup })).toBeNull();
    expect(getPriorConvoSetup({ storedSetup: {}, endpoint: 'OpenCode Go' })).toBeNull();
  });

  /** Agents and assistants resolve their own selection through dedicated storage. */
  it('excludes agents and assistants', () => {
    expect(
      getPriorConvoSetup({
        storedSetup: { ...storedSetup, endpoint: EModelEndpoint.agents },
        endpoint: EModelEndpoint.agents,
      }),
    ).toBeNull();
    expect(
      getPriorConvoSetup({
        storedSetup: { ...storedSetup, endpoint: EModelEndpoint.assistants },
        endpoint: EModelEndpoint.assistants,
      }),
    ).toBeNull();
  });
});
