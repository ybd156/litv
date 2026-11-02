package store

import (
	"context"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"github.com/litv/litv/server/internal/epg"
)

// UnmatchedChannel 是没匹配上 EPG 的频道（B5）。
//
// BestGuess / BestScore 是"最接近的候选"，但分数没到自动应用的阈值——
// 直接摊给用户看，比让他自己一个个翻 EPG 列表强。
type UnmatchedChannel struct {
	ID        int64  `json:"id"`
	Number    int    `json:"number"`
	Name      string `json:"name"`
	Group     string `json:"group"`
	BestGuess string `json:"best_guess"`
	BestScore int    `json:"best_score"`
}

// MappingConflict 是"多个频道映射到同一个 EPG 频道"的冲突（B6）。
//
// 这几乎总是误匹配：两个直播频道不可能共用一份节目单。
// 不提示的话，用户只会看到"某个台的节目单是另一个台的"。
type MappingConflict struct {
	EpgID    string   `json:"epg_id"`
	Channels []string `json:"channels"`
}

// AutoMapReport 是自动匹配的结果（B1/B2）。
type AutoMapReport struct {
	Scanned   int                `json:"scanned"`
	Matched   int                `json:"matched"`
	Unmatched []UnmatchedChannel `json:"unmatched"`
	Conflicts []MappingConflict  `json:"conflicts"`
}

// autoMapThreshold 是自动应用的最低置信度。
//
// 只应用"归一化后完全一致"和"前缀一致"（90 分以上）这两种高置信情况；
// 更模糊的匹配只在报告里给建议，不擅自改库——映射错了比没映射更糟，
// 用户会看到某个台的节目单是别的台的，而且很难想到是映射错了。
const autoMapThreshold = 90

// AutoMapChannelsReport 按频道名自动匹配，并返回完整报告。
func (s *Store) AutoMapChannelsReport(ctx context.Context) (AutoMapReport, error) {
	rep := AutoMapReport{Unmatched: []UnmatchedChannel{}, Conflicts: []MappingConflict{}}

	rows, err := s.db.QueryContext(ctx,
		`SELECT id, number, name, COALESCE(group_name,'') FROM channels WHERE epg_id=''`)
	if err != nil {
		return rep, err
	}
	type ch struct {
		id     int64
		number int
		name   string
		group  string
	}
	var pending []ch
	for rows.Next() {
		var c ch
		if rows.Scan(&c.id, &c.number, &c.name, &c.group) == nil {
			pending = append(pending, c)
		}
	}
	rows.Close()
	rep.Scanned = len(pending)
	if len(pending) == 0 {
		rep.Conflicts, _ = s.MappingConflicts(ctx)
		return rep, nil
	}

	// EPG 侧：xmltv_id + 显示名
	rows, err = s.db.QueryContext(ctx, `SELECT xmltv_id, COALESCE(NULLIF(display_name,''), xmltv_id) FROM epg_channels`)
	if err != nil {
		return rep, err
	}
	var epgList []epgCand
	index := map[string]string{}
	for rows.Next() {
		var e epgCand
		if rows.Scan(&e.xmltv, &e.disp) == nil {
			epgList = append(epgList, e)
			if k := normChannelName(e.disp); k != "" {
				index[k] = e.xmltv
			}
		}
	}
	rows.Close()

	keys := make([]string, 0, len(index))
	for k := range index {
		keys = append(keys, k)
	}
	// 长名优先，避免「cctv1」吃掉「cctv1综合」
	sort.Slice(keys, func(i, j int) bool { return len(keys[i]) > len(keys[j]) })

	for _, c := range pending {
		n := normChannelName(c.name)
		xmltv, score, ok := matchOne(n, index, keys)

		// 高置信才自动应用
		if ok && score >= autoMapThreshold {
			if _, err := s.db.ExecContext(ctx,
				`UPDATE channels SET epg_id=? WHERE id=? AND epg_id=''`, xmltv, c.id); err == nil {
				rep.Matched++
				continue
			}
		}

		// 没到阈值：给出最佳候选供用户判断（B2）
		bestID, bestScore := "", score
		if !ok {
			bestID, bestScore = bestByScore(c.name, epgList)
		} else {
			bestID = xmltv
		}
		rep.Unmatched = append(rep.Unmatched, UnmatchedChannel{
			ID: c.id, Number: c.number, Name: c.name, Group: c.group,
			BestGuess: bestID, BestScore: bestScore,
		})
	}

	rep.Conflicts, _ = s.MappingConflicts(ctx)
	return rep, nil
}

