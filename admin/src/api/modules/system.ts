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

export async function fetchLogs(lines = 200): Promise<string[]> {
  const raw = await http.get<unknown>('system/logs', { lines })
  return normalizeLogs(raw)
}
