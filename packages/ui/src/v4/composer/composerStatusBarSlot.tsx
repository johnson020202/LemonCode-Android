import { createContext, useContext } from "react";

/**
 * Composer 状态栏的宿主节点。
 *
 * <p>Token 用量状态栏的数据（会话快照、子代理、上下文额度）都在工具栏组件里，
 * 但它要显示在输入框底部——位置由 ChatPromptEditor 决定。用一个只装 DOM 节点的
 * context 把两侧接起来：编辑器负责落位，工具栏负责内容。
 *
 * <p>节点为 null 表示当前没有可用的宿主（草稿态、只读面板或测试渲染），
 * 此时状态栏退回工具栏原位显示。
 */
export const ComposerStatusBarSlotContext = createContext<HTMLDivElement | null>(null);

export function useComposerStatusBarSlot(): HTMLDivElement | null {
  return useContext(ComposerStatusBarSlotContext);
}
