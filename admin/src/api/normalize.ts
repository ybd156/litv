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
