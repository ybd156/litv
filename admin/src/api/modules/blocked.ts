import { http } from '../http'
import type { BlockedInfo } from '../types'

/**
 * 屏蔽清单：整类或单个频道不下发给客户端。
 *
 * 只有**边缘降级部署**有这个接口（`edge/` 的 Worker 实现），NAS 服务端没有；
 * 页面本身也只在边缘部署里出现（`deploy.ts` 的 EDGE_DEPLOY）。
 *
 * 几个请求都带 silent：页面自己有错误态（StateBlock），
 * 不需要再叠一条全局红条。
 */
export function getBlocked(): Promise<BlockedInfo> {
  return http.get<BlockedInfo>('blocked', undefined, { silent: true })
}

/** 加一条屏蔽规则。kind=group 按分类整类挡，kind=channel 按频道名精确挡。 */
export function addBlocked(kind: 'group' | 'channel', value: string): Promise<BlockedInfo> {
  return http.post<BlockedInfo>('blocked', { kind, value }, { silent: true })
}

/** 解除一条屏蔽规则 */
export function removeBlocked(kind: 'group' | 'channel', value: string): Promise<BlockedInfo> {
  return http.del<BlockedInfo>('blocked', { kind, value }, { silent: true })
}
