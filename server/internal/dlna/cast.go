package dlna

import (
	"context"
	"encoding/xml"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// Renderer 是局域网里一个可被投屏的 DLNA 设备。
type Renderer struct {
	UDN        string `json:"udn"`
	Name       string `json:"name"`
	Model      string `json:"model"`
	Location   string `json:"location"`
	ControlURL string `json:"control_url"`
}

const ssdpMulticast = "239.255.255.250:1900"

// avTransportType 是投屏需要的服务类型。
const avTransportType = "urn:schemas-upnp-org:service:AVTransport:1"

// DiscoverRenderers 在局域网里找可投屏的设备。
//
// 为什么放在服务端而不是手机端：
//   iOS 从 14 开始对本地网络多播有严格限制，SSDP 需要 Apple 单独批准的
//   multicast 权限（要审核，自签包拿不到）。而 NAS 和电视在同一个局域网，
//   由服务端发 M-SEARCH 没有任何限制——手机只需要调接口拿列表。
//   顺带安卓也不用申请多播权限，两端逻辑还能完全一致。
func DiscoverRenderers(ctx context.Context, timeout time.Duration) ([]Renderer, error) {
	if timeout <= 0 {
		timeout = 2 * time.Second
	}

	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return nil, fmt.Errorf("创建 UDP 失败: %w", err)
	}
	defer conn.Close()

	msg := strings.Join([]string{
		"M-SEARCH * HTTP/1.1",
		"HOST: " + ssdpMulticast,
		`MAN: "ssdp:discover"`,
		"MX: 2",
		"ST: urn:schemas-upnp-org:device:MediaRenderer:1",
		"", "",
	}, "\r\n")

	dst, err := net.ResolveUDPAddr("udp4", ssdpMulticast)
	if err != nil {
		return nil, fmt.Errorf("解析组播地址失败: %w", err)
	}
	// 发两次：UDP 不可靠，丢一个包就搜不到设备
	for i := 0; i < 2; i++ {
		if _, err := conn.WriteToUDP([]byte(msg), dst); err != nil {
			return nil, fmt.Errorf("发送 M-SEARCH 失败: %w", err)
		}
		time.Sleep(120 * time.Millisecond)
	}

	// 收应答。同一个设备可能回多条，按 LOCATION 去重。
	locations := map[string]bool{}
	deadline := time.Now().Add(timeout)
	_ = conn.SetReadDeadline(deadline)
	buf := make([]byte, 4096)
	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
		}
		n, _, err := conn.ReadFromUDP(buf)
		if err != nil {
			break // 超时即结束
		}
		if loc := headerValue(string(buf[:n]), "location"); loc != "" {
			locations[loc] = true
		}
		if time.Now().After(deadline) {
			break
		}
	}

	if len(locations) == 0 {
		return []Renderer{}, nil
	}

	// 并发拉设备描述，避免设备多时串行等待
	var (
		mu   sync.Mutex
		wg   sync.WaitGroup
		out  []Renderer
		cli  = &http.Client{Timeout: 3 * time.Second}
		seen = map[string]bool{}
	)
	for loc := range locations {
		wg.Add(1)
		go func(loc string) {
			defer wg.Done()
			r, err := describe(ctx, cli, loc)
			if err != nil || r == nil {
				return
			}
			mu.Lock()
			if !seen[r.UDN] {
				seen[r.UDN] = true
				out = append(out, *r)
			}
			mu.Unlock()
		}(loc)
	}
	wg.Wait()

	// 按名字排序，界面列表稳定
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if out[j].Name < out[i].Name {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	return out, nil
}

// deviceDescription 只取我们需要的字段。
type deviceDescription struct {
	Device struct {
		FriendlyName string `xml:"friendlyName"`
		ModelName    string `xml:"modelName"`
		UDN          string `xml:"UDN"`
		ServiceList  struct {
			Services []struct {
				ServiceType string `xml:"serviceType"`
				ControlURL  string `xml:"controlURL"`
			} `xml:"service"`
		} `xml:"serviceList"`
	} `xml:"device"`
}

