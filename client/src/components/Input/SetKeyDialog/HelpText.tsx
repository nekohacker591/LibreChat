import { memo } from 'react';
import { EModelEndpoint } from 'librechat-data-provider';
import { useLocalize } from '~/hooks';

function HelpText({ endpoint }: { endpoint: string }) {
  const localize = useLocalize();
  const textMap = {
    [EModelEndpoint.google]: (
      <>
        <small className="mt-4 break-all text-text-secondary">
          {localize('com_endpoint_config_google_service_key')}
          {': '}
          {localize('com_endpoint_config_key_google_need_to')}{' '}
          <a
            target="_blank"
            href="https://console.cloud.google.com/vertex-ai"
            rel="noreferrer"
            className="text-link underline"
          >
            {localize('com_endpoint_config_key_google_vertex_ai')}
          </a>{' '}
          {localize('com_endpoint_config_key_google_vertex_api')}{' '}
          <a
            target="_blank"
            href="https://console.cloud.google.com/projectselector/iam-admin/serviceaccounts/create?walkthrough_id=iam--create-service-account#step_index=1"
            rel="noreferrer"
            className="text-link underline"
          >
            {localize('com_endpoint_config_key_google_service_account')}
          </a>
          {'. '}
          {localize('com_endpoint_config_key_google_vertex_api_role')}
        </small>
        <small className="break-all text-text-secondary">
          {localize('com_endpoint_config_google_api_key')}
          {': '}
          {localize('com_endpoint_config_google_api_info')}{' '}
          <a
            target="_blank"
            href="https://makersuite.google.com/app/apikey"
            rel="noreferrer"
            className="text-link underline"
          >
            {localize('com_endpoint_config_click_here')}
          </a>{' '}
        </small>
      </>
    ),
  };

  const endpointStr = typeof endpoint === 'string' ? endpoint.toLowerCase() : '';
  const isLLMGateway =
    endpointStr.includes('llm gateway') ||
    endpointStr.includes('llmgateway') ||
    endpointStr.includes('devpass');

  if (isLLMGateway) {
    return (
      <small className="mt-4 block break-words text-text-secondary">
        Enter your API token (starts with <code className="text-xs">llmgtwy_</code> or DevPass token). See{' '}
        <a
          target="_blank"
          href="https://docs.llmgateway.io/"
          rel="noreferrer"
          className="text-link underline"
        >
          LLM Gateway Docs
        </a>{' '}
        for details on authentication and available models.
      </small>
    );
  }

  return textMap[endpoint] || null;
}

export default memo(HelpText);
