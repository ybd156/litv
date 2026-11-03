// 自测：不需要任何外部依赖，也不需要真的订阅地址。
//
// 两段：
//   A. 起本地 http 服务喂两份构造好的 m3u，跑 build.mjs，
//      对产出的 playlist.m3u / seed.sql 做断言
//   B. 把 schema.sql + seed.sql 灌进内存 SQLite（node:sqlite，Node 24 自带），
//      模拟"第二天重建"，再跑 BOOTSTRAP_SQL，断言响应体
//
// 只在 CI 里跑（.github/workflows/edge-deploy.yml 的 check 任务）。
//
// 覆盖的行为：
//   - 跨源同名频道去重、多条线路合并
//   - group-title 里带逗号不被截断
//   - #EXTVLCOPT 的 UA / Referer 既要进 D1，也要回写进 m3u
//   - seed.sql 只清订阅数据、保留手工数据，且不写死频道 id
//   - 重建后手工线路重新挂到新 id 上（不是挂空）
//   - channel_overrides 的改名 / 隐藏生效
//   - bootstrap 响应体是合法 JSON，频道按 sort 排序，stream_count 正确
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { BOOTSTRAP_SQL } from "../src/bootstrap.sql.js";

const checks = [];
const check = (name, ok) => checks.push([name, !!ok]);

// ---------------------------------------------------------------- A. m3u 生成

const SRC_A = `#EXTM3U
#EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV-1" tvg-logo="http://logo/1.png" group-title="央视",CCTV-1 综合
#EXTVLCOPT:http-user-agent=Mozilla/5.0 Test
http://a.example/1.m3u8
#EXTINF:-1 tvg-id="hunan" group-title="卫视",湖南卫视
#EXTVLCOPT:http-referrer=http://ref.example/
http://a.example/hn.ts
#EXTINF:-1 group-title="新闻,财经",测试"引号"台
http://a.example/q.ts
`;

const SRC_B = `#EXTM3U
#EXTINF:-1 tvg-id="cctv1" group-title="央视",CCTV-1 综合
http://b.example/1.ts
#EXTINF:-1 tvg-id="cctv2" group-title="央视",CCTV-2 财经
http://b.example/2.ts
`;

const server = createServer((req, res) => {
  if (req.url === "/a.m3u") { res.writeHead(200); res.end(SRC_A); }
  else if (req.url === "/b.m3u") { res.writeHead(200); res.end(SRC_B); }
  else { res.writeHead(404); res.end("no"); }
});

await new Promise((r) => server.listen(0, "127.0.0.1", r));
const port = server.address().port;

const child = spawn(process.execPath, ["scripts/build.mjs"], {
  env: {
    ...process.env,
    SOURCE_URLS: `http://127.0.0.1:${port}/a.m3u\nhttp://127.0.0.1:${port}/b.m3u`,
  },
  stdio: "inherit",
});

const exitCode = await new Promise((r) => child.on("exit", r));
server.close();

if (exitCode !== 0) {
  console.error(`build 退出码 ${exitCode}`);
  process.exit(1);
}

const m3u = await readFile("public/playlist.m3u", "utf8");
const seedSql = await readFile("sql/seed.sql", "utf8");

console.log("\n--- playlist.m3u ---\n" + m3u);

