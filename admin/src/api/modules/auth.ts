import { http } from '../http'

/**
 * 应急登录：飞牛网关不可用时，用管理口令换一个会话 token。
 *
 * 服务端只认 `{ password }`（严格 JSON 解码，多传 username 直接 400），
 * 所以这里不要「顺手」加字段。
 */
export function login(password: string): Promise<{ token: string }> {
  return http.post<{ token: string }>('auth/login', { password })
}

/**
 * 首次初始化状态：这台实例还没设过管理口令时为 true。
 *
 * 服务端只回一个布尔值（未鉴权接口不回显任何配置）。探测失败按 false 处理——
 * 宁可让用户看到登录页，也不要在服务不可用时把「设置密码」表单推给任何人。
 */
export function fetchSetupState(): Promise<{ required: boolean }> {
  // silent：探测失败是正常分支（老实例、服务未起），不弹全局错误条。
  return http.get<{ required: boolean }>('auth/setup', undefined, { silent: true })
}

/**
 * 首次设置管理密码。成功时服务端会顺手签发会话，前端直接拿到 token；
 * 若密码已写入但会话没建成，返回 `need_login: true`（去登录页即可）。
 */
export function setupAdmin(password: string): Promise<{
  token: string
  expires_at: number
  need_login?: boolean
}> {
  return http.post<{ token: string; expires_at: number; need_login?: boolean }>('auth/setup', {
    password,
  })
}
