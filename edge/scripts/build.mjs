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

const UA = process.env.SOURCE_UA || "VLC/3.0.20 LibVLC/3.0.20";
const OUT_DIR = "public";
const SQL_DIR = "sql";
const MIN_CHANNELS = Number(process.env.MIN_CHANNELS || "1");
const FETCH_TIMEOUT_MS = 30_000;
const INSERT_BATCH = 50;

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
        console.error(
          `SOURCES_FILE（${file}）不是可解析的 JSON，拒绝继续。` +
            `前 120 字符：${redact(raw.trimStart())}`,
        );
        process.exit(1);
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

  try {
    const res = await fetch(src.url, {
      headers,
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      redirect: "follow",
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
    if (src.ua || src.referer) {
      for (const e of entries) {
        if (!e.ua) e.ua = src.ua;
        if (src.referer && !e.headers["Referer"]) e.headers["Referer"] = src.referer;
      }
    }

    return { entries, status: entries.length > 0 ? "ok" : "empty_list" };
  } catch (err) {
    // 只报错误类型，不带 URL
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
    "-- 1. 只清订阅生成的数据，管理台手工加的频道和线路留着",
    "DELETE FROM streams WHERE origin = 'm3u';",
    "DELETE FROM channels WHERE origin = 'm3u';",
    "",
    "-- 2. 插入本次订阅解析出的频道（id 自增，跨重建不稳定，所以外部一律用 stable_key）",
    batchInsert(
      "channels",
      ["stable_key", "number", "name", '"group"', "logo", "tvg_id", "epg_id", "enabled", "sort", "origin"],
      chRows,
    ),
    "",
    "-- 3. 手工线路重新挂到新频道 id 上（频道没了就跳过，不能挂成 NULL）",
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
function renderMeta(channels, sourceCount, lineCount) {
  const info = {
    at: Math.floor(Date.now() / 1000),
    sources: sourceCount,
    channels: channels.length,
    lines: lineCount,
  };
  return [
    "-- 由 scripts/build.mjs 生成：记录本次重建结果",
    `INSERT INTO kv (k, v) VALUES ('last_build', ${sqlStr(JSON.stringify(info))})`,
    "ON CONFLICT(k) DO UPDATE SET v = excluded.v;",
    "",
  ].join("\n");
}

// ---------- 节目单 ----------

// 往前留 6 小时（正在播的节目要有），往后 3 天。
const EPG_BACK_SEC = 6 * 3600;
const EPG_FWD_SEC = 72 * 3600;
// 至少隔 6 小时才刷一次：D1 免费版每天只给 10 万行写入，
// 小时级重建 + 全量节目单会直接打爆。
const EPG_MIN_INTERVAL_SEC = 6 * 3600;

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
    try {
      const res = await fetch(u, {
        headers: { "User-Agent": UA, Accept: "*/*" },
        signal: AbortSignal.timeout(60_000),
        redirect: "follow",
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
  for (let i = 0; i < sources.length; i++) {
    const r = await fetchOne(sources[i], i + 1);
    all.push(...r.entries);
    statuses.push({ id: sources[i].id, status: r.status, count: r.entries.length });
  }

  if (all.length === 0) {
    console.error("所有源都没解析出条目，拒绝生成空播放列表（线上保留上一次的数据）");
    process.exit(1);
  }

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
    console.error(`去重后只有 ${channels.length} 个频道，低于 MIN_CHANNELS=${MIN_CHANNELS}`);
    process.exit(1);
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
  await writeFile(`${SQL_DIR}/meta.sql`, renderMeta(channels, sources.length, totalLines), "utf8");

  // 节目单：至少隔 6 小时刷一次。D1 免费版每天 10 万行写入，
  // 小时级重建再叠全量节目单会打爆，所以这里单独限速。
  const epgLast = await loadEpgState();
  const nowTs = Math.floor(Date.now() / 1000);

  if (nowTs - epgLast < EPG_MIN_INTERVAL_SEC) {
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

main().catch((err) => {
  console.error(`生成失败：${err.message}`);
  process.exit(1);
});
