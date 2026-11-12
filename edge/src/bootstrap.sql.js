// /api/v1/tv/bootstrap 的响应体，在 SQLite 里直接拼成 JSON 字符串。
//
// 为什么不用 JS 拼：
//   免费版 Worker 每次请求只有 10ms CPU。在 JS 里 map + JSON.stringify
//   是 O(频道数) 的开销——几百个频道 1~2ms，上千个就开始贴近上限，
//   再多就是错误 1102。放到 SQL 里之后，Worker 只剩"取一行、吐字节"，
//   开销和频道数无关。
//
// 为什么不用构建期预渲染成静态文件：
//   管理台改了频道名/分组/隐藏之后，要等到第二天重建才生效。
//   放在 SQL 里每次现算，改完立刻生效。
//
// 注意：D1 的查询耗时算在 wall time 上，不计入 Worker 的 CPU 预算。
// 但 **D1 的行读写是要算免费额度的**，所以这条 SQL 的成本要抠：
//   · 屏蔽清单用 MATERIALIZED CTE 只解析一次（以前每个 WHERE 都重新求值）
//   · 线路数用一次聚合代替逐行相关子查询（以前是 N+1）
//   · 节目单只扫最近几小时（以前全表扫，3 万行/次）
//
// 排序假设：内层子查询带 ORDER BY，外层 json_group_array 按子查询产出顺序聚合。
// SQLite 实际行为如此（selftest 有断言），但这不是文档承诺——
// 万一哪天顺序乱了，只是频道排序不对，不会坏。
//
// 绑定参数顺序（共 9 个，顺序不能变）：
//   1 服务端名  2 设备 id  3 设备名  4 设备 id（配置）
//   5 设备 id（配置版本）  6 设备 id（收藏）
//   7 EPG 窗口起  8 EPG 窗口止  9 「正在播」判定的 now
// 用重复绑定而不是 ?1/?2 编号参数：编号参数在 D1 上的行为没有实测过，
// 而普通 `?` 是按位置绑定的标准用法。**改 JSON 里键的顺序就要同步改这里**
// 和 index.ts 的 tvBootstrap —— 错位不会报错，只会静默查不到数据。

/** 没下发过配置时用的默认值。必须是一份**完整**对象：
 *  客户端读 device.config.autoplay 这类字段，给 null 会直接 NPE。 */
export const DEFAULT_DEVICE_CONFIG =
  '{"decode":"auto","buffer_ms":3000,"aspect":"auto","autoplay":true,"engine":"auto"}';

/**
 * 频道列表版本号（客户端拿心跳里的这个值跟本地比，变了才静默重拉列表）。
 *
 * 为什么用 kv 里的计数器，而不是像服务端那样现算数据指纹：
 * 指纹是一次 channels 全表 + streams 全表的聚合，服务端跑在 NAS 的本地
 * SQLite 上无所谓；边缘版跑在 D1 免费额度里，而心跳是每 30 秒一次——
 * 5 台设备一天就是一万多次全表扫描，写入/读取额度直接打爆。
 * 计数器只读一行，代价可以忽略。
 *
 * 代价是「必须在每个改频道的写入点 +1」。这个风险用两件事兜住：
 *   1. 管理台的写操作在**路由层**统一推进（不逐个 handler 加，漏不掉）；
 *   2. 每小时重建必然 +1，所以最坏情况是「改完最多 1 小时客户端才刷新」。
 *
 * COALESCE 放在子查询**外面**：kv 里还没这行时标量子查询返回零行 → NULL，
 * 写在里面的 COALESCE 兜不住。
 */
export const CHANNELS_VERSION_SQL = `COALESCE(
  (SELECT v FROM kv WHERE k = 'channels_version'), '0'
)`;

/**
 * 屏蔽清单的取值表达式（给**低频**的管理台接口用；bootstrap 走 CTE 更省）。
 *
 * `json_valid` 是防御：万一那行被人手改坏，`json_each` 会直接抛错，
 * 而 bootstrap 是客户端启动的唯一入口——宁可"什么都不屏蔽"也不能整页崩。
 */
const BLOCKED_KV_SQL = `CASE WHEN json_valid((SELECT v FROM kv WHERE k = 'blocked'))
                             THEN (SELECT v FROM kv WHERE k = 'blocked')
                             ELSE '{}' END`;

