package store

import (
	"context"
	"fmt"
)

// ChannelsVersion 返回一个「频道列表指纹」，用来让客户端判断要不要重新拉列表。
//
// 为什么需要它（真机反馈）：管理员在服务端启用/新增了一个频道，
// 电视端一直看不到 —— 因为客户端只在打开播放页时 bootstrap 一次，
// 之后再没有任何机制告诉它「列表变了」。用户只能杀进程重进。
//
// 为什么用指纹而不是计数器：
// 计数器要在每一个会改频道的写入点手动 +1（导入、改名、启用停用、删除、
// 批量操作、换源、EPG 映射…），漏一处就永远不更新，而且这种漏测不出来。
// 指纹是从数据本身算出来的，改哪儿都会变。
//
// 覆盖的变化：新增 / 删除 / 启用停用 / 排序 / 改名 / 改分组 / 改台标 /
// 改 tvg_id / 改 EPG 映射 / 上游移除标记 / 线路增删。
// 唯一覆盖不到的是「字段改成同样的值」（那本来也不需要刷新）。
//
// 成本：一次 channels 全表扫描 + 一次 streams 计数。3000 个台约 1ms，
// 心跳每 30 秒一次，可以接受。
func (s *Store) ChannelsVersion(ctx context.Context) (string, error) {
	var n, maxID, enabledSum, sortSum, textSum int64
	err := s.db.QueryRowContext(ctx, `
		SELECT COUNT(*),
		       COALESCE(MAX(id), 0),
		       COALESCE(SUM(enabled), 0),
		       COALESCE(SUM(sort), 0),
		       COALESCE(SUM(
		           LENGTH(name) + LENGTH(logo) + LENGTH(group_name) +
		           LENGTH(tvg_id) + LENGTH(epg_id) + src_missing
		       ), 0)
		FROM channels`).Scan(&n, &maxID, &enabledSum, &sortSum, &textSum)
	if err != nil {
		return "", err
	}

	var streams int64
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM streams`).Scan(&streams); err != nil {
		return "", err
	}

	return fmt.Sprintf("c%d-%d-%d-%d-%d-s%d", n, maxID, enabledSum, sortSum, textSum, streams), nil
}
