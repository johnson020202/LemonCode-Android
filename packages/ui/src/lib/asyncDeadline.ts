/**
 * 给本机 RPC 读取加可判定的上限。
 *
 * 供应商设置页的加载态只由 getView 是否返回决定；调用悬着（运行时未就绪、
 * 通道半开）时页面没有错误态也没有重试入口。超时后至少能让调用方收敛。
 */
export function withDeadline<T>(
  operation: Promise<T>,
  timeoutMs: number,
  createError: () => Error,
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(createError());
    }, timeoutMs);
    operation.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}