/** 追加到 channels/groups 的 WHERE 后面 */
export const NOT_BLOCKED_SQL = `
         AND COALESCE(o.group_name, c."group") NOT IN (
               SELECT value FROM json_each(${BLOCKED_KV_SQL}, '$.groups')
             )
         AND COALESCE(o.name, c.name) NOT IN (
               SELECT value FROM json_each(${BLOCKED_KV_SQL}, '$.channels')
             )`;

/**
 * 「正在播」只看最近这么久内开始的节目。
 *
 * 为什么要有这个上界：`start_at <= now AND end_at > now` 这种写法会扫
 * epg_programs 里**所有历史上开始过的行**（实测 3 万行/次 bootstrap），
 * 而节目单本身只保留前后几天，越久越没用。加上下界之后走
 * `idx_epg_time(start_at, end_at)` 的范围扫描，只读最近几小时那几百行。
 *
 * 取 6 小时是因为重建时写入的 EPG 窗口就是「往前 6 小时」，所以正在播的
 * 节目 `start_at` 不可能早于 now-6h。**和 build.mjs 的 EPG_BACK_SEC 对齐**。
 */
export const EPG_NOW_LOOKBACK_SEC = 6 * 3600;

export const BOOTSTRAP_SQL = `
WITH
  -- 屏蔽清单只解析一次。
  -- 以前每个 WHERE 里都写 (SELECT v FROM kv...) + json_each(...)，
  -- 500 个频道 × 2 个条件 = 上千次虚拟表扫描，全在读同一行。
  blk AS MATERIALIZED (
    SELECT CASE WHEN json_valid(v) THEN v ELSE '{}' END AS v
      FROM kv WHERE k = 'blocked'
    UNION ALL
    SELECT '{}' AS v WHERE NOT EXISTS (SELECT 1 FROM kv WHERE k = 'blocked')
  ),
  blk_g AS MATERIALIZED (
    SELECT value FROM json_each((SELECT v FROM blk LIMIT 1), '$.groups')
  ),
  blk_c AS MATERIALIZED (
    SELECT value FROM json_each((SELECT v FROM blk LIMIT 1), '$.channels')
  ),
  -- 每个频道的线路数：一次聚合，替代「每行一个相关子查询」的 N+1。
  st AS MATERIALIZED (
    SELECT channel_id, COUNT(*) AS n FROM streams GROUP BY channel_id
  )
SELECT json_object(
  -- 响应信封必须是 { code, msg, data }，这是跨端契约（AGENTS.md §4）。
  -- 只拼 data 而不套信封，客户端解析 code 拿到 null → 当成 -1 →
  -- 界面上就是「加载失败：错误码 -1」。
  'code', 0,
  'msg', 'ok',
  'data', json_object(
    'server', json_object('version', 'edge', 'name', ?),
  -- 设备身份与配置。config 来自管理台的「下发配置」，
  -- 客户端据此调解码方式 / 缓冲 / 画面比例。
  'device', json_object(
    'id', ?,
    'name', ?,
    'config', json(
      -- 用 json_patch 把「库里存的」盖在「默认值」上：客户端读的是完整对象，
      -- 只存半个对象（老数据、或者以后换了写法）会让它拿到 undefined。
      json_patch(
        '${DEFAULT_DEVICE_CONFIG}',
        COALESCE(NULLIF((SELECT config_json FROM device_state WHERE device_id = ?), ''), '{}')
      )
    ),
    'config_version', COALESCE(
      (SELECT config_version FROM device_state WHERE device_id = ?), 1
    )
  ),
  -- 与心跳同源的频道指纹：客户端拿到 bootstrap 就记下基线，
  -- 之后心跳里发现变了才重拉，避免每次心跳都白拉一次列表。
  'channels_version', ${CHANNELS_VERSION_SQL},
  'groups', COALESCE((
    SELECT json_group_array(json_object('name', g, 'count', n))
      FROM (
        SELECT COALESCE(o.group_name, c."group") AS g, COUNT(*) AS n
          FROM channels c
          LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
         WHERE COALESCE(o.enabled, c.enabled) = 1
           AND COALESCE(o.group_name, c."group") NOT IN (SELECT value FROM blk_g)
           AND COALESCE(o.name, c.name) NOT IN (SELECT value FROM blk_c)
         GROUP BY g
         ORDER BY n DESC, g ASC
      )
  ), json('[]')),
  -- 电视端列表的「按源分类」第一列。只给 id 和名称，**不带地址**。
  'sources', COALESCE((
    SELECT json_group_array(json_object('id', id, 'name', name))
      FROM (SELECT id, name FROM sources WHERE enabled = 1 ORDER BY sort, id)
  ), json('[]')),
  -- xmltv 节目单地址：客户端自己下载、解析、本地缓存。
  -- 服务端不再存节目数据 —— D1 免费额度按行算，一次全量节目单几千行，
  -- 而它本质上是「客户端要看的参考数据」，不是「服务端要算的状态」。
  -- 地址在重建时从源 m3u 的 x-tvg-url 抄进 last_build。
  'epg_url', COALESCE(
    (SELECT json_extract(v, '$.epg_url') FROM kv WHERE k = 'last_build'), ''
  ),
  'channels', COALESCE((
    SELECT json_group_array(json_object(
      'id', id,
      'number', number,
      'name', name,
      'group', grp,
      'logo', logo,
      'tvg_id', tvg_id,
      'epg_id', epg_id,
      'enabled', json('true'),
      'sort', sort,
      'stream_count', sc,
      'source_id', 0
    ))
      FROM (
        SELECT c.id AS id,
               c.number AS number,
               COALESCE(o.name, c.name) AS name,
               COALESCE(o.group_name, c."group") AS grp,
               COALESCE(o.logo, c.logo) AS logo,
               c.tvg_id AS tvg_id,
               c.epg_id AS epg_id,
               c.sort AS sort,
               COALESCE(st.n, 0) AS sc
          FROM channels c
          LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
          LEFT JOIN st ON st.channel_id = c.id
         WHERE COALESCE(o.enabled, c.enabled) = 1
           AND COALESCE(o.group_name, c."group") NOT IN (SELECT value FROM blk_g)
           AND COALESCE(o.name, c.name) NOT IN (SELECT value FROM blk_c)
         ORDER BY c.sort, c.number, c.id
      )
  ), json('[]')),
  -- 收藏是**按设备**的，所以要绑 device_id
  'favorites', COALESCE((
    SELECT json_group_array(channel_id)
      FROM (
        SELECT channel_id FROM favorites
         WHERE device_id = ?
         ORDER BY created_at, channel_id
      )
  ), json('[]')),
  -- 首页「正在播出」：一次 JOIN 取完当前节目。
  -- 客户端以前拿空数组，首页那栏永远是空的。
  --
  -- 三个参数：窗口起点（now-6h）/ 窗口终点（now）/ 判定用 now。
  -- 加上界是为了走索引范围扫描，否则要扫全部历史节目。
  'now', COALESCE((
    SELECT json_group_array(json_object(
      'channel_id', channel_id,
      'title', title,
      'start', start_at,
      'end', end_at
    ))
      FROM (
        -- 输出的 channel_id 必须是 **JOIN 上的频道**（c.id），不是 p.channel_id。
        -- p.channel_id 是写库时挂的那一个固定频道；一个 xmltv 节目源被多个
        -- 频道共用时，用它输出会让所有频道都显示成同一个 id，
        -- 客户端按 id 匹配就只有一个台能看到「正在播」。
        SELECT c.id AS channel_id, p.title AS title,
               p.start_at AS start_at, p.end_at AS end_at
          FROM epg_programs p
          -- 按 epg_id 关联，不是 channel_id：同一个节目源被多个频道共用
          -- （「央视」与「央视-MCP」里都有 CCTV1），写库时 channel_id 只挂了
          -- 其中一个，按 channel_id JOIN 会让另一个频道首页永远没有「正在播」。
          JOIN channels c ON c.epg_id = p.xmltv_id
          LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
         WHERE p.start_at >= ? AND p.start_at <= ? AND p.end_at > ?
           AND COALESCE(o.enabled, c.enabled) = 1
           AND COALESCE(o.group_name, c."group") NOT IN (SELECT value FROM blk_g)
           AND COALESCE(o.name, c.name) NOT IN (SELECT value FROM blk_c)
         GROUP BY c.id
         ORDER BY c.id
      )
  ), json('[]'))
  )
) AS body
`;
