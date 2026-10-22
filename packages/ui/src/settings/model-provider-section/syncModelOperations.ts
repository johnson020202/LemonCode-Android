import type { ModelConnectivityResult } from "@lcode/shared";

export const MODEL_PROBE_CONCURRENCY = 4;

export interface SyncModelProbeResult {
  readonly id: string;
  readonly success: boolean;
  readonly stage?: "probe" | "save";
  readonly message?: string;
  /**
   * 探测因超时/并发排队取消而中断，而不是供应商给出了否定结论。
   * 这类结果不能作为关闭已配置模型的依据，否则一次探测压力就会让模型从可选列表消失。
   */
  readonly inconclusive?: boolean;
}

const INCONCLUSIVE_MESSAGE = /cancelled|aborted|timed? ?out|timeout/i;

/** 只把“供应商明确拒绝”之外的中断识别为不可判定，避免掩盖真实鉴权/模型错误。 */
export function isInconclusiveProbeMessage(message: string | undefined): boolean {
  return message !== undefined && INCONCLUSIVE_MESSAGE.test(message);
}

/**
 * 协议返回的结论是否足以判定模型不可用。
 * 优先使用目标 Environment 给出的结构化 code；旧协议没有该字段时回退到错误文字判定。
 */
export function isInconclusiveProbeError(error: {
  readonly message: string;
  readonly code?: string;
}): boolean {
  if (error.code === "inconclusive") return true;
  if (error.code !== undefined) return false;
  return isInconclusiveProbeMessage(error.message);
}

export function normalizeModelIds(ids: readonly string[]): string[] {
  return [...new Set(ids.map((id) => id.trim()).filter(Boolean))];
}

export function filterModelIds(ids: readonly string[], query: string): string[] {
  const normalized = query.trim().toLowerCase();
  return ids.filter((id) => id.toLowerCase().includes(normalized));
}

export function selectedModelIds(rows: readonly string[], selected: ReadonlySet<string>): string[] {
  return normalizeModelIds(rows).filter((id) => selected.has(id));
}

export function probeTargetIds(
  rows: readonly string[],
  visibleRows: readonly string[],
  selected: ReadonlySet<string>,
  searching: boolean,
): string[] {
  // 搜索时提交范围收缩为匹配项与勾选交集，避免默认全选的隐藏模型被误检测；未搜索时即全局勾选快照。
  return normalizeModelIds(searching ? visibleRows : rows).filter((id) => selected.has(id));
}

export async function probeAndSyncModel({
  id,
  signal,
  isConfigured,
  probe,
  add,
  setEnabled,
}: {
  id: string;
  signal: AbortSignal;
  isConfigured: () => boolean;
  probe: () => Promise<ModelConnectivityResult>;
  add: () => Promise<void>;
  setEnabled: (enabled: boolean) => Promise<void>;
}): Promise<SyncModelProbeResult> {
  if (signal.aborted) return { id, success: false };
  const wasConfigured = isConfigured();
  let result: ModelConnectivityResult;
  try {
    result = await probe();
  } catch (error) {
    result = { success: false, error: { message: errorMessage(error) } };
  }
  // 临时检测不拥有成员；关闭后的迟到响应或被其他入口删除的旧成员都不能再写配置。
  if (signal.aborted || (wasConfigured && !isConfigured())) return { id, success: false };
  if (result.success) {
    try {
      if (isConfigured()) await setEnabled(true);
      else await add();
    } catch (error) {
      return { id, success: false, stage: "save", message: errorMessage(error) };
    }
    return { id, success: true };
  }
  // 取消/超时只说明这次没测出结论，不是模型不可用。用它禁用已配置模型，
  // 会让并发排队中的模型成批从选择器消失，表现为“选模型时列表空了”。
  const inconclusive = isInconclusiveProbeError(result.error);
  if (!inconclusive) {
    try {
      if (isConfigured()) await setEnabled(false);
    } catch (error) {
      return { id, success: false, stage: "save", message: errorMessage(error) };
    }
  }
  return {
    id,
    success: false,
    stage: "probe",
    message: result.error.message,
    ...(inconclusive ? { inconclusive: true } : {}),
  };
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export async function runCancelablePool<TInput, TResult>({
  items,
  concurrency,
  shouldContinue,
  run,
  onResult,
}: {
  items: readonly TInput[];
  concurrency: number;
  shouldContinue: () => boolean;
  run: (item: TInput) => Promise<TResult>;
  onResult: (result: TResult) => void;
}): Promise<void> {
  if (!Number.isInteger(concurrency) || concurrency < 1) {
    throw new Error("concurrency must be a positive integer");
  }

  let cursor = 0;
  let failed = false;
  const worker = async () => {
    while (!failed && shouldContinue()) {
      const index = cursor;
      cursor += 1;
      const item = items[index];
      if (item === undefined) return;
      try {
        const result = await run(item);
        if (!shouldContinue()) return;
        onResult(result);
      } catch (error) {
        failed = true;
        throw error;
      }
    }
  };

  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, () => worker()));
}
