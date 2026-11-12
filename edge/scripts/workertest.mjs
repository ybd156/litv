// 自测（第二部分）：直接跑 `src/index.ts` 的 fetch handler。
//
// 为什么要单独一套：
//   `selftest.mjs` 只测 build.mjs（抓取/去重/生成 SQL），从来没执行过 Worker 本体。
//   而限速、平台校验、口令优先级、注销这些都在 Worker 里，出错时
//   selftest 全绿也没用。
//
// 怎么做到不依赖 Cloudflare：
//   Node 24 能直接 import .ts（类型擦除），`Request` / `Response` / `crypto.subtle`
//   都是标准全局对象；D1 用一个基于 node:sqlite 的薄封装代替。
//   SQL 会真的执行，所以 `INSERT ... ON CONFLICT ... RETURNING` 这类
//   只在真库上才会暴露的问题也能覆盖。
import { DatabaseSync } from "node:sqlite";
import { readFile } from "node:fs/promises";
import worker from "../src/index.ts";
import { checkFetchTarget } from "../src/net.ts";

const schema = await readFile(new URL("../schema.sql", import.meta.url), "utf8");
const db = new DatabaseSync(":memory:");
db.exec(schema);

/** 最小 D1 兼容层：只实现 Worker 用到的 prepare/bind/first/run/all。 */
class Statement {
  constructor(sql) {
    this.sql = sql;
    this.args = [];
  }

  bind(...args) {
    this.args = args.map((v) => (typeof v === "boolean" ? Number(v) : v));
    return this;
  }

  async first() {
    return db.prepare(this.sql).get(...this.args) ?? null;
  }

  async all() {
    return { results: db.prepare(this.sql).all(...this.args), meta: { changes: 0 } };
  }

  async run() {
    // meta.last_row_id / changes 必须给：Worker 里有 6 处靠它们取新 id，
    // 以前这个替身返回 {}，于是「注册返回 device_id」这类链路等于从没执行过。
    const info = db.prepare(this.sql).run(...this.args);
    return {
      success: true,
      meta: { last_row_id: Number(info.lastInsertRowid), changes: Number(info.changes) },
    };
  }
}

const DB = {
  prepare: (sql) => new Statement(sql),
  // D1 的 batch 是**事务**语义（要么全成要么全不成）。替身也必须这样，
  // 否则「批量操作改到一半失败」的行为在测试里永远看不出来。
  async batch(stmts) {
    const out = [];
    db.exec("BEGIN");
    try {
      for (const s of stmts) out.push(await s.run());
      db.exec("COMMIT");
    } catch (err) {
      db.exec("ROLLBACK");
      throw err;
    }
    return out;
  },
};

const PLAYLIST_BODY = "#EXTM3U\n#EXTINF:-1 group-title=\"t\",Fake\nhttp://up.example/x.ts\n";

const env = {
  DB,
  ASSETS: {
    fetch: async () =>
      new Response(PLAYLIST_BODY, {
        status: 200,
        headers: { "content-type": "application/vnd.apple.mpegurl" },
      }),
  },
  BIND_CODE: "123456",
  ADMIN_PASSWORD: "testpass123",
  SERVER_NAME: "LITV Edge",
};

const checks = [];
const check = (name, ok, detail = "") => {
  checks.push([name, !!ok, detail]);
};

function post(path, body, ip) {
  return call("POST", path, body, ip);
}

