package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"

	"github.com/litv/litv/server/internal/store"
)

// TestChannelListHonorsPageAndSize 是频道列表分页的回归测试。
//
// 修复前必然失败：handler 只认 offset/limit（默认 50），
// 而管理台传的是 page/size —— 两个参数都被忽略，
// 于是永远返回第一页 50 条：用户看到「只有 50 个台」，翻页也没反应。
func TestChannelListHonorsPageAndSize(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	const total = 60
	for i := 0; i < total; i++ {
		if _, err := a.st.CreateChannel(ctx, store.Channel{
			Number: i + 1, Name: fmt.Sprintf("频道%02d", i+1), Enabled: true,
		}); err != nil {
			t.Fatalf("建频道失败: %v", err)
		}
	}

	// 第 1 页：size=50 -> 50 条
	p1 := fetchChannelPage(t, a, "page=1&size=50")
	if p1.Total != total {
		t.Errorf("total 期望 %d，实际 %d", total, p1.Total)
	}
	if len(p1.Items) != 50 {
		t.Errorf("第 1 页期望 50 条，实际 %d", len(p1.Items))
	}

	// 第 2 页：剩下的 10 条
	p2 := fetchChannelPage(t, a, "page=2&size=50")
	if len(p2.Items) != 10 {
		t.Errorf("第 2 页期望 10 条，实际 %d（page 参数没生效？）", len(p2.Items))
	}
	if len(p1.Items) > 0 && len(p2.Items) > 0 && p1.Items[0].ID == p2.Items[0].ID {
		t.Error("第 1 页与第 2 页内容相同——offset 没生效")
	}

	// 管理台「取全部」的场景：size=500 应当拿到全部 60 条
	all := fetchChannelPage(t, a, "page=1&size=500")
	if len(all.Items) != total {
		t.Errorf("size=500 期望 %d 条，实际 %d", total, len(all.Items))
	}
}

// TestChannelListClampsLargeLimit 确认超大 limit 被截到 500 而不是回退成 50。
func TestChannelListClampsLargeLimit(t *testing.T) {
	a, _ := newTestAPI(t)
	ctx := context.Background()

	for i := 0; i < 30; i++ {
		if _, err := a.st.CreateChannel(ctx, store.Channel{
			Number: i + 1, Name: fmt.Sprintf("台%02d", i+1), Enabled: true,
		}); err != nil {
			t.Fatalf("建频道失败: %v", err)
		}
	}
	// limit=9999 应该被截到 500（我们只有 30 个，所以返回 30）
	got := fetchChannelPage(t, a, "limit=9999")
	if len(got.Items) != 30 {
		t.Errorf("超大 limit 应被截到 500 而非回退 50，期望 30 条，实际 %d", len(got.Items))
	}
}

type channelPage struct {
	Total int64           `json:"total"`
	Items []store.Channel `json:"items"`
}

func fetchChannelPage(t *testing.T, a *API, query string) channelPage {
	t.Helper()
	rec := adminRequest(t, a, http.MethodGet, "/api/v1/channels?"+query, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("列频道失败：%d %s", rec.Code, rec.Body.String())
	}
	var out struct {
		Data channelPage `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	return out.Data
}
