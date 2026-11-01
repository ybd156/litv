/** 错误码 → 中文人话（对齐 docs/design/01-API.md 末尾的错误码表） */
export const ERROR_MESSAGES: Record<number, string> = {
  1001: '参数不对，检查一下填写的内容',
  2001: '未认证：请从飞牛桌面重新打开本应用',
  2002: '当前账号没有管理权限',
  2003: '设备未绑定或令牌已失效，请在电视上重新生成绑定码',
  3001: '频道不存在，可能已被删除，刷新列表试试',
  3002: '这个频道没有可用线路，去加一条源地址',
  4001: '上游拉流失败，源站没响应，稍后再试',
  5001: '服务端内部出错了，去「系统设置 → 日志」看看',
}

export function messageForCode(code: number, fallback?: string): string {
  if (code === 0) return fallback || 'ok'
  const mapped = ERROR_MESSAGES[code]
  if (mapped) return fallback ? `${mapped}（${fallback}）` : mapped
  return fallback ? `${fallback}（错误码 ${code}）` : `请求失败（错误码 ${code}）`
}

export class ApiError extends Error {
  readonly code: number
  readonly status: number
  readonly detail?: string

  constructor(code: number, message: string, status = 0, detail?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.detail = detail
  }

  get isUnauthorized(): boolean {
    return this.code === 2001 || this.status === 401
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError
}

/** 把任意异常压成一句可以给用户看的中文 */
export function toErrorMessage(e: unknown): string {
  if (isApiError(e)) return e.message
  if (e instanceof Error) return e.message || '未知错误'
  if (typeof e === 'string') return e
  return '未知错误'
}
