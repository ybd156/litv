package blocklist

import (
	"encoding/json"
	"errors"
	"testing"
)

func TestParseEmptyAndBroken(t *testing.T) {
	for _, raw := range []string{"", "   ", "{", "null", `{"groups":"购物"}`, "[1,2,3]"} {
		l := Parse(raw)
		if !l.Empty() {
			t.Fatalf("Parse(%q) 应当是空清单，得到 %+v", raw, l)
		}
	}
}

func TestParseNormalizes(t *testing.T) {
	l := Parse(`{"groups":[" 购物 ","购物","","  "],"channels":["CCTV-购物"]}`)
	if len(l.Groups) != 1 || l.Groups[0] != "购物" {
		t.Fatalf("分组应当去空白去重，得到 %v", l.Groups)
	}
	if len(l.Channels) != 1 || l.Channels[0] != "CCTV-购物" {
		t.Fatalf("频道名解析错：%v", l.Channels)
	}
}

func TestEncodeRoundTrip(t *testing.T) {
	var l List
	if err := l.Add("group", " 购物 "); err != nil {
		t.Fatal(err)
	}
	if err := l.Add("channel", "某台"); err != nil {
		t.Fatal(err)
	}
	raw, err := l.Encode()
	if err != nil {
		t.Fatal(err)
	}
	back := Parse(raw)
	if !back.BlocksGroup("购物") || !back.BlocksChannel("某台") {
		t.Fatalf("往返后规则丢了：%s", raw)
	}
	// 空清单也要是合法 JSON（人工看 kv 时不至于一脸问号）
	emptyRaw, err := List{}.Encode()
	if err != nil {
		t.Fatal(err)
	}
	var probe map[string]any
	if err := json.Unmarshal([]byte(emptyRaw), &probe); err != nil {
		t.Fatalf("空清单应当是可解析 JSON：%v", err)
	}
}

func TestBlocks(t *testing.T) {
	l := List{Groups: []string{"购物"}, Channels: []string{"CCTV-1"}}
	cases := []struct {
		group, name string
		want        bool
	}{
		{"购物", "随便什么台", true},        // 整类被挡
		{"电影", "CCTV-1", true},        // 单台被挡
		{"电影", "cctv-1", true},        // 单台不分大小写
		{"电影", "CCTV-1 综合", false},    // 精确匹配，不做前缀
		{"购物频道", "某某", false},         // 分类不匹配就是放行
		{"", "", false},                // 空频道名不算命中
		{"其他", "CCTV-1", true},         // 名字命中优先于分类
	}
	for _, c := range cases {
		if got := l.Blocks(c.group, c.name); got != c.want {
			t.Fatalf("Blocks(%q,%q)=%v，期望 %v", c.group, c.name, got, c.want)
		}
	}
}

func TestAddRemoveIdempotent(t *testing.T) {
	var l List
	if err := l.Add("group", "购物"); err != nil {
		t.Fatal(err)
	}
	if err := l.Add("group", "购物"); err != nil {
		t.Fatalf("重复加同一条应当幂等：%v", err)
	}
	if len(l.Groups) != 1 {
		t.Fatalf("重复加不该出现两条：%v", l.Groups)
	}
	if err := l.Remove("group", "购物"); err != nil {
		t.Fatal(err)
	}
	if err := l.Remove("group", "购物"); err != nil {
		t.Fatalf("删不存在的规则应当幂等：%v", err)
	}
	if !l.Empty() {
		t.Fatalf("删完应当是空清单：%+v", l)
	}
}

func TestAddRejectsBadInput(t *testing.T) {
	var l List
	if err := l.Add("nope", "x"); !errors.Is(err, ErrBadKind) {
		t.Fatalf("未知 kind 应当报 ErrBadKind，得到 %v", err)
	}
	if err := l.Add("group", "   "); !errors.Is(err, ErrEmptyValue) {
		t.Fatalf("空名字应当报 ErrEmptyValue，得到 %v", err)
	}
	if err := l.Remove("channel", ""); !errors.Is(err, ErrEmptyValue) {
		t.Fatalf("删除空名字应当报 ErrEmptyValue，得到 %v", err)
	}
}
