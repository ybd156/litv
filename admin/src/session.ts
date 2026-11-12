import { computed, reactive } from 'vue'
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
 * 当前账号能不能做写操作。
 *
 * ★ 服务端语义（`api.go` requireAdmin）：非管理员**可以 GET 全部管理接口**，
 * 只有非 GET 才 403/2002。也就是说只读账号能把每个页面都看一遍，
 * 但所有「保存/删除/停用」按钮点下去才报错——这对用户是纯浪费。
 * 这里只做**提示与禁用按钮**，不动路由也不做权限拦截：
 * 权限判定的唯一权威是服务端，前端拦下来只是省一次 403。
 */
export const canWrite = computed(() => session.me?.is_admin === true)

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
