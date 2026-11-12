package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/litv/litv/server/internal/store"
)

// TestReminderLifecycle 覆盖 C5：设置 / 列表 / 去重 / 删除。
func TestReminderLifecycle(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	chID, err := a.st.CreateChannel(ctx, store.Channel{Number: 1, Name: "CCTV-1", Enabled: true})
	if err != nil {
		t.Fatalf("建频道失败: %v", err)
	}
	tok := boundDeviceToken(t, a, "rem-dev")

	start := time.Now().Add(30 * time.Minute).Unix()

	// 设置提醒
	rec := devicePOST(t, a, "/api/v1/tv/reminder", tok, map[string]any{
		"channel_id": chID, "title": "新闻联播", "start_at": start,
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("设置提醒失败：%d %s", rec.Code, rec.Body.String())
	}
	var created struct {
		Data struct {
			ID          int64  `json:"id"`
			ChannelName string `json:"channel_name"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &created); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if created.Data.ID <= 0 {
		t.Fatal("没拿到提醒 id")
	}
	if created.Data.ChannelName != "CCTV-1" {
		t.Errorf("频道名应回填为 CCTV-1，实际 %q", created.Data.ChannelName)
	}

	// 重复设置同一档节目：应返回同一条，不产生两条推送
	rec = devicePOST(t, a, "/api/v1/tv/reminder", tok, map[string]any{
		"channel_id": chID, "title": "新闻联播", "start_at": start,
	})
	var dup struct {
		Data struct {
			ID int64 `json:"id"`
		} `json:"data"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &dup)
	if dup.Data.ID != created.Data.ID {
		t.Errorf("重复设置应复用同一条提醒：%d vs %d", dup.Data.ID, created.Data.ID)
	}

	// 列表里能看到
	rec = deviceGET(t, a, "/api/v1/tv/reminders", tok)
	if rec.Code != http.StatusOK {
		t.Fatalf("列提醒失败：%d", rec.Code)
	}
	var list struct {
		Data struct {
			Total int `json:"total"`
		} `json:"data"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &list)
	if list.Data.Total != 1 {
		t.Errorf("提醒数期望 1，实际 %d", list.Data.Total)
	}

	// 删除
	rec = deviceDELETE(t, a, fmt.Sprintf("/api/v1/tv/reminder/%d", created.Data.ID), tok)
	if rec.Code != http.StatusOK {
		t.Fatalf("删除提醒失败：%d %s", rec.Code, rec.Body.String())
	}
	rec = deviceGET(t, a, "/api/v1/tv/reminders", tok)
	_ = json.Unmarshal(rec.Body.Bytes(), &list)
	if list.Data.Total != 0 {
		t.Errorf("删除后应为 0，实际 %d", list.Data.Total)
	}
}

// TestReminderRejectsPastAndFarFuture 确认时间范围校验。
func TestReminderRejectsPastAndFarFuture(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()
	chID, _ := a.st.CreateChannel(ctx, store.Channel{Number: 1, Name: "台", Enabled: true})
	tok := boundDeviceToken(t, a, "rem-dev2")

	cases := []struct {
		name    string
		startAt int64
	}{
		{"已经开始", time.Now().Add(-2 * time.Hour).Unix()},
		{"太远（超过 7 天）", time.Now().Add(30 * 24 * time.Hour).Unix()},
	}
	for _, c := range cases {
		rec := devicePOST(t, a, "/api/v1/tv/reminder", tok, map[string]any{
			"channel_id": chID, "title": "x", "start_at": c.startAt,
		})
		if rec.Code == http.StatusOK {
			t.Errorf("%s：应当被拒绝，实际 200", c.name)
		}
	}
}

// TestDueRemindersWindow 确认只推最近 10 分钟内到点的提醒。
//
// 服务停过一段时间再起来时，不应该把几天前的提醒全部补推一遍。
func TestDueRemindersWindow(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()
	now := time.Now().Unix()

	// 刚过点（应推）
	freshID, _ := a.st.CreateReminder(ctx, 1, 1, "台", "刚开播", now-60)
	// 很久以前（不该推）
	oldID, _ := a.st.CreateReminder(ctx, 1, 1, "台", "早就播完了", now-3600)

	due, err := a.st.DueReminders(ctx, now)
	if err != nil {
		t.Fatalf("取待推提醒失败: %v", err)
	}
	foundFresh, foundOld := false, false
	for _, r := range due {
		if r.ID == freshID {
			foundFresh = true
		}
		if r.ID == oldID {
			foundOld = true
		}
	}
	if !foundFresh {
		t.Error("刚过点的提醒应当被推送")
	}
	if foundOld {
		t.Error("一小时前的提醒不该补推——那对用户是骚扰")
	}
}

// TestDeviceRecordRequiresFFmpeg 确认录制不可用时给出明确错误而不是静默失败。
func TestDeviceRecordRequiresFFmpeg(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()
	chID, _ := a.st.CreateChannel(ctx, store.Channel{Number: 1, Name: "台", Enabled: true})
	tok := boundDeviceToken(t, a, "rec-dev")

	// 测试环境没有配置 a.rec，应当返回 503 并说明原因
	rec := devicePOST(t, a, "/api/v1/tv/record", tok, map[string]any{
		"channel_id": chID, "duration_sec": 60,
	})
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("期望 503（未配置 ffmpeg），实际 %d %s", rec.Code, rec.Body.String())
	}
}

// ---------- 设备请求辅助 ----------

// jsonBody 把任意值编码成请求体。
func jsonBody(t *testing.T, v any) *bytes.Buffer {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("编码请求体失败: %v", err)
	}
	return bytes.NewBuffer(b)
}

func devicePOST(t *testing.T, a *API, path, token string, body any) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, path, jsonBody(t, body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()
	a.Handler(false).ServeHTTP(rec, req)
	return rec
}

func deviceDELETE(t *testing.T, a *API, path, token string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodDelete, path, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()
	a.Handler(false).ServeHTTP(rec, req)
	return rec
}
