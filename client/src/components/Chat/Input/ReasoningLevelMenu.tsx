import { useMemo } from 'react';
import * as Ariakit from '@ariakit/react';
import { Brain, Check, ChevronDown } from 'lucide-react';
import { TooltipAnchor, composerControlClasses } from '@librechat/client';
import {
  ReasoningEffort,
  getEndpointField,
  getSettingsKeys,
  normalizeEndpointName,
  paramSettings,
  resolveDropParamsUIKeys,
} from 'librechat-data-provider';
import type { SettingDefinition, TConversation } from 'librechat-data-provider';
import type { TranslationKeys } from '~/hooks';
import { useGetEndpointsQuery, useGetStartupConfig } from '~/data-provider';
import { useLocalize, useSetIndexOptions } from '~/hooks';
import { cn } from '~/utils';

/** Levels offered in the composer — the widely supported subset of the full
 *  parameter set. `Auto` leaves the provider default untouched. */
const LEVELS = [
  ReasoningEffort.unset,
  ReasoningEffort.none,
  ReasoningEffort.low,
  ReasoningEffort.medium,
  ReasoningEffort.high,
  ReasoningEffort.max,
] as const;

/** Labels for values the composer does not offer but the side panel can set. */
const FALLBACK_LABELS: Record<string, TranslationKeys> = {
  [ReasoningEffort.unset]: 'com_ui_auto',
  [ReasoningEffort.none]: 'com_ui_none',
  [ReasoningEffort.minimal]: 'com_ui_minimal',
  [ReasoningEffort.low]: 'com_ui_low',
  [ReasoningEffort.medium]: 'com_ui_medium',
  [ReasoningEffort.high]: 'com_ui_high',
  [ReasoningEffort.xhigh]: 'com_ui_xhigh',
  [ReasoningEffort.max]: 'com_ui_max',
};

