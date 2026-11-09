import { http } from '../http'
import { normalizeLogs } from '../normalize'
import type { Me, SystemConfig, SystemInfo } from '../types'

export function fetchMe(): Promise<Me> {
  return http.get<Me>('system/me')
}

export function fetchSystemInfo(): Promise<SystemInfo> {
  return http.get<SystemInfo>('system/info')
}

export function fetchSystemConfig(): Promise<SystemConfig> {
  return http.get<SystemConfig>('system/config')
}

export function saveSystemConfig(patch: Partial<SystemConfig>): Promise<SystemConfig> {
  return http.put<SystemConfig>('system/config', patch)
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

export async function fetchLogs(lines = 200): Promise<string[]> {
  const raw = await http.get<unknown>('system/logs', { lines })
  return normalizeLogs(raw)
}
