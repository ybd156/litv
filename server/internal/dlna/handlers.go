package dlna

import (
	"encoding/xml"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
)

// 设备描述：告诉局域网"我是个 MediaServer，服务在这几个 URL 上"。
func (s *Server) handleDesc(w http.ResponseWriter, r *http.Request) {
	base := s.BaseURL()
	body := fmt.Sprintf(`<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
    <friendlyName>%s</friendlyName>
    <manufacturer>LITV</manufacturer>
    <manufacturerURL>https://github.com/ybd156/litv</manufacturerURL>
    <modelDescription>IPTV 多终端管理平台</modelDescription>
    <modelName>LITV MediaServer</modelName>
    <modelNumber>1.0</modelNumber>
    <serialNumber>1</serialNumber>
    <UDN>%s</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
        <SCPDURL>/dlna/cds.xml</SCPDURL>
        <controlURL>/dlna/cds/control</controlURL>
        <eventSubURL>/dlna/cds/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/dlna/cms.xml</SCPDURL>
        <controlURL>/dlna/cms/control</controlURL>
        <eventSubURL>/dlna/cms/event</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>`, xmlEscape(s.opt.FriendlyName), s.opt.UDN)
	_ = base
	w.Header().Set("Content-Type", `text/xml; charset="utf-8"`)
	_, _ = io.WriteString(w, body)
}

// SCPD 是服务的动作描述。最小实现：只声明我们用到的动作。
func (s *Server) handleCDSScpd(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", `text/xml; charset="utf-8"`)
	_, _ = io.WriteString(w, `<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action>
      <name>Browse</name>
      <argumentList>
        <argument><name>ObjectID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>
        <argument><name>BrowseFlag</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable></argument>
        <argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>
        <argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>
        <argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
        <argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>
        <argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>
        <argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
        <argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
        <argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType><allowedValueList><allowedValue>BrowseMetadata</allowedValue><allowedValue>BrowseDirectChildren</allowedValue></allowedValueList></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_UpdateID</name><dataType>ui4</dataType></stateVariable>
  </serviceStateTable>
</scpd>`)
}

func (s *Server) handleCMSScpd(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", `text/xml; charset="utf-8"`)
	_, _ = io.WriteString(w, `<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action>
      <name>GetProtocolInfo</name>
      <argumentList>
        <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
        <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="yes"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>`)
}

