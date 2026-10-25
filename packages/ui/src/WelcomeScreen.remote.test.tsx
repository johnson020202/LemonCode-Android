import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import type { IPlatformService } from "@lcode/shared";

registerHooks({
  load(url, context, nextLoad) {
    if (/\.(?:gif|jpe?g|png|webp|svg)$/.test(url)) {
      return {
        format: "module",
        shortCircuit: true,
        source: `export default ${JSON.stringify(url)};`,
      };
    }
    return nextLoad(url, context);
  },
});

const { LCodeIntlProvider } = await import("@/i18n/IntlProvider.js");
const { PlatformProvider } = await import("@/hooks/usePlatform.js");
const { ServiceProvider } = await import("@/hooks/useServices.js");
const { StoreProvider } = await import("@/store/StoreProvider.js");
const { WelcomeScreen } = await import("./WelcomeScreen.js");

// StoreProvider 初始化会应用主题；静态渲染只需要最小 DOM 表面，不引入 jsdom。
(globalThis as Record<string, unknown>).document = {
  documentElement: {
    classList: { toggle() {}, add() {}, remove() {} },
    hasAttribute: () => false,
    style: {},
  },
  querySelector: () => null,
  createElement: () => ({}),
  head: { append() {} },
};

const broadcastStub = {
  send: async () => {},
  acquireClaim: async () => ({}),
  commitClaim: async () => {},
  releaseClaim: async () => {},
  tryClaim: async () => false,
  onMessage: () => () => {},
} as unknown as import("@lcode/services").IBroadcastService;

function render(platform: IPlatformService) {
  return renderToStaticMarkup(
    <LCodeIntlProvider initialLocale="zh-CN">
      <PlatformProvider platform={platform}>
        <ServiceProvider services={{} as never}>
          <StoreProvider broadcastService={broadcastStub}>
            <WelcomeScreen onComplete={() => {}} />
          </StoreProvider>
        </ServiceProvider>
      </PlatformProvider>
    </LCodeIntlProvider>,
  );
}

test("Android 本机欢迎页显示扫码和完整网址输入，保留进入和取消", () => {
  const markup = render({
    scanRemoteConnection: () => {},
    enterRemoteConnectionUrl: () => {},
  } as IPlatformService);
  assert.match(markup, /data-testid="login-scan-remote"/);
  assert.match(markup, /扫码连接电脑/);
  assert.match(markup, /data-testid="login-enter-remote-url"/);
  assert.match(markup, /填写远程网址/);
  assert.match(markup, /data-testid="login-use-api-key-button"/);
  assert.match(markup, /data-testid="oauth-cancel"/);
});

test("普通 Web 或远程镜像没有 Android 能力时不显示连接入口", () => {
  const markup = render({} as IPlatformService);
  assert.doesNotMatch(markup, /login-scan-remote/);
  assert.doesNotMatch(markup, /login-enter-remote-url/);
  assert.match(markup, /data-testid="login-use-api-key-button"/);
  assert.match(markup, /data-testid="oauth-cancel"/);
});
