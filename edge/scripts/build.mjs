// 拉取上游 m3u 订阅 → 解析 → 合并去重 → 产出两样东西：
//
//   1. public/playlist.m3u   给第三方播放器用的普通播放列表
//   2. sql/seed.sql          灌进 D1 的频道表 + 线路表
//
// 第二条是"接入现有 app"的关键：app 不认识 m3u，它只认 /api/v1/tv/bootstrap
// 和 /api/v1/tv/play/{id}。所以要把 m3u 落成表。
//
// 纪律：日志里只出现计数，绝不打印上游 URL 或凭据。

import { mkdir, readFile, writeFile } from "node:fs/promises";
// 出站目标校验与 Worker 共用一份（edge/src/net.ts）。
// CI runner 上抓一个不可信 m3u 里写的内网地址，是能摸到云元数据端点的。
import { checkFetchTarget, fetchGuarded } from "../src/net.ts";

const UA = process.env.SOURCE_UA || "VLC/3.0.20 LibVLC/3.0.20";
// 「全局默认 UA」有两个用途：
//   1. 拉取订阅（没配就用 VLC，多数面板不校验）
//   2. **作为每条线路的兜底 UA 下发** —— 只认显式配置
// 以前只做了第 1 条，于是「面板要求带特定 UA 才给流」表现为
// 「能导入、客户端播不了」：play 接口不给 ua，播放器用系统默认 UA 被 403。
// VLC 这个默认值不能写进线路，否则等于把播放器伪装成 VLC。
const CONFIGURED_UA = String(process.env.SOURCE_UA || "").trim();
const OUT_DIR = "public";
const SQL_DIR = "sql";
const MIN_CHANNELS = Number(process.env.MIN_CHANNELS || "1");
// 缩水护栏：本次频道数低于「上次成功重建」的这个比例就拒绝生成。
// MIN_CHANNELS 只挡得住「全空」，挡不住「500 个变成 3 个」——
// 而那种半失败每小时后重演一次，线上会一直是残的。
const MIN_KEEP_RATIO = Number(process.env.MIN_KEEP_RATIO || "0.6");
const ALLOW_SHRINK = process.env.ALLOW_SHRINK === "1";
const BUILD_STATE_FILE = process.env.BUILD_STATE_FILE || "";
const FETCH_TIMEOUT_MS = 30_000;
// 同时抓几个源。串行的话 N 个源就是 N 次完整握手相加。
const FETCH_CONCURRENCY = Math.max(1, Number(process.env.FETCH_CONCURRENCY || "4") || 4);
const INSERT_BATCH = 50;
// 什么都没变时也别写空文件：wrangler 对「没有语句的 SQL 文件」的行为
// 没有承诺，报错会让后面所有步骤（含 deploy）连锁跳过。
const SQL_NOOP = "SELECT 1;";

// 从 m3u 头里的 x-tvg-url 收集节目单地址
const epgUrls = new Set();

// ---------- 解析 ----------

// #EXTINF 的属性段和频道名之间用逗号分隔，但属性值里也可能有逗号
// （group-title="新闻,财经"），所以要跳过引号内的逗号。
function splitExtinf(line) {
  let inQuote = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '"') inQuote = !inQuote;
    else if (c === "," && !inQuote) return [line.slice(0, i), line.slice(i + 1)];
  }
  return [line, ""];
}

function parseAttrs(head) {
  const attrs = {};
  const re = /([A-Za-z0-9_-]+)="([^"]*)"/g;
  let m;
  while ((m = re.exec(head)) !== null) attrs[m[1].toLowerCase()] = m[2];
  return attrs;
}

function parseM3U(text) {
  const entries = [];
  let cur = null;

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;

    if (line.startsWith("#EXTM3U")) continue;

    if (line.startsWith("#EXTINF")) {
      const [head, name] = splitExtinf(line);
      const attrs = parseAttrs(head);
      cur = {
        name: name.trim(),
        tvgId: attrs["tvg-id"] || "",
        tvgName: attrs["tvg-name"] || "",
        logo: attrs["tvg-logo"] || "",
        group: attrs["group-title"] || "",
        ua: "",
        headers: {},
        extras: [],
        url: "",
      };
      continue;
    }

    if (line.startsWith("#EXTGRP:")) {
      if (cur && !cur.group) cur.group = line.slice(8).trim();
      continue;
    }

    // #EXTVLCOPT 里常带源站要求的 UA / Referer。
    // 不抽出来的话，客户端直连会 403 —— 这是"直连打不开、中转能看"的老问题。
    if (line.startsWith("#EXTVLCOPT:")) {
      const opt = line.slice(11);
      const eq = opt.indexOf("=");
      if (cur && eq > 0) {
        const key = opt.slice(0, eq).trim().toLowerCase();
        const val = opt.slice(eq + 1).trim();
        if (key === "http-user-agent" && val) cur.ua = val;
        else if ((key === "http-referrer" || key === "http-referer") && val) {
          cur.headers["Referer"] = val;
        }
      }
      continue;
    }

    if (line.startsWith("#")) {
      // #KODIPROP 之类跟播放器有关，普通播放列表里保留，D1 里不需要
      if (cur && /^#(KODIPROP|EXT-X-)/i.test(line)) cur.extras.push(line);
      continue;
    }

    if (cur && !cur.url) {
      cur.url = line;
      if (cur.name) entries.push(cur);
      cur = null;
    }
  }

  return entries;
}

// ---------- 去重 ----------

// 保守归一化：只抹掉空白和常见分隔符，不剥 "HD"/"4K" 之类的后缀——
// 那些往往是真的不同频道，合并错了比重复更糟。
function normalizeName(name) {
  return name.toLowerCase().replace(/[\s\-_.·|/\\]+/g, "");
}

function dedupeKey(e) {
  return e.tvgId ? `id:${e.tvgId}` : `name:${normalizeName(e.name)}`;
}

// ---------- 格式嗅探 ----------