async function call(method, path, body, ip, token, envOverride) {
  const headers = { "cf-connecting-ip": ip };
  if (body !== undefined) headers["content-type"] = "application/json";
  if (token) headers.authorization = `Bearer ${token}`;
  const req = new Request("http://edge.local" + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const res = await worker.fetch(req, envOverride ?? env);
  const text = await res.text();
  let parsed = null;
  try {
    parsed = JSON.parse(text);
  } catch {
    /* 播放列表不是 JSON */
  }
  return { status: res.status, headers: res.headers, text, body: parsed };
}

const reg = (code, key, platform, ip) =>
  post("/api/v1/device/register", { code, device_key: key, platform }, ip);

// ---------------------------------------------------------------- 设备绑定与限速
const A = "203.0.113.10";
let firstTen = [];
for (let i = 0; i < 10; i++) firstTen.push((await reg("000000", `a-${i}`, "androidtv", A)).body.code);
check("绑定码错误前 10 次都是 2001", firstTen.every((c) => c === 2001), JSON.stringify(firstTen));
check("第 11 次被限速 2005", (await reg("000000", "a-11", "androidtv", A)).body.code === 2005);
check("限速后正确码也被挡", (await reg("123456", "a-ok", "androidtv", A)).body.code === 2005);

// 桶名带前缀（bind: / admin: / playlist:），所以断言要按前缀查；
// 共用一个裸 IP 桶时，电视输错 10 次会连带锁住管理台登录。
const rowA = db.prepare("SELECT fails FROM bind_attempts WHERE ip = ?").get(`bind:${A}`);
check("被挡的请求也计入失败数", rowA && rowA.fails >= 11, JSON.stringify(rowA));

// 窗口是 600 秒：把 window_start 往前拨 601 秒，应当立刻恢复
db.prepare("UPDATE bind_attempts SET window_start = window_start - 601 WHERE ip = ?").run(`bind:${A}`);
const reset = await reg("123456", "a-ok", "androidtv", A);
check("窗口过后正确码可以绑定", reset.body.code === 0, JSON.stringify(reset.body));
check("绑定成功后该 IP 计数被清除", !db.prepare("SELECT 1 FROM bind_attempts WHERE ip = ?").get(`bind:${A}`));
check("成功绑定会换发 token", typeof reset.body?.data?.token === "string" && reset.body.data.token.length === 64);

// ---------------------------------------------------------------- 平台冲突
const B = "203.0.113.11";
check("同 device_key 换平台被拒", (await reg("123456", "a-ok", "ios", B)).body.code === 2002);
check("同 device_key 同平台可以重新绑定", (await reg("123456", "a-ok", "androidtv", B)).body.code === 0);

// 历史行是混合大小写时不该误判冲突
db.prepare(
  "INSERT INTO devices (device_key, token_hash, platform, name, created_at, last_seen) VALUES (?,?,?,?,?,?)",
).run("legacy-key", "deadbeef", "iOS", "", 0, 0);
const legacy = await reg("123456", "legacy-key", "ios", B);
check("历史 platform=iOS 与 ios 不冲突", legacy.body.code === 0, JSON.stringify(legacy.body));
check(
  "冲突判断通过后顺手迁移成小写",
  db.prepare("SELECT platform FROM devices WHERE device_key = ?").get("legacy-key")?.platform === "ios",
);

// ---------------------------------------------------------------- 平台取值
for (const [raw, label] of [
  ["admin", "admin"],
  ["ADMIN", "ADMIN"],
  [" Admin ", "带空格的 Admin"],
]) {
  const r = await reg("123456", `reserved-${label}`, raw, B);
  check(`保留值 platform=${label} 被拒`, r.body.code === 2002, JSON.stringify(r.body));
}

const empty = await reg("123456", "empty-plat", "", B);
check("platform 为空时落成 android", empty.body.code === 0);
check(
  "空 platform 实际存为 android",
  db.prepare("SELECT platform FROM devices WHERE device_key = ?").get("empty-plat")?.platform === "android",
);

// 与 Go 端对齐：平台名不参与鉴权，不做字符集白名单
const spaced = await reg("123456", "spaced-plat", "Android TV", B);
check("带空格的 platform 不再被拒（与 Go 对齐）", spaced.body.code === 0, JSON.stringify(spaced.body));
check(
  "带空格的 platform 原样小写落库",
  db.prepare("SELECT platform FROM devices WHERE device_key = ?").get("spaced-plat")?.platform === "android tv",
);

const ctrl = await reg("123456", "ctrl-plat", "and\u0000roid", B);
check("控制字符被剔除后仍可绑定", ctrl.body.code === 0);
check(
  "控制字符没有落库",
  db.prepare("SELECT platform FROM devices WHERE device_key = ?").get("ctrl-plat")?.platform === "android",
);

// ---------------------------------------------------------------- 管理台会话
const C = "203.0.113.20";
check("配了 ADMIN_PASSWORD 后 BIND_CODE 不能登录", (await post("/api/v1/auth/login", { password: "123456" }, C)).body.code === 2001);
const login = await post("/api/v1/auth/login", { password: "testpass123" }, C);
const token = login.body?.data?.token;
check("ADMIN_PASSWORD 可以登录", login.body.code === 0 && typeof token === "string");
// 未认证必须是 2001 + 401：管理台只把这个组合当成「会话失效」，
// 回 2003（设备语义）会让控制台停在死页面上，连登录入口都没有。
const noSess = await call("GET", "/api/v1/admin/stats", undefined, C);
check("无会话访问管理接口被拒", noSess.body.code === 2001, JSON.stringify(noSess.body));
check("未认证同时回 HTTP 401", noSess.status === 401, String(noSess.status));
check("登录后可以访问管理接口", (await call("GET", "/api/v1/admin/stats", undefined, C, token)).body.code === 0);

const logout = await call("POST", "/api/v1/auth/logout", undefined, C, token);
check("注销成功", logout.body.code === 0);
check("注销后 token 立刻失效", (await call("GET", "/api/v1/admin/stats", undefined, C, token)).body.code === 2001);
check("重复注销仍然幂等成功", (await call("POST", "/api/v1/auth/logout", undefined, C, token)).body.code === 0);
check("无 token 注销不报错", (await call("POST", "/api/v1/auth/logout", undefined, C)).body.code === 0);

// ---------------------------------------------------------------- 设备端主链路
// 以前整套测试里没有任何一条「拿设备 token 打 /tv/*」的用例，
// 于是把 token 改存明文、或者删掉启动链路，测试照样全绿。
const G = "203.0.113.40";
const bind = await reg("123456", "e2e-key", "androidtv", G);
const devToken = bind.body?.data?.token;
check("注册返回 64 位 token", typeof devToken === "string" && devToken.length === 64);
check("注册返回真实 device_id（不是 null）", typeof bind.body?.data?.device_id === "number" && bind.body.data.device_id > 0);

const storedHash = db.prepare("SELECT token_hash FROM devices WHERE device_key = ?").get("e2e-key")?.token_hash;
check(
  "服务端只存 token 的 SHA-256，不存明文",
  !!storedHash && storedHash !== devToken && /^[0-9a-f]{64}$/.test(storedHash),
);

const boot = await call("GET", "/api/v1/tv/bootstrap", undefined, G, devToken);
check("带设备 token 能拿到 bootstrap", boot.body?.code === 0, JSON.stringify(boot.body).slice(0, 160));
check(
  "bootstrap 带 channels_version（客户端靠它判断要不要重拉列表）",
  typeof boot.body?.data?.channels_version === "string",
  JSON.stringify(boot.body?.data?.channels_version),
);

const hb = await call("GET", "/api/v1/tv/heartbeat", undefined, G, devToken);
check("心跳也带 channels_version", typeof hb.body?.data?.channels_version === "string");

const noTok = await call("GET", "/api/v1/tv/bootstrap", undefined, G);
check("没有设备 token 拿不到 bootstrap", noTok.body?.code === 2003, JSON.stringify(noTok.body));

// ---------------------------------------------------------------- 错误码语义
// 参数错必须是 1001。用 2001 的话管理台会当成「会话失效」，
// 用户填错一个源地址就被整页踢到未授权页。
const H = "203.0.113.41";
const adminTok = (await post("/api/v1/auth/login", { password: "testpass123" }, H)).body?.data?.token;
const badSrc = await call("POST", "/api/v1/sources", { url: "ftp://nope" }, H, adminTok);
check("参数错误码是 1001 而不是 2001", badSrc.body.code === 1001, JSON.stringify(badSrc.body));
const badJson = await call("POST", "/api/v1/sources", "not-an-object", H, adminTok);
check("请求体不合法也是 1001", badJson.body.code === 1001, JSON.stringify(badJson.body));

// ---------------------------------------------------------------- 限速桶隔离
// 电视输错 10 次绑定码，不该把同一出口 IP 的管理台登录一起锁掉。
const I = "203.0.113.42";
for (let i = 0; i < 10; i++) await reg("000000", `i-${i}`, "androidtv", I);
check("绑定码被限速后管理台登录仍然可用", (await post("/api/v1/auth/login", { password: "testpass123" }, I)).body.code === 0);

// ---------------------------------------------------------------- 会话过期
db.prepare("UPDATE admin_sessions SET expires_at = 0").run();
const expired = await call("GET", "/api/v1/admin/stats", undefined, C, token);
check(
  "会话过期后必须重新登录（2001 + 401）",
  expired.body.code === 2001 && expired.status === 401,
  `${expired.body.code}/${expired.status}`,
);

// ---------------------------------------------------------------- playlist
const D = "203.0.113.30";
let wrongOk = true;
for (let i = 0; i < 5; i++) {
  const r = await call("GET", "/playlist.m3u?t=000000", undefined, D);
  if (r.status !== 404) wrongOk = false;
}
check("playlist 错 t 一律 404", wrongOk);

const E = "203.0.113.31";
const good = await call("GET", "/playlist.m3u?t=123456", undefined, E);
check("playlist 正确 t 返回内容", good.status === 200 && good.text.startsWith("#EXTM3U"));
check("playlist 不带缓存", good.headers.get("cache-control") === "no-store");
check(
  "playlist 成功后计数被清除",
  !db.prepare("SELECT 1 FROM bind_attempts WHERE ip = ?").get(`playlist:${E}`),
);

// 20 次错误之后开始限速；此时正确 t 也被挡
const F = "203.0.113.32";
for (let i = 0; i < 20; i++) await call("GET", "/playlist.m3u?t=000000", undefined, F);
const blocked = await call("GET", "/playlist.m3u?t=123456", undefined, F);
check("playlist 超限后正确 t 也 404", blocked.status === 404);
check(
  "被挡的请求不再累加计数（挡住之后就不写了）",
  db.prepare("SELECT fails FROM bind_attempts WHERE ip = ?").get(`playlist:${F}`)?.fails === 20,
);

// 性能回归：正常请求**不写限速表**。
// 播放列表是客户端自动轮询的，以前每次都要 INSERT 一行再 DELETE 一行，
// D1 免费版的写入额度按行算，第三方播放器几分钟一次就能吃掉一大截。
const P = "203.0.113.33";
const perfOk = await call("GET", "/playlist.m3u?t=123456", undefined, P);
check("正常拉取播放列表", perfOk.status === 200);
check(
  "正常拉取不写限速表（0 行写入）",
  !db.prepare("SELECT 1 FROM bind_attempts WHERE ip = ?").get(`playlist:${P}`),
);

// 猜错一次要留痕，猜对之后要清掉：既限速又不误伤
const Q = "203.0.113.34";
await call("GET", "/playlist.m3u?t=000000", undefined, Q);
check(
  "猜错会记账",
  db.prepare("SELECT fails FROM bind_attempts WHERE ip = ?").get(`playlist:${Q}`)?.fails === 1,
);
await call("GET", "/playlist.m3u?t=123456", undefined, Q);
check(
  "猜对之后计数被清掉",
  !db.prepare("SELECT 1 FROM bind_attempts WHERE ip = ?").get(`playlist:${Q}`),
);

// 窗口是 60 秒（不是绑定码那套 600 秒）：拨 61 秒应当立刻恢复
db.prepare("UPDATE bind_attempts SET window_start = window_start - 61 WHERE ip = ?").run(`playlist:${F}`);
const recovered = await call("GET", "/playlist.m3u?t=123456", undefined, F);
check("playlist 窗口是 60 秒（61 秒后恢复）", recovered.status === 200, String(recovered.status));

// ---------------------------------------------------------------- 客户端主接口：收藏 / 续播 / 列表 / 播放
//
// 这一整块以前没有任何用例：把收藏改成永远返回 ok、把续播表删掉，
// 测试照样全绿。现在它们真的读写 D1（替身）。

db.prepare(
  `INSERT INTO channels (stable_key, number, name, "group", origin) VALUES ('id:t1', 1, '测试台', '测试', 'm3u')`,
).run();
const tid = db.prepare("SELECT id FROM channels WHERE stable_key = 'id:t1'").get().id;
db.prepare(
  `INSERT INTO streams (channel_id, channel_key, label, url, ua, headers, origin)
   VALUES (?,?,?,?,'','{"Referer":"http://r.example/"}','m3u')`,
).run(tid, "id:t1", "线路 1", "http://up.example/1.m3u8");
db.prepare(
  `INSERT INTO channels (stable_key, number, name, "group", origin) VALUES ('id:t2', 2, '空台', '测试', 'm3u')`,
).run();
const emptyId = db.prepare("SELECT id FROM channels WHERE stable_key = 'id:t2'").get().id;

const J = "203.0.113.50";
const devJ = await reg("123456", "dev-j", "androidtv", J);
const tokJ = devJ.body?.data?.token;

const listRes = await call("GET", "/api/v1/tv/channels?limit=10", undefined, J, tokJ);
check("频道列表接口可用", listRes.body?.code === 0 && listRes.body.data.total === 2, JSON.stringify(listRes.body));
check(
  "频道列表项形状与 bootstrap 一致",
  !!listRes.body?.data?.items?.[0] && "stream_count" in listRes.body.data.items[0],
);

const playRes = await call("GET", `/api/v1/tv/play/${tid}`, undefined, J, tokJ);
check("播放返回直连地址", playRes.body?.data?.mode === "direct" && playRes.body.data.url === "http://up.example/1.m3u8");
check("播放下发 Referer（源站要校验）", playRes.body?.data?.headers?.Referer === "http://r.example/");
check("kind 按 .m3u8 判成 hls", playRes.body?.data?.kind === "hls");

// UA 是播放时属性：改源上的 UA 应该**立刻生效**，不用重建整份频道表。
// 单源部署时 play 直接读 sources.ua；多源时回落到 build 写进 streams 的快照
// （那时每条线路来自哪个源是确定的）。
{
  // 这里 sources 表还是空的（管理台的源是后面才插的），先造一个单源场景
  db.prepare(
    "INSERT OR REPLACE INTO sources (id, name, url, ua, referer, enabled, sort, created_at) VALUES (1,'单源','http://panel.example/x.m3u','qqlive','',1,0,0)",
  ).run();
  const p2 = await call("GET", `/api/v1/tv/play/${tid}`, undefined, J, tokJ);
  check(
    "单源：改源 UA 立刻生效（不用重建）",
    p2.body?.data?.ua === "qqlive",
    JSON.stringify(p2.body?.data?.ua),
  );
  check(
    "单源：headers 里的 User-Agent 也跟着变",
    p2.body?.data?.headers?.["User-Agent"] === "qqlive",
    JSON.stringify(p2.body?.data?.headers),
  );

  // 再加一个配了 UA 的源 → 多源，回落到 streams.ua（这条是空）
  db.prepare(
    "INSERT INTO sources (id, name, url, ua, enabled, sort, created_at) VALUES (2,'第二个源','http://b.example/x.m3u','ua-from-b',1,1,0)",
  ).run();
  const p3 = await call("GET", `/api/v1/tv/play/${tid}`, undefined, J, tokJ);
  check(
    "多源：回落到 build 快照，不瞎猜是哪个源的 UA",
    p3.body?.data?.ua === "",
    JSON.stringify(p3.body?.data?.ua),
  );

  // 收尾：清掉，后面的用例还要自己插源
  db.prepare("DELETE FROM sources").run();
}

// 没有线路要 3002（客户端有专门文案），不是笼统的 2004
const noStream = await call("GET", `/api/v1/tv/play/${emptyId}`, undefined, J, tokJ);
check("无可用线路回 3002", noStream.body?.code === 3002, JSON.stringify(noStream.body));

const streamsRes = await call("GET", `/api/v1/tv/streams/${tid}`, undefined, J, tokJ);
check("线路列表可用", Array.isArray(streamsRes.body?.data) && streamsRes.body.data.length === 1);
check("线路列表不泄漏上游地址", !JSON.stringify(streamsRes.body).includes("up.example"));

const epgRes = await call("GET", `/api/v1/tv/epg/${tid}?day=0`, undefined, J, tokJ);
check("未关联 EPG 时返回 note 而不是报错", epgRes.body?.code === 0 && epgRes.body.data.note === "该频道未关联 EPG");
check("epg 的 channel 带 number/group", epgRes.body?.data?.channel?.number === 1);

const epg404 = await call("GET", "/api/v1/tv/epg/999999?day=0", undefined, J, tokJ);
check("不存在的频道回 3001", epg404.body?.code === 3001, JSON.stringify(epg404.body));

// 收藏
check("收藏成功", (await call("POST", "/api/v1/tv/favorite", { channel_id: tid, on: true }, J, tokJ)).body.code === 0);
check(
  "收藏真的落库",
  db.prepare("SELECT 1 FROM favorites WHERE device_id = ? AND channel_id = ?").get(devJ.body.data.device_id, tid) !== undefined,
);
check("重复收藏不报错也不重复", (await call("POST", "/api/v1/tv/favorite", { channel_id: tid, on: true }, J, tokJ)).body.code === 0);
check(
  "收藏不会重复插入",
  db.prepare("SELECT COUNT(*) AS n FROM favorites WHERE channel_id = ?").get(tid).n === 1,
);
check("取消收藏", (await call("POST", "/api/v1/tv/favorite", { channel_id: tid, on: false }, J, tokJ)).body.code === 0);
check(
  "取消后真的删掉",
  db.prepare("SELECT COUNT(*) AS n FROM favorites WHERE channel_id = ?").get(tid).n === 0,
);
check("收藏缺 channel_id 回 1001", (await call("POST", "/api/v1/tv/favorite", { on: true }, J, tokJ)).body.code === 1001);

// 续播：只在**别的设备**的记录里找
const K = "203.0.113.51";
const devK = await reg("123456", "dev-k", "androidtv", K);
const tokK = devK.body?.data?.token;
check("续播上报成功", (await call("POST", "/api/v1/tv/progress", { channel_id: tid, position_sec: 12 }, K, tokK)).body.code === 0);
check(
  "上报落库",
  db.prepare("SELECT position_sec FROM watch_progress WHERE device_id = ?").get(devK.body.data.device_id)?.position_sec === 12,
);

const resumeOther = await call("GET", "/api/v1/tv/resume", undefined, J, tokJ);
check(
  "另一台设备能拿到续播点",
  resumeOther.body?.data?.available === true && resumeOther.body.data.channel_id === tid,
  JSON.stringify(resumeOther.body),
);
check("边缘版续播标记没有时移", resumeOther.body?.data?.timeshift_available === 0);

const resumeSelf = await call("GET", "/api/v1/tv/resume", undefined, K, tokK);
check("自己看过的台不当成续播点", resumeSelf.body?.data?.available === false, JSON.stringify(resumeSelf.body));

// 心跳：带 app_version 要落到设备上，并带回 config_version
const hb2 = await call("POST", "/api/v1/tv/heartbeat", { playing_channel: tid, state: "playing", app_version: "2.3.4" }, J, tokJ);
check("心跳返回 config_version", typeof hb2.body?.data?.config_version === "number");
check(
  "心跳把 app_version 记下来",
  db.prepare("SELECT app_version FROM device_state WHERE device_id = ?").get(devJ.body.data.device_id)?.app_version === "2.3.4",
);

// 管理台下发配置 → 客户端下一次 bootstrap 生效
const adminTok2 = (await post("/api/v1/auth/login", { password: "testpass123" }, "203.0.113.52")).body?.data?.token;
const cfgPut = await call(
  "PUT",
  `/api/v1/devices/${devJ.body.data.device_id}/config`,
  { autoplay: false, buffer_ms: 8000 },
  "203.0.113.52",
  adminTok2,
);
check("下发配置成功", cfgPut.body?.code === 0, JSON.stringify(cfgPut.body));
check(
  "配置按完整对象合并（缺省字段仍在）",
  cfgPut.body?.data?.config?.autoplay === false &&
    cfgPut.body.data.config.buffer_ms === 8000 &&
    cfgPut.body.data.config.decode === "auto",
);
const bootJ = await call("GET", "/api/v1/tv/bootstrap", undefined, J, tokJ);
check(
  "bootstrap 下发下发的配置",
  bootJ.body?.data?.device?.config?.autoplay === false && bootJ.body?.data?.device?.config_version >= 2,
  JSON.stringify(bootJ.body?.data?.device),
);
check(
  "bootstrap 下发设备自己的名字",
  bootJ.body?.data?.device?.id === devJ.body.data.device_id,
);

// ---------------------------------------------------------------- 管理台页面「数据面」冒烟
//
// 自带管理台（admin/ 的 Vue）每个页面进来就打一组接口。这里按页面把它们
// 全打一遍，断言三种结果**只有三种**：
//   ok          页面能渲染（code 0 + data 不为 null）
//   unsupported 页面能拿到一句人话（2006「边缘模式不支持…」），而不是崩
//   empty       页面拿到空集合（EPG 源列表这类）
//
// 之所以要这条：边缘版以前对 `channels/{id}/epg` 是"没有这个接口"(2004)，
// 页面只会弹一句莫名其妙的红条。现在这类缺口全部收敛成 2006。
const adminCheck = async (label, method, path, kind, tokenForAdmin, body) => {
  const payload = method === "GET" ? undefined : (body ?? {});
  const r = await call(method, path, payload, "203.0.113.60", tokenForAdmin);
  const code = r.body?.code;
  if (kind === "ok") {
    // 写操作成功时 data 就是 null（服务端也是这么回的），所以只看 code
    check(`[管理台] ${label}`, code === 0, `${code} ${JSON.stringify(r.body?.msg ?? "")}`);
  } else if (kind === "unsupported") {
    check(
      `[管理台] ${label} 明确说不支持`,
      code === 2006 && String(r.body?.msg ?? "").includes("边缘模式不支持"),
      `${code} ${JSON.stringify(r.body?.msg ?? "")}`,
    );
  } else {
    check(`[管理台] ${label} 返回空集合`, code === 0 && Array.isArray(r.body?.data), String(code));
  }
};

const adminTok3 = (await post("/api/v1/auth/login", { password: "testpass123" }, "203.0.113.60")).body?.data?.token;

// 造一个源，让「源管理」页有东西可渲染
db.prepare(
  "INSERT INTO sources (id, name, url, ua, referer, enabled, sort, created_at) VALUES (?,?,?,?,?,?,?,?)",
).run(1, "测试源", "http://panel.example/get.php", "", "", 1, 0, 0);

// 概览
await adminCheck("概览 / system/me", "GET", "/api/v1/system/me", "ok", adminTok3);
await adminCheck("概览 / system/info", "GET", "/api/v1/system/info", "ok", adminTok3);
await adminCheck("概览 / sources", "GET", "/api/v1/sources", "ok", adminTok3);
await adminCheck("概览 / channels", "GET", "/api/v1/channels?limit=200", "ok", adminTok3);
await adminCheck("概览 / devices", "GET", "/api/v1/devices", "ok", adminTok3);

// 源管理
await adminCheck("源管理 / 刷新（走 CI）", "POST", "/api/v1/sources/1/refresh", "unsupported", adminTok3);
await adminCheck("源管理 / 预览", "GET", "/api/v1/sources/1/preview", "unsupported", adminTok3);
await adminCheck("源管理 / 更新", "PUT", "/api/v1/sources/1", "ok", adminTok3);

// 频道管理
await adminCheck("频道管理 / groups", "GET", "/api/v1/groups", "ok", adminTok3);
await adminCheck("频道管理 / 线路列表", "GET", `/api/v1/channels/${tid}/streams`, "ok", adminTok3);
await adminCheck("频道管理 / 拖拽排序", "POST", "/api/v1/channels/reorder", "ok", adminTok3, {
  ids: [tid, emptyId],
});
await adminCheck("频道管理 / 批量操作", "POST", "/api/v1/channels/batch", "ok", adminTok3, {
  ids: [tid],
  action: "enable",
});
await adminCheck("频道管理 / 台标上传", "POST", `/api/v1/channels/${tid}/logo`, "unsupported", adminTok3);
await adminCheck("频道管理 / EPG 手工映射", "PUT", `/api/v1/channels/${tid}/epg`, "unsupported", adminTok3);

// EPG 管理
await adminCheck("EPG / 统计", "GET", "/api/v1/epg/stats", "ok", adminTok3);
await adminCheck("EPG / 频道", "GET", "/api/v1/epg/channels", "ok", adminTok3);
await adminCheck("EPG / 搜索", "GET", "/api/v1/epg/search?q=x", "ok", adminTok3);
await adminCheck("EPG / 源列表", "GET", "/api/v1/epg/sources", "empty", adminTok3);
await adminCheck("EPG / 映射表", "GET", "/api/v1/epg/mapping", "empty", adminTok3);
await adminCheck("EPG / 手动刷新", "POST", "/api/v1/epg/refresh", "unsupported", adminTok3);
await adminCheck("EPG / 自动映射", "POST", "/api/v1/epg/automap", "unsupported", adminTok3);

// 终端设备
await adminCheck("设备 / 待批准列表", "GET", "/api/v1/devices?status=pending", "empty", adminTok3);
await adminCheck("设备 / 下发配置", "PUT", `/api/v1/devices/${devJ.body.data.device_id}/config`, "ok", adminTok3);
await adminCheck("设备 / 生成绑定码", "POST", "/api/v1/devices/bindcode", "unsupported", adminTok3);
await adminCheck("设备 / 审批", "POST", `/api/v1/devices/${devJ.body.data.device_id}/approve`, "unsupported", adminTok3);

// 系统设置（页面在边缘版被隐藏，接口仍然要给出人话而不是 500）
await adminCheck("系统 / 配置", "GET", "/api/v1/system/config", "unsupported", adminTok3);
await adminCheck("系统 / 日志", "GET", "/api/v1/system/logs", "ok", adminTok3);

// 每一条响应都必须是 JSON 信封——否则页面会显示「服务端返回的不是 JSON」
const rawProbe = await call("GET", "/api/v1/system/info", undefined, "203.0.113.60", adminTok3);
check(
  "管理台接口一律返回 JSON 信封",
  rawProbe.headers.get("content-type")?.includes("application/json") &&
    typeof rawProbe.body?.code === "number",
  rawProbe.headers.get("content-type") ?? "",
);

// ---------------------------------------------------------------- 屏蔽（整类 / 单个频道）
//
// 用 kv 里一行 JSON，不新建表——D1 免费额度按行计费，这份清单只在改配置时变。
// 断言要打在「列表里真的没了」上，而不是只看接口回了 0。
const admB = (await post("/api/v1/auth/login", { password: "testpass123" }, "203.0.113.70")).body?.data?.token;
db.prepare(
  `INSERT INTO channels (stable_key, number, name, "group", origin) VALUES ('id:shop1', 10, '购物1台', '购物', 'm3u')`,
).run();
db.prepare(
  `INSERT INTO channels (stable_key, number, name, "group", origin) VALUES ('id:shop2', 11, '购物2台', '购物', 'm3u')`,
).run();
db.prepare(
  `INSERT INTO channels (stable_key, number, name, "group", origin) VALUES ('id:adult', 12, '某测试台', '成人', 'm3u')`,
).run();

const beforeBlk = await call("GET", "/api/v1/channels?size=200", undefined, "203.0.113.70", admB);
const beforeCount = beforeBlk.body?.data?.total ?? 0;
check("屏蔽前列表里有购物台", beforeCount >= 3, String(beforeCount));

const blkAdd = await call("POST", "/api/v1/admin/blocked", { kind: "group", value: "购物" }, "203.0.113.70", admB);
check("屏蔽分组成功", blkAdd.body?.code === 0 && blkAdd.body.data.groups.includes("购物"), JSON.stringify(blkAdd.body));

const afterBlk = await call("GET", "/api/v1/channels?size=200", undefined, "203.0.113.70", admB);
check(
  "整类频道从管理台列表里消失",
  !(afterBlk.body?.data?.items ?? []).some((c) => c.group === "购物"),
  JSON.stringify((afterBlk.body?.data?.items ?? []).map((c) => c.group)),
);
check(
  "total 也跟着减少（不是只过滤了当前页）",
  (afterBlk.body?.data?.total ?? 0) === beforeCount - 2,
  `${beforeCount} -> ${afterBlk.body?.data?.total}`,
);

// 客户端（电视）看到的必须是同一份列表，否则用户以为同步坏了
const devB = await reg("123456", "dev-blk", "androidtv", "203.0.113.71");
const bootBlk = await call("GET", "/api/v1/tv/bootstrap", undefined, "203.0.113.71", devB.body.data.token);
check(
  "被屏蔽的分组不出现在 bootstrap",
  !(bootBlk.body?.data?.channels ?? []).some((c) => c.group === "购物"),
);
check(
  "被屏蔽的分组也不出现在 groups 统计里",
  !(bootBlk.body?.data?.groups ?? []).some((g) => g.name === "购物"),
);

// 单个频道
await call("POST", "/api/v1/admin/blocked", { kind: "channel", value: "某测试台" }, "203.0.113.70", admB);
const afterCh = await call("GET", "/api/v1/channels?size=200", undefined, "203.0.113.70", admB);
check(
  "屏蔽单个频道只挡它自己",
  !(afterCh.body?.data?.items ?? []).some((c) => c.name === "某测试台"),
  JSON.stringify((afterCh.body?.data?.items ?? []).map((c) => c.name)),
);

// 解除
const blkDel = await call(
  "DELETE",
  `/api/v1/admin/blocked?kind=group&value=${encodeURIComponent("购物")}`,
  undefined,
  "203.0.113.70",
  admB,
);
check("解除屏蔽成功", blkDel.body?.code === 0 && !blkDel.body.data.groups.includes("购物"));
const restored = await call("GET", "/api/v1/channels?size=200", undefined, "203.0.113.70", admB);
check("解除后频道回来了", (restored.body?.data?.total ?? 0) === beforeCount - 1, String(restored.body?.data?.total));

// 坏数据不能让整页崩：手改坏 kv 那一行时按「没屏蔽」处理
db.prepare("UPDATE kv SET v = '{bad json' WHERE k = 'blocked'").run();
const badBlk = await call("GET", "/api/v1/admin/blocked", undefined, "203.0.113.70", admB);
check("屏蔽清单存坏时回落成空清单而不是报错", badBlk.body?.code === 0 && Array.isArray(badBlk.body.data.groups));
const bootBad = await call("GET", "/api/v1/tv/bootstrap", undefined, "203.0.113.71", devB.body.data.token);
check("屏蔽清单存坏时 bootstrap 仍然可用", bootBad.body?.code === 0, JSON.stringify(bootBad.body).slice(0, 120));
db.prepare("UPDATE kv SET v = '{\"groups\":[],\"channels\":[]}' WHERE k = 'blocked'").run();

check("屏蔽参数错回 1001", (await call("POST", "/api/v1/admin/blocked", { kind: "x", value: "a" }, "203.0.113.70", admB)).body?.code === 1001);

// 屏蔽页要能**从源里读出分类**直接点，而不是让人手打分组名
// （前面的用例已经把清单清空了，这里重新屏蔽一个来验证标记）
await call("POST", "/api/v1/admin/blocked", { kind: "group", value: "购物" }, "203.0.113.70", admB);
const info = await call("GET", "/api/v1/admin/blocked", undefined, "203.0.113.70", admB);
const cand = info.body?.data?.candidates ?? [];
check(
  "屏蔽页能列出源里的分类（带频道数）",
  cand.some((c) => c.name === "购物" && c.count >= 2) && cand.some((c) => c.name === "成人"),
  JSON.stringify(cand),
);
check(
  "候选里标出了哪些已经屏蔽",
  cand.find((c) => c.name === "购物")?.blocked === true &&
    cand.find((c) => c.name === "成人")?.blocked === false,
  JSON.stringify(cand),
);
// 屏蔽完不能从候选里消失——否则界面上再也找不到它、没法解除
check(
  "已屏蔽的分类仍留在候选列表里",
  cand.some((c) => c.name === "购物" && c.blocked === true),
);

// 源里已经没有、但屏蔽清单里记着的分类也要补上来（重建后频道不入库的情况）
db.prepare("DELETE FROM channels WHERE \"group\" = '成人'").run();
await call("POST", "/api/v1/admin/blocked", { kind: "group", value: "已下架分类" }, "203.0.113.70", admB);
const info2 = await call("GET", "/api/v1/admin/blocked", undefined, "203.0.113.70", admB);
check(
  "源里消失的分类仍能从屏蔽清单里看到",
  (info2.body?.data?.candidates ?? []).some((c) => c.name === "已下架分类"),
  JSON.stringify(info2.body?.data?.candidates),
);
await call("DELETE", `/api/v1/admin/blocked?kind=group&value=${encodeURIComponent("已下架分类")}`, undefined, "203.0.113.70", admB);

// 自带管理台的「屏蔽」页走的是兼容层路径 /api/v1/blocked（Vue 那边统一 api/v1）。
// 两条路径必须给同一份数据，否则两个管理台看到的不一样。
const compatList = await call("GET", "/api/v1/blocked", undefined, "203.0.113.70", admB);
check("兼容层 /api/v1/blocked 可读", compatList.body?.code === 0 && Array.isArray(compatList.body.data.candidates));
const compatAdd = await call("POST", "/api/v1/blocked", { kind: "group", value: "测试" }, "203.0.113.70", admB);
check("兼容层可以加屏蔽", compatAdd.body?.code === 0 && compatAdd.body.data.groups.includes("测试"));
const bothPaths = await call("GET", "/api/v1/admin/blocked", undefined, "203.0.113.70", admB);
check(
  "两个路径读到的是同一份清单",
  JSON.stringify(bothPaths.body?.data?.groups?.sort()) === JSON.stringify(compatAdd.body?.data?.groups?.sort()),
);
const compatDel = await call("DELETE", "/api/v1/blocked?kind=group&value=%E6%B5%8B%E8%AF%95", undefined, "203.0.113.70", admB);
check("兼容层可以解除屏蔽", compatDel.body?.code === 0 && !compatDel.body.data.groups.includes("测试"));

// ---------------------------------------------------------------- 立即重建的自检
const rebuildInfo = await call("GET", "/api/v1/admin/build", undefined, "203.0.113.70", admB);
check("build 接口回报重建目标（排查「点了没反应」用）", rebuildInfo.body?.data?.rebuild_target !== undefined);
check(
  "没配 token 时 can_rebuild 为 false（不留假按钮）",
  rebuildInfo.body?.data?.can_rebuild === false,
  JSON.stringify(rebuildInfo.body?.data),
);
const rebuildNo = await call("POST", "/api/v1/admin/rebuild", {}, "203.0.113.70", admB);
check("没配 token 时点重建给出人话", rebuildNo.body?.code === 2006 && String(rebuildNo.body.msg).includes("GITHUB_DISPATCH_TOKEN"));

// ---------------------------------------------------------------- 管理台口令与绑定码彻底分开
//
// 以前 adminPasscode = ADMIN_PASSWORD || BIND_CODE，没配密码时退回 6 位绑定码。
// 那等于把管理权限压在一个会显示在电视绑定页、家里每个人都要输入的短数字上。
// 现在必须用独立密码；没配就登不进去（而不是"能用但很弱"）。
const N = "203.0.113.80";

{
  const envNoPwd = { ...env, ADMIN_PASSWORD: "" };
  const r = await call("POST", "/api/v1/auth/login", { password: "123456" }, N, undefined, envNoPwd);
  check(
    "没配 ADMIN_PASSWORD 时，绑定码不能登录管理台",
    r.body?.code === 2001,
    JSON.stringify(r.body),
  );
}

{
  const r = await call("POST", "/api/v1/auth/login", { password: "123456" }, N);
  check("绑定码不能当管理台口令（配了密码时也拒绝）", r.body?.code === 2001, JSON.stringify(r.body));
  const ok2 = await call("POST", "/api/v1/auth/login", { password: "testpass123" }, N);
  check("ADMIN_PASSWORD 才能登录", ok2.body?.code === 0);
}

// ---------------------------------------------------------------- 设置自省：绑定码 / 入口
const admS = (await post("/api/v1/auth/login", { password: "testpass123" }, N)).body?.data?.token;

const st1 = await call("GET", "/api/v1/admin/settings", undefined, N, admS);
check("settings 接口可用", st1.body?.code === 0, JSON.stringify(st1.body));
check("默认给的是打码绑定码", st1.body?.data?.bind_code === "12•••56", JSON.stringify(st1.body?.data));
check("同时带一份 masked（前端不必自己算）", st1.body?.data?.bind_code_masked === "12•••56");
check("标记出管理台密码已配置", st1.body?.data?.admin_password_set === true);

const st2 = await call("GET", "/api/v1/admin/settings?reveal=1", undefined, N, admS);
check("reveal=1 才给明文", st2.body?.data?.bind_code === "123456", JSON.stringify(st2.body?.data));

check(
  "未登录拿不到 settings（否则绑定码就漏了）",
  (await call("GET", "/api/v1/admin/settings", undefined, N)).body?.code === 2001,
);

check("默认入口是根路径", st1.body?.data?.admin_entry === "/", JSON.stringify(st1.body?.data?.admin_entry));

// 入口自定义：只有那一个路径能拿到首页，默认入口一律 404
{
  const envCustom = { ...env, ADMIN_PATH: "litv-9f3a" };
  const entry = await call("GET", "/litv-9f3a", undefined, N, undefined, envCustom);
  check("自定义入口能拿到管理台首页", entry.status === 200, String(entry.status));

  for (const p of ["/", "/index.html", "/admin.html"]) {
    const r = await call("GET", p, undefined, N, undefined, envCustom);
    check(`配了入口后 ${p} 返回 404（不给扫描递钥匙）`, r.status === 404, String(r.status));
  }

  // 备用单文件管理台藏在入口下面，不再占一个能猜到的公开路径
  const simple = await call("GET", "/litv-9f3a/simple", undefined, N, undefined, envCustom);
  check("备用单文件台挂在入口下的 /simple", simple.status === 200, String(simple.status));

  // assets 必须继续放行，否则首页加载不到脚本。
  // 用一个明确回 200 的假 ASSETS 来区分「被入口检查拦住」和「assets 里没有」。
  const envAsset = {
    ...env,
    ADMIN_PATH: "litv-9f3a",
    ASSETS: { fetch: async () => new Response("ok", { status: 200 }) },
  };
  const asset = await call("GET", "/assets/index-abc.js", undefined, N, undefined, envAsset);
  check("配了入口后静态资源仍放行（没被入口检查误伤）", asset.status === 200, String(asset.status));
}

// 没配入口时：根路径给 Vue 版，/admin.html 交给 assets（单文件备用台）
{
  const envPlain = { ...env, ADMIN_PATH: "", ASSETS: { fetch: async () => new Response("home", { status: 200 }) } };
  const root = await call("GET", "/", undefined, N, undefined, envPlain);
  check("没配入口时根路径仍然能打开", root.status === 200, String(root.status));
  const legacy = await call("GET", "/admin.html", undefined, N, undefined, envPlain);
  check("没配入口时 /admin.html 仍是单文件备用台", legacy.status === 200, String(legacy.status));
}

// 非法入口值必须被规范化掉（否则 // 或 .. 会绕开检查）
{
  const envBad = { ...env, ADMIN_PATH: "../etc" };
  const root = await call("GET", "/", undefined, N, undefined, envBad);
  check("非法 ADMIN_PATH 被忽略、退回默认入口", root.status === 200, String(root.status));
}

// wrangler.toml 与代码的契约：入口控制跑在 Worker 里，但这几个路径默认会被
// Cloudflare 的 assets 直接返回（不跑 Worker）。不列进 run_worker_first 的话，
// 配了 ADMIN_PATH 之后根路径照样能打开管理台 —— 测试里跑不出来，只有线上会漏。
{
  const toml = await readFile(new URL("../wrangler.toml", import.meta.url), "utf8");
  const m = toml.match(/run_worker_first\s*=\s*\[([^\]]*)\]/);
  const listed = m ? m[1].split(",").map((s) => s.trim().replace(/^["']|["']$/g, "")) : [];
  for (const need of ["/", "/index.html", "/admin.html", "/api/*", "/playlist.m3u"]) {
    check(`wrangler.toml 的 run_worker_first 包含 ${need}`, listed.includes(need), JSON.stringify(listed));
  }
}

// ---------------------------------------------------------------- 出站目标校验
// 探活/测源会把 D1 里的地址发给边缘去请求，CI 更是直接抓不可信 m3u。
// 真服务端有 safedial.IsPrivateAddr，这边必须对齐。
const ssrfCases = [
  ["http://10.0.0.5/x", true],
  ["http://127.0.0.1/x", true],
  ["http://169.254.169.254/latest/meta-data/", true],
  ["http://192.168.1.1/x", true],
  ["http://172.16.5.5/x", true],
  ["http://[::1]/x", true],
  ["http://localhost/x", true],
  ["http://metadata.google.internal/x", true],
  ["ftp://example.com/x", true],
  ["not a url", true],
  ["http://172.32.5.5/x", false],
  ["https://panel.example.org:8080/get.php", false],
];
for (const [u, shouldBlock] of ssrfCases) {
  const msg = checkFetchTarget(u);
  check(
    `出站校验${shouldBlock ? "拦截" : "放行"} ${u}`,
    (msg !== null) === shouldBlock,
    String(msg),
  );
}

// ---------------------------------------------------------------- 汇总
const failed = checks.filter(([, ok]) => !ok);
for (const [name, ok, detail] of checks) {
  console.log(`${ok ? "  ok  " : "  FAIL"} ${name}${!ok && detail ? "  -> " + detail : ""}`);
}
console.log(`\n${checks.length - failed.length}/${checks.length} 通过`);
if (failed.length) process.exit(1);

// 节目单路由。这条曾经整段漏掉：tvEpg() 写好了但没接进路由，
// 客户端调 /api/v1/tv/epg/{id} 落到 2004「没有这个接口」，
// 表现为电视上节目单永远是空的。
{
  const epgDev = await call("GET", "/api/v1/tv/bootstrap", undefined, J, tokJ);
  check("剧集前置：设备 token 有效", epgDev.body?.code === 0);

  // 给 channel 插一条当前时段节目
  const nowSec = Math.floor(Date.now() / 1000);
  db.prepare(
    `INSERT INTO epg_programs (channel_id, xmltv_id, start_at, end_at, title)
     VALUES (?, '', ?, ?, '测试节目')`,
  ).run(tid, nowSec - 600, nowSec + 1800);

  const epgRes = await call("GET", `/api/v1/tv/epg/${tid}`, undefined, J, tokJ);
  check("tv/epg 路由存在（不是 2004 没有这个接口）", epgRes.body?.code === 0, JSON.stringify(epgRes.body).slice(0, 120));
  check(
    "tv/epg 返回当天节目",
    Array.isArray(epgRes.body?.data?.programs) && epgRes.body.data.programs.length > 0,
    JSON.stringify(epgRes.body?.data?.programs?.length),
  );
  check("tv/epg 带日边界给客户端", typeof epgRes.body?.data?.day_start === "number");
}