// handleCMSControl 只实现 GetProtocolInfo——不少客户端在 Browse 之前会先问一句。
func (s *Server) handleCMSControl(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if !strings.Contains(string(body), "GetProtocolInfo") {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	resp := `<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
      <Source>http-get:*:application/vnd.apple.mpegurl:*,http-get:*:video/mp2t:*,http-get:*:video/mp4:*</Source>
      <Sink></Sink>
    </u:GetProtocolInfoResponse>
  </s:Body>
</s:Envelope>`
	w.Header().Set("Content-Type", `text/xml; charset="utf-8"`)
	_, _ = io.WriteString(w, resp)
}

// handleCDSControl 处理 Browse：两级目录「分组 → 频道」。
func (s *Server) handleCDSControl(w http.ResponseWriter, r *http.Request) {
	raw, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	body := string(raw)

	objectID := soapArg(body, "ObjectID")
	browseFlag := soapArg(body, "BrowseFlag")
	startIdx := atoiSafe(soapArg(body, "StartingIndex"))
	reqCount := atoiSafe(soapArg(body, "RequestedCount"))
	if browseFlag == "" {
		browseFlag = "BrowseDirectChildren"
	}

	didl, total, err := s.browse(r, objectID, browseFlag, startIdx, reqCount)
	if err != nil {
		slog.Warn("DLNA Browse 失败", "object", objectID, "err", err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	resp := fmt.Sprintf(`<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <Result>%s</Result>
      <NumberReturned>%d</NumberReturned>
      <TotalMatches>%d</TotalMatches>
      <UpdateID>1</UpdateID>
    </u:BrowseResponse>
  </s:Body>
</s:Envelope>`, xmlEscape(didl), countItems(didl), total)

	w.Header().Set("Content-Type", `text/xml; charset="utf-8"`)
	_, _ = io.WriteString(w, resp)
}

// browse 生成 DIDL-Lite。
//
// ObjectID 约定：
//
//	"0"           → 根，列出所有分组
//	"group:<名>"  → 该分组下的频道
func (s *Server) browse(r *http.Request, objectID, browseFlag string, start, count int) (string, int, error) {
	ctx := r.Context()
	var sb strings.Builder
	sb.WriteString(`<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" ` +
		`xmlns:dc="http://purl.org/dc/elements/1.1/" ` +
		`xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">`)

	// 根：列分组
	if objectID == "" || objectID == "0" {
		groups, err := s.src.Groups(ctx)
		if err != nil {
			return "", 0, err
		}
		for _, g := range groups {
			fmt.Fprintf(&sb,
				`<container id="group:%s" parentID="0" restricted="1" searchable="0">`+
					`<dc:title>%s</dc:title>`+
					`<upnp:class>object.container.storageFolder</upnp:class>`+
					`</container>`,
				xmlEscape(g.Name), xmlEscape(fmt.Sprintf("%s (%d)", g.Name, g.Count)))
		}
		sb.WriteString(`</DIDL-Lite>`)
		return sb.String(), len(groups), nil
	}

	// 分组：列频道
	group := strings.TrimPrefix(objectID, "group:")
	items, err := s.src.ChannelsInGroup(ctx, group)
	if err != nil {
		return "", 0, err
	}
	total := len(items)
	if start > 0 && start < len(items) {
		items = items[start:]
	}
	if count > 0 && count < len(items) {
		items = items[:count]
	}

	for _, it := range items {
		url, err := s.src.StreamURL(ctx, it.ID)
		if err != nil || url == "" {
			continue // 没地址的频道不列出来，免得电视点了报错
		}
		logo := ""
		if it.Logo != "" {
			logo = fmt.Sprintf(`<upnp:albumArtURI>%s</upnp:albumArtURI>`, xmlEscape(it.Logo))
		}
		fmt.Fprintf(&sb,
			`<item id="ch:%d" parentID="%s" restricted="1">`+
				`<dc:title>%s</dc:title>`+
				`<upnp:class>object.item.videoItem</upnp:class>`+
				`%s`+
				`<res protocolInfo="http-get:*:application/vnd.apple.mpegurl:*">%s</res>`+
				`</item>`,
			it.ID, xmlEscape(objectID), xmlEscape(it.Name), logo, xmlEscape(url))
	}
	sb.WriteString(`</DIDL-Lite>`)
	return sb.String(), total, nil
}

// ---------------------------------------------------------------- 小工具

func soapArg(body, name string) string {
	open := "<" + name + ">"
	closeTag := "</" + name + ">"
	i := strings.Index(body, open)
	if i < 0 {
		return ""
	}
	rest := body[i+len(open):]
	j := strings.Index(rest, closeTag)
	if j < 0 {
		return ""
	}
	return xmlUnescape(strings.TrimSpace(rest[:j]))
}

func atoiSafe(s string) int {
	n, err := strconv.Atoi(strings.TrimSpace(s))
	if err != nil {
		return 0
	}
	return n
}

func countItems(didl string) int {
	return strings.Count(didl, "<item ")
}

func xmlEscape(s string) string {
	var b strings.Builder
	_ = xml.EscapeText(&b, []byte(s))
	return b.String()
}

func xmlUnescape(s string) string {
	r := strings.NewReplacer(
		"&lt;", "<", "&gt;", ">", "&quot;", `"`, "&apos;", "'", "&amp;", "&",
	)
	return r.Replace(s)
}
