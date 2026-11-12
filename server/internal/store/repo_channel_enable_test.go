package store

import (
	"context"
	"testing"
)

// TestEnableChannelClearsMissingFlag 真机反馈回归。
//
// 现象：管理员在管理台把一个台「启用」，电视端却永远看不到它。
//
// 根因：上游刷新时把频道标了 src_missing=1（"上游已移除"），
// 而电视端取列表用的是 `enabled=1 AND src_missing=0`。
// PatchChannel 只改 enabled、不清 src_missing，于是
// 「管理台显示已启用」和「客户端看不到」同时成立，
// 只能等下次源刷新恰好又包含这个台才会恢复。
//
// 用户手动启用 = 明确表示"我要这个台"，必须覆盖自动判定。
func TestEnableChannelClearsMissingFlag(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()

	srcID := seedSource(t, st, "src-mcp")
	chID, err := st.CreateChannel(ctx, Channel{
		Name: "MCP测试台", Group: "测试", Enabled: true, SourceID: srcID,
	})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}

	// 模拟上游刷新：这个台在上游消失了 → 被标成 missing
	if _, err := st.DB().ExecContext(ctx,
		`UPDATE channels SET src_missing=1, missing_since=? WHERE id=?`, Now(), chID); err != nil {
		t.Fatalf("标记 missing 失败: %v", err)
	}

	// 前置确认：标了 missing 之后，客户端拿不到它（这就是"看不到"的状态）
	list, err := st.ListAllEnabledChannels(ctx)
	if err != nil {
		t.Fatalf("取列表失败: %v", err)
	}
	for _, c := range list {
		if c.ID == chID {
			t.Fatal("前置条件不成立：标了 missing 的频道不该出现在客户端列表里")
		}
	}

	// 管理员在管理台点「启用」
	enabled := true
	if err := st.PatchChannel(ctx, chID, ChannelPatch{Enabled: &enabled}); err != nil {
		t.Fatalf("启用频道失败: %v", err)
	}

	// 关键断言：src_missing 必须被清掉，客户端要能重新看到它
	list2, err := st.ListAllEnabledChannels(ctx)
	if err != nil {
		t.Fatalf("取列表失败: %v", err)
	}
	found := false
	for _, c := range list2 {
		if c.ID == chID {
			found = true
			break
		}
	}
	if !found {
		t.Fatal("启用后客户端仍看不到这个台——src_missing 没被清掉（真机反馈的就是这个）")
	}

	var missing, since int64
	if err := st.DB().QueryRowContext(ctx,
		`SELECT src_missing, missing_since FROM channels WHERE id=?`, chID).Scan(&missing, &since); err != nil {
		t.Fatalf("读回失败: %v", err)
	}
	if missing != 0 || since != 0 {
		t.Fatalf("src_missing/missing_since 未清零：missing=%d since=%d", missing, since)
	}
}

// TestDisableChannelKeepsMissingFlag 反向保护：停用不该动 missing 标记。
//
// 停用是"我不想看"，和"上游没有"是两件事，混在一起会让
// 用户重新启用时误以为台回来了。
func TestDisableChannelKeepsMissingFlag(t *testing.T) {
	st := openTestStore(t)
	ctx := context.Background()

	srcID := seedSource(t, st, "src-keep")
	chID, err := st.CreateChannel(ctx, Channel{
		Name: "停用台", Group: "测试", Enabled: true, SourceID: srcID,
	})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	if _, err := st.DB().ExecContext(ctx,
		`UPDATE channels SET src_missing=1, missing_since=? WHERE id=?`, Now(), chID); err != nil {
		t.Fatalf("标记 missing 失败: %v", err)
	}

	disabled := false
	if err := st.PatchChannel(ctx, chID, ChannelPatch{Enabled: &disabled}); err != nil {
		t.Fatalf("停用失败: %v", err)
	}

	var missing int64
	if err := st.DB().QueryRowContext(ctx,
		`SELECT src_missing FROM channels WHERE id=?`, chID).Scan(&missing); err != nil {
		t.Fatalf("读回失败: %v", err)
	}
	if missing != 1 {
		t.Fatal("停用把 missing 标记也清掉了——两者应该是独立的")
	}
}
