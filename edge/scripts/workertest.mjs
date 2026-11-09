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
    return { results: db.prepare(this.sql).all(...this.args) };
  }

  async run() {
    db.prepare(this.sql).run(...this.args);
    return { success: true, meta: {} };
  }
}

const DB = { prepare: (sql) => new Statement(sql) };

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

async function call(method, path, body, ip, token) {
  const headers = { "cf-connecting-ip": ip };
  if (body !== undefined) headers["content-type"] = "application/json";
  if (token) headers.authorization = `Bearer ${token}`;
  const req = new Request("http://edge.local" + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const res = await worker.fetch(req, env);
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

const rowA = db.prepare("SELECT fails FROM bind_attempts WHERE ip = ?").get(A);
check("被挡的请求也计入失败数", rowA && rowA.fails >= 11, JSON.stringify(rowA));

// 窗口是 600 秒：把 window_start 往前拨 601 秒，应当立刻恢复
db.prepare("UPDATE bind_attempts SET window_start = window_start - 601 WHERE ip = ?").run(A);
const reset = await reg("123456", "a-ok", "androidtv", A);
check("窗口过后正确码可以绑定", reset.body.code === 0, JSON.stringify(reset.body));
check("绑定成功后该 IP 计数被清除", !db.prepare("SELECT 1 FROM bind_attempts WHERE ip = ?").get(A));
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
check("无会话访问管理接口被拒", (await call("GET", "/api/v1/admin/stats", undefined, C)).body.code === 2003);
check("登录后可以访问管理接口", (await call("GET", "/api/v1/admin/stats", undefined, C, token)).body.code === 0);

const logout = await call("POST", "/api/v1/auth/logout", undefined, C, token);
check("注销成功", logout.body.code === 0);
check("注销后 token 立刻失效", (await call("GET", "/api/v1/admin/stats", undefined, C, token)).body.code === 2003);
check("重复注销仍然幂等成功", (await call("POST", "/api/v1/auth/logout", undefined, C, token)).body.code === 0);
check("无 token 注销不报错", (await call("POST", "/api/v1/auth/logout", undefined, C)).body.code === 0);

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

// 20 次错误之后第 21 次开始限速；此时正确 t 也被挡
const F = "203.0.113.32";
for (let i = 0; i < 20; i++) await call("GET", "/playlist.m3u?t=000000", undefined, F);
const blocked = await call("GET", "/playlist.m3u?t=123456", undefined, F);
check("playlist 超限后正确 t 也 404", blocked.status === 404);
check(
  "playlist 计数达到 21",
  db.prepare("SELECT fails FROM bind_attempts WHERE ip = ?").get(`playlist:${F}`)?.fails === 21,
);

// 窗口是 60 秒（不是绑定码那套 600 秒）：拨 61 秒应当立刻恢复
db.prepare("UPDATE bind_attempts SET window_start = window_start - 61 WHERE ip = ?").run(`playlist:${F}`);
const recovered = await call("GET", "/playlist.m3u?t=123456", undefined, F);
check("playlist 窗口是 60 秒（61 秒后恢复）", recovered.status === 200, String(recovered.status));

// ---------------------------------------------------------------- 汇总
const failed = checks.filter(([, ok]) => !ok);
for (const [name, ok, detail] of checks) {
  console.log(`${ok ? "  ok  " : "  FAIL"} ${name}${!ok && detail ? "  -> " + detail : ""}`);
}
console.log(`\n${checks.length - failed.length}/${checks.length} 通过`);
if (failed.length) process.exit(1);
