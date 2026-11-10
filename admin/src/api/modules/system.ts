import { http } from '../http'
import { normalizeLogView, type LogView } from '../normalize'
import type { Me, SystemConfig, SystemInfo } from '../types'

/**
 * 会话探针。
 *
 * 这三个 GET 都 silent：`session.ts` 有 `state==='error'` 的整页错误态（带重试），
 * 概览/设置页也都用 `Promise.allSettled` + 局部错误块兜底，全局红条纯属重复。
 */
export function fetchMe(): Promise<Me> {
  return http.get<Me>('system/me', undefined, { silent: true })
}

export function fetchSystemInfo(): Promise<SystemInfo> {
  return http.get<SystemInfo>('system/info', undefined, { silent: true })
}

export function fetchSystemConfig(): Promise<SystemConfig> {
  return http.get<SystemConfig>('system/config', undefined, { silent: true })
}

/** 保存配置：调用方（设置页）自己 toast 成功/失败，这里 silent 免双份提示。 */
export function saveSystemConfig(patch: Partial<SystemConfig>): Promise<SystemConfig> {
  return http.put<SystemConfig>('system/config', patch, { silent: true })
}

/**
 * 注销当前应急登录会话。
 *
 * 以前管理台没有任何退出入口，setAdminToken('') 只清本地存储，
 * 服务端 admin_sessions 里那一行还在，token 能一直用到 TTL（默认 7 天）。
 * 现在请服务端真正删掉会话；失败由调用方决定是否提示。
 */
export function logoutAdmin(): Promise<void> {
  return http.post<void>('auth/logout')
}

/** 日志正文（兼容旧调用点）。 */
export async function fetchLogs(lines = 200): Promise<string[]> {
  const raw = await http.get<unknown>('system/logs', { lines }, { silent: true })
  return normalizeLogView(raw).lines
}

/** 日志正文 + 服务端说明（面板空态要用 hint，不能自己猜原因）。 */
export async function fetchLogView(lines = 200): Promise<LogView> {
  const raw = await http.get<unknown>('system/logs', { lines }, { silent: true })
  return normalizeLogView(raw)
}