// 与 server/internal/parse/parse.go 的 looksLikeURL 保持一致。
const PROTOCOLS = [
  "http://", "https://", "rtsp://", "rtmp://", "rtp://", "udp://",
  "rtp2://", "p3p://", "p2p://", "mitv://", "igmp://", "file://",
];

function looksLikeURL(s) {
  const t = String(s).trim().toLowerCase();
  return PROTOCOLS.some((p) => t.startsWith(p));
}

// 国内 TXT 源经常是 GBK，先按 UTF-8 严格解，失败再退 GBK。
function decodeBody(buf) {
  if (buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf) {
    return new TextDecoder("utf-8").decode(buf.subarray(3));
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(buf);
  } catch {
    try {
      return new TextDecoder("gbk").decode(buf);
    } catch {
      return new TextDecoder("utf-8").decode(buf);
    }
  }
}

// 判据照抄 server/internal/parse 的 Detect()，别自己另立一套。
function detectFormat(text) {
  const t = text.replace(/^\uFEFF/, "").trimStart();
  if (!t) return "empty";
  if (t[0] === "{" || t[0] === "[") return "json";
  if (t[0] === "<") return "html";

  const head = t.slice(0, 512);
  if (head.includes("#EXTM3U") || head.includes("#EXTINF")) return "m3u";
  if (head.includes("#genre#")) return "txt";
  for (const line of head.split(/\r?\n/)) {
    if (line.includes(",") && line.includes("://")) return "txt";
  }
  return "unknown";
}

// `名字,地址` 从右往左找第一个后面紧跟协议头的逗号——
// 频道名里可能带逗号，地址也可能是 rtp:// 这类。
function splitNameURL(line) {
  for (let i = line.length - 1; i >= 0; i--) {
    if (line[i] !== ",") continue;
    if (looksLikeURL(line.slice(i + 1))) {
      return [line.slice(0, i).trim(), line.slice(i + 1).trim()];
    }
  }
  const parts = line.split(",");
  if (parts.length < 2) return ["", ""];
  return [parts[0].trim(), parts.slice(1).join(",").trim()];
}

// TXT：`分组,#genre#` 切组，`频道名,地址` 一条频道。
function parseTXT(text) {
  const entries = [];
  let group = "未分组";

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;

    if (line.includes("#genre#")) {
      const g = line.split(",")[0].replace("#genre#", "").trim();
      if (g) group = g;
      continue;
    }

    const [name, url] = splitNameURL(line);
    if (!url || !looksLikeURL(url)) continue;

    entries.push({
      name,
      tvgId: "",
      tvgName: "",
      logo: "",
      group,
      ua: "",
      headers: {},
      extras: [],
      url,
    });
  }
  return entries;
}

