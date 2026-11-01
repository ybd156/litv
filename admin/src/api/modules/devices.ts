import { http } from '../http'
import { asArray } from '../normalize'
import type { BindCode, Device, DeviceConfig } from '../types'

export async function listDevices(): Promise<Device[]> {
  const raw = await http.get<unknown>('devices')
  return asArray<Device>(raw)
}

/** 未绑定设备（待批准）。01-API.md 里挂在公开段，但注明需要 admin */
export async function listPendingDevices(): Promise<Device[]> {
  const raw = await http.get<unknown>('device/pending')
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
