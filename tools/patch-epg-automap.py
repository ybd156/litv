"""频道与 EPG 自动匹配。

问题：EPG 数据导入正常（126 个频道 / 21239 条节目），但 channels.epg_id 全是空的，
所以电视端节目单列、频道行里的「当前节目」永远是空的。

原因：源 M3U 里没有 tvg-id（223 个频道 0 个有），导入时无法自动关联；
而管理台的映射面板要手动一个个点。

改法：按频道名自动匹配 EPG 的 display_name（归一化后比较：去空格、去横线、转小写）。
在 EPG 同步完成后、以及频道导入后各跑一次。
"""
import io

# ---------- 1. store 里加自动匹配 ----------
p = "server/internal/store/repo_probe.go"
try:
    s = io.open(p, encoding="utf-8").read()
except FileNotFoundError:
    p = "server/internal/store/repo.go"
    s = io.open(p, encoding="utf-8").read()

if "AutoMapChannels" not in s:
    s = s.rstrip() + """

// AutoMapChannels 按频道名把频道关联到 EPG 频道。
//
// 为什么需要：源 M3U 里常常没有 tvg-id，导入后 channels.epg_id 全空，
// 电视端的节目单和「当前节目」就永远是空的。管理台虽能手动映射，
// 但几百个频道一个个点不现实。
//
// 归一化规则：去掉空格、横线、下划线，转小写，再去掉常见的「高清/HD」后缀。
// 只填空值，不覆盖用户手动设过的映射。
func (s *Store) AutoMapChannels(ctx context.Context) (int, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, name FROM channels WHERE epg_id=''`)
	if err != nil {
		return 0, err
	}
	type ch struct {
		id   int64
		name string
	}
	var pending []ch
	for rows.Next() {
		var c ch
		if rows.Scan(&c.id, &c.name) == nil {
			pending = append(pending, c)
		}
	}
	rows.Close()
	if len(pending) == 0 {
		return 0, nil
	}

	// EPG 侧：归一化名 -> xmltv_id
	erows, err := s.db.QueryContext(ctx, `SELECT xmltv_id, display_name FROM epg_channels`)
	if err != nil {
		return 0, err
	}
	index := map[string]string{}
	for erows.Next() {
		var xmltv, disp string
		if erows.Scan(&xmltv, &disp) == nil {
			k := normChannelName(disp)
			if k != "" {
				index[k] = xmltv
			}
		}
	}
	erows.Close()

	updated := 0
	for _, c := range pending {
		if xmltv, ok := index[normChannelName(c.name)]; ok {
			if _, err := s.db.ExecContext(ctx,
				`UPDATE channels SET epg_id=? WHERE id=? AND epg_id=''`, xmltv, c.id); err == nil {
				updated++
			}
		}
	}
	return updated, nil
}

// normChannelName 归一化频道名用于匹配：去空格/横线/下划线，转小写，
// 去掉「高清」「HD」这类后缀——源里和 EPG 里的写法经常差这几个字。
func normChannelName(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	for _, cut := range []string{"高清", "标清", "超清", "hd", "sd", " ", "-", "_", "·"} {
		s = strings.ReplaceAll(s, cut, "")
	}
	return s
}
"""

    # 补 import
    for pkg in ("strings", "context"):
        if f'"{pkg}"' not in s.split("func ")[0]:
            s = s.replace("import (\n", f'import (\n\t"{pkg}"\n', 1)

    io.open(p, "w", encoding="utf-8").write(s)
    print("store: 已加 AutoMapChannels")
else:
    print("store: 已有")