// 诊断信息脱敏：日志在公开仓库里可见，不能把带账号的地址打出去。
function redact(s) {
  return String(s)
    .replace(/\/\/[^\s"']+/g, "//…")
    .replace(/\b[A-Za-z0-9_-]{24,}\b/g, "…")
    .slice(0, 100);
}

// ---------- 源清单 ----------

// wrangler d1 execute --json 的输出形状有好几种，全都兜住。
function extractRows(json) {
  if (Array.isArray(json)) {
    if (json.length && Array.isArray(json[0]?.results)) return json[0].results;
    return json;
  }
  if (json && Array.isArray(json.results)) return json.results;
  if (json && json.result && Array.isArray(json.result[0]?.results)) {
    return json.result[0].results;
  }
  return [];
}

/**
 * 源清单来源，优先级：
 *   1. SOURCES_FILE —— CI 从 D1 的 sources 表读出来写的临时 JSON（主路径，管理台配的）
 *   2. SOURCE_URLS   —— 老的 Secret，一行一个地址（回退，不推荐）
 *
 * 每个源可以带自己的 UA / Referer：有些面板不认默认 UA，直接 403。
 * 注意这是「拉取订阅时」用的 UA，不是播放时用的——播放用的 UA 在线路里。
 */
async function loadSources() {
  const file = process.env.SOURCES_FILE;
  if (file) {
    let raw;
    try {
      raw = await readFile(file, "utf8");
    } catch {
      // 文件不存在 = 这次没读（比如首次部署还没建表），走回退
      raw = "";
    }

    if (raw.trim()) {
      // wrangler 会往 stdout 打进度行，JSON 前后可能有噪声，切出数组再解析
      const a = raw.indexOf("[");
      const b = raw.lastIndexOf("]");
      let rows = null;
      if (a >= 0 && b > a) {
        try {
          rows = extractRows(JSON.parse(raw.slice(a, b + 1)));
        } catch {
          rows = null;
        }
      }

      if (rows === null) {
        // 读到了文件却解析不了，绝不能静默当成"没有源"——
        // 那样会表现为"重建成功但一个频道都没有"，极难排查。
        failBuild(
          `SOURCES_FILE（${file}）不是可解析的 JSON，拒绝继续。` +
            `前 120 字符：${redact(raw.trimStart())}`,
        );
      }

      const list = rows
        .map((r) => ({
          id: Number(r.id) || 0,
          name: String(r.name ?? ""),
          url: String(r.url ?? ""),
          ua: String(r.ua ?? ""),
          referer: String(r.referer ?? ""),
        }))
        .filter((s) => s.url);

      console.log(`源清单：${list.length} 个启用`);
      return list;
    }
  }

  const fallback = (process.env.SOURCE_URLS || "")
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter((s) => s && !s.startsWith("#"))
    .map((url) => ({ id: 0, name: "", url, ua: "", referer: "" }));

  if (fallback.length > 0) console.log(`源清单：回退到 SOURCE_URLS，${fallback.length} 个`);
  return fallback;
}

// ---------- 抓取 ----------

/**
 * 返回 { entries, status }。status 会回写到 sources.last_status，
 * 管理台据此显示"这个源还活着吗、上次拉到几条"。
 */
async function fetchOne(src, index) {
  const label = src.name ? `源 ${index}（${src.name}）` : `源 ${index}`;
  const headers = { "User-Agent": src.ua || UA, Accept: "*/*" };
  if (src.referer) headers["Referer"] = src.referer;

  const badTarget = checkFetchTarget(src.url);
  if (badTarget) {
    console.warn(`[${label}] 跳过：${badTarget}`);
    return { entries: [], status: "blocked" };
  }

  try {
    const res = await fetchGuarded(src.url, {
      headers,
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
    });
    if (!res.ok) {
      console.warn(`[${label}] HTTP ${res.status}`);
      return { entries: [], status: `http_${res.status}` };
    }

    const buf = new Uint8Array(await res.arrayBuffer());
    const text = decodeBody(buf);

    // 节目单地址写在 m3u 第一行的 x-tvg-url 上，顺手收走
    for (const m of text.slice(0, 500).matchAll(/x-tvg-url="([^"]+)"/gi)) {
      for (const u of m[1].split(",")) if (u.trim()) epgUrls.add(u.trim());
    }

    const format = detectFormat(text);

    let entries;
    switch (format) {
      case "m3u":
        entries = parseM3U(text);
        break;
      case "txt":
        entries = parseTXT(text);
        break;
      case "html":
        console.warn(
          `[${label}] 拿到的是网页（HTML），不是播放列表。` +
            `需要面板里「订阅 / 播放列表」那个地址，通常带 get.php?...type=m3u_plus 之类。`,
        );
        return { entries: [], status: "html" };
      case "json":
        console.warn(
          `[${label}] 拿到的是 JSON，不是播放列表。` +
            `这多半是面板的 API，请改用订阅导出地址。`,
        );
        return { entries: [], status: "json" };
      case "empty":
        console.warn(`[${label}] 响应是空的`);
        return { entries: [], status: "empty" };
      default:
        console.warn(`[${label}] 认不出格式，开头是：${redact(text.trimStart())}`);
        return { entries: [], status: "unknown" };
    }

    console.log(`[${label}] ${format} 格式，${entries.length} 条`);

    // 源上配的 UA / Referer 是"这个源认的头"，既用于拉取，也作为
    // 每条线路的播放兜底——条目自己带了（#EXTVLCOPT）就不覆盖。
    // 很多源拉取不校验 UA，但播放分片校验，只配拉取会表现为"能导入不能播"。
    //
    // 优先级：条目自带 > 源上配的 > 全局 SOURCE_UA（仅当显式配置过）。
    // 第三档是这次补的：以前只把 SOURCE_UA 用在拉取请求上，从不下发，
    // 于是「面板要求特定 UA 才给流」的用户在客户端一律播不了。
    const lineUA = src.ua || CONFIGURED_UA;
    if (lineUA || src.referer) {
      for (const e of entries) {
        if (!e.ua) e.ua = lineUA;
        if (src.referer && !e.headers["Referer"]) e.headers["Referer"] = src.referer;
      }
    }

    return { entries, status: entries.length > 0 ? "ok" : "empty_list" };
  } catch (err) {
    // 只报错误类型，不带 URL
    const msg = String(err?.message || "");
    if (msg.startsWith("blocked:")) {
      console.warn(`[${label}] 跳转/目标被安全策略拦下`);
      return { entries: [], status: "blocked" };
    }
    console.warn(`[${label}] 失败：${err.name}`);
    return { entries: [], status: `error_${err.name}` };
  }
}

// ---------- 输出：播放列表 ----------

function quote(s) {
  return String(s).replace(/"/g, "'");
}

function renderM3U(channels) {
  const lines = ["#EXTM3U"];
  for (const ch of channels) {
    const attrs = [];
    if (ch.tvgId) attrs.push(`tvg-id="${quote(ch.tvgId)}"`);
    if (ch.tvgName) attrs.push(`tvg-name="${quote(ch.tvgName)}"`);
    if (ch.logo) attrs.push(`tvg-logo="${quote(ch.logo)}"`);
    attrs.push(`group-title="${quote(ch.group)}"`);
    lines.push(`#EXTINF:-1 ${attrs.join(" ")},${ch.name}`);
    for (const extra of ch.extras) lines.push(extra);
    // UA / Referer 要跟着线路一起吐回去，否则第三方播放器直连那些
    // "认 UA 才给流"的源会 403。app 那边走 headers 字段，这里是给普通播放器的。
    if (ch.line.ua) lines.push(`#EXTVLCOPT:http-user-agent=${ch.line.ua}`);
    if (ch.line.headers["Referer"]) {
      lines.push(`#EXTVLCOPT:http-referrer=${ch.line.headers["Referer"]}`);
    }
    lines.push(ch.line.url);
  }
  return lines.join("\n") + "\n";
}

// ---------- 输出：D1 seed ----------

function sqlStr(v) {
  if (v === null || v === undefined) return "''";
  return "'" + String(v).replace(/'/g, "''") + "'";
}

function sqlInt(v) {
  const n = Number(v);
  return Number.isFinite(n) ? String(Math.trunc(n)) : "0";
}

function batchInsert(table, columns, rows) {
  const out = [];
  for (let i = 0; i < rows.length; i += INSERT_BATCH) {
    const chunk = rows.slice(i, i + INSERT_BATCH);
    out.push(
      `INSERT INTO ${table} (${columns.join(", ")}) VALUES\n` +
        chunk.map((r) => "  (" + r.join(", ") + ")").join(",\n") +
        ";",
    );
  }
  return out.join("\n\n");
}

/**
 * 多行 upsert。冲突时只更新 updateColumns 指定的列，
 * 其余列（epg_id / enabled / stable_key）保持库里的值。
 *
 * 为什么不用「先删再插」：channels.id 是被别处引用的（epg_programs.channel_id、
 * streams.channel_id）。删了重插等于每次重建给所有频道换一批新 id。
 */
function batchUpsert(table, columns, rows, keyColumn, updateColumns) {
  const out = [];
  const setClause = updateColumns.map((c) => `${c} = excluded.${c}`).join(",\n         ");
  for (let i = 0; i < rows.length; i += INSERT_BATCH) {
    const chunk = rows.slice(i, i + INSERT_BATCH);
    out.push(
      `INSERT INTO ${table} (${columns.join(", ")}) VALUES\n` +
        chunk.map((r) => "  (" + r.join(", ") + ")").join(",\n") +
        `\nON CONFLICT(${keyColumn}) DO UPDATE SET\n         ${setClause};`,
    );
  }
  return out.join("\n\n");
}

function renderSeed(channels) {
  const chRows = [];
  const stRows = [];

  channels.forEach((ch, i) => {
    const key = dedupeKey(ch);
    chRows.push([
      sqlStr(key),
      sqlInt(i + 1), // number
      sqlStr(ch.name),
      sqlStr(ch.group),
      sqlStr(ch.logo),
      sqlStr(ch.tvgId),
      sqlStr(ch.tvgId), // epg_id 先跟 tvg_id 走，边缘模式不解析节目单
      "1",
      sqlInt(i), // sort
      sqlStr("m3u"),
    ]);

    // 同一频道名下的每条线路都进 streams 表，客户端可以手动切线路。
    ch.lines.forEach((line, idx) => {
      stRows.push([
        // 频道 id 由数据库自增，这里用 stable_key 反查，
        // 这样重建后 id 变了也不会插错地方。
        `(SELECT id FROM channels WHERE stable_key = ${sqlStr(key)})`,
        sqlStr(key),
        sqlStr(`线路 ${idx + 1}`),
        sqlStr(""),
        sqlInt(ch.lines.length - idx), // 靠前的优先级高
        "-1", // health 未探测
        "0",
        sqlStr(line.url),
        sqlStr(line.ua),
        sqlStr(JSON.stringify(line.headers)),
        sqlStr("m3u"),
      ]);
    });
  });

  return [
    "-- 由 scripts/build.mjs 生成，不要手改",
    "",
    "-- 1. 只清订阅生成的线路，手工线路留着（频道不再删，见第 2 步）",
    "DELETE FROM streams WHERE origin = 'm3u';",
    "",
    "-- 2. 频道用 upsert，**不再删了重建**。",
    "--    以前 DELETE + INSERT，AUTOINCREMENT 让 id 每次都变，而 epg_programs",
    "--    是按 channel_id 存的、6 小时才整表重刷一次 —— 于是 6 小时里有 5 小时",
    "--    节目单全挂在已经不存在的 id 上，客户端一直「暂无节目单」。",
    "--    epg_id 与 enabled 刻意不参与更新：",
    "--      epg_id 由 epg.sql 回写，这里覆盖会把上次匹配的结果清空；",
    "--      enabled 是管理台「隐藏」的产物，覆盖等于撤销用户操作。",
    batchUpsert(
      "channels",
      ["stable_key", "number", "name", '"group"', "logo", "tvg_id", "epg_id", "enabled", "sort", "origin"],
      chRows,
      "stable_key",
      // sort 也刻意不更新：管理台的「拖拽排序」就是改 channels.sort，
      // 每小时跟着订阅顺序覆盖一遍等于把用户的排序擦掉。
      // 新频道插入时仍然用订阅顺序当初始值。
      ["number", "name", '"group"', "logo", "tvg_id", "origin"],
    ),
    "",
    "-- 3. 手工线路兜底重挂（id 现已稳定，这步主要给老库迁移；频道没了就跳过）",
    "UPDATE streams",
    "   SET channel_id = (SELECT c.id FROM channels c WHERE c.stable_key = streams.channel_key)",
    " WHERE origin = 'manual'",
    "   AND channel_key <> ''",
    "   AND EXISTS (SELECT 1 FROM channels c WHERE c.stable_key = streams.channel_key);",
    "",
    "-- 4. 插入本次的线路",
    batchInsert(
      "streams",
      ["channel_id", "channel_key", "label", "quality", "priority", "health", "latency_ms", "url", "ua", "headers", "origin"],
      stRows,
    ),
    "",
    "-- 5. 清理订阅里已经消失的频道：判据是「一条 m3u 线路都没有」。",
    "--    第 4 步刚给本次所有频道插过线路，所以这时候还空的就是真的没了。",
    "--    比把几千个 key 拼成一个巨型 NOT IN 稳（D1 单条语句有长度上限）。",
    "DELETE FROM channels",
    " WHERE origin = 'm3u'",
    "   AND NOT EXISTS (",
    "     SELECT 1 FROM streams s",
    "      WHERE s.channel_id = channels.id AND s.origin = 'm3u'",
    "   );",
    "",
  ].join("\n");
}

// 回写每个源的拉取结果，管理台据此显示"上次拉取：ok / 12 条"。
function renderSourceStatus(results) {
  const ts = Math.floor(Date.now() / 1000);
  const lines = ["-- 由 scripts/build.mjs 生成：回写每个源的拉取结果"];
  for (const r of results) {
    // 回退模式（SOURCE_URLS）没有 id，没什么可回写的
    if (!r.id) continue;
    lines.push(
      `UPDATE sources SET last_fetch_at = ${ts}, ` +
        `last_status = ${sqlStr(r.status)}, last_count = ${sqlInt(r.count)} ` +
        `WHERE id = ${sqlInt(r.id)};`,
    );
  }
  return lines.join("\n") + "\n";
}

// 重建结果写进 kv，管理台据此显示"数据是什么时候更新的"，
// 不用去翻 GitHub Actions。
function renderMeta(channels, sourceCount, lineCount, rawCount, sourceIds, epgUrl) {
  const info = {
    at: Math.floor(Date.now() / 1000),
    sources: sourceCount,
    // 这次参与重建的源 id 集合。下次重建拿它对比，区分「换源」和「源坏了」——
    // 光看频道数变化分不出这两者。
    source_ids: sourceIds || "",
    channels: channels.length,
    // 屏蔽之前解析到多少条。缩水护栏比的是这个数，否则用户屏蔽一整类
    // 就会被自己的护栏拦下来。
    channels_raw: rawCount,
    lines: lineCount,
    // xmltv 节目单地址：随 bootstrap 下发给客户端，由客户端自己下载解析。
    // 服务端不存节目数据，D1 一行都不用写。
    epg_url: epgUrl || "",
  };
  return [
    "-- 由 scripts/build.mjs 生成：记录本次重建结果",
    `INSERT INTO kv (k, v) VALUES ('last_build', ${sqlStr(JSON.stringify(info))})`,
    "ON CONFLICT(k) DO UPDATE SET v = excluded.v;",
    // 频道表变了就把版本号 +1：客户端（Android onChannelsVersion）拿心跳里的
    // 这个值跟本地比，变了才静默重拉列表。没有它，管理台改完频道、
    // 正在播的电视得杀进程才能看到。
    "INSERT INTO kv (k, v) VALUES ('channels_version', '1')",
    "ON CONFLICT(k) DO UPDATE SET v = CAST(CAST(v AS INTEGER) + 1 AS TEXT);",
    // 限速表只增不删：每个失败过的 IP 会永久留一行。
    // 每天重建时顺手清掉一天前的，成本可以忽略。
    `DELETE FROM bind_attempts WHERE window_start < ${Math.floor(Date.now() / 1000)} - 86400;`,
    "",
  ].join("\n");
}

// ---------- 节目单 ----------

// 往前留 26 小时（够跨过两次刷新、也能看昨天的回看），往后 3 天。
//
// 为什么是 26 不是 6：节目单现在**每天只灌一次**（见 EPG_MIN_INTERVAL_SEC），
// 窗口必须覆盖到下一次刷新之前，否则中间那段时间回看节目单是空的。
const EPG_BACK_SEC = 26 * 3600;
const EPG_FWD_SEC = 72 * 3600;
// 至少隔 24 小时才刷一次。
//
// D1 免费版每天只给 **10 万行写入**，而一次全量节目单就是几千行——
// 以前小时级重建 + 6 小时刷节目单，光节目单一天就吃掉 4 次，
// 额度是用户自己的，不该拿它换"节目单早 6 小时更新"。
const EPG_MIN_INTERVAL_SEC = 24 * 3600;

function decodeEntities(s) {
  return String(s)
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(Number(d)))
    .replace(/&amp;/g, "&");
}

function normName(s) {
  return String(s).toLowerCase().replace(/[\s\-_.·|/\\()（）\[\]【】]+/g, "");
}

// XMLTV 时间："20261029175900 +0800"
function parseXmltvTime(s) {
  const m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?:\s*([+-])(\d{2})(\d{2}))?$/.exec(
    String(s).trim(),
  );
  if (!m) return 0;
  const [, y, mo, d, h, mi, se, sign, oh, om] = m;
  let t = Date.UTC(+y, +mo - 1, +d, +h, +mi, +se) / 1000;
  if (sign) {
    const off = Number(oh) * 3600 + Number(om) * 60;
    t -= sign === "-" ? -off : off;
  }
  return t;
}

function parseXmltv(xml) {
  const channels = new Set();
  for (const m of xml.matchAll(/<channel\b[^>]*\bid="([^"]+)"/g)) channels.add(m[1]);

  const programs = [];
  for (const m of xml.matchAll(/<programme\b([^>]*)>([\s\S]*?)<\/programme>/g)) {
    const attrs = m[1];
    const body = m[2];
    const ch = /channel="([^"]+)"/.exec(attrs);
    const st = /start="([^"]+)"/.exec(attrs);
    if (!ch || !st) continue;
    const sp = /stop="([^"]+)"/.exec(attrs);
    const pick = (tag) => {
      const r = new RegExp(`<${tag}[^>]*>([\\s\\S]*?)</${tag}>`).exec(body);
      return r ? decodeEntities(r[1]).replace(/<[^>]*>/g, "").trim() : "";
    };
    programs.push({
      channel: ch[1],
      start: parseXmltvTime(st[1]),
      stop: sp ? parseXmltvTime(sp[1]) : 0,
      title: pick("title"),
      desc: pick("desc"),
      cat: pick("category"),
    });
  }
  return { channels, programs };
}

