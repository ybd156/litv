package api

import (
	"context"
	"net/http"
	"path/filepath"
	"strings"
	"testing"

	"github.com/litv/litv/server/internal/store"
)

// TestRecordingFileRefusesPathOutsideRecordDir 覆盖读侧的目录穿越。
//
// 删除侧早就有「必须证明路径属于录制目录」的校验（recordPathInside），
// 但读取侧之前只 os.Stat 一下就交给 http.ServeFile：
// 只要数据库里的 rec.Path 被写坏（历史 bug、导入、手工改库），
// 这个接口就是**任意本地文件下载**。
//
// 期望：目录外的路径一律 404，且响应体里绝不能出现文件内容。
func TestRecordingFileRefusesPathOutsideRecordDir(t *testing.T) {
	a, _ := newTestAPI(t)
	dir := t.TempDir()
	a.SetRecordDir(dir)

	// 故意放在录制目录之外
	outsideDir := t.TempDir()
	outside := filepath.Join(outsideDir, "secret.ts")
	writeFile(t, outside)

	chID, err := a.st.CreateChannel(context.Background(), store.Channel{Name: "台", Enabled: true})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	id := seedRecording(t, a, chID, outside)

	rec := adminRequest(t, a, http.MethodGet, "/api/v1/recordings/"+itoa(id)+"/file", nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("GET 目录外录制文件 = %d，期望 404（不能把任意本地文件吐出去）", rec.Code)
	}
	if strings.Contains(rec.Body.String(), "payload") {
		t.Fatal("响应体里出现了目录外文件的内容")
	}
}

// TestRecordingFileServesFileInsideRecordDir 保证上面那条收紧没有误伤正常路径。
func TestRecordingFileServesFileInsideRecordDir(t *testing.T) {
	a, _ := newTestAPI(t)
	dir := t.TempDir()
	a.SetRecordDir(dir)

	file := filepath.Join(dir, "ok.ts")
	writeFile(t, file)

	chID, err := a.st.CreateChannel(context.Background(), store.Channel{Name: "台", Enabled: true})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	id := seedRecording(t, a, chID, file)

	rec := adminRequest(t, a, http.MethodGet, "/api/v1/recordings/"+itoa(id)+"/file", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET 目录内录制文件 = %d，期望 200", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "payload") {
		t.Fatal("正常路径没拿到文件内容")
	}
}
