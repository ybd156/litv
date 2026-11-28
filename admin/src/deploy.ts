/**
 * 部署形态。
 *
 * 同一个管理台被三种宿主使用：
 *
 *   - **gateway**：飞牛网关托管，构建 base 是 `/app/litv/`，身份由网关的
 *     `X-Trim-*` 头注入，正常不需要登录。
 *   - **direct**：自建直连（Docker / 1Panel / 裸机反代），跑的是同一个 Go 服务端，
 *     但没有网关——身份来自 `auth.admin_password`。
 *   - **edge**：Cloudflare Worker，CI 用 `--base=/` 构建（见 edge-deploy.yml），
 *     没有网关，口令登录是唯一入口。
 *
 * ★ 判据顺序（2026-11-28 修正）：**先问服务端**（`GET /api/v1/health` 的 `endpoint`），
 * 拿不到才退回编译期常量。以前只用 `BASE_URL === '/'` 判 edge，结果自建 Docker 挂在
 * 根路径时被当成边缘部署，登录失效页硬说「这是 Cloudflare 边缘部署」。
 * 编译期常量只能说明「静态资源挂在哪个前缀」，说明不了「谁在跑这个进程」。
 *
 * 用途只有一个：**不要让界面说出当前部署里根本不存在的东西**
 * （比如在公网域名上提示「请从飞牛桌面打开」）。
 */
export type AdminMode = 'gateway' | 'direct' | 'edge'

/** 编译期兜底：base 是根路径时最可能是 edge（飞牛构建固定 `/app/litv/`）。 */
export const EDGE_DEPLOY = (import.meta.env.BASE_URL || '/') === '/'

let mode: AdminMode | null = null

/** 服务端说了算；传 null 表示「还不知道」，回退到编译期推断。 */
export function setAdminMode(m: AdminMode | null): void {
  mode = m
}

export function adminMode(): AdminMode {
  return mode ?? (EDGE_DEPLOY ? 'edge' : 'gateway')
}

export const isEdge = (): boolean => adminMode() === 'edge'
export const isDirect = (): boolean => adminMode() === 'direct'
export const hasGateway = (): boolean => adminMode() === 'gateway'

/**
 * 问一次服务端自己是什么形态，**在挂载 Vue 之前**跑（见 main.ts）。
 *
 * 探活是公开接口，失败不阻塞启动：超时 3 秒，失败就按编译期推断。
 * 不能靠「探测某个 edge 专有路径是不是 404」——SPA 兜底会把未知路径回 200。
 */
export async function loadDeployMode(): Promise<void> {
  try {
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), 3000)
    const base = (import.meta.env.BASE_URL || '/').replace(/\/*$/, '/')
    const res = await fetch(`${base}api/v1/health`, {
      signal: ctl.signal,
      headers: { Accept: 'application/json' },
    })
    clearTimeout(timer)
    if (!res.ok) {
      setAdminMode(null)
      return
    }
    const body = (await res.json()) as { data?: { endpoint?: string } }
    const ep = body.data?.endpoint
    setAdminMode(ep === 'edge' || ep === 'gateway' || ep === 'direct' ? ep : null)
  } catch {
    setAdminMode(null)
  }
}
