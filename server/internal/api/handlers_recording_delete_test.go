package api

import (
	"context"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"github.com/litv/litv/server/internal/store"
)

// seedRecording 造一条"已完成、有文件"的录制记录。
func seedRecording(t *testing.T, a *API, channelID int64, path string) int64 {
	t.Helper()
	ctx := context.Background()
	id, err := a.st.CreateRecording(ctx, store.Recording{
		ChannelID: channelID,
		Name:      "测试录制",
		StartAt:   1000,
		EndAt:     2000,
	})
	if err != nil {
		t.Fatalf("建录制失败: %v", err)
	}
	if err := a.st.UpdateRecording(ctx, id, "finished", path, int64(len("payload"))); err != nil {
		t.Fatalf("更新录制状态失败: %v", err)
	}
	return id
}

func writeFile(t *testing.T, path string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatalf("建目录失败: %v", err)
	}
	if err := os.WriteFile(path, []byte("payload"), 0o644); err != nil {
		t.Fatalf("写文件失败: %v", err)
	}
}

// TestDeleteRecordingRemovesFileInsideRecordDir 是本轮 A4 的核心回归。
//
// 修复前必然失败：main 把录制目录算进局部变量 recDir，从不回写 cfg.Record.Dir，
// 于是默认配置下 handler 里的判断是
//
//	strings.HasPrefix(Clean(rec.Path), Clean(""))
//
// 而 Clean("") == "." —— 任何绝对路径都不以 "." 开头，判断恒为假。
// 结果是 DB 行被删了、文件永久留在磁盘上，用户再也找不到它（也没有入口能删）。
func TestDeleteRecordingRemovesFileInsideRecordDir(t *testing.T) {
	a, _ := newTestAPI(t)
	dir := t.TempDir()
	a.SetRecordDir(dir)

	file := filepath.Join(dir, "rec1.ts")
	writeFile(t, file)

	chID, err := a.st.CreateChannel(context.Background(), store.Channel{Name: "台", Enabled: true})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	id := seedRecording(t, a, chID, file)

	rec := adminRequest(t, a, http.MethodDelete, "/api/v1/recordings/"+itoa(id), nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("删除录制失败：%d %s", rec.Code, rec.Body.String())
	}
	if _, serr := os.Stat(file); !os.IsNotExist(serr) {
		t.Fatalf("录制文件仍然存在（应被删除）：%s", file)
	}
	if _, gerr := a.st.GetRecording(context.Background(), id); gerr == nil {
		t.Error("数据库行没有被删除")
	}
}

// TestDeleteRecordingRefusesSiblingPrefix 覆盖「字符串前缀不是目录包含关系」。
//
// 修复前必然失败：dir=/root/record 时 /root/record-old/x.ts 也满足
// strings.HasPrefix —— 一次误删就删到了录制目录之外。
// 现在用 filepath.Rel，必须拒绝，并且**连数据库行都不能删**
// （删了行就只剩一个查不到的孤儿文件）。
func TestDeleteRecordingRefusesSiblingPrefix(t *testing.T) {
	a, _ := newTestAPI(t)
	root := t.TempDir()
	recDir := filepath.Join(root, "record")
	siblingDir := filepath.Join(root, "record-old")
	a.SetRecordDir(recDir)

	outside := filepath.Join(siblingDir, "x.ts")
	writeFile(t, outside)

	chID, _ := a.st.CreateChannel(context.Background(), store.Channel{Name: "台", Enabled: true})
	id := seedRecording(t, a, chID, outside)

	rec := adminRequest(t, a, http.MethodDelete, "/api/v1/recordings/"+itoa(id), nil)
	if rec.Code == http.StatusOK {
		t.Fatalf("同前缀兄弟目录下的文件被当作「录制文件」删掉了：%s", outside)
	}
	if rec.Code != http.StatusConflict {
		t.Errorf("期望 409，实际 %d %s", rec.Code, rec.Body.String())
	}
	if _, serr := os.Stat(outside); serr != nil {
		t.Fatalf("目录外的文件被删除了（这是越界删除）：%v", serr)
	}
	if _, gerr := a.st.GetRecording(context.Background(), id); gerr != nil {
		t.Error("校验失败时不该删数据库行（会留下查不到的孤儿文件）")
	}
}

// TestDeleteRecordingFailsClosedWithoutDir 确认拿不到录制目录时拒绝删除。
func TestDeleteRecordingFailsClosedWithoutDir(t *testing.T) {
	a, _ := newTestAPI(t) // recordDir 保持空
	dir := t.TempDir()
	file := filepath.Join(dir, "rec.ts")
	writeFile(t, file)

	chID, _ := a.st.CreateChannel(context.Background(), store.Channel{Name: "台", Enabled: true})
	id := seedRecording(t, a, chID, file)

	rec := adminRequest(t, a, http.MethodDelete, "/api/v1/recordings/"+itoa(id), nil)
	if rec.Code != http.StatusConflict {
		t.Fatalf("没有录制目录时应 fail closed（409），实际 %d %s", rec.Code, rec.Body.String())
	}
	if _, serr := os.Stat(file); serr != nil {
		t.Fatal("没有录制目录时仍然删了文件")
	}
}

// TestDeleteRecordingRefusesSymlink 确认不跟随符号链接。
func TestDeleteRecordingRefusesSymlink(t *testing.T) {
	a, _ := newTestAPI(t)
	dir := t.TempDir()
	a.SetRecordDir(dir)

	// 真实文件放在目录外，目录内放一个指向它的软链。
	outsideFile := filepath.Join(t.TempDir(), "real.ts")
	writeFile(t, outsideFile)
	link := filepath.Join(dir, "link.ts")
	if err := os.Symlink(outsideFile, link); err != nil {
		t.Skipf("当前环境无法创建符号链接，跳过: %v", err)
	}

	chID, _ := a.st.CreateChannel(context.Background(), store.Channel{Name: "台", Enabled: true})
	id := seedRecording(t, a, chID, link)

	rec := adminRequest(t, a, http.MethodDelete, "/api/v1/recordings/"+itoa(id), nil)
	if rec.Code != http.StatusConflict {
		t.Errorf("符号链接应被拒绝（409），实际 %d %s", rec.Code, rec.Body.String())
	}
	if _, serr := os.Stat(outsideFile); serr != nil {
		t.Fatalf("链接指向的真实文件被删了：%v", serr)
	}
}