/**
 * 把我们的频道对上 XMLTV 的频道 id。
 *
 * 我们的 m3u 里没有 tvg-id（实测 223 个全是空），只能按名字对：
 *   1. 归一化后精确相等
 *   2. 最长前缀——必须"最长"，否则 CCTV1 会把 CCTV10~19 全抢走
 */
function matchEpg(ourChannels, xmlChannelIds) {
  const ids = [...xmlChannelIds];
  const exact = new Map(ids.map((id) => [normName(id), id]));
  const byLen = ids
    .map((id) => ({ id, n: normName(id) }))
    // 太短的 id 不能拿来做前缀匹配：节目源里有个脏 id 就叫 "C"，
    // 它会把所有 C 开头的频道（CGTN、CHC…）全吞掉。实测线上确实发生了一次。
    .filter((x) => x.n.length >= 3)
    .sort((a, b) => b.n.length - a.n.length);

  const map = new Map(); // stable_key -> xmltv id
  for (const ch of ourChannels) {
    const n = normName(ch.name);
    if (!n) continue;
    if (exact.has(n)) {
      map.set(dedupeKey(ch), exact.get(n));
      continue;
    }
    const p = byLen.find((x) => n.startsWith(x.n));
    if (p) map.set(dedupeKey(ch), p.id);
  }
  return map;
}

