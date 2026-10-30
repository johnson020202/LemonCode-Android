import {
  LCODE_VERSION,
  buildRuntimeLCodeApiUrl,
  buildRuntimeLCodeEndpointUrls,
} from "@lcode/shared";

const DESKTOP_OAUTH_CALLBACK_URI = "lcode://oauth/callback";

export function readEnv(env: NodeJS.ProcessEnv, key: string): string | undefined {
  const value = env[key];
  if (!value) {
    return undefined;
  }

  const trimmed = value.trim();
  return trimmed === "" ? undefined : trimmed;
}

export function readBoolean(env: NodeJS.ProcessEnv, key: string, fallback: boolean): boolean {
  const raw = readEnv(env, key);
  if (raw == null) {
    return fallback;
  }

  return raw !== "0" && raw.toLowerCase() !== "false";
}

export function buildLCodeApiUrlFromEnv(env: NodeJS.ProcessEnv, path: string): string {
  // OAuth provider 是运行时配置，必须跟随传入 env.LCODE_ENV；
  // 地址来自 .env 的通用变量，默认线上；登录与 token 交换必须使用同一配置来源。
  return buildRuntimeLCodeApiUrl(env, path);
}

/**
 * 官网 `/app/oauth/login` 对 redirect 参数做严格校验：只接受
 * `zcode://oauth/callback`，其它 scheme 一律返回 null，结果是页面上的
 * 「打开 ZCode」按钮点了没有任何反应。桌面端两个 scheme 都注册，
 * 因此允许按运行环境覆盖；Android 传 `zcode://oauth/callback`。
 */
export function buildDesktopOAuthRedirectUriFromEnv(env: NodeJS.ProcessEnv): string {
  const url = new URL("/app/oauth/login", buildRuntimeLCodeEndpointUrls(env).origin);
  url.searchParams.set(
    "redirect",
    readEnv(env, "LCODE_OAUTH_CALLBACK_URI") ?? DESKTOP_OAUTH_CALLBACK_URI,
  );
  // Website 需要按 App 版本决定是否关闭自动 deep link；缺少版本时必须兼容旧客户端行为。
  url.searchParams.set("app_version", LCODE_VERSION);
  return url.toString();
}
