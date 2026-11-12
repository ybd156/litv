package api

import (
	"context"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/litv/litv/server/internal/dlna"
	"github.com/litv/litv/server/internal/store"
)

// dlnaSource 把 store / 签名器 / 转封装接到 DLNA 的 ChannelSource 接口上。
//
// 放一层适配器的原因：dlna 包不该依赖 store 和 auth（否则它没法单独测试）。
type dlnaSource struct {
	a *API
}

func (d dlnaSource) Groups(ctx context.Context) ([]dlna.GroupInfo, error) {
	rows, err := d.a.st.ListGroups(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]dlna.GroupInfo, 0, len(rows))
	for _, r := range rows {
		name, _ := r["name"].(string)
		cnt, _ := r["count"].(int)
		if name == "" {
			name = "未分组"
		}
		out = append(out, dlna.GroupInfo{Name: name, Count: cnt})
	}
	return out, nil
}

func (d dlnaSource) ChannelsInGroup(ctx context.Context, group string) ([]dlna.Item, error) {
	enabled := true
	chans, _, err := d.a.st.ListChannels(ctx, store.ChannelQuery{
		Group:   group,
		Enabled: &enabled,
		Limit:   500,
	})
	if err != nil {
		return nil, err
	}
	out := make([]dlna.Item, 0, len(chans))
	for _, c := range chans {
		out = append(out, dlna.Item{ID: c.ID, Name: c.Name, Logo: c.Logo})
	}
	return out, nil
}

// StreamURL 生成电视能直接播的绝对地址。
//
// DLNA 客户端不会带我们的 device token，所以这里用一枚长期签名（7 天），
// 并且优先给 HLS——绝大多数电视只认 HLS。
func (d dlnaSource) StreamURL(ctx context.Context, channelID int64) (string, error) {
	st, err := d.a.st.BestStream(ctx, channelID)
	if err != nil {
		return "", err
	}
	kind := "hls"
	path := fmt.Sprintf("/live/%d/index.m3u8", channelID)
	if !strings.Contains(strings.ToLower(st.URL), ".m3u8") {
		if d.a.rx == nil || !d.a.rx.Available() {
			// 没有 ffmpeg 就把裸 TS 直接给出去——部分电视/盒子其实能吃 TS
			path = fmt.Sprintf("/live/%d/stream.ts", channelID)
			kind = "ts"
		} else {
			path = fmt.Sprintf("/live/%d/remux.m3u8", channelID)
		}
	}
	_ = kind

	exp := time.Now().Add(7 * 24 * time.Hour).Unix()
	sig := d.a.signer.Sign(channelID, exp, dlnaDeviceKey)
	q := url.Values{}
	q.Set("sig", sig)
	q.Set("exp", strconv.FormatInt(exp, 10))
	q.Set("dk", dlnaDeviceKey)

	base := "http://127.0.0.1"
	if d.a.dlna != nil {
		base = d.a.dlna.BaseURL()
	}
	return base + path + "?" + q.Encode(), nil
}

const dlnaDeviceKey = "dlna"
