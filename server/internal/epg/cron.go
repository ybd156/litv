package epg

import (
	"strconv"
	"strings"
	"time"
)

// Matches 判断 5 段 cron 表达式是否匹配 t（精确到分钟）。
//
// 支持的语法（够用且好验证，不引入第三方库）：
//
//	*        任意值
//	5        精确值
//	1-5      区间
//	*/15     步长
//	1-5/2    区间内步长
//	1,3,5    列表（可与上面任意组合）
//
// 字段顺序：分 时 日 月 周（周 0/7 都表示周日）。
//
// 表达式非法时返回 false —— 宁可不同步，也不要在配置写错时疯狂重试。
func Matches(expr string, t time.Time) bool {
	fields := strings.Fields(strings.TrimSpace(expr))
	if len(fields) != 5 {
		return false
	}
	dom := t.Day()
	month := int(t.Month())
	dow := int(t.Weekday()) // 0=周日

	if !matchField(fields[0], t.Minute(), 0, 59) {
		return false
	}
	if !matchField(fields[1], t.Hour(), 0, 23) {
		return false
	}
	if !matchField(fields[2], dom, 1, 31) {
		return false
	}
	if !matchField(fields[3], month, 1, 12) {
		return false
	}
	// 周字段允许 0 和 7 都表示周日
	dowField := fields[4]
	if matchField(dowField, dow, 0, 6) {
		return true
	}
	if dow == 0 && matchField(dowField, 7, 0, 7) {
		return true
	}
	return false
}

// matchField 判断单个字段是否命中某个值。
func matchField(field string, v, min, max int) bool {
	for _, part := range strings.Split(field, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		// 裸 * 就是通配。这一步必须在最前面：
		// 否则 "*" 会落到下面的精确值分支，Atoi("*") 失败，整条表达式永远不匹配
		// ——调度器看着配好了，实际上一次都不会触发。
		if part == "*" {
			return true
		}
		// 步长：*/n 或 a-b/n
		if slash := strings.Index(part, "/"); slash >= 0 {
			base := part[:slash]
			stepStr := part[slash+1:]
			step, err := strconv.Atoi(stepStr)
			if err != nil || step <= 0 {
				continue
			}
			lo, hi := min, max
			if base != "*" {
				l, h, ok := parseRange(base, min, max)
				if !ok {
					continue
				}
				lo, hi = l, h
			}
			if v >= lo && v <= hi && (v-lo)%step == 0 {
				return true
			}
			continue
		}
		// 区间
		if strings.Contains(part, "-") {
			l, h, ok := parseRange(part, min, max)
			if ok && v >= l && v <= h {
				return true
			}
			continue
		}
		// 精确值
		if n, err := strconv.Atoi(part); err == nil && n == v {
			return true
		}
	}
	return false
}

// parseRange 解析 "a-b"。
func parseRange(s string, min, max int) (int, int, bool) {
	parts := strings.SplitN(s, "-", 2)
	if len(parts) != 2 {
		return 0, 0, false
	}
	lo, err1 := strconv.Atoi(strings.TrimSpace(parts[0]))
	hi, err2 := strconv.Atoi(strings.TrimSpace(parts[1]))
	if err1 != nil || err2 != nil {
		return 0, 0, false
	}
	if lo > hi || lo < min || hi > max {
		return 0, 0, false
	}
	return lo, hi, true
}