async function loadEpgState() {
  const file = process.env.EPG_STATE_FILE;
  if (!file) return 0;
  try {
    const raw = await readFile(file, "utf8");
    const a = raw.indexOf("[");
    const b = raw.lastIndexOf("]");
    if (a < 0 || b <= a) return 0;
    const rows = extractRows(JSON.parse(raw.slice(a, b + 1)));
    return rows.length ? Number(rows[0].v) || 0 : 0;
  } catch {
    return 0;
  }
}

function renderEpg(rows, matched, now) {
  const lines = ["-- 由 scripts/build.mjs 生成：节目单（整表替换）", "DELETE FROM epg_programs;"];

  const values = rows.map((r) =>
    [
      `(SELECT id FROM channels WHERE stable_key = ${sqlStr(r.key)})`,
      sqlStr(r.xmltv),
      sqlInt(r.start),
      sqlInt(r.stop),
      sqlStr(r.title),
      sqlStr(r.desc),
      sqlStr(r.cat),
    ].join(", "),
  );

  if (values.length) {
    for (let i = 0; i < values.length; i += INSERT_BATCH) {
      lines.push(
        "INSERT INTO epg_programs (channel_id, xmltv_id, start_at, end_at, title, description, category) VALUES\n  (" +
          values.slice(i, i + INSERT_BATCH).join("),\n  (") +
          ");",
      );
    }
  }

  // 把匹配结果写回频道，客户端和节目单页都要看这个
  for (const [key, xmltv] of matched) {
    lines.push(
      `UPDATE channels SET epg_id = ${sqlStr(xmltv)}, tvg_id = CASE WHEN tvg_id = '' THEN ${sqlStr(xmltv)} ELSE tvg_id END WHERE stable_key = ${sqlStr(key)};`,
    );
  }

  lines.push(
    `INSERT INTO kv (k, v) VALUES ('last_epg_at', ${sqlStr(String(now))}) ON CONFLICT(k) DO UPDATE SET v = excluded.v;`,
  );
  return lines.join("\n") + "\n";
}

