import { Constants, LocalStorageKeys } from 'librechat-data-provider';
import {
  carryConvoToolToggles,
  clearAllConversationStorage,
  clearLocalStorage,
} from '../localStorage';
import { setTimestampedValue } from '../timestamps';

describe('clearAllConversationStorage', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('wipes the selection and conversation state but keeps unrelated keys', () => {
    localStorage.setItem(LocalStorageKeys.LAST_SPEC, 'some-spec');
    localStorage.setItem(LocalStorageKeys.LAST_MODEL, JSON.stringify({ openAI: 'gpt-4o' }));
    localStorage.setItem(LocalStorageKeys.LAST_TOOLS, JSON.stringify(['web_search']));
    localStorage.setItem(
      `${LocalStorageKeys.LAST_CONVO_SETUP}_0`,
      JSON.stringify({ spec: 'some-spec' }),
    );
    localStorage.setItem(`${LocalStorageKeys.AGENT_ID_PREFIX}0`, 'agent_1');
    localStorage.setItem('unrelated-key', 'keep-me');

    clearAllConversationStorage();

    expect(localStorage.getItem(LocalStorageKeys.LAST_SPEC)).toBeNull();
    expect(localStorage.getItem(LocalStorageKeys.LAST_MODEL)).toBeNull();
    expect(localStorage.getItem(LocalStorageKeys.LAST_TOOLS)).toBeNull();
    expect(localStorage.getItem(`${LocalStorageKeys.LAST_CONVO_SETUP}_0`)).toBeNull();
    expect(localStorage.getItem(`${LocalStorageKeys.AGENT_ID_PREFIX}0`)).toBeNull();
    expect(localStorage.getItem('unrelated-key')).toBe('keep-me');
  });
});

describe('clearLocalStorage', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('drops composer drafts so an account change cannot restore the last user text', () => {
    /** A files draft carries the whole text of a paste held as a file, and the browser tab keeps
     * its identity across an in-app account switch, so leaving these behind let the next account
     * be handed the previous one's writing by the ordinary draft restore. */
    localStorage.setItem(
      `${LocalStorageKeys.FILES_DRAFT}new`,
      JSON.stringify({ fileIds: [], pendingPastes: { 'paste-1': { encodedText: 'c2VjcmV0' } } }),
    );
    localStorage.setItem(`${LocalStorageKeys.TEXT_DRAFT}new`, 'half-written message');
    localStorage.setItem('unrelated-key', 'keep-me');

    clearLocalStorage();

    expect(localStorage.getItem(`${LocalStorageKeys.FILES_DRAFT}new`)).toBeNull();
    expect(localStorage.getItem(`${LocalStorageKeys.TEXT_DRAFT}new`)).toBeNull();
    expect(localStorage.getItem('unrelated-key')).toBe('keep-me');
  });

  it('drops them even for the pane skipFirst would otherwise spare', () => {
    localStorage.setItem(`${LocalStorageKeys.FILES_DRAFT}new:0`, JSON.stringify({ fileIds: [] }));
    localStorage.setItem(`${LocalStorageKeys.TEXT_DRAFT}new:0`, 'first pane text');

    clearLocalStorage(true);

    expect(localStorage.getItem(`${LocalStorageKeys.FILES_DRAFT}new:0`)).toBeNull();
    expect(localStorage.getItem(`${LocalStorageKeys.TEXT_DRAFT}new:0`)).toBeNull();
  });
});

describe('carryConvoToolToggles', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  const webSearchKey = (suffix: string) => `${LocalStorageKeys.LAST_WEB_SEARCH_TOGGLE_}${suffix}`;
  const mcpKey = (suffix: string) => `${LocalStorageKeys.LAST_MCP_}${suffix}`;

  it('copies the outgoing conversation toggles onto the new-chat defaults', () => {
    setTimestampedValue(webSearchKey('convo-1'), JSON.stringify(true));
    setTimestampedValue(mcpKey('convo-1'), JSON.stringify(['server-a']));

    carryConvoToolToggles('convo-1');

    expect(localStorage.getItem(webSearchKey(Constants.NEW_CONVO))).toBe('true');
    expect(localStorage.getItem(mcpKey(Constants.NEW_CONVO))).toBe('["server-a"]');
    expect(localStorage.getItem(`${webSearchKey(Constants.NEW_CONVO)}_TIMESTAMP`)).not.toBeNull();
  });

  /** A conversation that already is new owns those keys itself; there is nothing to copy. */
  it('leaves the defaults alone when the conversation already is new', () => {
    setTimestampedValue(webSearchKey(Constants.NEW_CONVO), JSON.stringify(false));

    carryConvoToolToggles(Constants.NEW_CONVO);
    carryConvoToolToggles(undefined);
    carryConvoToolToggles('');

    expect(localStorage.getItem(webSearchKey(Constants.NEW_CONVO))).toBe('false');
  });

  it('leaves a toggle the conversation never set untouched', () => {
    setTimestampedValue(webSearchKey('convo-1'), JSON.stringify(true));

    carryConvoToolToggles('convo-1');

    expect(
      localStorage.getItem(`${LocalStorageKeys.LAST_CODE_TOGGLE_}${Constants.NEW_CONVO}`),
    ).toBeNull();
  });

  /** An expired toggle is not a preference, and copying it would resurrect it for two days. */
  it('skips a toggle whose value expired', () => {
    const staleKey = webSearchKey('convo-1');
    localStorage.setItem(staleKey, JSON.stringify(true));
    localStorage.setItem(`${staleKey}_TIMESTAMP`, String(Date.now() - 3 * 24 * 60 * 60 * 1000));

    carryConvoToolToggles('convo-1');

    expect(localStorage.getItem(webSearchKey(Constants.NEW_CONVO))).toBeNull();
  });
});
