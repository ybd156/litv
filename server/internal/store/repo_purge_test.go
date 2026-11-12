package store

import (
	"context"
	"testing"
)

// TestPurgePlayEventsAndAuditLogs 覆盖「这两张表从来没人清」的修复。
//
// 长期运行的 NAS 上 play_events / audit_logs 只增不减：前者每次起播/卡顿
// 都插一行，后者每个管理操作都插一行。清理必须只删过期的，不能把新数据
// 一起带走（否则「最近 24 小时」的统计会凭空少一截）。
func TestPurgePlayEventsAndAuditLogs(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()
	now := Now()
	old := now - 40*24*3600 // 40 天前

	for _, ts := range []int64{old, now} {
		if _, err := st.DB().ExecContext(ctx,
			`INSERT INTO play_events(device_id,channel_id,event,startup_ms,created_at) VALUES(1,1,'start',100,?)`,
			ts); err != nil {
			t.Fatalf("写播放事件失败: %v", err)
		}
		if _, err := st.DB().ExecContext(ctx,
			`INSERT INTO audit_logs(user_id,action,target,detail,ip,created_at) VALUES(1,'play','1','','',?)`,
			ts); err != nil {
			t.Fatalf("写审计日志失败: %v", err)
		}
	}

	n, err := st.PurgePlayEvents(ctx, now-30*24*3600)
	if err != nil {
		t.Fatalf("清理播放事件失败: %v", err)
	}
	if n != 1 {
		t.Fatalf("清理了 %d 行播放事件，期望 1 行", n)
	}

	n, err = st.PurgeOldAuditLogs(ctx, now-30*24*3600)
	if err != nil {
		t.Fatalf("清理审计日志失败: %v", err)
	}
	if n != 1 {
		t.Fatalf("清理了 %d 行审计日志，期望 1 行", n)
	}

	var events, logs int
	if err := st.DB().QueryRow(`SELECT COUNT(*) FROM play_events`).Scan(&events); err != nil {
		t.Fatal(err)
	}
	if err := st.DB().QueryRow(`SELECT COUNT(*) FROM audit_logs`).Scan(&logs); err != nil {
		t.Fatal(err)
	}
	if events != 1 || logs != 1 {
		t.Fatalf("新数据被误删：play_events=%d audit_logs=%d", events, logs)
	}
}
