import { http, request } from '../http'
import { asArray } from '../normalize'
import type { BindCode, Device, DeviceConfig } from '../types'

export async function listDevices(): Promise<Device[]> {
  const raw = await http.get<unknown>('devices')
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
  const raw = await request<unknown>('devices', { query: { status: 'pending' }, silent: true })
  return asArray<Device>(raw)
}

export function createBindCode(): Promise<BindCode> {
  return http.post<BindCode>('devices/bindcode')
}

export function approveDevice(id: number): Promise<unknown> {
  return http.post<unknown>(`devices/${id}/approve`)
}

export function rejectDevice(id: number): Promise<unknown> {
  return http.post<unknown>(`devices/${id}/reject`)
}

/** 吊销：DELETE 后 token 立即失效 */
export function revokeDevice(id: number): Promise<unknown> {
  return http.del<unknown>(`devices/${id}`)
}

export function updateDeviceConfig(id: number, config: DeviceConfig): Promise<unknown> {
  return http.put<unknown>(`devices/${id}/config`, config)
}
