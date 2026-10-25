import type { ProviderApiType } from "@lcode/provider";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select.js";
import { useLCodeIntl } from "@/i18n/IntlProvider.js";
import {
  TID_MODEL_PROVIDER_API_FORMAT_ITEM,
  TID_MODEL_PROVIDER_API_FORMAT_TRIGGER,
  testId,
} from "@lcode/shared";

// 展示顺序按实际使用频率排：OpenAI 兼容协议（尤其 /chat/completions）是第三方中转与
// 自建网关最常见的接入方式，放在最前面，减少每次新建供应商都要下拉翻找。
// 这里只决定下拉顺序，不改 api.type 的语义默认值——运行时空值仍回退 anthropic-messages。
const PROVIDER_CONNECTION_API_FORMATS: readonly ProviderApiType[] = [
  "openai-chat-completions",
  "openai-responses",
  "anthropic-messages",
];

const PROVIDER_CONNECTION_API_FORMAT_PATHS: Record<ProviderApiType, string> = {
  "anthropic-messages": "/v1/messages",
  "openai-chat-completions": "/chat/completions",
  "openai-responses": "/responses",
};

const PROVIDER_CONNECTION_API_FORMAT_TITLE_IDS: Record<ProviderApiType, string> = {
  "anthropic-messages": "settings.modelProvider.apiFormat.title.anthropicMessages",
  "openai-chat-completions": "settings.modelProvider.apiFormat.title.chatCompletions",
  "openai-responses": "settings.modelProvider.apiFormat.title.responses",
};

export function resolveProviderConnectionApiFormatOptions(): ProviderApiType[] {
  return [...PROVIDER_CONNECTION_API_FORMATS];
}

export function resolveProviderConnectionApiFormatDisplayLabel(
  intl: { formatMessage: (descriptor: { id: string }) => string },
  format: ProviderApiType,
): string {
  const title = intl.formatMessage({
    id: PROVIDER_CONNECTION_API_FORMAT_TITLE_IDS[format],
  });
  return `${title} (${PROVIDER_CONNECTION_API_FORMAT_PATHS[format]})`;
}

export function ProviderApiFormatSelect({
  apiFormatOptions = PROVIDER_CONNECTION_API_FORMATS,
  triggerId,
  value,
  onChange,
}: {
  apiFormatOptions?: readonly ProviderApiType[];
  triggerId?: string;
  value: ProviderApiType;
  onChange: (value: ProviderApiType) => void;
}) {
  const { intl } = useLCodeIntl();

  return (
    <Select value={value} onValueChange={(nextValue) => onChange(nextValue as ProviderApiType)}>
      <SelectTrigger
        id={triggerId}
        data-testid={TID_MODEL_PROVIDER_API_FORMAT_TRIGGER}
        size="lg"
        className="w-full justify-between"
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent align="start">
        {apiFormatOptions.map((format) => (
          <SelectItem
            key={format}
            value={format}
            data-testid={testId(TID_MODEL_PROVIDER_API_FORMAT_ITEM, format)}
          >
            {resolveProviderConnectionApiFormatDisplayLabel(intl, format)}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
