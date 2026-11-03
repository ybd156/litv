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
  // 节目单的日边界用哪个时区算，默认东八区
  TZ_NAME?: string;
  // 可选：配了才能在管理台里一键触发重建。没配就只靠每天定时。
  GITHUB_DISPATCH_TOKEN?: string;
  GITHUB_REPO?: string;
  GITHUB_WORKFLOW?: string;
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

// 节目单的日边界必须按**当地日历日**算，不能按 UTC 零点截断——
// 东八区用户会看到节目单整体错位 8 小时。
function tzOffsetSec(tz: string, atSec: number): number {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: tz,
    hour12: false,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).formatToParts(new Date(atSec * 1000));
  const g = (t: string) => Number(parts.find((p) => p.type === t)?.value ?? 0);
  const asUTC = Date.UTC(
    g("year"),
    g("month") - 1,
    g("day"),
    g("hour") % 24,
    g("minute"),
    g("second"),
  );
  return Math.round(asUTC / 1000) - atSec;
}

function localDayRange(nowSec: number, tz: string, day: number): [number, number] {
  const off = tzOffsetSec(tz, nowSec);
  const localMidnight = Math.floor((nowSec + off) / 86400) * 86400 + day * 86400;
  const start = localMidnight - off;
  return [start, start + 86400];
}

interface EpgRow {
  id: number;
  channel_id: number;
  start_at: number;
  end_at: number;
  title: string;
  description: string;
  category: string;
}