async function buildEpg(channels, urls) {
  const now = Math.floor(Date.now() / 1000);
  const from = now - EPG_BACK_SEC;
  const to = now + EPG_FWD_SEC;

  const xmlIds = new Set();
  const programs = [];

  for (const u of urls) {
    // x-tvg-url 来自上游 m3u 文本，是**不可信输入**：不校验就等于让源决定
    // CI 去访问哪里。
    const badTarget = checkFetchTarget(u);
    if (badTarget) {
      console.warn(`[EPG] 跳过：${badTarget}`);
      continue;
    }
    try {
      const res = await fetchGuarded(u, {
        headers: { "User-Agent": UA, Accept: "*/*" },
        signal: AbortSignal.timeout(60_000),
      });
      if (!res.ok) {
        console.warn(`[EPG] HTTP ${res.status}`);
        continue;
      }
      const parsed = parseXmltv(decodeBody(new Uint8Array(await res.arrayBuffer())));
      for (const id of parsed.channels) xmlIds.add(id);
      programs.push(...parsed.programs);
      console.log(`[EPG] ${parsed.channels.size} 个频道 / ${parsed.programs.length} 条节目`);
    } catch (err) {
      console.warn(`[EPG] 拉取失败：${err.name}`);
    }
  }

  if (xmlIds.size === 0) {
    console.warn("[EPG] 没拿到任何节目单，跳过（保留线上已有的）");
    return null;
  }

  const matched = matchEpg(channels, xmlIds);
  console.log(`[EPG] 频道匹配 ${matched.size}/${channels.length}`);

  // xmltv id -> 我们的 stable_key，避免在下面循环里做 O(n²) 反查
  const keyOf = new Map();
  for (const [key, xmltv] of matched) if (!keyOf.has(xmltv)) keyOf.set(xmltv, key);

  const rows = [];
  for (const p of programs) {
    const key = keyOf.get(p.channel);
    if (!key) continue;
    if (p.start < from || p.start > to) continue;
    // 没有 stop 的用 +1 小时兜底
    const stop = p.stop > p.start ? p.stop : p.start + 3600;
    rows.push({
      key,
      xmltv: p.channel,
      start: p.start,
      stop,
      title: p.title,
      desc: p.desc,
      cat: p.cat,
    });
  }

  rows.sort((a, b) => a.start - b.start);
  console.log(`[EPG] 窗口内 ${rows.length} 条`);
  return { rows, matched, now };
}

// ---------- 主流程 ----------

/** 上一次成功重建的频道数（来自 D1 的 kv.last_build，CI 会 dump 成文件） */
/**
 * 从同一个文件里读两件事：上次重建的频道数（缩水护栏）、屏蔽清单。
 *
 * 屏蔽清单和 last_build 是同一张 kv 表里的两行，所以 CI 那边用**一条**
 * SELECT 一起查回来（省一次 D1 查询，免费额度是按查询/行算的）。
 */
async function loadBuildState() {
  const empty = { prevChannels: 0, prevSourceIds: undefined, blockedGroups: [], blockedChannels: [] };
  if (!BUILD_STATE_FILE) return empty;
  try {
    const raw = await readFile(BUILD_STATE_FILE, "utf8");
    const a = raw.indexOf("[");
    const b = raw.lastIndexOf("]");
    if (a < 0 || b <= a) return empty;
    const rows = extractRows(JSON.parse(raw.slice(a, b + 1)));
    const out = { ...empty };
    for (const r of rows) {
      if (r.k === "last_build") {
        try {
          const prev = JSON.parse(String(r.v || "{}"));
          out.prevChannels = Number(prev.channels_raw ?? prev.channels) || 0;
          // 上次参与重建的源 id 集合。用来区分「你换源了」和「源坏了」——
          // 两者都会让频道数变少，但只有后者该拦。旧记录没这个字段时为 undefined。
          if (typeof prev.source_ids === "string") out.prevSourceIds = prev.source_ids;
        } catch {
          /* 存坏了就当没有基线 */
        }
      } else if (r.k === "blocked") {
        try {
          const o = JSON.parse(String(r.v || "{}"));
          if (Array.isArray(o.groups)) out.blockedGroups = o.groups.map(String);
          if (Array.isArray(o.channels)) out.blockedChannels = o.channels.map(String);
        } catch {
          /* 同上：宁可少一道过滤，也不要让部署整体失败 */
        }
      }
    }
    return out;
  } catch {
    // 读不到就当没有基线：宁可少一道护栏，也不要因为格式变化让部署整体失败
    return empty;
  }
}

/**
 * 保证每份生成的 SQL 至少有一条语句。
 *
 * wrangler 对「只有注释」的文件会怎么处理没有承诺；一旦报错，
 * 后面的步骤（灌节目单、回写状态、secret、deploy）会连锁跳过，
 * 表现为工作流常年红而看不出原因。
 */
