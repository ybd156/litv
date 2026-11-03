// 边缘版「最小 litvd」+ 管理台后端。
//
// 目标：让现有的 Android TV / 手机 / iOS 客户端在**零改动**的前提下，
// 连上 Cloudflare 也能看电视。靠的是两个客户端本来就有的能力：
//
//   1. /api/v1/tv/play/{id} 返回 mode="direct" + 上游绝对地址 + headers 时，
//      客户端自己直连源站（见 android Api.absolute / PlayInfo.playHeaders）
//   2. 绑定页本来就是「服务器地址 + 6 位数字绑定码」两个输入框
//
// 所以这里只实现协议，不碰任何媒体字节。没有中转、没有 ffmpeg、没有磁盘。
//
// 免费版约束（决定了代码长什么样）：
//   - 每次请求 10ms CPU → 只做点查询和拼 JSON，绝不解析大文件
//   - 100k 请求/天 → 静态资源不计数，接口够用
//   - 50 subrequest/请求 → 每个接口最多 3 次 D1 查询

import { BOOTSTRAP_SQL } from "./bootstrap.sql.js";

interface Env {
  ASSETS: Fetcher;
  DB: D1Database;
  BIND_CODE: string;
  SERVER_NAME?: string;
}

// 设备默认配置（decode / buffer_ms / aspect / autoplay / engine）现在写在
// src/bootstrap.sql.js 里，跟响应体一起由 SQLite 生成。改的时候只改那一处。

const ADMIN_SESSION_SEC = 7 * 24 * 3600;
const RATE_WINDOW_SEC = 600;
const RATE_MAX_FAILS = 10;

// ---------------------------------------------------------------- 基础设施