// describe 拉 LOCATION 指向的 XML，解析出名字和 AVTransport 控制地址。
func describe(ctx context.Context, cli *http.Client, location string) (*Renderer, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, location, nil)
	if err != nil {
		return nil, err
	}
	resp, err := cli.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}

	var d deviceDescription
	if err := xml.Unmarshal(body, &d); err != nil {
		return nil, err
	}

	var ctrl string
	for _, s := range d.Device.ServiceList.Services {
		if strings.EqualFold(s.ServiceType, avTransportType) {
			ctrl = s.ControlURL
			break
		}
	}
	if ctrl == "" {
		return nil, fmt.Errorf("设备没有 AVTransport 服务")
	}

	// controlURL 通常是相对路径，要按 LOCATION 的 base 解析成绝对地址
	base, err := url.Parse(location)
	if err != nil {
		return nil, err
	}
	ref, err := url.Parse(ctrl)
	if err != nil {
		return nil, err
	}
	abs := base.ResolveReference(ref).String()

	name := strings.TrimSpace(d.Device.FriendlyName)
	if name == "" {
		name = "DLNA 设备"
	}
	return &Renderer{
		UDN:        strings.TrimSpace(d.Device.UDN),
		Name:       name,
		Model:      strings.TrimSpace(d.Device.ModelName),
		Location:   location,
		ControlURL: abs,
	}, nil
}

// Cast 让指定设备播放 mediaURL（先 SetAVTransportURI，再 Play）。
func Cast(ctx context.Context, controlURL, title, mediaURL string) error {
	if controlURL == "" {
		return fmt.Errorf("控制地址为空")
	}
	meta := didlLite(title, mediaURL)
	setURI := fmt.Sprintf(
		"<InstanceID>0</InstanceID><CurrentURI>%s</CurrentURI><CurrentURIMetaData>%s</CurrentURIMetaData>",
		xmlEscape(mediaURL), xmlEscape(meta))
	if err := soapCall(ctx, controlURL, "SetAVTransportURI", setURI); err != nil {
		return fmt.Errorf("设置播放地址失败: %w", err)
	}
	if err := soapCall(ctx, controlURL, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>"); err != nil {
		return fmt.Errorf("开始播放失败: %w", err)
	}
	return nil
}

// Stop 让设备停止播放。
func Stop(ctx context.Context, controlURL string) error {
	return soapCall(ctx, controlURL, "Stop", "<InstanceID>0</InstanceID>")
}

// soapCall 发一条 UPnP AVTransport 的 SOAP 请求。
func soapCall(ctx context.Context, controlURL, action, inner string) error {
	body := `<?xml version="1.0" encoding="utf-8"?>` +
		`<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" ` +
		`s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">` +
		`<s:Body><u:` + action + ` xmlns:u="` + avTransportType + `">` +
		inner + `</u:` + action + `></s:Body></s:Envelope>`

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, controlURL, strings.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", `text/xml; charset="utf-8"`)
	req.Header.Set("SOAPACTION", `"`+avTransportType+`#`+action+`"`)

	cli := &http.Client{Timeout: 8 * time.Second}
	resp, err := cli.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		// 设备常在响应体里带 UPnPError，读一小段便于排查
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("设备返回 %s: %s", resp.Status, strings.TrimSpace(string(b)))
	}
	return nil
}

// didlLite 生成投屏用的元数据。
//
// 很多电视不认空 metadata，会拒绝播放；给一份最小可用的 DIDL-Lite 兼容性最好。
func didlLite(title, mediaURL string) string {
	return `<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" ` +
		`xmlns:dc="http://purl.org/dc/elements/1.1/" ` +
		`xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">` +
		`<item id="0" parentID="-1" restricted="1">` +
		`<dc:title>` + xmlEscape(title) + `</dc:title>` +
		`<upnp:class>object.item.videoItem</upnp:class>` +
		`<res protocolInfo="http-get:*:video/mp2t:*">` + xmlEscape(mediaURL) + `</res>` +
		`</item></DIDL-Lite>`
}

// 注：xmlEscape 已在 handlers.go 里定义，这里直接复用。

// LanIPv4 对外暴露本机局域网地址（投屏地址要用它拼）。
func LanIPv4() string { return localIPv4() }