async function ensureNoopSql() {
  for (const name of ["seed.sql", "source-status.sql", "meta.sql", "epg.sql"]) {
    const p = `${SQL_DIR}/${name}`;
    let text;
    try {
      text = await readFile(p, "utf8");
    } catch {
      continue;
    }
    const hasStatement = text
      .split("\n")
      .some((line) => line.trim() !== "" && !line.trim().startsWith("--"));
    if (!hasStatement) await writeFile(p, `${text.trimEnd()}\n${SQL_NOOP}\n`, "utf8");
  }
}

/** 有并发上限的 map：结果顺序与输入一致 */
async function mapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    for (;;) {
      const i = next++;
      if (i >= items.length) return;
      out[i] = await fn(items[i], i);
    }
  });
  await Promise.all(workers);
  return out;
}

/**
 * 生成失败时的统一出口。
 *
 * 除了 stderr，还在 GitHub Actions 里发一条 `::error::` annotation：
 * 失败那一步后面的步骤会被全部跳过，而 Summary 只有点进运行页面才看得到。
 * annotation 会直接标在**步骤名旁边**，也能被 API 读出来
 * （`GET /repos/{o}/{r}/check-runs/{id}/annotations`）——排查时不用翻日志。
 *
 * 只输出第一行：多行会撑爆 annotation 的显示区域。
 */
function failBuild(msg) {
  console.error(msg);
  const first = String(msg).split("\n")[0].replace(/%/g, "%25").replace(/\r/g, "%0D").replace(/\n/g, "%0A");
  if (process.env.GITHUB_ACTIONS === "true") {
    console.error(`::error title=生成播放列表失败::${first}`);
  }
  process.exit(1);
}

