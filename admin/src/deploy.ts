/**
 * 部署形态。
 *
 * 同一个管理台被两种宿主使用：
 *
 *   - **NAS**：飞牛网关托管，构建 base 是 `/app/litv/`，身份由网关的
 *     `X-Trim-*` 头注入，正常不需要登录。
 *   - **edge**：Cloudflare Worker，CI 用 `--base=/` 构建（见 edge-deploy.yml），
 *     没有网关，口令登录是唯一入口。
 *
 * `BASE_URL` 是编译期常量，比运行时探测 `/api/v1/health` 更可靠：
 * 不用多发一次请求，也不会因为服务端暂时不可用而误判形态。
 *
 * 用途只有一个：**不要让界面说出当前部署里根本不存在的东西**
 * （比如在公网域名上提示「请从飞牛桌面打开」）。
 */
export const EDGE_DEPLOY = (import.meta.env.BASE_URL || '/') === '/'
