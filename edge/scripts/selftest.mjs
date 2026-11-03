// 自测：不需要任何外部依赖，也不需要真的订阅地址。
//
// 三段：
//   A. 起本地 http 服务喂两份 m3u，跑 build.mjs，断言 playlist.m3u / seed.sql
//   B. 把 schema.sql + seed.sql 灌进内存 SQLite（node:sqlite，Node 24 自带），
//      模拟"第二天重建"，再跑 BOOTSTRAP_SQL，断言响应体
//   C. 再喂一份 GBK 编码的 TXT 和一份 HTML 登录页，断言：
//      TXT 能被认出来并解析（含名字里带逗号），HTML 给出可读提示而不是静默失败
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
//   - TXT 格式（含 GBK 编码、名字带逗号）能认；HTML/JSON 给出可读诊断
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { readFile, writeFile, unlink } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { BOOTSTRAP_SQL } from "../src/bootstrap.sql.js";

const checks = [];
const check = (name, ok) => checks.push([name, !!ok]);

// ---------------------------------------------------------------- 素材

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

// GBK 编码的 TXT：整份文件都是 GBK，这里只有分组名"央视"是非 ASCII，
// 用它的 GBK 字节（D1 EB CA D3）表示。其余故意保持 ASCII——
// 真实 GBK 文件不会混着 UTF-8，混了就没法测编码判定了。
const TXT_GBK = Buffer.concat([
  Buffer.from([0xd1, 0xeb, 0xca, 0xd3]),
  Buffer.from(",#genre#\n"),
  Buffer.from("CCTV-1 Test,http://t.example/1.ts\n"),
  Buffer.from("CCTV-2 Test,http://t.example/2.ts\n"),
  Buffer.from("Drama,HD,http://t.example/3.ts\n"),
]);

const HTML_LOGIN = `<!doctype html><html><head><title>登录</title></head>
<body><form>请登录</form></body></html>`;

// ---------------------------------------------------------------- 服务

// 记录每个请求带的 UA / Referer，用来验证"每个源用自己的 UA"。
const seen = {};

const server = createServer((req, res) => {
  seen[req.url] = {
    ua: req.headers["user-agent"] || "",
    referer: req.headers["referer"] || "",
  };
  if (req.url === "/a.m3u") { res.writeHead(200); res.end(SRC_A); }
  else if (req.url === "/b.m3u") { res.writeHead(200); res.end(SRC_B); }
  else if (req.url === "/c.txt") { res.writeHead(200); res.end(TXT_GBK); }
  else if (req.url === "/d.html") { res.writeHead(200); res.end(HTML_LOGIN); }
  else { res.writeHead(404); res.end("no"); }
});

await new Promise((r) => server.listen(0, "127.0.0.1", r));
const base = `http://127.0.0.1:${server.address().port}`;

function runBuild(env) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, ["scripts/build.mjs"], {
      env: { ...process.env, ...env },
    });
    let out = "";
    let err = "";
    child.stdout.on("data", (d) => { out += d; process.stdout.write(d); });
    child.stderr.on("data", (d) => { err += d; process.stderr.write(d); });
    child.on("exit", (code) => resolve({ code, out, err }));
  });
}

// ---------------------------------------------------------------- A. m3u 生成

