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
