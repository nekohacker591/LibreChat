import { Constants, LocalStorageKeys, TConversation, isUUID } from 'librechat-data-provider';
import { getTimestampedValue, setTimestampedValue } from './timestamps';

export function getLocalStorageItems() {
  const items = {
    lastSelectedModel: localStorage.getItem(LocalStorageKeys.LAST_MODEL) ?? '',
    lastSelectedTools: localStorage.getItem(LocalStorageKeys.LAST_TOOLS) ?? '',
    lastConversationSetup: localStorage.getItem(LocalStorageKeys.LAST_CONVO_SETUP + '_0') ?? '',
  };

  const lastSelectedModel = items.lastSelectedModel
    ? (JSON.parse(items.lastSelectedModel) as Record<string, string | undefined> | null)
    : {};
  const lastSelectedTools = items.lastSelectedTools
    ? (JSON.parse(items.lastSelectedTools) as string[] | null)
    : [];
  const lastConversationSetup = items.lastConversationSetup
    ? (JSON.parse(items.lastConversationSetup) as Partial<TConversation> | null)
    : {};

  return {
    lastSelectedModel,
    lastSelectedTools,
    lastConversationSetup,
  };
}

/** Drops every composer draft. These hold whatever the user typed, and a paste held as a file
 * keeps its entire text in the files draft, so they outlive a sign-out and the browser tab keeps
 * its identity across an in-app account switch: the ordinary draft restore could otherwise hand
 * the next account the previous one's writing. Called on the way out of a session rather than only
 * on the way in, because a social sign-in returns through the silent refresh and never passes the
 * login mutation at all. */
export function clearComposerDraftStorage() {
  Object.keys(localStorage).forEach((key) => {
    if (
      key.startsWith(LocalStorageKeys.FILES_DRAFT) ||
      key.startsWith(LocalStorageKeys.TEXT_DRAFT)
    ) {
      localStorage.removeItem(key);
    }
  });
}

export function clearLocalStorage(skipFirst?: boolean) {
  /** Ahead of `skipFirst`: that exception exists to preserve the first pane's settings, and a
   * shared browser is no place to make an exception for someone else's writing. */
  clearComposerDraftStorage();
  const keys = Object.keys(localStorage);
  keys.forEach((key) => {
    if (skipFirst === true && key.endsWith('0')) {
      return;
    }
    if (
      key.startsWith(LocalStorageKeys.LAST_MCP_) ||
      key.startsWith(LocalStorageKeys.LAST_CODE_TOGGLE_) ||
      key.startsWith(LocalStorageKeys.LAST_MEMORY_TOGGLE_) ||
      key.startsWith(LocalStorageKeys.ASST_ID_PREFIX) ||
      key.startsWith(LocalStorageKeys.AGENT_ID_PREFIX) ||
      key.startsWith(LocalStorageKeys.LAST_CONVO_SETUP) ||
      key === LocalStorageKeys.LAST_SPEC ||
      key === LocalStorageKeys.LAST_TOOLS ||
      key === LocalStorageKeys.LAST_MODEL ||
      key === LocalStorageKeys.FILES_TO_DELETE ||
      /** A permissive code approval default belongs to the account that chose it, not to
       * whoever signs in next on a shared browser. */
      key === LocalStorageKeys.LAST_CODE_APPROVAL_MODE
    ) {
      localStorage.removeItem(key);
    }
  });
}

export function clearConversationStorage(conversationId?: string | null) {
  if (!conversationId) {
    return;
  }
  if (!isUUID.safeParse(conversationId)?.success) {
    console.warn(
      `Conversation ID ${conversationId} is not a valid UUID. Skipping local storage cleanup.`,
    );
    return;
  }
  const keys = Object.keys(localStorage);
  keys.forEach((key) => {
    if (key.includes(conversationId)) {
      localStorage.removeItem(key);
    }
  });
}
export function clearAllConversationStorage() {
  const keys = Object.keys(localStorage);
  keys.forEach((key) => {
    if (
      key.startsWith(LocalStorageKeys.LAST_MCP_) ||
      key.startsWith(LocalStorageKeys.LAST_CODE_TOGGLE_) ||
      key.startsWith(LocalStorageKeys.LAST_MEMORY_TOGGLE_) ||
      key.startsWith(LocalStorageKeys.TEXT_DRAFT) ||
      key.startsWith(LocalStorageKeys.ASST_ID_PREFIX) ||
      key.startsWith(LocalStorageKeys.AGENT_ID_PREFIX) ||
      key.startsWith(LocalStorageKeys.LAST_CONVO_SETUP) ||
      key === LocalStorageKeys.LAST_SPEC ||
      key === LocalStorageKeys.LAST_MODEL ||
      key === LocalStorageKeys.LAST_TOOLS
    ) {
      localStorage.removeItem(key);
    }
  });
}

/** Every composer toggle `BadgeRowContext` reads back for a new conversation. Keys are
 * per-conversation; the copy new chats read lives under the `new` suffix. Pinned state
 * rides a `_pinned` suffix this never matches. */
const CONVO_TOGGLE_KEY_PREFIXES: LocalStorageKeys[] = [
  LocalStorageKeys.LAST_CODE_TOGGLE_,
  LocalStorageKeys.LAST_WEB_SEARCH_TOGGLE_,
  LocalStorageKeys.LAST_FILE_SEARCH_TOGGLE_,
  LocalStorageKeys.LAST_ARTIFACTS_TOGGLE_,
  LocalStorageKeys.LAST_SKILLS_TOGGLE_,
  LocalStorageKeys.LAST_MEMORY_TOGGLE_,
  LocalStorageKeys.LAST_MCP_,
];

/** Carries the outgoing conversation's tool toggles onto the new-chat defaults, so a toggle
 * flipped inside a conversation survives the New Chat button the way the model, params and
 * tools do. A conversation that is already new owns those keys itself, so there is nothing to
 * copy, and an expired value is skipped rather than resurrected: `getTimestampedValue` drops
 * anything past the storage window because an expired toggle is not a preference. */
export function carryConvoToolToggles(sourceConversationId?: string | null) {
  const sourceKey = sourceConversationId || (Constants.NEW_CONVO as string);
  if (sourceKey === Constants.NEW_CONVO) {
    return;
  }
  for (const prefix of CONVO_TOGGLE_KEY_PREFIXES) {
    const value = getTimestampedValue(`${prefix}${sourceKey}`);
    if (value == null) {
      continue;
    }
    setTimestampedValue(`${prefix}${Constants.NEW_CONVO}`, value);
  }
}
