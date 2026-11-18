/** 首屏读取超时的稳定标识；UI 用它替换原始错误文案。 */
export const PROVIDER_SETTINGS_LOAD_TIMEOUT_CODE = "provider-settings-load-timeout";

export type ProviderSettingsLoadTimeoutError = Error & {
  code: typeof PROVIDER_SETTINGS_LOAD_TIMEOUT_CODE;
};

export function createProviderSettingsLoadTimeoutError(
  timeoutMs: number,
): ProviderSettingsLoadTimeoutError {
  const error = new Error(
    `Provider settings getView did not return within ${timeoutMs}ms`,
  ) as ProviderSettingsLoadTimeoutError;
  error.code = PROVIDER_SETTINGS_LOAD_TIMEOUT_CODE;
  return error;
}

export function isProviderSettingsLoadTimeout(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    (error as { code?: unknown }).code === PROVIDER_SETTINGS_LOAD_TIMEOUT_CODE
  );
}
