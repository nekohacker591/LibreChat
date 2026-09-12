import { EModelEndpoint } from 'librechat-data-provider';
import { useFormContext, Controller } from 'react-hook-form';
import InputWithLabel from './InputWithLabel';

const CustomEndpoint = ({
  endpoint,
  userProvideURL,
}: {
  endpoint: EModelEndpoint | string;
  userProvideURL?: boolean | null;
}) => {
  const { control } = useFormContext();
  const endpointStr = typeof endpoint === 'string' ? endpoint.toLowerCase() : '';
  const isLLMGateway =
    endpointStr.includes('llm gateway') ||
    endpointStr.includes('llmgateway') ||
    endpointStr.includes('devpass');

  return (
    <form className="flex-wrap">
      <Controller
        name="apiKey"
        control={control}
        render={({ field }) => (
          <InputWithLabel
            id="apiKey"
            {...field}
            label={isLLMGateway ? `${endpoint} API Token` : `${endpoint} API Key`}
            placeholder={isLLMGateway ? 'llmgtwy_... or DevPass token' : undefined}
            labelClassName="mb-1"
            inputClassName="mb-2"
            secret
          />
        )}
      />
      {userProvideURL && (
        <Controller
          name="baseURL"
          control={control}
          render={({ field }) => (
            <InputWithLabel
              id="baseURL"
              {...field}
              label={`${endpoint} API URL`}
              labelClassName="mb-1"
            />
          )}
        />
      )}
    </form>
  );
};

export default CustomEndpoint;