async function tvEpg(env: Env, channelId: number, url: URL): Promise<Response> {
  const ch = await env.DB.prepare(
    `SELECT c.id,
            COALESCE(o.name, c.name) AS name,
            COALESCE(o.logo, c.logo) AS logo,
            COALESCE(c.epg_id, '') AS epg_id
       FROM channels c
       LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
      WHERE c.id = ?`,
  )
    .bind(channelId)
    .first<{ id: number; name: string; logo: string; epg_id: string }>();

  if (!ch) return fail(2004, "频道不存在");

  const day = Math.max(-7, Math.min(7, Number(url.searchParams.get("day") || "0") || 0));
  const now = nowSec();
  const tz = env.TZ_NAME || "Asia/Shanghai";
  const [start, end] = localDayRange(now, tz, day);

  // 日边界和时区一起下发，客户端不要自己按设备时区算
  const date = new Date((start + tzOffsetSec(tz, start)) * 1000).toISOString().slice(0, 10);

  if (!ch.epg_id) {
    return ok({ channel: ch, programs: [], note: "该频道未关联 EPG" });
  }

  const { results } = await env.DB.prepare(
    `SELECT id, channel_id, start_at, end_at, title, description, category
       FROM epg_programs
      WHERE channel_id = ? AND start_at >= ? AND start_at < ?
      ORDER BY start_at`,
  )
    .bind(channelId, start, end)
    .all<EpgRow>();

  return ok({
    channel: ch,
    programs: (results ?? []).map((p) => ({
      id: p.id,
      epg_channel_id: p.channel_id,
      start_at: p.start_at,
      end_at: p.end_at,
      title: p.title,
      description: p.description,
      category: p.category,
    })),
    day,
    date,
    timezone: tz,
    server_time: now,
    day_start: start,
    day_end: end,
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

  // 自带管理台的登录页发的是 { password }，管理台简版发的是 { code }，两个都收。
  if (!safeEqual(String(body.code ?? body.password ?? ""), env.BIND_CODE)) {
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

// ---------------------------------------------------------------- 订阅源

interface SourceRow {
  id: number;
  name: string;
  url: string;
  ua: string;
  referer: string;
  enabled: number;
  sort: number;
  created_at: number;
  last_fetch_at: number;
  last_status: string;
  last_count: number;
}

async function adminSources(env: Env): Promise<Response> {
  const { results } = await env.DB.prepare(
    `SELECT id, name, url, ua, referer, enabled, sort, created_at,
            last_fetch_at, last_status, last_count
       FROM sources ORDER BY sort, id`,
  ).all<SourceRow>();
  return ok(results ?? []);
}

function readSourceBody(body: Record<string, unknown>) {
  const url = String(body.url ?? "").trim();
  const name = String(body.name ?? "").trim().slice(0, 64);
  const ua = String(body.ua ?? "").trim().slice(0, 512);
  const referer = String(body.referer ?? "").trim().slice(0, 512);
  const enabled = typeof body.enabled === "boolean" ? (body.enabled ? 1 : 0) : null;
  const sort = Number.isFinite(Number(body.sort)) ? Math.trunc(Number(body.sort)) : null;
  return { url, name, ua, referer, enabled, sort };
}

async function adminAddSource(req: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(2001, "请求体不是合法 JSON");
  }

  const f = readSourceBody(body);
  if (!/^https?:\/\//i.test(f.url)) return fail(2001, "地址必须以 http:// 或 https:// 开头");
  if (f.url.length > 2048) return fail(2001, "地址太长");

  const res = await env.DB.prepare(
    `INSERT INTO sources (name, url, ua, referer, enabled, sort, created_at)
     VALUES (?,?,?,?,?,?,?)`,
  )
    .bind(f.name, f.url, f.ua, f.referer, f.enabled ?? 1, f.sort ?? 0, nowSec())
    .run();

  return ok({ id: Number(res.meta.last_row_id) });
}

async function adminUpdateSource(req: Request, env: Env, id: number): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(2001, "请求体不是合法 JSON");
  }

  const exists = await env.DB.prepare("SELECT id FROM sources WHERE id = ?").bind(id).first();
  if (!exists) return fail(2004, "源不存在");

  const f = readSourceBody(body);
  if (f.url && !/^https?:\/\//i.test(f.url)) {
    return fail(2001, "地址必须以 http:// 或 https:// 开头");
  }
  if (f.url.length > 2048) return fail(2001, "地址太长");

  await env.DB.prepare(
    `UPDATE sources SET
       name    = COALESCE(NULLIF(?, ''), name),
       url     = COALESCE(NULLIF(?, ''), url),
       ua      = COALESCE(?, ua),
       referer = COALESCE(?, referer),
       enabled = COALESCE(?, enabled),
       sort    = COALESCE(?, sort)
     WHERE id = ?`,
  )
    .bind(
      f.name,
      f.url,
      // ua / referer 允许清空，所以用"传了就覆盖"而不是 NULLIF
      typeof body.ua === "string" ? f.ua : null,
      typeof body.referer === "string" ? f.referer : null,
      f.enabled,
      f.sort,
      id,
    )
    .run();

  return ok(null);
}

async function adminDeleteSource(env: Env, id: number): Promise<Response> {
  await env.DB.prepare("DELETE FROM sources WHERE id = ?").bind(id).run();
  return ok(null);
}

/**
 * 测试一个源：用这个源自己的 UA / Referer 拉一下，只读前 512 字节判断格式。
 *
 * 注意这是**边缘视角**，通了不代表你家能拉。只能当参考。
 */
async function adminTestSource(env: Env, id: number): Promise<Response> {
  const src = await env.DB.prepare(
    "SELECT id, url, ua, referer FROM sources WHERE id = ?",
  )
    .bind(id)
    .first<{ id: number; url: string; ua: string; referer: string }>();
  if (!src) return fail(2004, "源不存在");

  const headers: Record<string, string> = { Accept: "*/*" };
  if (src.ua) headers["User-Agent"] = src.ua;
  if (src.referer) headers["Referer"] = src.referer;

  const t0 = Date.now();
  let status = 0;
  let format = "error";
  let sample = "";

  try {
    const res = await fetch(src.url, {
      headers,
      redirect: "follow",
      signal: AbortSignal.timeout(8000),
    });
    status = res.status;

    if (!res.ok) {
      format = `http_${res.status}`;
      res.body?.cancel().catch(() => {});
    } else {
      const reader = res.body?.getReader();
      if (reader) {
        const { value } = await reader.read();
        if (value) sample = new TextDecoder().decode(value.slice(0, 512));
        reader.cancel().catch(() => {});
      }
      const head = sample.trimStart();
      if (head.includes("#EXTM3U") || head.includes("#EXTINF")) format = "m3u";
      else if (head.includes("#genre#")) format = "txt";
      else if (head.startsWith("<")) format = "html";
      else if (head.startsWith("{") || head.startsWith("[")) format = "json";
      else if (!head) format = "empty";
      else format = "unknown";
    }
  } catch (e) {
    format = `error_${(e as Error).name}`;
  }

  return ok({ status, format, latency_ms: Date.now() - t0 });
}

// ---------------------------------------------------------------- 重建

async function adminBuildInfo(env: Env): Promise<Response> {
  const row = await env.DB.prepare("SELECT v FROM kv WHERE k = 'last_build'").first<{ v: string }>();

  let last: unknown = null;
  try {
    last = row ? JSON.parse(row.v) : null;
  } catch {
    // 存坏了就当没有，不影响别的
  }

  return ok({
    last_build: last,
    can_rebuild: Boolean(env.GITHUB_DISPATCH_TOKEN),
    schedule: "每天 05:17（北京时间）",
  });
}

/**
 * 从管理台触发一次重建。
 *
 * 为什么是"触发 CI"而不是在 Worker 里直接拉取解析：免费版每次请求只有
 * 10ms CPU，解析一个像样的播放列表就超了。抓取和解析必须留在 Actions，
 * 这里只负责按一下按钮。
 */
async function adminRebuild(env: Env): Promise<Response> {
  if (!env.GITHUB_DISPATCH_TOKEN) {
    return fail(
      2006,
      "没有配置 GITHUB_DISPATCH_TOKEN，管理台不能触发重建。" +
        "去 Actions 手动跑一次，或者等每天的定时任务。",
    );
  }

  const repo = env.GITHUB_REPO || "ybd156/litv";
  const workflow = env.GITHUB_WORKFLOW || "edge-deploy.yml";

  const res = await fetch(
    `https://api.github.com/repos/${repo}/actions/workflows/${workflow}/dispatches`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.GITHUB_DISPATCH_TOKEN}`,
        Accept: "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "litv-edge",
        "content-type": "application/json",
      },
      body: JSON.stringify({ ref: "main" }),
    },
  );
  res.body?.cancel().catch(() => {});

  if (res.status === 204) return ok({ dispatched: true });
  return fail(2006, `GitHub 拒绝了触发请求（HTTP ${res.status}）`);
}

// ---------------------------------------------------------------- 自带管理台兼容层
//
// LITV2 自带的管理台（admin/ 的 Vue）调的是 /api/v1/{sources,channels,devices,system,epg}，
// 数据形状见 admin/src/api/types.ts。这里按那套形状回话，让它能直接跑。
//
// 原则：能真做的真做，做不到的**明确报错**。返回假数据会让人以为配置生效了，
// 比报错更糟。

const EDGE_ME = { uid: "edge", username: "admin", is_admin: true };

function isoOrNull(sec: number): string | null {
  return sec > 0 ? new Date(sec * 1000).toISOString() : null;
}

function notSupported(what: string): Response {
  return fail(2006, `边缘模式不支持${what}`);
}

function toCompatSource(r: SourceRow) {
  const good = r.last_status === "ok";
  return {
    id: r.id,
    name: r.name || `源 ${r.id}`,
    type: "m3u",
    url: r.url,
    enabled: r.enabled === 1,
    status: r.last_status === "" ? "never" : good ? "ok" : "error",
    last_fetch_at: isoOrNull(r.last_fetch_at),
    error: r.last_status === "" || good ? null : r.last_status,
    channel_count: r.last_count,
    user_agent: r.ua,
  };
}

// ---- sources ----

async function compatSources(env: Env): Promise<Response> {
  const { results } = await env.DB.prepare(
    `SELECT id, name, url, ua, referer, enabled, sort, created_at,
            last_fetch_at, last_status, last_count
       FROM sources ORDER BY sort, id`,
  ).all<SourceRow>();
  return ok((results ?? []).map(toCompatSource));
}

async function compatAddSource(req: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(2001, "请求体不是合法 JSON");
  }

  const url = String(body.url ?? "").trim();
  if (!/^https?:\/\//i.test(url)) return fail(2001, "地址必须以 http:// 或 https:// 开头");
  if (url.length > 2048) return fail(2001, "地址太长");

  const res = await env.DB.prepare(
    `INSERT INTO sources (name, type, url, ua, referer, enabled, sort, created_at)
     VALUES (?,?,?,?,?,?,?,?)`,
  )
    .bind(
      String(body.name ?? "").slice(0, 64),
      "m3u",
      url,
      String(body.user_agent ?? "").slice(0, 512),
      "",
      body.enabled === false ? 0 : 1,
      Number(body.sort ?? 0) || 0,
      nowSec(),
    )
    .run();

  const row = await env.DB.prepare(
    `SELECT id, name, url, ua, referer, enabled, sort, created_at,
            last_fetch_at, last_status, last_count FROM sources WHERE id = ?`,
  )
    .bind(Number(res.meta.last_row_id))
    .first<SourceRow>();

  return ok(row ? toCompatSource(row) : null);
}

async function compatUpdateSource(req: Request, env: Env, id: number): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(2001, "请求体不是合法 JSON");
  }

  const url = String(body.url ?? "").trim();
  if (url && !/^https?:\/\//i.test(url)) return fail(2001, "地址必须以 http:// 或 https:// 开头");

  await env.DB.prepare(
    `UPDATE sources SET
       name    = COALESCE(NULLIF(?, ''), name),
       url     = COALESCE(NULLIF(?, ''), url),
       ua      = COALESCE(?, ua),
       enabled = COALESCE(?, enabled)
     WHERE id = ?`,
  )
    .bind(
      String(body.name ?? "").slice(0, 64),
      url.slice(0, 2048),
      typeof body.user_agent === "string" ? String(body.user_agent).slice(0, 512) : null,
      typeof body.enabled === "boolean" ? (body.enabled ? 1 : 0) : null,
      id,
    )
    .run();

  const row = await env.DB.prepare(
    `SELECT id, name, url, ua, referer, enabled, sort, created_at,
            last_fetch_at, last_status, last_count FROM sources WHERE id = ?`,
  )
    .bind(id)
    .first<SourceRow>();

  return row ? ok(toCompatSource(row)) : fail(2004, "源不存在");
}

// ---- channels ----

interface ChannelBrief {
  id: number;
  stable_key: string;
  origin: string;
}

async function loadChannel(env: Env, id: number): Promise<ChannelBrief | null> {
  return env.DB.prepare("SELECT id, stable_key, origin FROM channels WHERE id = ?")
    .bind(id)
    .first<ChannelBrief>();
}

/** 改一个频道的字段。订阅频道写 overrides，手工频道直接改本行。 */
async function setChannelFields(
  env: Env,
  ch: ChannelBrief,
  f: { name?: string | null; group?: string | null; enabled?: number | null; number?: number | null },
): Promise<void> {
  if (ch.origin === "manual") {
    await env.DB.prepare(
      `UPDATE channels SET
         name    = COALESCE(?, name),
         "group" = COALESCE(?, "group"),
         enabled = COALESCE(?, enabled),
         number  = COALESCE(?, number)
       WHERE id = ?`,
    )
      .bind(f.name ?? null, f.group ?? null, f.enabled ?? null, f.number ?? null, ch.id)
      .run();
    return;
  }

  await env.DB.prepare(
    `INSERT INTO channel_overrides (stable_key, name, group_name, enabled, updated_at)
     VALUES (?,?,?,?,?)
     ON CONFLICT(stable_key) DO UPDATE SET
       name       = COALESCE(excluded.name, channel_overrides.name),
       group_name = COALESCE(excluded.group_name, channel_overrides.group_name),
       enabled    = COALESCE(excluded.enabled, channel_overrides.enabled),
       updated_at = excluded.updated_at`,
  )
    .bind(ch.stable_key, f.name ?? null, f.group ?? null, f.enabled ?? null, nowSec())
    .run();
}

async function compatChannels(env: Env, url: URL): Promise<Response> {
  const q = (url.searchParams.get("q") || "").trim();
  const group = (url.searchParams.get("group") || "").trim();
  const page = Math.max(1, Number(url.searchParams.get("page") || "1") || 1);
  const size = Math.min(200, Math.max(1, Number(url.searchParams.get("size") || "20") || 20));

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

  const from =
    "FROM channels c LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key" +
    (where.length ? " WHERE " + where.join(" AND ") : "");

  const total = await env.DB.prepare(`SELECT COUNT(*) AS n ${from}`)
    .bind(...binds)
    .first<{ n: number }>();

  const { results } = await env.DB.prepare(
    `SELECT c.id, c.number,
            COALESCE(o.name, c.name) AS name,
            COALESCE(o.group_name, c."group") AS grp,
            COALESCE(o.logo, c.logo) AS logo,
            c.tvg_id, c.epg_id,
            COALESCE(o.enabled, c.enabled) AS enabled,
            (SELECT COUNT(*) FROM streams s WHERE s.channel_id = c.id) AS stream_count
       ${from}
      ORDER BY c.sort, c.number, c.id
      LIMIT ? OFFSET ?`,
  )
    .bind(...binds, size, (page - 1) * size)
    .all<{
      id: number;
      number: number;
      name: string;
      grp: string;
      logo: string;
      tvg_id: string;
      epg_id: string;
      enabled: number;
      stream_count: number;
    }>();

  return ok({
    total: total?.n ?? 0,
    items: (results ?? []).map((r) => ({
      id: r.id,
      number: r.number,
      name: r.name,
      group: r.grp,
      logo: r.logo,
      tvg_id: r.tvg_id,
      epg_id: r.epg_id || null,
      enabled: r.enabled === 1,
      stream_count: r.stream_count,
    })),
  });
}

async function compatGroups(env: Env): Promise<Response> {
  const { results } = await env.DB.prepare(
    `SELECT COALESCE(o.group_name, c."group") AS name, COUNT(*) AS count
       FROM channels c
       LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
      GROUP BY name ORDER BY count DESC, name ASC`,
  ).all<{ name: string; count: number }>();
  return ok(results ?? []);
}

async function compatCreateChannel(req: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(2001, "请求体不是合法 JSON");
  }

  const name = String(body.name ?? "").trim();
  if (!name) return fail(2001, "频道名不能为空");

  const res = await env.DB.prepare(
    `INSERT INTO channels (stable_key, number, name, "group", logo, tvg_id, epg_id, enabled, sort, origin)
     VALUES (?,?,?,?,?,?,?,?,?, 'manual')`,
  )
    .bind(
      `manual:${crypto.randomUUID()}`,
      Number(body.number ?? 0) || 0,
      name.slice(0, 128),
      String(body.group ?? "未分组").slice(0, 64) || "未分组",
      String(body.logo ?? "").slice(0, 512),
      String(body.tvg_id ?? "").slice(0, 128),
      String(body.epg_id ?? "").slice(0, 128),
      body.enabled === false ? 0 : 1,
      0,
    )
    .run();

  const id = Number(res.meta.last_row_id);
  return ok({
    id,
    number: Number(body.number ?? 0) || 0,
    name,
    group: String(body.group ?? "未分组"),
    logo: String(body.logo ?? ""),
    tvg_id: String(body.tvg_id ?? ""),
    epg_id: String(body.epg_id ?? "") || null,
    enabled: body.enabled !== false,
    stream_count: 0,
  });
}

async function compatBatch(req: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(2001, "请求体不是合法 JSON");
  }

  const ids = Array.isArray(body.ids) ? body.ids.map(Number).filter((n) => n > 0) : [];
  const action = String(body.action ?? "");
  const value = typeof body.value === "string" ? body.value : "";

  if (ids.length === 0) return fail(2001, "没有选中频道");
  if (ids.length > 500) return fail(2001, "一次最多 500 个");

  for (const id of ids) {
    const ch = await loadChannel(env, id);
    if (!ch) continue;

    if (action === "delete") {
      if (ch.origin === "manual") {
        await env.DB.batch([
          env.DB.prepare("DELETE FROM streams WHERE channel_id = ?").bind(id),
          env.DB.prepare("DELETE FROM channels WHERE id = ?").bind(id),
        ]);
      } else {
        await setChannelFields(env, ch, { enabled: 0 });
      }
    } else if (action === "enable") {
      await setChannelFields(env, ch, { enabled: 1 });
    } else if (action === "disable") {
      await setChannelFields(env, ch, { enabled: 0 });
    } else if (action === "set_group") {
      if (!value) return fail(2001, "缺少目标分组");
      await setChannelFields(env, ch, { group: value.slice(0, 64) });
    } else {
      return fail(2001, `不认识的操作：${action}`);
    }
  }

  return ok(null);
}

async function compatReorder(req: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(2001, "请求体不是合法 JSON");
  }

  const ids = Array.isArray(body.ids) ? body.ids.map(Number).filter((n) => n > 0) : [];
  if (ids.length === 0) return fail(2001, "没有选中频道");
  if (ids.length > 500) return fail(2001, "一次最多 500 个");

  // sort 直接写成新顺序的下标。批量操作走事务，避免改一半。
  const stmts = ids.map((id, i) =>
    env.DB.prepare("UPDATE channels SET sort = ? WHERE id = ?").bind(i, id),
  );
  await env.DB.batch(stmts);

  return ok(null);
}

async function compatStreams(env: Env, channelId: number): Promise<Response> {
  const { results } = await env.DB.prepare(
    `SELECT id, url, label, quality, latency_ms
       FROM streams WHERE channel_id = ?
      ORDER BY priority DESC, id ASC`,
  )
    .bind(channelId)
    .all<{ id: number; url: string; label: string; quality: string; latency_ms: number }>();

  return ok(
    (results ?? []).map((r) => ({
      id: r.id,
      url: r.url,
      quality: r.quality || r.label,
      enabled: true,
      latency_ms: r.latency_ms,
    })),
  );
}

async function compatAddStream(req: Request, env: Env, channelId: number): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(2001, "请求体不是合法 JSON");
  }

  const url = String(body.url ?? "").trim();
  if (!/^https?:\/\//i.test(url)) return fail(2001, "地址必须以 http:// 或 https:// 开头");

  const ch = await loadChannel(env, channelId);
  if (!ch) return fail(2004, "频道不存在");

  const res = await env.DB.prepare(
    `INSERT INTO streams (channel_id, channel_key, label, quality, priority, health, latency_ms, url, ua, headers, origin)
     VALUES (?,?,?,'',0,-1,0,?,'','{}','manual')`,
  )
    .bind(channelId, ch.stable_key, "手工线路", url.slice(0, 2048))
    .run();

  return ok({ id: Number(res.meta.last_row_id), url, quality: "手工线路", enabled: true, latency_ms: 0 });
}

// ---- devices ----

async function compatDevices(env: Env, url: URL): Promise<Response> {
  // 边缘没有"待批准"流程：绑定码对了就是 bound
  if (url.searchParams.get("status") === "pending") return ok([]);

  const { results } = await env.DB.prepare(
    "SELECT id, name, platform, last_seen FROM devices ORDER BY last_seen DESC",
  ).all<{ id: number; name: string; platform: string; last_seen: number }>();

  const now = nowSec();
  return ok(
    (results ?? []).map((d) => ({
      id: d.id,
      name: d.name || "未命名",
      platform: d.platform,
      model: "",
      online: now - d.last_seen < 300,
      last_heartbeat_at: isoOrNull(d.last_seen),
      status: "bound",
      ip: "",
      app_version: "",
      config: null,
    })),
  );
}

// ---- system ----

async function compatSystemInfo(env: Env): Promise<Response> {
  const row = await env.DB.prepare(
    `SELECT
       (SELECT COUNT(*) FROM channels WHERE enabled = 1) AS channels_total,
       (SELECT COUNT(*) FROM devices WHERE last_seen > ?) AS devices_online,
       (SELECT COUNT(*) FROM sources WHERE last_status NOT IN ('', 'ok')) AS sources_failed`,
  )
    .bind(nowSec() - 300)
    .first<{ channels_total: number; devices_online: number; sources_failed: number }>();

  return ok({
    version: "edge",
    arch: "cloudflare-workers",
    channels_total: row?.channels_total ?? 0,
    devices_online: row?.devices_online ?? 0,
    sources_failed: row?.sources_failed ?? 0,
  });
}

// ---- 路由分发 ----

async function compatAdmin(
  req: Request,
  env: Env,
  path: string,
  method: string,
  url: URL,
): Promise<Response | null> {
  // sources
  if (path === "/api/v1/sources") {
    if (method === "POST") return compatAddSource(req, env);
    return compatSources(env);
  }
  const srcId = /^\/api\/v1\/sources\/(\d+)$/.exec(path);
  if (srcId) {
    const id = Number(srcId[1]);
    if (method === "PUT") return compatUpdateSource(req, env, id);
    if (method === "DELETE") {
      await env.DB.prepare("DELETE FROM sources WHERE id = ?").bind(id).run();
      return ok(null);
    }
  }
  if (/^\/api\/v1\/sources\/\d+\/(refresh|preview)$/.test(path)) {
    // 拉取 + 解析会超免费版的 10ms CPU，只能交给 CI
    return notSupported("在页面里拉取源（请点「立即重建」，抓取和解析在 CI 里做）");
  }

  // channels
  if (path === "/api/v1/channels") {
    if (method === "POST") return compatCreateChannel(req, env);
    return compatChannels(env, url);
  }
  if (path === "/api/v1/channels/batch" && method === "POST") return compatBatch(req, env);
  if (path === "/api/v1/channels/reorder" && method === "POST") return compatReorder(req, env);
  if (path === "/api/v1/groups") return compatGroups(env);

  const chId = /^\/api\/v1\/channels\/(\d+)$/.exec(path);
  if (chId) {
    const id = Number(chId[1]);
    const ch = await loadChannel(env, id);
    if (!ch) return fail(2004, "频道不存在");

    if (method === "DELETE") {
      if (ch.origin === "manual") {
        await env.DB.batch([
          env.DB.prepare("DELETE FROM streams WHERE channel_id = ?").bind(id),
          env.DB.prepare("DELETE FROM channels WHERE id = ?").bind(id),
        ]);
      } else {
        await setChannelFields(env, ch, { enabled: 0 });
      }
      return ok(null);
    }

    if (method === "PUT") {
      let body: Record<string, unknown>;
      try {
        body = (await req.json()) as Record<string, unknown>;
      } catch {
        return fail(2001, "请求体不是合法 JSON");
      }
      await setChannelFields(env, ch, {
        name: typeof body.name === "string" ? body.name.slice(0, 128) : null,
        group: typeof body.group === "string" ? body.group.slice(0, 64) : null,
        enabled: typeof body.enabled === "boolean" ? (body.enabled ? 1 : 0) : null,
        number: Number.isFinite(Number(body.number)) ? Math.trunc(Number(body.number)) : null,
      });
      return ok({ id });
    }
  }

  const chStreams = /^\/api\/v1\/channels\/(\d+)\/streams$/.exec(path);
  if (chStreams) {
    const id = Number(chStreams[1]);
    if (method === "POST") return compatAddStream(req, env, id);
    return compatStreams(env, id);
  }

  const chStream = /^\/api\/v1\/channels\/(\d+)\/streams\/(\d+)$/.exec(path);
  if (chStream && method === "DELETE") {
    await env.DB.prepare("DELETE FROM streams WHERE id = ? AND channel_id = ?")
      .bind(Number(chStream[2]), Number(chStream[1]))
      .run();
    return ok(null);
  }

  if (/^\/api\/v1\/channels\/\d+\/logo$/.test(path)) {
    // 没有对象存储，台标只能是外链
    return notSupported("上传/抓取台标（边缘模式没有图片存储，台标用源里的外链）");
  }

  // devices
  if (path === "/api/v1/devices") return compatDevices(env, url);
  if (path === "/api/v1/devices/bindcode") {
    return notSupported("生成绑定码（边缘模式的绑定码就是 BIND_CODE，不临时签发）");
  }
  const devId = /^\/api\/v1\/devices\/(\d+)$/.exec(path);
  if (devId && method === "DELETE") {
    await env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(Number(devId[1])).run();
    return ok(null);
  }
  if (/^\/api\/v1\/devices\/\d+\/(approve|reject|config)$/.test(path)) {
    return notSupported("设备审批 / 下发配置（边缘模式没有审批流程）");
  }

  // system
  if (path === "/api/v1/system/me") return ok(EDGE_ME);
  if (path === "/api/v1/system/info") return compatSystemInfo(env);
  if (path === "/api/v1/system/config") {
    return notSupported("系统配置（边缘部署的配置在 wrangler.toml 和 Secrets 里）");
  }
  if (path === "/api/v1/system/logs") return ok([]);

  // epg：节目单由 CI 从源的 x-tvg-url 拉取并解析，这里只读
  if (path.startsWith("/api/v1/epg/")) {
    if (path === "/api/v1/epg/channels") {
      const { results } = await env.DB.prepare(
        `SELECT xmltv_id AS id, COUNT(*) AS program_count
           FROM epg_programs GROUP BY xmltv_id ORDER BY program_count DESC`,
      ).all();
      return ok(results ?? []);
    }

    if (path === "/api/v1/epg/stats") {
      const row = await env.DB.prepare(
        `SELECT COUNT(DISTINCT xmltv_id) AS channels, COUNT(*) AS programs,
                MIN(start_at) AS earliest_start, MAX(end_at) AS latest_end
           FROM epg_programs`,
      ).first<{
        channels: number;
        programs: number;
        earliest_start: number | null;
        latest_end: number | null;
      }>();

      const sync = await env.DB.prepare("SELECT v FROM kv WHERE k = 'last_epg_at'").first<{
        v: string;
      }>();

      const earliest = row?.earliest_start ?? 0;
      const latest = row?.latest_end ?? 0;
      return ok({
        sources: 0,
        enabled_sources: 0,
        channels: row?.channels ?? 0,
        programs: row?.programs ?? 0,
        earliest_start: earliest,
        latest_end: latest,
        covered_days: earliest && latest ? Math.ceil((latest - earliest) / 86400) : 0,
        last_sync: Number(sync?.v ?? 0) || 0,
        db_bytes: 0,
        keep_days: 3,
      });
    }

    if (path === "/api/v1/epg/search") {
      const q = (url.searchParams.get("q") || "").trim();
      if (!q) return ok([]);
      const { results } = await env.DB.prepare(
        `SELECT p.channel_id AS epg_channel_id, p.xmltv_id,
                COALESCE(o.name, c.name) AS channel_name,
                p.title, p.start_at, p.end_at, p.category
           FROM epg_programs p
           JOIN channels c ON c.id = p.channel_id
           LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
          WHERE p.title LIKE ?
          ORDER BY p.start_at LIMIT 100`,
      )
        .bind(`%${q}%`)
        .all();
      return ok(results ?? []);
    }

    if (path === "/api/v1/epg/sources" || path === "/api/v1/epg/mapping") return ok([]);

    return notSupported("EPG 导入与映射（节目单由 CI 从源的 x-tvg-url 自动拉取）");
  }

  return null;
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
    // 自带管理台的登录接口（admin/src/views/LoginView.vue 调的是 auth/login）
    if (path === "/api/v1/auth/login" && method === "POST") return adminLogin(req, env);
    if (path === "/api/v1/auth/logout") return ok(null);

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
      if (path === "/api/v1/admin/build") return adminBuildInfo(env);
      if (path === "/api/v1/admin/rebuild" && method === "POST") return adminRebuild(env);
      if (path === "/api/v1/admin/channels") return adminChannels(env, url);
      if (path === "/api/v1/admin/streams") return adminStreams(env, url);
      if (path === "/api/v1/admin/devices") return adminDevices(env);

      if (path === "/api/v1/admin/sources") {
        if (method === "POST") return adminAddSource(req, env);
        return adminSources(env);
      }

      const srcMatch = /^\/api\/v1\/admin\/source\/(\d+)$/.exec(path);
      if (srcMatch) {
        const id = Number(srcMatch[1]);
        if (method === "PATCH" || method === "PUT") return adminUpdateSource(req, env, id);
        if (method === "DELETE") return adminDeleteSource(env, id);
      }

      const srcTest = /^\/api\/v1\/admin\/source\/(\d+)\/test$/.exec(path);
      if (srcTest && method === "POST") return adminTestSource(env, Number(srcTest[1]));

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

    // ---- 自带管理台（LITV2 的 Vue 应用）----
    // 它调的是 /api/v1/{sources,channels,groups,devices,system,epg}。
    if (/^\/api\/v1\/(sources|channels|groups|devices|system|epg)(\/|$)/.test(path)) {
      if (!(await authAdmin(req, env))) return fail(2003, "管理台未登录或会话已过期");
      const r = await compatAdmin(req, env, path, method, url);
      return r ?? fail(2004, "没有这个接口");
    }

    // ---- 以下都要设备 token ----
    if (!(await authDevice(req, env))) return fail(2003, "设备未认证");

    if (path === "/api/v1/tv/bootstrap") return tvBootstrap(env);

    const playMatch = /^\/api\/v1\/tv\/play\/(\d+)$/.exec(path);
    if (playMatch) return tvPlay(env, Number(playMatch[1]), url);

    const streamsMatch = /^\/api\/v1\/tv\/streams\/(\d+)$/.exec(path);
    if (streamsMatch) return tvStreams(env, Number(streamsMatch[1]));

    const epgMatch = /^\/api\/v1\/tv\/epg\/(\d+)$/.exec(path);
    if (epgMatch) return tvEpg(env, Number(epgMatch[1]), url);

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