check("CCTV-1 只出现一次（跨源去重）", (m3u.match(/CCTV-1 综合/g) || []).length === 1);
check("共 4 个频道", (m3u.match(/#EXTINF/g) || []).length === 4);
check("含逗号的 group-title 未被截断", m3u.includes('group-title="新闻,财经"'));
check('频道名里的引号原样保留', m3u.includes('测试"引号"台'));
check("m3u 里带回了 EXTVLCOPT UA", m3u.includes("#EXTVLCOPT:http-user-agent=Mozilla/5.0 Test"));
check("m3u 里带回了 EXTVLCOPT Referer", m3u.includes("#EXTVLCOPT:http-referrer=http://ref.example/"));

check("seed 只清 m3u 数据，保留手工数据", seedSql.includes("DELETE FROM channels WHERE origin = 'm3u';"));
check("seed 不写死频道 id", !/INSERT INTO channels \([^)]*\bid\b/.test(seedSql));
check("频道带 stable_key", seedSql.includes("'id:cctv1'"));
check("线路用 stable_key 反查频道 id", seedSql.includes("(SELECT id FROM channels WHERE stable_key = 'id:cctv1')"));
check("手工线路重建后重新挂 id", seedSql.includes("WHERE origin = 'manual'") && seedSql.includes("c.stable_key = streams.channel_key"));
check("手工线路频道没了不会挂成 NULL", seedSql.includes("AND EXISTS (SELECT 1 FROM channels c WHERE c.stable_key = streams.channel_key)"));
check("UA 落到 streams.ua", seedSql.includes("Mozilla/5.0 Test"));
check("Referer 落到 headers", seedSql.includes("ref.example"));
check("CCTV-1 有两条线路", (seedSql.match(/'http:\/\/[ab]\.example\/1\.(m3u8|ts)'/g) || []).length === 2);
check("先删后插", seedSql.indexOf("DELETE FROM streams") < seedSql.indexOf("INSERT INTO streams"));

// ---------------------------------------------------------------- B. SQL 行为

const db = new DatabaseSync(":memory:");
db.exec(await readFile("schema.sql", "utf8"));
db.exec(seedSql);

const one = (sql, ...args) => db.prepare(sql).get(...args);

// 手工数据：一个手工频道 + 两条手工线路（一条挂在订阅频道上）
db.exec(`INSERT INTO channels (stable_key, number, name, "group", origin)
         VALUES ('manual:1', 999, '我的测试台', '手工', 'manual')`);
const manualChId = one(`SELECT id FROM channels WHERE stable_key = 'manual:1'`).id;
db.prepare(`INSERT INTO streams (channel_id, channel_key, label, url, origin)
            VALUES (?,?,?,?,'manual')`).run(manualChId, "manual:1", "手工线路", "http://m.example/1.ts");

const cctvIdV1 = one(`SELECT id FROM channels WHERE stable_key = 'id:cctv1'`).id;
db.prepare(`INSERT INTO streams (channel_id, channel_key, label, url, origin)
            VALUES (?,?,?,?,'manual')`).run(cctvIdV1, "id:cctv1", "手工补线", "http://m.example/2.ts");

// 模拟第二天重建
db.exec(seedSql);

const cctvIdV2 = one(`SELECT id FROM channels WHERE stable_key = 'id:cctv1'`).id;
const reattached = one(`SELECT channel_id FROM streams WHERE url = 'http://m.example/2.ts'`);
const manualStillThere = one(`SELECT id FROM channels WHERE stable_key = 'manual:1'`);
const manualStreamOk = one(`SELECT channel_id FROM streams WHERE url = 'http://m.example/1.ts'`);

check("重建后订阅频道 id 确实变了（测试前提成立）", cctvIdV2 !== cctvIdV1);
check("手工线路重建后挂回新的频道 id", reattached && reattached.channel_id === cctvIdV2);
check("手工频道重建后仍在", !!manualStillThere);
check("手工频道的手工线路没被牵连", manualStreamOk && manualStreamOk.channel_id === manualChId);

// 人工覆盖：改一个名、藏一个台
db.exec(`INSERT INTO channel_overrides (stable_key, name, updated_at) VALUES ('id:cctv1', '改过的CCTV1', 0)`);
db.exec(`INSERT INTO channel_overrides (stable_key, enabled, updated_at) VALUES ('id:cctv2', 0, 0)`);

const raw = db.prepare(BOOTSTRAP_SQL).get("LITV").body;

let body = null;
try {
  body = JSON.parse(raw);
} catch (e) {
  console.error("bootstrap 响应体不是合法 JSON：" + e.message);
}

check("bootstrap 是合法 JSON", body !== null);
check("隐藏的频道不出现在响应里", body && !body.channels.some((c) => c.name.includes("CCTV-2")));
check("改名生效", body && body.channels.some((c) => c.name === "改过的CCTV1"));
check(
  "频道按 sort/number 排序",
  body && JSON.stringify(body.channels.map((c) => c.name)) ===
    JSON.stringify(['改过的CCTV1', "我的测试台", "湖南卫视", '测试"引号"台']),
);
check("频道总数 = 4", body && body.channels.length === 4);
check("stream_count 含手工线路（CCTV-1 共 3 条）", body && body.channels[0].stream_count === 3);
check("enabled 是布尔 true 不是 1", body && body.channels[0].enabled === true);
check("device.config.autoplay 是布尔 true", body && body.device.config.autoplay === true);
check("groups 计数正确", body && JSON.stringify(body.groups.map((g) => g.name).sort()) ===
  JSON.stringify(["手工", "卫视", "央视", "新闻,财经"].sort()));
check("sources / now / favorites 是空数组", body &&
  Array.isArray(body.sources) && body.sources.length === 0 &&
  Array.isArray(body.now) && body.now.length === 0 &&
  Array.isArray(body.favorites) && body.favorites.length === 0);

// ---------------------------------------------------------------- 汇总

let bad = 0;
for (const [name, pass] of checks) {
  console.log(`${pass ? "✅" : "❌"} ${name}`);
  if (!pass) bad++;
}
console.log(`\n${checks.length - bad}/${checks.length} 通过`);
process.exit(bad === 0 ? 0 : 1);
