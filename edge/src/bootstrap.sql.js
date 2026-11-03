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
//
// 排序假设：内层子查询带 ORDER BY，外层 json_group_array 按子查询产出顺序聚合。
// SQLite 实际行为如此（selftest 有断言），但这不是文档承诺——
// 万一哪天顺序乱了，只是频道排序不对，不会坏。
export const BOOTSTRAP_SQL = `
SELECT json_object(
  'server', json_object('version', 'edge', 'name', ?),
  'device', json_object(
    'id', 0,
    'name', 'TV',
    'config', json_object(
      'decode', 'auto',
      'buffer_ms', 3000,
      'aspect', 'auto',
      'autoplay', json('true'),
      'engine', 'auto'
    )
  ),
  'groups', COALESCE((
    SELECT json_group_array(json_object('name', g, 'count', n))
      FROM (
        SELECT COALESCE(o.group_name, c."group") AS g, COUNT(*) AS n
          FROM channels c
          LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
         WHERE COALESCE(o.enabled, c.enabled) = 1
         GROUP BY g
         ORDER BY n DESC, g ASC
      )
  ), json('[]')),
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
               (SELECT COUNT(*) FROM streams s WHERE s.channel_id = c.id) AS sc
          FROM channels c
          LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
         WHERE COALESCE(o.enabled, c.enabled) = 1
         ORDER BY c.sort, c.number, c.id
      )
  ), json('[]')),
  'favorites', json('[]'),
  'now', json('[]'),
  'sources', json('[]')
) AS body
`;