async function main() {
  const sources = await loadSources();

  if (sources.length === 0) {
    // 首次部署时还没人配源，这是正常流程：先把 Worker 发上去，
    // 管理台能打开了再去「源」里添加。所以这里不算失败。
    console.log("没有启用的订阅源，跳过数据更新（管理台 → 源 里添加后重跑）。");
    await mkdir(SQL_DIR, { recursive: true });
    await writeFile(`${SQL_DIR}/seed.sql`, "-- 没有启用的订阅源，本次不更新频道数据\n", "utf8");
    await writeFile(`${SQL_DIR}/source-status.sql`, "", "utf8");
    await writeFile(`${SQL_DIR}/meta.sql`, "", "utf8");
    await writeFile(`${SQL_DIR}/epg.sql`, "", "utf8");
    return;
  }

  console.log(`共 ${sources.length} 个源`);

  const all = [];
  const statuses = [];
  // 并发抓：源多的时候串行会把这些等待时间加起来（每个源都要走一次
  // TCP+TLS+下载），重建本来就一小时一次，没必要再白白慢几十秒。
  // 限到 4 是为了不把上游面板打到限流。
  const fetched = await mapLimit(sources, FETCH_CONCURRENCY, (src, i) => fetchOne(src, i + 1));
  fetched.forEach((r, i) => {
    all.push(...r.entries);
    statuses.push({ id: sources[i].id, status: r.status, count: r.entries.length });
  });

  if (all.length === 0) {
    failBuild("所有源都没解析出条目，拒绝生成空播放列表（线上保留上一次的数据）");
  }

  // 屏蔽清单在生成期就生效：这些频道**根本不入库**，也不进 playlist.m3u。
  // （Worker 侧还有一层同规则的过滤，点完立刻生效、不用等这一次重建。）
  const state0 = await loadBuildState();
  const rawCount = all.length;
  const blockedG = new Set(state0.blockedGroups);
  const blockedC = new Set(state0.blockedChannels);
  const keptEntries = all.filter(
    (e) => !blockedG.has(e.group || "未分组") && !blockedC.has(e.name),
  );
  if (keptEntries.length !== all.length) {
    console.log(`屏蔽规则挡掉 ${all.length - keptEntries.length} 条（分组 ${blockedG.size} / 频道 ${blockedC.size}）`);
  }
  all.length = 0;
  all.push(...keptEntries);

  // 按 key 合并：首次出现的当主线路，后面的都并成备选线路
  const byKey = new Map();
  for (const e of all) {
    const key = dedupeKey(e);
    const hit = byKey.get(key);
    if (hit) {
      hit.lines.push({ url: e.url, ua: e.ua, headers: e.headers });
      continue;
    }
    byKey.set(key, {
      ...e,
      group: e.group || "未分组",
      lines: [{ url: e.url, ua: e.ua, headers: e.headers }],
    });
  }

  const channels = [...byKey.values()];

  if (channels.length < MIN_CHANNELS) {
    failBuild(`去重后只有 ${channels.length} 个频道，低于 MIN_CHANNELS=${MIN_CHANNELS}`);
  }

  // 缩水护栏。MIN_CHANNELS 只挡得住「全空」，挡不住「500 个变 3 个」：
  // 那种半失败（单个源返回了残缺内容）一旦成功写入，线上每小时都是残的。
  //
  // 比的是**屏蔽之前**的条目数：用户主动屏蔽一整类时频道数会掉，
  // 那不是故障，不该被护栏拦下来。
  //
  // 但「数字变小」本身不足以判定故障——**换源/删源**也会让数字变小，
  // 那是正常操作。所以先看源配置有没有变：
  //   · 源集合变了（增删源）      → 预期内的变化，放行
  //   · 源集合没变但有源拉取失败  → 这才是故障信号，拦
  //   · 源集合没变且全部成功却暴跌 → 源端内容异常，拦
  // 旧版本写的 last_build 没有 source_ids，无从对比 → 放行并记一条日志，
  // 免得升级后第一次重建就被自己拦住。
  const prevChannels = state0.prevChannels;
  const floor = Math.floor(prevChannels * MIN_KEEP_RATIO);
  const curSourceIds = statuses
    .map((s) => s.id)
    .filter((id) => id > 0)
    .sort((a, b) => a - b)
    .join(",");
  const prevSourceIds = state0.prevSourceIds;
  // 三种「不能怪源」的情况，都放行：
  //   1. 上次记录里根本没有源集合（旧版本写的）——升级后第一次，无从判断
  //   2. 源集合真的变了（增删源、换订阅）——正常操作
  // 其余（含两边都走 SOURCE_URLS 回退模式、集合相同）按故障处理。
  const prevRecordedSet = prevSourceIds !== undefined;
  const sourcesChanged = prevRecordedSet && prevSourceIds !== curSourceIds;
  const anySourceFailed = statuses.some((s) => s.status !== "ok" && s.status !== "empty_list");
  const shrinking = prevChannels > 0 && rawCount < floor;

  if (shrinking && !prevRecordedSet) {
    console.log(
      `频道数 ${prevChannels} → ${rawCount}；上次记录里没有源集合（旧版本），` +
        `无从判断是否换过源，本次放行（下次起会对比）。`,
    );
  } else if (shrinking && sourcesChanged && !anySourceFailed) {
    console.log(
      `频道数 ${prevChannels} → ${rawCount}，但源集合变了（[${prevSourceIds}] → [${curSourceIds}]），` +
        `按正常变更处理，不拦。`,
    );
  } else if (shrinking && !ALLOW_SHRINK) {
    // annotation 只显示第一行，所以把「怎么办」也说进第一行
    const why = anySourceFailed
      ? `有源拉取失败（${statuses.filter((s) => s.status !== "ok" && s.status !== "empty_list").map((s) => `源${s.id}:${s.status}`).join(" ")}）`
      : "源配置没变，但条目数暴跌（源端内容异常？）";
    failBuild(
      `频道数从 ${prevChannels} 降到 ${rawCount}（低于 ${Math.round(MIN_KEEP_RATIO * 100)}% 底线）：${why}，` +
        `拒绝覆盖线上数据。确认要保留这份结果就去 Settings → Variables 加 ALLOW_SHRINK=1 再重跑。`,
    );
  }

  await mkdir(OUT_DIR, { recursive: true });
  await mkdir(SQL_DIR, { recursive: true });

  await writeFile(
    `${OUT_DIR}/playlist.m3u`,
    renderM3U(channels.map((ch) => ({ ...ch, line: ch.lines[0] }))),
    "utf8",
  );
  const totalLines = channels.reduce((n, c) => n + c.lines.length, 0);

  await writeFile(`${SQL_DIR}/seed.sql`, renderSeed(channels), "utf8");
  await writeFile(`${SQL_DIR}/source-status.sql`, renderSourceStatus(statuses), "utf8");
  await writeFile(
    `${SQL_DIR}/meta.sql`,
    renderMeta(channels, sources.length, totalLines, rawCount, curSourceIds, [...epgUrls][0] || ""),
    "utf8",
  );

  // 节目单：至少隔 6 小时刷一次。D1 免费版每天 10 万行写入，
  // 小时级重建再叠全量节目单会打爆，所以这里单独限速。
  const epgLast = await loadEpgState();
  const nowTs = Math.floor(Date.now() / 1000);

  // 节目单**默认不进 D1**。
  //
  // 一份全量节目单在 D1 里是几千行写入，而 D1 免费额度按行算、属于部署者自己。
  // 而且节目单本质上是「客户端要看的参考数据」，不是「服务端要算的状态」——
  // 最省的做法是把 xmltv 地址直接下发给客户端，让客户端自己下载解析、本地缓存：
  //   · 服务端 0 行写入、0 行读取
  //   · 客户端拿到的永远是最新数据，不用等重建
  //   · 缓存放在手机上，重装才重新下
  //
  // 设 LITV_EPG_IN_D1=1 可以恢复旧行为（服务端灌库）。
  // 保留它是因为有些客户端还没实现 xmltv 解析，过渡期要能兜底。
  const epgInD1 = process.env.LITV_EPG_IN_D1 === "1";

  if (!epgInD1) {
    console.log(`[EPG] 不灌库（客户端自己解析），下发地址：${[...epgUrls].join(", ") || "(源里没有)"}`);
    await writeFile(`${SQL_DIR}/epg.sql`, "-- 节目单不进 D1：地址随 bootstrap 下发给客户端\n", "utf8");
  } else if (nowTs - epgLast < EPG_MIN_INTERVAL_SEC) {
    const hours = Math.round((nowTs - epgLast) / 3600);
    console.log(`[EPG] 上次 ${hours} 小时前刷过，本次跳过`);
    await writeFile(`${SQL_DIR}/epg.sql`, "-- 本次不刷新节目单\n", "utf8");
  } else if (epgUrls.size === 0) {
    console.log("[EPG] 源里没有 x-tvg-url，跳过");
    await writeFile(`${SQL_DIR}/epg.sql`, "-- 源里没有节目单地址\n", "utf8");
  } else {
    const built = await buildEpg(channels, [...epgUrls]);
    await writeFile(
      `${SQL_DIR}/epg.sql`,
      built ? renderEpg(built.rows, built.matched, built.now) : "-- 本次没拿到节目单，保留线上数据\n",
      "utf8",
    );
  }

  const withHeaders = channels.filter((c) => c.lines.some((l) => l.ua || Object.keys(l.headers).length));
  const groups = new Map();
  for (const ch of channels) groups.set(ch.group, (groups.get(ch.group) || 0) + 1);

  console.log(`条目 ${all.length} → ${channels.length} 个频道 / ${totalLines} 条线路`);
  console.log(`带 UA 或 Referer 的频道 ${withHeaders.length} 个`);
  console.log(`分组 ${groups.size} 个`);
  for (const [g, n] of [...groups.entries()].sort((a, b) => b[1] - a[1]).slice(0, 10)) {
    console.log(`  ${g}: ${n}`);
  }
}

main()
  .then(ensureNoopSql)
  .catch((err) => {
    // 未捕获异常也走 failBuild：否则这类失败只有 stderr 里有痕迹，
    // annotation / summary 都抓不到。
    failBuild(`生成失败：${err?.message || err}`);
  });