function json(body: unknown, status = 200): Response {
  // 注意：Android 的健康检查是靠字符串搜 `"code":0`，所以不能有空格。
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function ok(data: unknown): Response {
  return json({ code: 0, msg: "ok", data });
}

// 2001 参数错 / 2002 设备冲突 / 2003 未认证 / 2004 不存在 / 2005 限速
function fail(code: number, msg: string): Response {
  return json({ code, msg, data: null });
}

async function sha256hex(s: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// 定长比较，避免按字节提前返回泄露口令前缀
function safeEqual(a: string, b: string): boolean {
  const ab = new TextEncoder().encode(a);
  const bb = new TextEncoder().encode(b);
  if (ab.length !== bb.length) return false;
  let diff = 0;
  for (let i = 0; i < ab.length; i++) diff |= ab[i] ^ bb[i];
  return diff === 0;
}

function randomToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function nowSec(): number {
  return Math.floor(Date.now() / 1000);
}

function bearer(req: Request): string {
  const m = /^Bearer\s+(.+)$/i.exec(req.headers.get("authorization") || "");
  return m ? m[1].trim() : "";
}

/** 失败计数与限速。返回 true 表示"已被锁"。 */
async function rateLimited(env: Env, ip: string): Promise<boolean> {
  const row = await env.DB.prepare(
    "SELECT fails, window_start FROM bind_attempts WHERE ip = ?",
  )
    .bind(ip)
    .first<{ fails: number; window_start: number }>();
  if (!row) return false;
  return nowSec() - row.window_start < RATE_WINDOW_SEC && row.fails >= RATE_MAX_FAILS;
}

async function noteFailure(env: Env, ip: string): Promise<void> {
  const now = nowSec();
  const row = await env.DB.prepare(
    "SELECT window_start FROM bind_attempts WHERE ip = ?",
  )
    .bind(ip)
    .first<{ window_start: number }>();

  if (row && now - row.window_start < RATE_WINDOW_SEC) {
    await env.DB.prepare("UPDATE bind_attempts SET fails = fails + 1 WHERE ip = ?")
      .bind(ip)
      .run();
  } else {
    await env.DB.prepare(
      "INSERT INTO bind_attempts (ip, fails, window_start) VALUES (?, 1, ?) " +
        "ON CONFLICT(ip) DO UPDATE SET fails = 1, window_start = excluded.window_start",
    )
      .bind(ip, now)
      .run();
  }
}

async function clearFailures(env: Env, ip: string): Promise<void> {
  await env.DB.prepare("DELETE FROM bind_attempts WHERE ip = ?").bind(ip).run();
}

function clientIp(req: Request): string {
  return req.headers.get("cf-connecting-ip") || "unknown";
}

// ---------------------------------------------------------------- 认证

interface DeviceRow {
  id: number;
  name: string;
  platform: string;
}

async function authDevice(req: Request, env: Env): Promise<DeviceRow | null> {
  const token = bearer(req);
  if (!token) return null;
  const hash = await sha256hex(token);
  const row = await env.DB.prepare(
    "SELECT id, name, platform FROM devices WHERE token_hash = ?",
  )
    .bind(hash)
    .first<DeviceRow>();
  return row ?? null;
}

async function authAdmin(req: Request, env: Env): Promise<boolean> {
  const token = bearer(req);
  if (!token) return false;
  const hash = await sha256hex(token);
  const row = await env.DB.prepare(
    "SELECT expires_at FROM admin_sessions WHERE token_hash = ?",
  )
    .bind(hash)
    .first<{ expires_at: number }>();
  return row !== null && row.expires_at > nowSec();
}

// ---------------------------------------------------------------- 公共接口

async function health(env: Env): Promise<Response> {
  return ok({
    name: env.SERVER_NAME || "LITV Edge",
    version: "edge",
    mode: "direct",
    time: nowSec(),
  });
}

async function deviceRegister(req: Request, env: Env): Promise<Response> {
  if (!env.BIND_CODE) return fail(2001, "服务端未配置绑定码");

  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(2001, "请求体不是合法 JSON");
  }

  // 绑定码只有 6 位数字（客户端输入框限死了），空间只有 100 万，
  // 不挡一下等于把设备注册开放给暴力枚举。
  const ip = clientIp(req);
  if (await rateLimited(env, ip)) return fail(2005, "尝试次数过多，请 10 分钟后再试");

  const code = String(body.code ?? "");
  if (!safeEqual(code, env.BIND_CODE)) {
    await noteFailure(env, ip);
    return fail(2001, "绑定码不正确");
  }
  await clearFailures(env, ip);

  const deviceKey = String(body.device_key ?? "").slice(0, 128);
  if (!deviceKey) return fail(2001, "缺少 device_key");

  const platform = String(body.platform ?? "").slice(0, 32);
  const name = String(body.name ?? "").slice(0, 64);
  const ts = nowSec();

  // 对齐服务端语义：同一个 device_key 换平台必须拒绝，
  // 否则知道 key 的人就能顶掉这台设备。
  const existing = await env.DB.prepare(
    "SELECT id, platform FROM devices WHERE device_key = ?",
  )
    .bind(deviceKey)
    .first<{ id: number; platform: string }>();

  if (existing && existing.platform && existing.platform !== platform) {
    return fail(2002, "该设备已用其他平台注册");
  }

  const token = randomToken();
  const hash = await sha256hex(token);

  let deviceId: number;
  if (existing) {
    await env.DB.prepare(
      "UPDATE devices SET token_hash = ?, name = ?, platform = ?, last_seen = ? WHERE id = ?",
    )
      .bind(hash, name, platform, ts, existing.id)
      .run();
    deviceId = existing.id;
  } else {
    const res = await env.DB.prepare(
      "INSERT INTO devices (device_key, token_hash, platform, name, created_at, last_seen) VALUES (?,?,?,?,?,?)",
    )
      .bind(deviceKey, hash, platform, name, ts, ts)
      .run();
    deviceId = Number(res.meta.last_row_id);
  }

  return ok({ device_id: deviceId, token, status: "bound", hint: "" });
}

async function tvBootstrap(env: Env): Promise<Response> {
  // 整个响应体在 SQLite 里拼成 JSON 字符串，Worker 只负责吐字节。
  // 不在 JS 里 map + JSON.stringify，所以 CPU 开销与频道数无关——
  // 这是这个接口能撑住几千个频道的原因。D1 的查询耗时算 wall time，
  // 不占 Worker 那 10ms 的 CPU 预算。
  const row = await env.DB.prepare(BOOTSTRAP_SQL)
    .bind(env.SERVER_NAME || "LITV")
    .first<{ body: string }>();

  if (!row || !row.body) return fail(2004, "频道表为空，先跑一次部署");

  return new Response(row.body, {
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

interface StreamRow {
  id: number;
  channel_id: number;
  label: string;
  quality: string;
  priority: number;
  health: number;
  latency_ms: number;
  url: string;
  ua: string;
  headers: string;
  origin: string;
  name?: string;
  logo?: string;
}

function parseHeaders(raw: string): Record<string, string> {
  try {
    const obj = JSON.parse(raw || "{}");
    if (obj && typeof obj === "object" && !Array.isArray(obj)) {
      const out: Record<string, string> = {};
      for (const [k, v] of Object.entries(obj)) {
        if (typeof v === "string" && v) out[k] = v;
      }
      return out;
    }
  } catch {
    // 存坏了就当没有，别让一条脏数据把播放彻底搞挂
  }
  return {};
}

async function tvPlay(env: Env, channelId: number, url: URL): Promise<Response> {
  const wantStream = Number(url.searchParams.get("stream_id") || "0");

  const sql = `
    SELECT s.id, s.channel_id, s.label, s.quality, s.priority, s.health,
           s.latency_ms, s.url, s.ua, s.headers, s.origin,
           COALESCE(o.name, c.name) AS name,
           COALESCE(o.logo, c.logo) AS logo
      FROM streams s
      JOIN channels c ON c.id = s.channel_id
      LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
     WHERE s.channel_id = ?${wantStream > 0 ? " AND s.id = ?" : ""}
     ORDER BY (s.health = 1) DESC, s.priority DESC, s.id ASC
     LIMIT 1`;

  const stmt = env.DB.prepare(sql);
  const row = wantStream > 0
    ? await stmt.bind(channelId, wantStream).first<StreamRow>()
    : await stmt.bind(channelId).first<StreamRow>();

  if (!row) return fail(2004, "这个频道没有可用线路");

  const headers = parseHeaders(row.headers);
  if (row.ua && !Object.keys(headers).some((k) => k.toLowerCase() === "user-agent")) {
    headers["User-Agent"] = row.ua;
  }

  // 客户端拿到 http(s) 绝对地址会原样使用（Api.absolute）。
  // mode=direct 表示"你自己去连源站"，服务端不转发任何字节。
  return ok({
    channel: { id: row.channel_id, name: row.name ?? "", logo: row.logo ?? "" },
    kind: /\.m3u8(\?|$)/i.test(row.url) ? "hls" : "ts",
    url: row.url,
    expires: nowSec() + 3600,
    stream_id: row.id,
    ua: row.ua,
    mode: "direct",
    headers,
  });
}

async function tvStreams(env: Env, channelId: number): Promise<Response> {
  const { results } = await env.DB.prepare(
    `SELECT id, label, quality, priority, health, latency_ms
       FROM streams WHERE channel_id = ?
      ORDER BY (health = 1) DESC, priority DESC, id ASC`,
  )
    .bind(channelId)
    .all();

  // 注意：不返回 url。终端只需要标签和健康度。
  return ok(results ?? []);
}

async function tvEpg(env: Env, channelId: number): Promise<Response> {
  const ch = await env.DB.prepare(
    `SELECT c.id, COALESCE(o.name, c.name) AS name, COALESCE(o.logo, c.logo) AS logo
       FROM channels c
       LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
      WHERE c.id = ?`,
  )
    .bind(channelId)
    .first<{ id: number; name: string; logo: string }>();

  if (!ch) return fail(2004, "频道不存在");

  return ok({
    channel: { id: ch.id, name: ch.name, logo: ch.logo },
    programs: [],
    note: "边缘模式没有节目单",
  });
}

// ---------------------------------------------------------------- 管理台

async function adminLogin(req: Request, env: Env): Promise<Response> {
  if (!env.BIND_CODE) return fail(2001, "服务端未配置绑定码");

  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(2001, "请求体不是合法 JSON");
  }

  const ip = clientIp(req);
  if (await rateLimited(env, ip)) return fail(2005, "尝试次数过多，请 10 分钟后再试");

  if (!safeEqual(String(body.code ?? ""), env.BIND_CODE)) {
    await noteFailure(env, ip);
    return fail(2001, "口令不正确");
  }
  await clearFailures(env, ip);

  const now = nowSec();
  // 顺手清掉过期会话，不用单独排一个定时任务
  await env.DB.prepare("DELETE FROM admin_sessions WHERE expires_at < ?").bind(now).run();

  const token = randomToken();
  const expires = now + ADMIN_SESSION_SEC;
  await env.DB.prepare(
    "INSERT INTO admin_sessions (token_hash, created_at, expires_at) VALUES (?,?,?)",
  )
    .bind(await sha256hex(token), now, expires)
    .run();

  return ok({ token, expires_at: expires });
}

async function adminStats(env: Env): Promise<Response> {
  const row = await env.DB.prepare(
    `SELECT
       (SELECT COUNT(*) FROM channels) AS channels,
       (SELECT COUNT(*) FROM channels WHERE enabled = 1) AS enabled,
       (SELECT COUNT(*) FROM streams) AS streams,
       (SELECT COUNT(DISTINCT "group") FROM channels) AS groups,
       (SELECT COUNT(*) FROM devices) AS devices,
       (SELECT COUNT(*) FROM channel_overrides) AS overrides`,
  ).first<Record<string, number>>();

  return ok(row ?? {});
}

async function adminChannels(env: Env, url: URL): Promise<Response> {
  const q = (url.searchParams.get("q") || "").trim();
  const group = (url.searchParams.get("group") || "").trim();
  const limit = Math.min(Math.max(Number(url.searchParams.get("limit") || "200"), 1), 500);

  const where: string[] = [];
  const binds: unknown[] = [];
  if (q) {
    where.push("(c.name LIKE ? OR c.tvg_id LIKE ?)");
    binds.push(`%${q}%`, `%${q}%`);
  }
  if (group) {
    where.push('COALESCE(o.group_name, c."group") = ?');
    binds.push(group);
  }

  const sql =
    `SELECT c.id, c.stable_key, c.number, c.origin,
            COALESCE(o.name, c.name) AS name,
            COALESCE(o.group_name, c."group") AS grp,
            COALESCE(o.logo, c.logo) AS logo,
            c.tvg_id,
            COALESCE(o.enabled, c.enabled) AS enabled,
            (SELECT COUNT(*) FROM streams s WHERE s.channel_id = c.id) AS streams
       FROM channels c
       LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key` +
    (where.length ? " WHERE " + where.join(" AND ") : "") +
    " ORDER BY c.sort, c.number, c.id LIMIT ?";

  const { results } = await env.DB.prepare(sql)
    .bind(...binds, limit)
    .all();

  const { results: groupRows } = await env.DB.prepare(
    `SELECT COALESCE(o.group_name, c."group") AS name, COUNT(*) AS count
       FROM channels c
       LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
      GROUP BY name ORDER BY count DESC`,
  ).all();

  return ok({ items: results ?? [], groups: groupRows ?? [] });
}

async function adminUpdateChannel(
  req: Request,
  env: Env,
  channelId: number,
): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(2001, "请求体不是合法 JSON");
  }

  const ch = await env.DB.prepare(
    "SELECT id, stable_key, origin FROM channels WHERE id = ?",
  )
    .bind(channelId)
    .first<{ id: number; stable_key: string; origin: string }>();
  if (!ch) return fail(2004, "频道不存在");

  const name = typeof body.name === "string" ? body.name.slice(0, 128) : null;
  const group = typeof body.group === "string" ? body.group.slice(0, 64) : null;
  const logo = typeof body.logo === "string" ? body.logo.slice(0, 512) : null;
  const enabled = typeof body.enabled === "boolean" ? (body.enabled ? 1 : 0) : null;

  if (ch.origin === "manual") {
    // 手工频道直接改本行，没有"重建冲掉"的问题
    await env.DB.prepare(
      `UPDATE channels SET
         name = COALESCE(?, name),
         "group" = COALESCE(?, "group"),
         logo = COALESCE(?, logo),
         enabled = COALESCE(?, enabled)
       WHERE id = ?`,
    )
      .bind(name, group, logo, enabled, channelId)
      .run();
  } else {
    // 订阅频道改到 overrides 表，下次重建仍然生效
    await env.DB.prepare(
      `INSERT INTO channel_overrides (stable_key, name, group_name, logo, enabled, updated_at)
       VALUES (?,?,?,?,?,?)
       ON CONFLICT(stable_key) DO UPDATE SET
         name       = COALESCE(excluded.name, channel_overrides.name),
         group_name = COALESCE(excluded.group_name, channel_overrides.group_name),
         logo       = COALESCE(excluded.logo, channel_overrides.logo),
         enabled    = COALESCE(excluded.enabled, channel_overrides.enabled),
         updated_at = excluded.updated_at`,
    )
      .bind(ch.stable_key, name, group, logo, enabled, nowSec())
      .run();
  }

  return ok(null);
}

async function adminDeleteChannel(env: Env, channelId: number): Promise<Response> {
  const ch = await env.DB.prepare(
    "SELECT id, stable_key, origin FROM channels WHERE id = ?",
  )
    .bind(channelId)
    .first<{ id: number; stable_key: string; origin: string }>();
  if (!ch) return fail(2004, "频道不存在");

  if (ch.origin === "manual") {
    await env.DB.batch([
      env.DB.prepare("DELETE FROM streams WHERE channel_id = ?").bind(channelId),
      env.DB.prepare("DELETE FROM channels WHERE id = ?").bind(channelId),
    ]);
  } else {
    // 订阅频道删不掉（下次重建还会回来），只能隐藏
    await env.DB.prepare(
      `INSERT INTO channel_overrides (stable_key, enabled, updated_at) VALUES (?, 0, ?)
       ON CONFLICT(stable_key) DO UPDATE SET enabled = 0, updated_at = excluded.updated_at`,
    )
      .bind(ch.stable_key, nowSec())
      .run();
  }

  return ok(null);
}

async function adminStreams(env: Env, url: URL): Promise<Response> {
  const channelId = Number(url.searchParams.get("channel_id") || "0");
  if (!channelId) return fail(2001, "缺少 channel_id");

  const { results } = await env.DB.prepare(
    `SELECT id, channel_id, label, quality, priority, health, latency_ms,
            url, ua, headers, origin
       FROM streams WHERE channel_id = ?
      ORDER BY priority DESC, id ASC`,
  )
    .bind(channelId)
    .all<StreamRow>();

  return ok((results ?? []).map((r) => ({ ...r, headers: parseHeaders(r.headers) })));
}

async function adminAddStream(req: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(2001, "请求体不是合法 JSON");
  }

  const channelId = Number(body.channel_id || 0);
  const rawUrl = String(body.url ?? "").trim();

  if (!channelId) return fail(2001, "缺少 channel_id");
  if (!/^https?:\/\//i.test(rawUrl)) return fail(2001, "地址必须以 http:// 或 https:// 开头");
  if (rawUrl.length > 2048) return fail(2001, "地址太长");

  const ch = await env.DB.prepare("SELECT stable_key FROM channels WHERE id = ?")
    .bind(channelId)
    .first<{ stable_key: string }>();
  if (!ch) return fail(2004, "频道不存在");

  const headers: Record<string, string> = {};
  const referer = String(body.referer ?? "").trim();
  if (referer) headers["Referer"] = referer;

  const res = await env.DB.prepare(
    `INSERT INTO streams (channel_id, channel_key, label, quality, priority, health, latency_ms, url, ua, headers, origin)
     VALUES (?,?,?,?,?,-1,0,?,?,?,'manual')`,
  )
    .bind(
      channelId,
      ch.stable_key,
      String(body.label ?? "手工线路").slice(0, 64),
      "",
      Number(body.priority ?? 0) || 0,
      rawUrl,
      String(body.ua ?? "").slice(0, 512),
      JSON.stringify(headers),
    )
    .run();

  return ok({ id: Number(res.meta.last_row_id) });
}

async function adminDeleteStream(env: Env, streamId: number): Promise<Response> {
  await env.DB.prepare("DELETE FROM streams WHERE id = ?").bind(streamId).run();
  return ok(null);
}

/**
 * 探活：从 Cloudflare 边缘发一个 Range 请求。
 *
 * 注意这个结果**不代表用户家里能播**——边缘的线路、DNS、组播可达性
 * 和家庭网络完全不同。只能当"这条源大概还活着"的参考。
 */
async function adminProbeStream(env: Env, streamId: number): Promise<Response> {
  const row = await env.DB.prepare(
    "SELECT id, url, ua, headers FROM streams WHERE id = ?",
  )
    .bind(streamId)
    .first<{ id: number; url: string; ua: string; headers: string }>();
  if (!row) return fail(2004, "线路不存在");

  const headers = parseHeaders(row.headers);
  if (row.ua && !Object.keys(headers).some((k) => k.toLowerCase() === "user-agent")) {
    headers["User-Agent"] = row.ua;
  }
  headers["Range"] = "bytes=0-1023";

  const t0 = Date.now();
  let healthy = 0;
  let status = 0;
  try {
    const res = await fetch(row.url, {
      method: "GET",
      headers,
      redirect: "follow",
      signal: AbortSignal.timeout(8000),
    });
    status = res.status;
    // 只要了前 1KB，别把整个流读进来
    res.body?.cancel().catch(() => {});
    healthy = res.ok || res.status === 206 ? 1 : 0;
  } catch {
    healthy = 0;
  }

  const latency = Date.now() - t0;
  await env.DB.prepare("UPDATE streams SET health = ?, latency_ms = ? WHERE id = ?")
    .bind(healthy, latency, streamId)
    .run();

  return ok({ health: healthy, latency_ms: latency, status });
}

async function adminDevices(env: Env): Promise<Response> {
  const { results } = await env.DB.prepare(
    "SELECT id, name, platform, device_key, created_at, last_seen FROM devices ORDER BY last_seen DESC",
  ).all();

  // device_key 是设备侧标识，管理台不需要看全，遮一半
  const items = (results ?? []).map((d) => ({
    ...d,
    device_key: String(d.device_key).slice(0, 8) + "…",
  }));

  return ok(items);
}

async function adminDeleteDevice(env: Env, deviceId: number): Promise<Response> {
  await env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(deviceId).run();
  return ok(null);
}

// ---------------------------------------------------------------- 路由

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname;
    const method = req.method.toUpperCase();

    // ---- 无需认证 ----
    if (path === "/api/v1/health") return health(env);
    if (path === "/api/v1/device/register" && method === "POST") {
      return deviceRegister(req, env);
    }
    if (path === "/api/v1/admin/login" && method === "POST") {
      return adminLogin(req, env);
    }

    // ---- 播放列表：里面是上游地址，必须和接口同等级保护 ----
    if (path === "/playlist.m3u") {
      const expected = env.BIND_CODE || "";
      const provided = url.searchParams.get("t") ?? "";
      if (!expected || !safeEqual(provided, expected)) {
        // 用 404 而不是 401：不告诉对方这里确实有东西
        return new Response("Not Found", { status: 404 });
      }
      const clean = new URL(url);
      clean.search = "";
      const res = await env.ASSETS.fetch(new Request(clean.toString(), req));
      const headers = new Headers(res.headers);
      headers.set("cache-control", "no-store");
      return new Response(res.body, { status: res.status, headers });
    }

    // ---- 其余静态文件交给 assets ----
    if (!path.startsWith("/api/")) {
      return env.ASSETS.fetch(req);
    }

    // ---- 管理台接口 ----
    if (path.startsWith("/api/v1/admin/")) {
      if (!(await authAdmin(req, env))) return fail(2003, "管理台未登录或会话已过期");

      if (path === "/api/v1/admin/stats") return adminStats(env);
      if (path === "/api/v1/admin/channels") return adminChannels(env, url);
      if (path === "/api/v1/admin/streams") return adminStreams(env, url);
      if (path === "/api/v1/admin/devices") return adminDevices(env);

      if (path === "/api/v1/admin/stream" && method === "POST") {
        return adminAddStream(req, env);
      }

      const chMatch = /^\/api\/v1\/admin\/channel\/(\d+)$/.exec(path);
      if (chMatch) {
        const id = Number(chMatch[1]);
        if (method === "PATCH" || method === "PUT") return adminUpdateChannel(req, env, id);
        if (method === "DELETE") return adminDeleteChannel(env, id);
      }

      const stMatch = /^\/api\/v1\/admin\/stream\/(\d+)$/.exec(path);
      if (stMatch) {
        const id = Number(stMatch[1]);
        if (method === "DELETE") return adminDeleteStream(env, id);
      }

      const probeMatch = /^\/api\/v1\/admin\/stream\/(\d+)\/probe$/.exec(path);
      if (probeMatch && method === "POST") {
        return adminProbeStream(env, Number(probeMatch[1]));
      }

      const devMatch = /^\/api\/v1\/admin\/device\/(\d+)$/.exec(path);
      if (devMatch && method === "DELETE") {
        return adminDeleteDevice(env, Number(devMatch[1]));
      }

      return fail(2004, "没有这个管理接口");
    }

    // ---- 以下都要设备 token ----
    if (!(await authDevice(req, env))) return fail(2003, "设备未认证");

    if (path === "/api/v1/tv/bootstrap") return tvBootstrap(env);

    const playMatch = /^\/api\/v1\/tv\/play\/(\d+)$/.exec(path);
    if (playMatch) return tvPlay(env, Number(playMatch[1]), url);

    const streamsMatch = /^\/api\/v1\/tv\/streams\/(\d+)$/.exec(path);
    if (streamsMatch) return tvStreams(env, Number(streamsMatch[1]));

    const epgMatch = /^\/api\/v1\/tv\/epg\/(\d+)$/.exec(path);
    if (epgMatch) return tvEpg(env, Number(epgMatch[1]));

    // ---- 边缘模式明确不支持的：返回空，让客户端自然降级 ----
    if (path.startsWith("/api/v1/tv/timeshift/")) {
      return ok({ enabled: false, available_seconds: 0, url: "" });
    }
    if (path === "/api/v1/tv/dlna/renderers") return ok({ total: 0, items: [] });
    if (path === "/api/v1/tv/remote/targets") return ok([]);
    if (path === "/api/v1/tv/resume") return ok({ available: false });

    if (path === "/api/v1/tv/heartbeat") {
      return ok({ config_version: 1, server_time: nowSec() });
    }

    // 收藏 / 播放上报 / 续播上报 / 提醒 / 预约录制：收下就好，没有地方存。
    // 客户端对这些失败是静默的，但返回成功能让界面状态更干净。
    if (
      path === "/api/v1/tv/favorite" ||
      path === "/api/v1/tv/stats" ||
      path === "/api/v1/tv/progress" ||
      path === "/api/v1/tv/reminder" ||
      path === "/api/v1/tv/record"
    ) {
      return ok(null);
    }

    return fail(2004, "边缘模式不支持这个接口");
  },
} satisfies ExportedHandler<Env>;
