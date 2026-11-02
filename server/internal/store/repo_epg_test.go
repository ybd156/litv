package store

import (
	"context"
	"path/filepath"
	"testing"
)

// TestUpsertEPGChannelRespectsPriority 是 A5（多源合并优先级）的回归测试。
//
// 同一 xmltv_id 出现在多个源时，只有**优先级不低于**当前记录所属源才覆盖。
// 否则低优先级源会把高优先级源的频道名/台标冲掉，多源合并结果就不可预期。
func TestUpsertEPGChannelRespectsPriority(t *testing.T) {
	dir := t.TempDir()
	st, err := Open(filepath.Join(dir, "t.db"))
	if err != nil {
		t.Fatalf("打开库失败: %v", err)
	}
	defer st.Close()
	ctx := context.Background()

	hiID, err := st.CreateEPGSourceEx(ctx, EPGSource{Name: "高优先级", URL: "http://a", Enabled: true, Priority: 10})
	if err != nil {
		t.Fatalf("建源失败: %v", err)
	}
	loID, err := st.CreateEPGSourceEx(ctx, EPGSource{Name: "低优先级", URL: "http://b", Enabled: true, Priority: 1})
	if err != nil {
		t.Fatalf("建源失败: %v", err)
	}

	// 高优先级先写
	if _, err := st.UpsertEPGChannel(ctx, hiID, "cctv1", "CCTV-1 综合（高清源）", "hi.png"); err != nil {
		t.Fatalf("写高优先级频道失败: %v", err)
	}

	// 低优先级后写：不应覆盖
	if _, err := st.UpsertEPGChannel(ctx, loID, "cctv1", "CCTV1（低清源）", "lo.png"); err != nil {
		t.Fatalf("写低优先级频道失败: %v", err)
	}

	var name, icon string
	var srcID int64
	if err := st.DB().QueryRowContext(ctx,
		`SELECT display_name, icon, source_id FROM epg_channels WHERE xmltv_id='cctv1'`).
		Scan(&name, &icon, &srcID); err != nil {
		t.Fatalf("读频道失败: %v", err)
	}
	if name != "CCTV-1 综合（高清源）" {
		t.Errorf("低优先级源覆盖了高优先级：display_name=%q", name)
	}
	if icon != "hi.png" {
		t.Errorf("低优先级源覆盖了台标：icon=%q", icon)
	}
	if srcID != hiID {
		t.Errorf("source_id 被低优先级源抢走：%d（期望 %d）", srcID, hiID)
	}

	// 反过来：更高优先级可以覆盖
	topID, _ := st.CreateEPGSourceEx(ctx, EPGSource{Name: "最高", URL: "http://c", Enabled: true, Priority: 100})
	if _, err := st.UpsertEPGChannel(ctx, topID, "cctv1", "CCTV-1 综合（最高优先级）", "top.png"); err != nil {
		t.Fatalf("写最高优先级频道失败: %v", err)
	}
	if err := st.DB().QueryRowContext(ctx,
		`SELECT display_name FROM epg_channels WHERE xmltv_id='cctv1'`).Scan(&name); err != nil {
		t.Fatalf("读频道失败: %v", err)
	}
	if name != "CCTV-1 综合（最高优先级）" {
		t.Errorf("更高优先级没能覆盖：display_name=%q", name)
	}
}

// TestEPGSourceCRUD 覆盖 A4（编辑）与新增字段的往返。
func TestEPGSourceCRUD(t *testing.T) {
	dir := t.TempDir()
	st, err := Open(filepath.Join(dir, "t.db"))
	if err != nil {
		t.Fatalf("打开库失败: %v", err)
	}
	defer st.Close()
	ctx := context.Background()

	id, err := st.CreateEPGSourceEx(ctx, EPGSource{
		Name: "源A", URL: "http://a/x.xml", Enabled: true,
		Priority: 5, UserAgent: "UA-1", Referer: "http://ref/", Kind: "url",
	})
	if err != nil {
		t.Fatalf("建源失败: %v", err)
	}

	got, err := st.GetEPGSource(ctx, id)
	if err != nil {
		t.Fatalf("读源失败: %v", err)
	}
	if got.Priority != 5 || got.UserAgent != "UA-1" || got.Referer != "http://ref/" || got.Kind != "url" {
		t.Fatalf("新增字段未正确保存: %+v", got)
	}

	// 编辑
	got.Name = "源A-改"
	got.Priority = 9
	got.Enabled = false
	if err := st.UpdateEPGSource(ctx, *got); err != nil {
		t.Fatalf("更新失败: %v", err)
	}
	after, _ := st.GetEPGSource(ctx, id)
	if after.Name != "源A-改" || after.Priority != 9 || after.Enabled {
		t.Fatalf("编辑未生效: %+v", after)
	}

	// 编辑不存在的源要报 ErrNotFound
	missing := *after
	missing.ID = 99999
	if err := st.UpdateEPGSource(ctx, missing); err != ErrNotFound {
		t.Errorf("更新不存在的源应返回 ErrNotFound，实际 %v", err)
	}
}
