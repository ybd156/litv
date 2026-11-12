import { http } from '../http'
import { asArray } from '../normalize'
import type { BindCode, Device, DeviceConfig } from '../types'

/**
 * 设备列表。
 *
 * silent：设备页自己有 `StateBlock` 错误块（带重试），概览页用 `Promise.allSettled`
 * 自己兜底（拉不到就显示 0/—），都不需要再叠一条全局红条。
 */
export async function listDevices(): Promise<Device[]> {
  const raw = await http.get<unknown>('devices', undefined, { silent: true })
  return asArray<Device>(raw)
}

/**
 * 未绑定设备（待批准）。
 *
 * 之前调的是 `device/pending`——服务端根本没有这个路由，所以每次打开
 * 「终端设备」页都弹一条 HTTP 404。服务端 listDevices 本来就支持
 * `?status=` 过滤，直接用它，少一个冗余接口。
 *
 * silent：这是可选请求（页面自己有兜底），失败不该糊用户一脸红条。
 */
export async function listPendingDevices(): Promise<Device[]> {
  const raw = await http.get<unknown>('devices', { status: 'pending' }, { silent: true })
  return asArray<Device>(raw)
}

export function createBindCode(): Promise<BindCode> {
  return http.post<BindCode>('devices/bindcode', undefined, { silent: true })
}

export function approveDevice(id: number): Promise<unknown> {
  return http.post<unknown>(`devices/${id}/approve`, undefined, { silent: true })
}

export function rejectDevice(id: number): Promise<unknown> {
  return http.post<unknown>(`devices/${id}/reject`, undefined, { silent: true })
}

/** 吊销：DELETE 后 token 立即失效 */
export function revokeDevice(id: number): Promise<unknown> {
  return http.del<unknown>(`devices/${id}`, undefined, { silent: true })
}

export function updateDeviceConfig(id: number, config: DeviceConfig): Promise<unknown> {
  return http.put<unknown>(`devices/${id}/config`, config, { silent: true })
}
