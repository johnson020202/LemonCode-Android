import { useEffect, useRef } from "react";
import type { WindowTabState } from "@/store/tabStore.js";
import { useLCodeSessionStore } from "@/store/lcodeSessionStore.js";
import { getWorkspaceState } from "@/store/lcodeSessionStoreSelectors.js";
import type { TabStore } from "@/store/tabStore.js";

export interface MobileMirrorNavigation {
  version: 1;
  tabs: Array<{
    workspacePath: string;
    workspaceIdentity?: string;
    activeTaskId: string | null;
  }>;
  activeWorkspaceKey: string | null;
  settingsActive: boolean;
}

interface NavigationReader {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

function text(value: unknown, max: number): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= max;
}

export function parseMobileMirrorNavigation(raw: string | null): MobileMirrorNavigation | null {
  try {
    const value: unknown = raw ? JSON.parse(raw) : null;
    if (!value || typeof value !== "object") return null;
    const record = value as Record<string, unknown>;
    if (record.version !== 1 || !Array.isArray(record.tabs)) return null;
    const tabs = record.tabs.flatMap((tab) => {
      if (!tab || typeof tab !== "object") return [];
      const candidate = tab as Record<string, unknown>;
      if (!text(candidate.workspacePath, 4096)) return [];
      if (candidate.workspaceIdentity !== undefined && !text(candidate.workspaceIdentity, 4096)) return [];
      if (candidate.activeTaskId !== null && !text(candidate.activeTaskId, 200)) return [];
      return [{
        workspacePath: candidate.workspacePath,
        ...(candidate.workspaceIdentity ? { workspaceIdentity: candidate.workspaceIdentity } : {}),
        activeTaskId: typeof candidate.activeTaskId === "string" ? candidate.activeTaskId : null,
      }];
    });
    const activeWorkspaceKey = record.activeWorkspaceKey === null || text(record.activeWorkspaceKey, 4096)
      ? record.activeWorkspaceKey
      : null;
    return {
      version: 1,
      tabs,
      activeWorkspaceKey,
      settingsActive: record.settingsActive === true,
    };
  } catch {
    return null;
  }
}

export function readMobileMirrorNavigation(
  storage: Pick<NavigationReader, "getItem"> | undefined,
  key: string,
): MobileMirrorNavigation | null {
  try {
    return parseMobileMirrorNavigation(storage?.getItem(key) ?? null);
  } catch {
    return null;
  }
}

/** 只记录用户导航，不缓存 Host 对话事实；root draft 的 null 也必须保留。 */
export function captureMobileMirrorNavigation(input: {
  tabs: readonly WindowTabState[];
  activeTabId: string | null;
  activeWorkspacePath: string | null;
  activeWorkspaceIdentity: string | null;
  activeTaskIdFor: (workspacePath: string, workspaceIdentity?: string) => string | null;
}): MobileMirrorNavigation {
  const tabs = input.tabs.filter((tab) => tab.kind === "workspace");
  return {
    version: 1,
    tabs: tabs.map((tab) => ({
      workspacePath: tab.workspacePath,
      ...(tab.workspaceIdentity ? { workspaceIdentity: tab.workspaceIdentity } : {}),
      activeTaskId: input.activeTaskIdFor(tab.workspacePath, tab.workspaceIdentity),
    })),
    activeWorkspaceKey: input.activeWorkspacePath
      ? input.activeWorkspaceIdentity?.trim() || input.activeWorkspacePath
      : null,
    settingsActive: input.tabs.some((tab) => tab.id === input.activeTabId && tab.kind === "settings"),
  };
}

/** 镜像页导航恢复；只恢复标识，不复制对话内容。 */
export function useMobileMirrorNavigation({
  storageKey,
  ready,
  tabStoreApi,
}: {
  storageKey?: string;
  ready: boolean;
  tabStoreApi: TabStore;
}): void {
  const initialRef = useRef<MobileMirrorNavigation | null | undefined>(undefined);
  if (initialRef.current === undefined && storageKey) {
    initialRef.current = readMobileMirrorNavigation(
      typeof window === "undefined" ? undefined : window.localStorage,
      storageKey,
    );
  }
  const restoredRef = useRef(false);
  useEffect(() => {
    if (!storageKey || !ready || restoredRef.current) return;
    restoredRef.current = true;
    const snapshot = initialRef.current;
    if (!snapshot || snapshot.tabs.length === 0) return;
    const state = tabStoreApi.getState();
    const activeIndex = Math.max(
      0,
      snapshot.tabs.findIndex(
        (tab) => (tab.workspaceIdentity?.trim() || tab.workspacePath) === snapshot.activeWorkspaceKey,
      ),
    );
    state.restoreTabs(
      snapshot.tabs.map((tab) => ({
        workspacePath: tab.workspacePath,
        ...(tab.workspaceIdentity ? { workspaceIdentity: tab.workspaceIdentity } : {}),
      })),
      activeIndex,
    );
    for (const tab of snapshot.tabs) {
      if (tab.activeTaskId) {
        useLCodeSessionStore
          .getState()
          .setActiveTaskId(tab.workspacePath, tab.activeTaskId, tab.workspaceIdentity);
      }
    }
    if (snapshot.settingsActive) tabStoreApi.getState().openSettingsTab();
  }, [ready, storageKey, tabStoreApi]);

  const activeTaskId = useLCodeSessionStore((state) => {
    const tabState = tabStoreApi.getState();
    return tabState.activeWorkspacePath
      ? getWorkspaceState(
          state,
          tabState.activeWorkspacePath,
          tabState.activeWorkspaceIdentity ?? undefined,
        ).activeTaskId
      : null;
  });
  const tabState = tabStoreApi.getState();
  useEffect(() => {
    if (!storageKey || !ready || !restoredRef.current) return;
    const state = tabStoreApi.getState();
    const snapshot = captureMobileMirrorNavigation({
      tabs: state.tabs,
      activeTabId: state.activeTabId,
      activeWorkspacePath: state.activeWorkspacePath,
      activeWorkspaceIdentity: state.activeWorkspaceIdentity,
      activeTaskIdFor: (workspacePath, workspaceIdentity) =>
        useLCodeSessionStore.getState().getWorkspaceState(workspacePath, workspaceIdentity)
          .activeTaskId,
    });
    persistMobileMirrorNavigation(
      typeof window === "undefined" ? undefined : window.localStorage,
      storageKey,
      snapshot,
    );
  }, [activeTaskId, ready, storageKey, tabState.activeTabId, tabState.activeWorkspaceIdentity, tabState.activeWorkspacePath, tabState.tabs, tabStoreApi]);
}

export function persistMobileMirrorNavigation(
  storage: Pick<NavigationReader, "setItem"> | undefined,
  key: string,
  navigation: MobileMirrorNavigation,
): void {
  try {
    storage?.setItem(key, JSON.stringify(navigation));
  } catch {
    // 隐私模式或配额限制只降级恢复能力，不阻断当前已连接工作区。
  }
}