// matchOne 先精确、再前缀。
func matchOne(n string, index map[string]string, keys []string) (string, int, bool) {
	if n == "" {
		return "", 0, false
	}
	if xmltv, ok := index[n]; ok {
		return xmltv, 100, true
	}
	for _, k := range keys {
		if k == "" || !strings.HasPrefix(n, k) {
			continue
		}
		// 前缀后面紧跟数字就不算（cctv1 不能吃掉 cctv10）
		rest := n[len(k):]
		if rest != "" && rest[0] >= '0' && rest[0] <= '9' {
			continue
		}
		return index[k], 90, true
	}
	return "", 0, false
}

// epgCand 是 EPG 侧的候选（xmltv_id + 显示名）。
type epgCand struct {
	xmltv string
	disp  string
}

// bestByScore 用 epg.MatchScore 找最接近的候选（不应用，只给建议）。
func bestByScore(channelName string, list []epgCand) (string, int) {
	best, bestScore := "", 0
	for _, e := range list {
		if sc := epg.MatchScore(channelName, e.disp); sc > bestScore {
			best, bestScore = e.xmltv, sc
		}
	}
	return best, bestScore
}

// ListUnmatchedChannels 列出所有还没映射 EPG 的频道（B5）。
func (s *Store) ListUnmatchedChannels(ctx context.Context) ([]UnmatchedChannel, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, number, name, COALESCE(group_name,'') FROM channels
		 WHERE epg_id='' ORDER BY number, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []UnmatchedChannel{}
	for rows.Next() {
		var c UnmatchedChannel
		if err := rows.Scan(&c.ID, &c.Number, &c.Name, &c.Group); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// MappingConflicts 找出被多个频道共用的 EPG 映射（B6）。
// conflictSameChannelScore 是"这两个名字其实是同一个台"的最低分。
//
// 70 分对应 epg.MatchScore 的"包含关系且长度接近"：
//
//	CCTV1综合  vs CCTV-1   -> 70
//	cctv1-MCP  vs CCTV-1   -> 70（normalize 会去掉连字符）
//
// 低于这个分说明名字差得远，基本可以断定是映射错了。
const conflictSameChannelScore = 70

// MappingConflicts 找出**真的**映射冲突（B6）。
//
// 关键：同一份 M3U 里一个台常常有多条线路（cctv1-MCP / cctv1-AV3A / CCTV1综合），
// 它们本来就应该共用同一份节目单。如果只按 "epg_id 出现多次" 判断，
// 几百个台的源会报出几十组"冲突"，全是噪音，真正错的那几条反而被淹掉。
//
// 所以这里再加一层判断：只有当一个组里**存在与 EPG 频道名对不上的频道**时，
// 才算冲突。全是同一个台的不同线路就静默跳过。
func (s *Store) MappingConflicts(ctx context.Context) ([]MappingConflict, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT c.epg_id,
		       COALESCE(NULLIF(e.display_name, ''), c.epg_id),
		       GROUP_CONCAT(c.name, '|')
		FROM channels c
		LEFT JOIN epg_channels e ON e.xmltv_id = c.epg_id
		WHERE c.epg_id != ''
		GROUP BY c.epg_id
		HAVING COUNT(*) > 1
		ORDER BY c.epg_id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []MappingConflict{}
	for rows.Next() {
		var epgID, epgName, names string
		if err := rows.Scan(&epgID, &epgName, &names); err != nil {
			return nil, err
		}
		list := strings.Split(names, "|")
		mismatch := 0
		for _, n := range list {
			if epg.MatchScore(n, epgName) < conflictSameChannelScore {
				mismatch++
			}
		}
		if mismatch == 0 {
			// 全是同一个台的不同线路，不算冲突
			continue
		}
		out = append(out, MappingConflict{EpgID: epgID, Channels: list})
	}
	return out, rows.Err()
}

// BatchSetChannelEpgID 批量设置映射（B3）。epgID 为空表示解除映射。
func (s *Store) BatchSetChannelEpgID(ctx context.Context, ids []int64, epgID string) (int, error) {
	if len(ids) == 0 {
		return 0, nil
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	stmt, err := tx.PrepareContext(ctx, `UPDATE channels SET epg_id=? WHERE id=?`)
	if err != nil {
		return 0, err
	}
	defer stmt.Close()
	n := 0
	for _, id := range ids {
		res, e := stmt.ExecContext(ctx, epgID, id)
		if e != nil {
			return 0, e
		}
		if c, _ := res.RowsAffected(); c > 0 {
			n++
		}
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return n, nil
}

// digitsRe 取频道名里的数字串（CCTV-1 → "1"，CCTV5+ → "5"）。
var digitsRe = regexp.MustCompile(`\d+`)

// MapByNumber 按"台号"批量匹配（B4）。
//
// 规则刻意保守：只有当某个数字在 EPG 里**唯一对应**一个频道时才映射。
// 例如 EPG 里只有一个名字带 "5" 的频道，就把名字里带 "5" 的直播频道映射过去。
// 有歧义（多个候选）就跳过，留给用户手动处理——乱映射比不映射更难排查。
func (s *Store) MapByNumber(ctx context.Context) (AutoMapReport, error) {
	rep := AutoMapReport{Unmatched: []UnmatchedChannel{}, Conflicts: []MappingConflict{}}

	rows, err := s.db.QueryContext(ctx,
		`SELECT id, number, name, COALESCE(group_name,'') FROM channels WHERE epg_id=''`)
	if err != nil {
		return rep, err
	}
	type ch struct {
		id     int64
		number int
		name   string
		group  string
	}
	var pending []ch
	for rows.Next() {
		var c ch
		if rows.Scan(&c.id, &c.number, &c.name, &c.group) == nil {
			pending = append(pending, c)
		}
	}
	rows.Close()
	rep.Scanned = len(pending)

	// 数字 -> 该数字在 EPG 里对应的 xmltv_id 集合
	rows, err = s.db.QueryContext(ctx,
		`SELECT xmltv_id, COALESCE(NULLIF(display_name,''), xmltv_id) FROM epg_channels`)
	if err != nil {
		return rep, err
	}
	numIndex := map[string]map[string]bool{}
	for rows.Next() {
		var xmltv, disp string
		if rows.Scan(&xmltv, &disp) != nil {
			continue
		}
		for _, d := range digitsRe.FindAllString(disp, -1) {
			if numIndex[d] == nil {
				numIndex[d] = map[string]bool{}
			}
			numIndex[d][xmltv] = true
		}
	}
	rows.Close()

	for _, c := range pending {
		nums := digitsRe.FindAllString(c.name, -1)
		if len(nums) == 0 {
			// 名字里没数字，退回用台号
			if c.number > 0 {
				nums = []string{strconv.Itoa(c.number)}
			}
		}
		var target string
		ambiguous := false
		for _, d := range nums {
			set := numIndex[d]
			if len(set) != 1 {
				continue // 没有候选，或候选不唯一
			}
			for k := range set {
				if target != "" && target != k {
					ambiguous = true
					break
				}
				target = k
			}
		}
		if ambiguous || target == "" {
			rep.Unmatched = append(rep.Unmatched, UnmatchedChannel{
				ID: c.id, Number: c.number, Name: c.name, Group: c.group,
			})
			continue
		}
		if _, err := s.db.ExecContext(ctx,
			`UPDATE channels SET epg_id=? WHERE id=? AND epg_id=''`, target, c.id); err == nil {
			rep.Matched++
		}
	}

	rep.Conflicts, _ = s.MappingConflicts(ctx)
	return rep, nil
}
