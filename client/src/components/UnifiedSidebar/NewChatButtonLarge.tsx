import { memo } from 'react';
import { SquarePen } from 'lucide-react';
import { Button } from '@librechat/client';
import { useShortcutAriaKey } from '~/hooks/useKeyboardShortcuts';
import useNewChat from '~/hooks/Chat/useNewChat';
import { useLocalize } from '~/hooks';

/**
 * Primary action for the chat-history panel: a full-width button above the
 * list, mirroring the mobile drawer's footer action. The rail keeps its
 * compact pencil for the collapsed state; this is the button the history it
 * creates visually belongs to. Rendered outside the drawer on small screens,
 * where the thumb-reachable footer button already exists.
 */
const NewChatButtonLarge = memo(function NewChatButtonLarge() {
  const localize = useLocalize();
  const { handleNewChatClick } = useNewChat();
  const newChatAriaKey = useShortcutAriaKey('newChat');

  return (
    <Button asChild className="h-11 w-full justify-start gap-2 px-3 text-sm font-medium">
      <a
        href="/c/new"
        data-testid="new-chat-button-large"
        aria-label={localize('com_ui_new_chat')}
        aria-keyshortcuts={newChatAriaKey}
        onClick={handleNewChatClick}
      >
        <SquarePen className="size-5 shrink-0" aria-hidden="true" />
        <span className="truncate">{localize('com_ui_new_chat')}</span>
      </a>
    </Button>
  );
});

NewChatButtonLarge.displayName = 'NewChatButtonLarge';

export default NewChatButtonLarge;
