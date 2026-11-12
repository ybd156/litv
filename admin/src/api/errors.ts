/** 错误码 → 中文人话（对齐 docs/design/01-API.md 末尾的错误码表） */
export const ERROR_MESSAGES: Record<number, string> = {
  1001: '参数不对，检查一下填写的内容',
  // 只作为「服务端没给 msg」时的兜底。具体原因以服务端 msg 为准：
  // 登录接口的 2001 是「口令不正确」，别处是「会话失效」。
  2001: '未认证，请重新登录',
  2002: '当前账号没有管理权限',
  2003: '设备未绑定或令牌已失效，请在电视上重新生成绑定码',
  3001: '频道不存在，可能已被删除，刷新列表试试',
  3002: '这个频道没有可用线路，去加一条源地址',
  4001: '上游拉流失败，源站没响应，稍后再试',
  // 边缘降级部署专用：该功能在边缘架构里没有对应物（不是没做，是本来就不存在）
  2006: '边缘模式不支持这个功能',
  5001: '服务端内部出错了，去「系统设置 → 日志」看看',
}

export function messageForCode(code: number, fallback?: string): string {
  if (code === 0) return fallback || 'ok'
  const detail = (fallback ?? '').trim()
  // 服务端已经给了人话就直接用。同一个码在不同接口含义不同——
  // 2001 在登录接口是「口令不正确」，在管理接口是「会话失效」。
  // 以前是把表里的通用文案放前面、真实原因缩进括号，结果登录失败时
  // 用户先读到一句「请从飞牛桌面重新打开本应用」，而 edge 部署上
  // 根本没有飞牛桌面。
  if (detail) {
    return ERROR_MESSAGES[code] ? detail : `${detail}（错误码 ${code}）`
  }
  return ERROR_MESSAGES[code] ?? `请求失败（错误码 ${code}）`
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
