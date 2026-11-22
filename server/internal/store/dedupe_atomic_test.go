package store

import (
	"context"
	"fmt"
	"testing"
)

// TestDedupeSourceStreamsIsAtomic 用触发器做故障注入，验证「多记录删除」是原子的。
//
// 为什么必须跨 chunk：分块是 400 条一块。如果只在第一块里失败，
// 单条 `DELETE ... WHERE id IN (...)` 本身就是原子的——测试会假绿。
// 所以这里造 450 组重复线路（去重后 450 条待删 → 第一块 400、第二块 50），
// 让第二块里的某条 id 触发 ABORT：
//   - 有事务：整笔回滚，900 条一条不少；
//   - 没事务（旧实现）：第一块 400 条已经删掉了 —— 这正是要抓的「删了一半」。
func TestDedupeSourceStreamsIsAtomic(t *testing.T) {
	ctx := context.Background()
	st := openTestStore(t)

	chID, err := st.CreateChannel(ctx, Channel{Number: 1, Name: "去重测试台", Group: "测试", Enabled: true})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}

	const pairs = 450
	var list []importStream
	var abortID int64
	for i := 0; i < pairs; i++ {
		u := fmt.Sprintf("http://example.com/dup-%03d.ts", i)
		first, err := st.AddStream(ctx, chID, u, "", 0)
		if err != nil {
			t.Fatalf("插线路失败: %v", err)
		}
		second, err := st.AddStream(ctx, chID, u, "", 0)
		if err != nil {
			t.Fatalf("插线路失败: %v", err)
		}
		list = append(list, importStream{id: first, url: u, chID: chID})
		list = append(list, importStream{id: second, url: u, chID: chID})
		// 最后一组的较小 id 会落在第二块里（去重保留较大的，删较小的）
		abortID = first
	}

	// 故障注入：只针对第二块里的那条 id 失败。
	if _, err := st.DB().ExecContext(ctx,
		fmt.Sprintf(`CREATE TRIGGER boom BEFORE DELETE ON streams WHEN old.id = %d BEGIN SELECT RAISE(ABORT, 'boom'); END`, abortID)); err != nil {
		t.Fatalf("建触发器失败: %v", err)
	}

	byCh := map[int64][]importStream{chID: list}
	if err := st.dedupeSourceStreams(ctx, byCh); err == nil {
		t.Fatal("触发 ABORT 了，去重却返回 nil")
	}

	// 关键断言：一条都不能少。
	if n := countRows(t, st, "streams", chID); n != pairs*2 {
		t.Fatalf("失败后线路数 = %d，期望 %d——部分删除说明删除没包在同一个事务里", n, pairs*2)
	}
}
