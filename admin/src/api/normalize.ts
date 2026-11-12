/** 服务端不同实现可能返回数组本体或 {items:[...]}，这里统一抹平 */
export function asArray<T>(raw: unknown): T[] {
  if (Array.isArray(raw)) return raw as T[]
  if (raw && typeof raw === 'object') {
    const obj = raw as Record<string, unknown>
    for (const key of ['items', 'list', 'rows', 'lines', 'data']) {
      const v = obj[key]
      if (Array.isArray(v)) return v as T[]
    }
  }
  return []
}

export function normalizeLogs(raw: unknown): string[] {
  if (typeof raw === 'string') {
    return raw.split(/\r?\n/).filter((l) => l.length > 0)
  }
  const arr = asArray<unknown>(raw)
  return arr.map((l) => (typeof l === 'string' ? l : JSON.stringify(l)))
}

/** 日志接口的完整视图：正文 + 服务端给的说明。 */
export interface LogView {
  lines: string[]
  /**
   * 服务端说明（例如「日志文件位于 $TRIM_PKGVAR/litv.log」）。
   *
   * ★ 为什么要单独取：`GET /system/logs` 现在返回 `{hint, lines:[]}`——服务端
   * 还没做环形缓冲，只有 hint 是有用信息。之前只读 lines，于是面板永远显示
   * 「空」并且用前端自己猜的理由（「日志级别设太高」）误导用户。
   * 空态必须透传这句 hint，不能替服务端编原因。
   */
  hint: string
}

export function normalizeLogView(raw: unknown): LogView {
  const lines = normalizeLogs(raw)
  let hint = ''
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    const h = (raw as Record<string, unknown>).hint
    if (typeof h === 'string') hint = h
  }
  return { lines, hint }
}
