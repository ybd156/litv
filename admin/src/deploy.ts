/**
 * 对面跑的是什么。
 *
 * 这里只有两样东西，**不要合并成一档**：
 *
 *   - `edge`：Cloudflare Worker（`edge/`），CI 用 `--base=/` 构建（见 edge-deploy.yml）。
 *     它自己的探活报 `version: "edge"` —— 这是全仓认定 edge 的唯一判据
 *     （客户端 `ReplayCapability.EDGE_VERSION` 用的也是这个值）。
 *   - `server`：Go 服务端。飞牛 fpk / Docker / 1Panel / 裸机反代跑的都是它，
 *     报的是版本号（`0.1.24` 之类），另加一个 `identity` 说明**身份从哪来**：
 *     `gateway`（飞牛网关注入 `X-Trim-*` 头）或 `password`（`auth.admin_password`）。
 *
 * ★ 2026-11-28 修正：以前用 `BASE_URL === '/'` 判 edge，结果自建 Docker 挂在根路径上
 * 被当成边缘部署，登录失效页硬说「这是 Cloudflare 边缘部署」。`BASE_URL` 只能说明
 * 静态资源挂在哪个前缀，说明不了谁在跑这个进程。判据只认服务端自己报的 `version`，
 * 拿不到时才退回编译期推断，且默认按 server 处理（宁可显示登录框，也别误报形态）。
 *
 * 用途只有一个：**不要让界面说出当前部署里根本不存在的东西**
 * （比如在自建服务端上提示「请从飞牛桌面打开」）。
 */
import { fetchHealth } from '@/api/modules/system'

/** 与 `edge/src/index.ts` 的 `health()` 里 `version: "edge"` 同源。 */
const EDGE_VERSION = 'edge'

export type Backend = 'edge' | 'server'
export type ServerIdentity = 'gateway' | 'password'

/** 编译期兜底：飞牛构建固定 base `/app/litv/`，根路径最可能是 edge 或自建容器。 */
export const EDGE_DEPLOY = (import.meta.env.BASE_URL || '/') === '/'

let backend: Backend | null = null
let identity: ServerIdentity | null = null

/** 服务端说了算；`null` 表示「还不知道」，回退到编译期推断。 */
export function setDeployState(b: Backend | null, id: ServerIdentity | null = null): void {
  backend = b
  identity = b === 'server' ? id : null
}

export function currentBackend(): Backend {
  return backend ?? (EDGE_DEPLOY ? 'edge' : 'server')
}

export const isEdge = (): boolean => currentBackend() === 'edge'
export const isServer = (): boolean => currentBackend() === 'server'

/**
 * 身份来源。
 *
 * 未知时按 `password`：自建容器是绝大多数情况，而且它的界面是「显示登录框」，
 * 比误报 `gateway` 后提示「请从飞牛桌面打开」安全得多。
 */
export function serverIdentity(): ServerIdentity {
  return identity ?? 'password'
}

export const hasGateway = (): boolean => isServer() && serverIdentity() === 'gateway'
export const usesPassword = (): boolean => isEdge() || (isServer() && serverIdentity() === 'password')

/** 探活必须有上限：后端半死不活时不能让管理台卡在「挂载之前」。 */
function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('health probe timeout')), ms)
    p.then(
      (v) => {
        clearTimeout(timer)
        resolve(v)
      },
      (e: unknown) => {
        clearTimeout(timer)
        reject(e instanceof Error ? e : new Error(String(e)))
      },
    )
  })
}

/**
 * 问一次服务端自己是谁，**在挂载 Vue 之前**跑（见 main.ts）。
 *
 * 探活是公开接口，失败不阻塞启动：请求自带超时（见 `api/modules/system.ts`）。
 * 不能靠「探测某个 edge 专有路径是不是 404」——SPA 兜底会把未知路径回 200。
 */
export async function loadDeployMode(): Promise<void> {
  try {
    const probe = await withTimeout(fetchHealth(), 3000)
    if (probe?.version === EDGE_VERSION) {
      setDeployState('edge', null)
      return
    }
    setDeployState('server', probe?.identity === 'gateway' ? 'gateway' : 'password')
  } catch {
    setDeployState(null, null)
  }
}