const first = await runBuild({ SOURCE_URLS: `${base}/a.m3u\n${base}/b.m3u` });
if (first.code !== 0) {
  console.error(`build 退出码 ${first.code}`);
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
    JSON.stringify(["改过的CCTV1", "我的测试台", "湖南卫视", '测试"引号"台']),
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

// ---------------------------------------------------------------- C. 源清单 / TXT / HTML

console.log("\n--- 源清单 + TXT + HTML ---");

// 模拟 CI 从 D1 读出来的源清单。故意在前面加一行噪声——
// wrangler 会往 stdout 打进度行，JSON 前后不一定干净。
const sourcesFile = "sources.test.json";
await writeFile(
  sourcesFile,
  "🚣 Executing on remote database (litv-edge)\n" +
    JSON.stringify([
      {
        results: [
          { id: 7, name: "txt源", url: `${base}/c.txt`, ua: "CustomUA/1.0", referer: "http://ref.example/" },
          { id: 8, name: "html源", url: `${base}/d.html`, ua: "", referer: "" },
        ],
        success: true,
      },
    ]),
  "utf8",
);

const second = await runBuild({ SOURCES_FILE: sourcesFile, SOURCE_URLS: "" });
await unlink(sourcesFile);

check("TXT + HTML 混合源仍然成功（部分源失败不拖垮整体）", second.code === 0);

const m3u2 = await readFile("public/playlist.m3u", "utf8");
check("GBK 编码的 TXT 被正确解码", m3u2.includes('group-title="央视"'));
check("TXT 的 3 个频道都进来了", (m3u2.match(/#EXTINF/g) || []).length === 3);
check("名字里带逗号时按协议头从右往左拆", m3u2.includes(",Drama,HD"));
check("HTML 源给出可读提示，不是静默失败", second.err.includes("HTML"));
check("诊断信息里不带完整地址（脱敏）", !second.err.includes("d.html"));

check("每个源用自己的 UA", seen["/c.txt"] && seen["/c.txt"].ua === "CustomUA/1.0");
check("每个源用自己的 Referer", seen["/c.txt"] && seen["/c.txt"].referer === "http://ref.example/");
check("没配 UA 的源回退到默认 UA", seen["/d.html"] && seen["/d.html"].ua.includes("VLC"));

const statusSql = await readFile("sql/source-status.sql", "utf8");
check("回写源状态带 id（供管理台显示）", statusSql.includes("WHERE id = 7;"));
check("失败的源也回写状态", statusSql.includes("'html'"));

const metaSql = await readFile("sql/meta.sql", "utf8");
check("写入重建信息（管理台显示上次重建时间）", metaSql.includes("'last_build'"));

// 源上配的 UA 不只是拉取用，也要落到线路的播放 UA 上——
// 很多源拉取不校验 UA，但播放分片校验，只配拉取会"能导入不能播"。
const seed2 = await readFile("sql/seed.sql", "utf8");
check("源上配的 UA 会灌到线路的播放 UA", seed2.includes("CustomUA/1.0"));
check("源上配的 Referer 也会灌到线路", seed2.includes("ref.example"));

// 源清单读坏了必须明确报错，不能静默当成"没有源"
const badFile = "sources.bad.json";
await writeFile(badFile, "this is not json at all", "utf8");
const brokenRun = await runBuild({ SOURCES_FILE: badFile, SOURCE_URLS: "" });
await unlink(badFile);
check(
  "源清单解析失败时明确报错（不静默跳过）",
  brokenRun.code !== 0 && brokenRun.err.includes("不是可解析的 JSON"),
);

// 没有源时不该炸：首次部署就是这个状态，得能正常发上去
const third = await runBuild({ SOURCES_FILE: "", SOURCE_URLS: "" });
check("没有源时不算失败（首次部署靠它）", third.code === 0);
check("没有源时给的是可读提示", third.out.includes("没有启用的订阅源"));

// ---------------------------------------------------------------- D. 节目单

// XMLTV 时间：东八区 "YYYYMMDDHHMMSS +0800"
function xmltvTime(ms) {
  const p = (n) => String(n).padStart(2, "0");
  const t = new Date(ms + 8 * 3600 * 1000);
  return (
    `${t.getUTCFullYear()}${p(t.getUTCMonth() + 1)}${p(t.getUTCDate())}` +
    `${p(t.getUTCHours())}${p(t.getUTCMinutes())}${p(t.getUTCSeconds())} +0800`
  );
}

const nowMs = Date.now();
const EPG_XML = `<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <channel id="CCTV1"><display-name>CCTV-1</display-name></channel>
  <channel id="CCTV10"><display-name>CCTV-10</display-name></channel>
  <programme channel="CCTV1" start="${xmltvTime(nowMs + 3600_000)}" stop="${xmltvTime(nowMs + 7200_000)}">
    <title>新闻联播</title><desc>测试描述</desc><category>新闻</category>
  </programme>
  <programme channel="CCTV10" start="${xmltvTime(nowMs + 3600_000)}" stop="${xmltvTime(nowMs + 7200_000)}">
    <title>科教片</title>
  </programme>
  <programme channel="CCTV1" start="${xmltvTime(nowMs + 400 * 86400_000)}" stop="${xmltvTime(nowMs + 401 * 86400_000)}">
    <title>太远的节目不该入库</title>
  </programme>
</tv>`;

const EPG_M3U_TEMPLATE = `#EXTM3U x-tvg-url="__EPG__"
#EXTINF:-1 group-title="央视",CCTV-1 综合
http://e.example/1.ts
#EXTINF:-1 group-title="央视",CCTV-10 科教
http://e.example/10.ts
`;

const epgServer = createServer((req, res) => {
  if (req.url === "/e.m3u") {
    const host = `http://${req.headers.host}`;
    res.writeHead(200);
    res.end(EPG_M3U_TEMPLATE.replace("__EPG__", `${host}/epg.xml`));
  } else if (req.url === "/epg.xml") {
    res.writeHead(200);
    res.end(EPG_XML);
  } else {
    res.writeHead(404);
    res.end("no");
  }
});
await new Promise((r) => epgServer.listen(0, "127.0.0.1", r));
const epgBase = `http://127.0.0.1:${epgServer.address().port}`;

console.log("\n--- 节目单 ---");

const epgRun = await runBuild({
  SOURCE_URLS: `${epgBase}/e.m3u`,
  EPG_STATE_FILE: "",
});
check("带 x-tvg-url 的源能跑通", epgRun.code === 0);

const epgSql = await readFile("sql/epg.sql", "utf8");
check("节目单写进了 epg.sql", epgSql.includes("新闻联播"));
check(
  "节目单按 channel_id 关联（用 stable_key 反查）",
  epgSql.includes("(SELECT id FROM channels WHERE stable_key"),
);
check("窗口外的节目不入库", !epgSql.includes("太远的节目"));
check("频道回写 epg_id", epgSql.includes("SET epg_id = 'CCTV1'"));

const cctv10Line = epgSql.split("\n").find((l) => l.includes("name:cctv10科教"));
check("最长前缀匹配：CCTV-10 不会错配到 CCTV1", !!cctv10Line && cctv10Line.includes("'CCTV10'"));
check("记录上次刷新时间", epgSql.includes("'last_epg_at'"));

// 刚刷过就跳过（D1 写入预算）
const stateFile = "epg-state.test.json";
await writeFile(stateFile, JSON.stringify([{ results: [{ v: String(Math.floor(Date.now() / 1000)) }] }]), "utf8");
const skipRun = await runBuild({ SOURCE_URLS: `${epgBase}/e.m3u`, EPG_STATE_FILE: stateFile });
await unlink(stateFile);
const skipSql = await readFile("sql/epg.sql", "utf8");
check("6 小时内不重复刷节目单", skipRun.code === 0 && skipSql.includes("本次不刷新"));

epgServer.close();

server.close();

// ---------------------------------------------------------------- 汇总

let bad = 0;
for (const [name, pass] of checks) {
  console.log(`${pass ? "✅" : "❌"} ${name}`);
  if (!pass) bad++;
}
console.log(`\n${checks.length - bad}/${checks.length} 通过`);
process.exit(bad === 0 ? 0 : 1);
