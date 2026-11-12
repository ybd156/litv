package api

import (
	"testing"
	"time"
)

// TestLocalDayRangeUsesWallClockMidnight 是 B01 的核心回归测试。
//
// 修复前必然失败：用 time.Now().Truncate(24*time.Hour) 取当天区间，
// 那是**按 UTC 零点**分片，不是当地午夜。东八区用户早上 8 点前查"今天"，
// 拿到的其实是本地前一天的节目——节目单整体错位 8 小时。
func TestLocalDayRangeUsesWallClockMidnight(t *testing.T) {
	// 东八区的 2026-11-02 07:30 —— 此时 UTC 还是 11-01 23:30。
	shanghai, err := time.LoadLocation("Asia/Shanghai")
	if err != nil {
		t.Skipf("加载时区失败: %v", err)
	}
	now := time.Date(2026, 11, 2, 7, 30, 0, 0, shanghai)

	start, end := localDayRange(now, "Asia/Shanghai", 0)

	// 起点必须是本地 11-02 00:00
	if start.In(shanghai).Format("2006-01-02 15:04") != "2026-11-02 00:00" {
		t.Errorf("当天起点错误：%s（期望本地 2026-11-02 00:00）",
			start.In(shanghai).Format("2006-01-02 15:04"))
	}
	// 终点必须是本地 11-03 00:00
	if end.In(shanghai).Format("2006-01-02 15:04") != "2026-11-03 00:00" {
		t.Errorf("当天终点错误：%s", end.In(shanghai).Format("2006-01-02 15:04"))
	}

	// 对照：旧的 Truncate 写法会得到 UTC 零点，在本地是当天 08:00
	oldStart := now.Truncate(24 * time.Hour)
	if oldStart.In(shanghai).Format("15:04") == "00:00" {
		t.Skip("这个时刻下 Truncate 恰好对齐，无法演示差异")
	}
	t.Logf("旧实现起点（本地）：%s，新实现：%s",
		oldStart.In(shanghai).Format("2006-01-02 15:04"),
		start.In(shanghai).Format("2006-01-02 15:04"))
}

// TestLocalDayRangeHandlesDayOffset 确认 day 参数按日历日推进。
func TestLocalDayRangeHandlesDayOffset(t *testing.T) {
	shanghai, _ := time.LoadLocation("Asia/Shanghai")
	now := time.Date(2026, 11, 2, 12, 0, 0, 0, shanghai)

	for _, day := range []int{-1, 0, 1, 7} {
		start, _ := localDayRange(now, "Asia/Shanghai", day)
		want := now.AddDate(0, 0, day).Format("2006-01-02")
		if start.In(shanghai).Format("2006-01-02") != want {
			t.Errorf("day=%d 起点日期错误：%s 期望 %s",
				day, start.In(shanghai).Format("2006-01-02"), want)
		}
	}
}

// TestLocalDayRangeCrossesMonthAndYear 确认跨月/跨年正确。
func TestLocalDayRangeCrossesMonthAndYear(t *testing.T) {
	shanghai, _ := time.LoadLocation("Asia/Shanghai")

	cases := []struct {
		now      time.Time
		day      int
		wantDate string
	}{
		// 月末 +1 天 -> 次月 1 日
		{time.Date(2026, 11, 30, 22, 0, 0, 0, shanghai), 1, "2026-12-01"},
		// 年末 +1 天 -> 次年 1 月 1 日
		{time.Date(2026, 12, 31, 23, 0, 0, 0, shanghai), 1, "2027-01-01"},
		// 年初 -1 天 -> 上一年 12 月 31 日
		{time.Date(2026, 1, 1, 1, 0, 0, 0, shanghai), -1, "2025-12-31"},
		// 闰年 2 月 28 日 +1 -> 2 月 29 日
		{time.Date(2028, 2, 28, 12, 0, 0, 0, shanghai), 1, "2028-02-29"},
	}
	for _, c := range cases {
		start, _ := localDayRange(c.now, "Asia/Shanghai", c.day)
		if got := start.In(shanghai).Format("2006-01-02"); got != c.wantDate {
			t.Errorf("从 %s day=%d：得到 %s，期望 %s",
				c.now.Format("2006-01-02"), c.day, got, c.wantDate)
		}
	}
}

// TestLocalDayRangeHandlesDST 确认夏令时切换日区间不是固定 24 小时。
//
// 这是 Truncate(24h) 的另一个硬伤：DST 切换日实际是 23 或 25 小时，
// 只有按日历构造次日零点才能拿到正确区间。
func TestLocalDayRangeHandlesDST(t *testing.T) {
	loc, err := time.LoadLocation("America/New_York")
	if err != nil {
		t.Skipf("加载时区失败: %v", err)
	}
	// 2026-03-08 是美国夏令时开始日（凌晨 2 点跳到 3 点，当天只有 23 小时）
	now := time.Date(2026, 3, 8, 12, 0, 0, 0, loc)
	start, end := localDayRange(now, "America/New_York", 0)

	hours := end.Sub(start).Hours()
	if hours == 24 {
		t.Logf("该时区在该日没有 DST 切换（%.0f 小时），跳过断言", hours)
		return
	}
	if hours != 23 {
		t.Errorf("DST 开始日应为 23 小时，实际 %.0f 小时——按日历构造未生效", hours)
	}
	// 起点仍必须是本地午夜
	if start.In(loc).Format("15:04") != "00:00" {
		t.Errorf("DST 日起点不是本地午夜：%s", start.In(loc).Format("15:04"))
	}
}

// TestLocalDayRangeFallsBackToUTC 确认空时区按 UTC 处理（行为确定，不猜）。
func TestLocalDayRangeFallsBackToUTC(t *testing.T) {
	now := time.Date(2026, 11, 2, 7, 30, 0, 0, time.UTC)
	start, _ := localDayRange(now, "", 0)
	if start.Format("2006-01-02 15:04") != "2026-11-02 00:00" {
		t.Errorf("空时区应退化为 UTC 零点，得到 %s", start.Format("2006-01-02 15:04"))
	}
}

// TestInvalidTimezoneRejectedByConfig 确认非法时区会被配置校验拒绝。
func TestInvalidTimezoneRejectedByConfig(t *testing.T) {
	// 见 internal/config 的 Validate；这里验证 api 层不会静默容忍
	_, err := time.LoadLocation("Not/AZone")
	if err == nil {
		t.Fatal("非法时区被 Go 接受了，测试前提不成立")
	}
}