export default function ReasoningLevelMenu({
  conversation,
  disabled,
}: {
  conversation: TConversation | null;
  disabled: boolean;
}) {
  const localize = useLocalize();
  const menuStore = Ariakit.useMenuStore({ focusLoop: true, placement: 'top-start' });
  const isOpen = menuStore.useState('open');
  const { setOption } = useSetIndexOptions();
  const { data: endpointsConfig = {} } = useGetEndpointsQuery();
  const { data: startupConfig } = useGetStartupConfig();

  /** Resolve the same effective parameter set the side panel renders, so the
   *  control only appears where `reasoning_effort` is actually supported
   *  (custom/OpenAI/Azure/OpenRouter endpoints, never agents or assistants). */
  const setting = useMemo((): SettingDefinition | undefined => {
    const endpoint = conversation?.endpoint;
    if (!endpoint) {
      return undefined;
    }
    const model = conversation?.model ?? '';
    const endpointType = getEndpointField(endpointsConfig, endpoint, 'type');
    const customParams = endpointsConfig[endpoint]?.customParams ?? {};
    const [combinedKey, endpointKey] = getSettingsKeys(endpointType ?? endpoint, model);
    const overriddenEndpointKey = customParams.defaultParamsEndpoint ?? endpointKey;
    const dropParamsEntry =
      startupConfig?.endpointsDropParamsMap?.[endpoint] ??
      startupConfig?.endpointsDropParamsMap?.[normalizeEndpointName(endpoint)];
    const resolvedDropParams = Array.isArray(dropParamsEntry)
      ? dropParamsEntry
      : dropParamsEntry?.[model];
    const dropParamsSet = resolveDropParamsUIKeys(
      Array.isArray(resolvedDropParams) ? resolvedDropParams : undefined,
      overriddenEndpointKey,
    );
    if (dropParamsSet.has('reasoning_effort')) {
      return undefined;
    }
    const override = customParams.paramDefinitions?.find(
      (param) => param.key === 'reasoning_effort',
    );
    if (override) {
      return override as SettingDefinition;
    }
    const parameters = paramSettings[combinedKey] ?? paramSettings[overriddenEndpointKey] ?? [];
    return parameters.find((param) => param?.key === 'reasoning_effort');
  }, [conversation?.endpoint, conversation?.model, endpointsConfig, startupConfig]);

  const levels = useMemo(() => {
    if (!setting) {
      return [];
    }
    const allowed = new Set(setting.options ?? []);
    return allowed.size === 0 ? [...LEVELS] : LEVELS.filter((level) => allowed.has(level));
  }, [setting]);

  if (!setting || conversation == null || levels.length === 0) {
    return null;
  }

  const current = String(conversation.reasoning_effort ?? ReasoningEffort.unset);
  const labelFor = (value: string): string => {
    const mapped = (setting.enumMappings as Record<string, string> | undefined)?.[value];
    const key = (mapped ?? FALLBACK_LABELS[value]) as TranslationKeys | undefined;
    return key ? localize(key) : value;
  };
  const selectedLabel = labelFor(current);

  const selectLevel = (level: ReasoningEffort) => {
    setOption('reasoning_effort')(level);
  };

  return (
    <Ariakit.MenuProvider store={menuStore}>
      <TooltipAnchor
        description={localize('com_endpoint_reasoning_effort')}
        disabled={isOpen}
        render={
          <Ariakit.MenuButton
            disabled={disabled}
            data-testid="reasoning-level-menu"
            aria-label={`${localize('com_endpoint_reasoning_effort')}: ${selectedLabel}`}
            className={cn(
              composerControlClasses(),
              'min-w-0 max-w-full px-2.5 md:px-theme-normal',
              isOpen && 'bg-surface-hover',
              disabled && 'cursor-not-allowed opacity-50',
            )}
          />
        }
      >
        <Brain className="size-4 shrink-0 text-text-secondary" aria-hidden="true" />
        <span className="min-w-0 max-w-[12rem] truncate">{selectedLabel}</span>
        <ChevronDown
          className={cn(
            'size-3 shrink-0 text-text-secondary transition-transform',
            isOpen && 'rotate-180',
          )}
          aria-hidden="true"
        />
      </TooltipAnchor>
      <Ariakit.Menu
        portal={true}
        gutter={8}
        unmountOnHide={true}
        className={cn(
          'z-50 flex min-w-[220px] max-w-[min(320px,calc(100vw-2rem))] flex-col rounded-xl',
          'max-h-[var(--popover-available-height)] overflow-y-auto border border-border-light bg-presentation p-1.5 shadow-lg',
          'origin-bottom opacity-0 transition-[opacity,transform] duration-200 ease-out',
          'data-[enter]:scale-100 data-[enter]:opacity-100',
          'scale-95 data-[leave]:scale-95 data-[leave]:opacity-0',
        )}
      >
        {/* Names the menu without adding an `h1` to the page outline. */}
        <Ariakit.MenuHeading
          render={<div />}
          className="px-2.5 py-1.5 text-xs font-medium text-text-secondary"
        >
          {localize('com_endpoint_reasoning_effort')}
        </Ariakit.MenuHeading>
        {levels.map((level) => {
          const isSelected = level === current;
          return (
            <Ariakit.MenuItemRadio
              key={level}
              name="reasoning_effort"
              value={level}
              checked={isSelected}
              hideOnClick={true}
              onChange={() => selectLevel(level)}
              className={cn(
                'group flex w-full cursor-pointer items-center gap-3 rounded-lg px-2.5 py-2',
                'outline-none transition-colors duration-theme-fast',
                'hover:bg-surface-hover data-[active-item]:bg-surface-hover',
                isSelected && 'bg-surface-active-alt',
              )}
            >
              <div className="min-w-0 flex-1 text-left text-sm font-medium text-text-primary">
                {labelFor(level)}
              </div>
              {isSelected && (
                <Check className="size-4 shrink-0 text-text-primary" aria-hidden="true" />
              )}
            </Ariakit.MenuItemRadio>
          );
        })}
      </Ariakit.Menu>
    </Ariakit.MenuProvider>
  );
}
