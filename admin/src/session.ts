import { reactive } from 'vue'
import { isApiError, toErrorMessage } from '@/api/errors'
import { fetchMe } from '@/api/modules/system'
import type { Me } from '@/api/types'

export type SessionState = 'loading' | 'ready' | 'unauthenticated' | 'error'

export const session = reactive({
  state: 'loading' as SessionState,
  me: null as Me | null,
  error: '',
})

/**
 * 管理台不设登录页：身份来自飞牛网关注入的 X-Trim-* 头，
 * 启动时问一次 GET api/v1/system/me 就知道有没有被网关放进来。
 */
export async function loadSession(): Promise<void> {
  session.state = 'loading'
  session.error = ''
  try {
    const me = await fetchMe()
    session.me = me
    session.state = 'ready'
  } catch (e) {
    if (isApiError(e) && e.isUnauthorized) {
      session.state = 'unauthenticated'
      return
    }
    session.error = toErrorMessage(e)
    session.state = 'error'
  }
}

/** 请求过程中被判定为未认证（token 过期 / 网关没注入头） */
export function markUnauthenticated(): void {
  session.state = 'unauthenticated'
}
