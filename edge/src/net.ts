// 出站抓取的**目标校验**，Worker 与 CI 共用。
//
// 为什么需要它：管理台的「测试源连通 / 测线路」会拿用户填的地址发请求，
// `build.mjs` 更是直接抓订阅地址、还要顺着 m3u 头里的 `x-tvg-url` 去抓节目单。
// 后者跑在 GitHub runner 上——那里能碰到 169.254.169.254 之类的元数据端点，
// 一个被投毒的 m3u 就能把 CI 变成内网探针。真服务端有 safedial.IsPrivateAddr
// （server/internal/fetch/fetch.go），边缘这边要对齐。
//
// 能力边界（must be honest）：
//   - Workers 里拿不到 DNS 解析结果，**域名解析到内网**这条拦不住。
//     真正的兜底是 Cloudflare 边缘到私网本来就不通（README 也写明内网源在
//     边缘模式下必然拉不到）。所以这里只能挡住字面量地址与明显的内网域后缀。
//   - 跳转必须逐跳校验：`redirect: "follow"` 会由运行时自己跳，
//     等于把刚校验过的地址又绕过去了。
const BLOCKED = "目标地址是本机或内网地址，边缘模式不允许访问";

/** 只做字面量判断，不做 DNS 解析 */
function isPrivateHost(host: string): boolean {
  const h = host.toLowerCase().replace(/^\[/, "").replace(/\]$/, "");

  if (h === "" || h === "localhost" || h.endsWith(".localhost")) return true;
  // .local 是 mDNS，.internal 常见于云厂商内网域名
  if (h.endsWith(".local") || h.endsWith(".internal")) return true;
  if (h === "metadata.google.internal" || h === "instance-data") return true;

  // IPv4 字面量
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(h);
  if (m) {
    const [a, b] = [Number(m[1]), Number(m[2])];
    if (a === 0) return true; // 0.0.0.0/8
    if (a === 10) return true; // 10/8
    if (a === 127) return true; // 环回
    if (a === 169 && b === 254) return true; // 链路本地（云元数据在这）
    if (a === 172 && b >= 16 && b <= 31) return true; // 172.16/12
    if (a === 192 && b === 168) return true; // 192.168/16
    if (a === 100 && b >= 64 && b <= 127) return true; // 运营商级 NAT
    if (a >= 224) return true; // 组播 / 保留
    return false;
  }

  // IPv6 字面量：环回、链路本地 fe80::/10、唯一本地 fc00::/7
  if (h === "::1" || h === "::") return true;
  if (/^fe[89ab][0-9a-f]:/.test(h)) return true;
  if (/^f[cd][0-9a-f]{2}:/.test(h)) return true;

  return false;
}

/** 只有 CI 自测需要放行：它用 127.0.0.1 起 fixture 服务器喂 build.mjs。
 *  Worker 运行时没有 process，所以线上这条路根本走不通；
 *  CI 的部署 job 也不会设这个变量（只有 `npm test` 会设）。
 *  这里用 globalThis 取，而不是直接写 `process`：Worker 的编译环境没有
 *  @types/node，直接引用会过不了 tsc。 */
function privateTargetsAllowed(): boolean {
  const proc = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process;
  return proc?.env?.EDGE_ALLOW_PRIVATE_FETCH === "1";
}

/**
 * 校验目标地址；可以抓返回 null，否则返回给用户看的中文原因。
 * 只允许 http/https——rtp:// udp:// 这些在边缘模式里本来就没法中转。
 */
export function checkFetchTarget(raw: string): string | null {
  let u: URL;
  try {
    u = new URL(String(raw));
  } catch {
    return "地址不是合法的 URL";
  }
  if (u.protocol !== "http:" && u.protocol !== "https:") {
    return "只支持 http:// 和 https:// 地址";
  }
  if (!privateTargetsAllowed() && isPrivateHost(u.hostname)) return BLOCKED;
  return null;
}

/**
 * 抓取一个**不可信来源**给的地址：每跳都校验，跳转自己跟进（最多 maxHops 跳）。
 *
 * 与 `fetch(url, { redirect: "follow" })` 的差别：后者会在运行时内部完成跳转，
 * 校验只覆盖第一跳，`Location` 指哪儿就去哪儿。
 *
 * 被拦时抛 Error，message 以 "blocked:" 开头，调用方据此区分「被安全策略拦下」
 * 和「网络错误」，别把前者报成后者——用户会去查网络。
 */
export async function fetchGuarded(
  raw: string,
  init: RequestInit = {},
  maxHops = 4,
): Promise<Response> {
  let target = raw;
  for (let hop = 0; hop <= maxHops; hop++) {
    const bad = checkFetchTarget(target);
    if (bad) throw new Error(`blocked: ${bad}`);

    const res = await fetch(target, { ...init, redirect: "manual" });
    if (res.status >= 300 && res.status < 400) {
      const loc = res.headers.get("location");
      res.body?.cancel().catch(() => {});
      if (!loc) throw new Error("blocked: 跳转没有 Location");
      target = new URL(loc, target).toString();
      continue;
    }
    return res;
  }
  throw new Error("blocked: 跳转次数过多");
}
