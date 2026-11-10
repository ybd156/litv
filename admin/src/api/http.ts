import { ApiError, messageForCode } from './errors'
import { mockEnabled, mockRequest } from './mock'

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

export interface RequestOptions {
  method?: HttpMethod
  body?: unknown
  query?: Record<string, unknown>
  /**
   * 静默：失败时不弹全局错误提示。
   *
   * 用于「有没有都行」的可选请求——比如同时拉列表和待批准列表，
   * 后者失败不该糊用户一脸红条（页面自己有兜底逻辑）。
   */
  silent?: boolean
}

/**
 * ★ 关键：baseURL 必须是「相对路径」api/v1。
 * 管理台部署在 /app/litv/ 下（飞牛网关），写死 /api/v1 会打到网关根路径上 404。
 * hash 路由下 document 目录恒为 /app/litv/，相对路径自然拼成 /app/litv/api/v1/xxx。
 */
export const API_PREFIX = 'api/v1'

function buildQuery(query?: Record<string, unknown>): string {
  if (!query) return ''
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === '') continue
    sp.set(k, String(v))
  }
  const s = sp.toString()
  return s ? `?${s}` : ''
}

export function apiUrl(path: string, query?: Record<string, unknown>): string {
  const rel = `${API_PREFIX}/${path.replace(/^\/+/, '')}`
  const base = import.meta.env.BASE_URL || '/'
  // 当前文档所在目录；正常（/app/litv/#/xxx）时等于 base，直接用相对路径
  const dir = location.pathname.replace(/[^/]*$/, '') || '/'
  const url = dir === base ? rel : base + rel
  return url + buildQuery(query)
}

let errorNotifier: ((msg: string) => void) | null = null
let unauthorizedHandler: (() => void) | null = null

/** 让 api 层能把错误弹到 Naive UI 的 message 上（由 NaiveBridge 注入） */
export function setApiErrorNotifier(fn: ((msg: string) => void) | null): void {
  errorNotifier = fn
}

/** 收到 2001 时通知上层切到「请从飞牛桌面打开」说明页 */
export function setUnauthorizedHandler(fn: (() => void) | null): void {
  unauthorizedHandler = fn
}

function notifyError(msg: string): void {
  if (errorNotifier) errorNotifier(msg)
}

/** 应急登录的 token 存 localStorage（正常从飞牛桌面进来不需要） */
const TOKEN_KEY = 'litv_admin_token'

export function adminToken(): string {
  try {
    return localStorage.getItem(TOKEN_KEY) ?? ''
  } catch {
    return ''
  }
}

export function setAdminToken(tk: string): void {
  try {
    if (tk) localStorage.setItem(TOKEN_KEY, tk)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* 隐私模式下 localStorage 可能不可用，忽略 */
  }
}

function fail(err: ApiError, silent = false): ApiError {
  if (err.isUnauthorized) unauthorizedHandler?.()
  else if (!silent) notifyError(err.message)
  return err
}

interface EnvelopeLike {
  code?: unknown
  msg?: unknown
  data?: unknown
}

function looksLikeEnvelope(v: unknown): v is EnvelopeLike {
  return typeof v === 'object' && v !== null && 'code' in (v as Record<string, unknown>)
}

export async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const method = opts.method ?? 'GET'

  if (mockEnabled()) {
    return mockRequest<T>(path, method, opts)
  }

  const url = apiUrl(path, opts.query)
  const init: RequestInit = {
    method,
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  }
  // 应急登录拿到的 token（网关不可用时用密码登录）。
  // 正常从飞牛桌面进来没有这个，身份由网关注入。
  const tk = adminToken()
  if (tk) (init.headers as Record<string, string>).Authorization = `Bearer ${tk}`
  if (opts.body instanceof FormData) {
    // 文件上传（如 EPG 本地导入）：交给浏览器自己带 multipart boundary，
    // **不能**设 Content-Type，也不能 JSON.stringify——否则服务端收不到文件。
    init.body = opts.body
  } else if (opts.body !== undefined) {
    init.headers = { ...(init.headers as Record<string, string>), 'Content-Type': 'application/json' }
    init.body = JSON.stringify(opts.body)
  }

  let res: Response
  try {
    res = await fetch(url, init)
  } catch (e) {
    throw fail(new ApiError(0, '连不上服务端，确认 LITV 服务在运行，或刷新页面重试', 0, String(e)), opts.silent)
  }

  const text = await res.text()
  let parsed: unknown = undefined
  if (text) {
    try {
      parsed = JSON.parse(text) as unknown
    } catch {
      parsed = undefined
    }
  }

  if (looksLikeEnvelope(parsed)) {
    const code = typeof parsed.code === 'number' ? parsed.code : 0
    const msg = typeof parsed.msg === 'string' ? parsed.msg : ''
    if (code !== 0) {
      throw fail(new ApiError(code, messageForCode(code, msg), res.status), opts.silent)
    }
    return parsed.data as T
  }

  if (!res.ok) {
    // 401 = 未认证（没身份 / 登录态过期），403 = 已认证但没有权限。
    // 这两种要给用户完全不同的下一步动作，合成一句「没权限或登录态过期」等于什么都没说。
    // 注意只有 401 能变成 2001（触发未认证页）；403 必须是 code 0，绝不能把
    // 「你不是管理员」升级成「重新登录」，那会把用户踢到错误的页面。
    const unauthorized = res.status === 401
    const hint = unauthorized
      ? '登录态已过期，请从飞牛桌面重新打开本应用'
      : res.status === 403
        ? '当前账号没有管理权限，请联系管理员'
        : `HTTP ${res.status}`
    throw fail(new ApiError(unauthorized ? 2001 : 0, hint, res.status, text.slice(0, 300)), opts.silent)
  }

  if (text && parsed === undefined) {
    // 200 但不是 JSON：通常是网关把请求兜底成了 index.html（路径不对）
    throw fail(
      new ApiError(0, '服务端返回的不是 JSON，检查接口路径（应为 api/v1/...）和网关转发', res.status, text.slice(0, 200)),
      opts.silent,
    )
  }

  // 少数接口可能直接返回裸数据（数组 / 对象），兼容一下
  return parsed as T
}

/**
 * 快捷方法的附加选项。
 *
 * `silent` 存在的原因：页面自己普遍有 `StateBlock` 错误块（首屏失败会显示
 * 「没加载出来 + 重试」），而 `request()` 默认还会再弹一条全局红条 —— 一次断网
 * 用户会同时看到两条错误，并且红条把页内那个带「重试」按钮的提示盖住。
 * 有自己错误态的调用一律传 `{ silent: true }`。
 */
export interface HttpCallOptions {
  silent?: boolean
}

export const http = {
  get: <T>(path: string, query?: Record<string, unknown>, opts?: HttpCallOptions) =>
    request<T>(path, { method: 'GET', query, silent: opts?.silent }),
  post: <T>(path: string, body?: unknown, opts?: HttpCallOptions) =>
    request<T>(path, { method: 'POST', body, silent: opts?.silent }),
  put: <T>(path: string, body?: unknown, opts?: HttpCallOptions) =>
    request<T>(path, { method: 'PUT', body, silent: opts?.silent }),
  del: <T>(path: string, query?: Record<string, unknown>, opts?: HttpCallOptions) =>
    request<T>(path, { method: 'DELETE', query, silent: opts?.silent }),
}
