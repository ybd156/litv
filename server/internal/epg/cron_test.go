package epg

import (
	"testing"
	"time"
)

// TestCronMatchesBasic 验证常用 cron 写法。
//
// A1 的调度器完全依赖这个匹配器：写错了就永远不会自动刷新，
// 而且不会有任何报错——所以必须逐种语法钉住。
func TestCronMatchesBasic(t *testing.T) {
	// 2026-11-03 是周一
	base := time.Date(2026, 11, 3, 3, 0, 0, 0, time.Local)

	cases := []struct {
		expr string
		t    time.Time
		want bool
	}{
		{"0 3 * * *", base, true},
		{"0 3 * * *", base.Add(time.Minute), false},
		{"*/15 * * * *", time.Date(2026, 11, 3, 3, 15, 0, 0, time.Local), true},
		{"*/15 * * * *", time.Date(2026, 11, 3, 3, 16, 0, 0, time.Local), false},
		{"0 0-6 * * *", time.Date(2026, 11, 3, 5, 0, 0, 0, time.Local), true},
		{"0 0-6 * * *", time.Date(2026, 11, 3, 7, 0, 0, 0, time.Local), false},
		{"0 3,9,15 * * *", time.Date(2026, 11, 3, 9, 0, 0, 0, time.Local), true},
		{"0 3,9,15 * * *", time.Date(2026, 11, 3, 10, 0, 0, 0, time.Local), false},
		{"0 3 1-7 * *", time.Date(2026, 11, 3, 3, 0, 0, 0, time.Local), true},
		{"0 3 8-14 * *", time.Date(2026, 11, 3, 3, 0, 0, 0, time.Local), false},
		// 2026-11-03 是周二，周字段 2 命中、1（周一）不命中
		{"0 3 * * 2", base, true},
		{"0 3 * * 1", base, false},
	}

	for _, c := range cases {
		if got := Matches(c.expr, c.t); got != c.want {
			t.Errorf("Matches(%q, %s) = %v，期望 %v", c.expr, c.t.Format("2006-01-02 15:04 Mon"), got, c.want)
		}
	}
}

// TestCronSundayAsSeven 确认 7 也表示周日（部分人这么写）。
func TestCronSundayAsSeven(t *testing.T) {
	sunday := time.Date(2026, 11, 8, 3, 0, 0, 0, time.Local)
	if sunday.Weekday() != time.Sunday {
		t.Fatalf("测试前提不成立：2026-11-08 不是周日")
	}
	if !Matches("0 3 * * 7", sunday) {
		t.Error("周字段 7 应当表示周日")
	}
	if !Matches("0 3 * * 0", sunday) {
		t.Error("周字段 0 应当表示周日")
	}
}

// TestCronRejectsMalformed 确认非法表达式返回 false（宁可不同步，也不乱跑）。
func TestCronRejectsMalformed(t *testing.T) {
	now := time.Now()
	for _, expr := range []string{
		"", "0 3 * *", "0 3 * * * *", "abc", "60 * * * *", "0 25 * * *",
	} {
		if Matches(expr, now) {
			t.Errorf("非法表达式 %q 不应匹配", expr)
		}
	}
}

// TestCronStepWithinRange 验证 a-b/n 写法。
func TestCronStepWithinRange(t *testing.T) {
	at := func(h int) time.Time { return time.Date(2026, 11, 3, h, 0, 0, 0, time.Local) }
	// 8-20 点之间每 4 小时：8,12,16,20
	for _, h := range []int{8, 12, 16, 20} {
		if !Matches("0 8-20/4 * * *", at(h)) {
			t.Errorf("%d 点应当匹配 0 8-20/4", h)
		}
	}
	for _, h := range []int{9, 13, 21} {
		if Matches("0 8-20/4 * * *", at(h)) {
			t.Errorf("%d 点不应匹配 0 8-20/4", h)
		}
	}
}
