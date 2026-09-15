import React, { useMemo, useRef } from 'react';
import { ImagePlus } from 'lucide-react';
import { FileUpload, TooltipAnchor, composerControlClasses } from '@librechat/client';
import type { TConversation } from 'librechat-data-provider';
import type { ExtendedFile, FileSetter } from '~/common';
import { useFileHandlingNoChatContext, useLocalize } from '~/hooks';
import { cn } from '~/utils';

/**
 * Native-feeling image attachment for the Android app.
 *
 * The generic paperclip opens the WebView's document chooser; this button
 * clicks a hidden image-only input instead, which MainActivity turns into the
 * system photo picker (or camera when capture is requested). Rendered only
 * inside the Android WebView so the desktop composer stays unchanged.
 */
const AttachImageButton = ({
  disabled,
  files,
  setFiles,
  setFilesLoading,
  conversation,
}: {
  disabled?: boolean | null;
  files: Map<string, ExtendedFile>;
  setFiles: FileSetter;
  setFilesLoading: React.Dispatch<React.SetStateAction<boolean>>;
  conversation: TConversation | null;
}) => {
  const localize = useLocalize();
  const inputRef = useRef<HTMLInputElement>(null);
  const isUploadDisabled = disabled ?? false;

  const isAndroid = useMemo(
    () => typeof navigator !== 'undefined' && /Android/i.test(navigator.userAgent),
    [],
  );

  const { handleFileChange } = useFileHandlingNoChatContext(undefined, {
    files,
    setFiles,
    setFilesLoading,
    conversation,
  });

  if (!isAndroid) {
    return null;
  }

  const label = localize('com_sidepanel_attach_files');

  const openPicker = () => {
    const input = inputRef.current;
    if (!input) {
      return;
    }
    /** Selected imperatively, exactly like the attach menu does. */
    input.accept = 'image/*,.heif,.heic';
    input.value = '';
    input.click();
  };

  return (
    <FileUpload ref={inputRef} handleFileChange={handleFileChange}>
      <TooltipAnchor
        description={label}
        id="attach-image"
        disabled={isUploadDisabled}
        render={
          <button
            type="button"
            aria-label={label}
            disabled={isUploadDisabled}
            data-testid="attach-image"
            className={cn(composerControlClasses(), 'px-2.5 md:px-theme-normal')}
            onClick={openPicker}
          >
            <ImagePlus className="size-4 shrink-0 text-text-secondary" aria-hidden="true" />
          </button>
        }
      />
    </FileUpload>
  );
};

export default React.memo(AttachImageButton);
