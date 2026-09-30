package connection

import "testing"

// 跨会话检索里最容易悄悄错的一段：次序、截断、没扫动的条数、每条命中归哪条会话。
// 拿掉任何一项，界面上都还是"有结果"，所以这四件事必须各有一条能单独变红的判据。
func bodyOf(provider string, scanned, took float64, hits ...map[string]interface{}) map[string]interface{} {
	list := make([]interface{}, 0, len(hits))
	for _, h := range hits {
		list = append(list, h)
	}
	return map[string]interface{}{
		"provider": provider, "scanned": scanned, "tookMs": took,
		"truncated": false, "hits": list,
	}
}

func hitOf(id, at, text string) map[string]interface{} {
	return map[string]interface{}{"messageId": id, "createTime": at, "content": text}
}

func TestMergeOrdersByTimeAcrossConversations(t *testing.T) {
	out := []convResult{
		{convId: "1", convName: "同事甲", body: bodyOf("keyword", 100, 20,
			hitOf("a", "2026-09-28T01:00:00.000Z", "早的"),
			hitOf("b", "2026-09-30T09:00:00.000Z", "最新的"))},
		{convId: "2", convName: "项目组", body: bodyOf("keyword", 50, 41,
			hitOf("c", "2026-09-29T12:00:00.000Z", "中间的"))},
	}
	hits, agg := mergeConvResults(out, 30)
	got := []string{}
	for _, h := range hits {
		got = append(got, h["messageId"].(string))
	}
	if len(got) != 3 || got[0] != "b" || got[1] != "c" || got[2] != "a" {
		t.Fatalf("各会话分头扫的命中必须合成一条时间倒序的列，实际 %v", got)
	}
	if agg["conversations"] != 2 || agg["skipped"] != 0 {
		t.Errorf("汇总的会话数不对：%v / %v", agg["conversations"], agg["skipped"])
	}
	if agg["scanned"].(float64) != 150 {
		t.Errorf("scanned 要把每条会话各扫的条数加起来，实际 %v", agg["scanned"])
	}
	// tookMs 是"最慢那条会话花了多久"，不是求和（并发跑的，求和会虚报）
	if agg["tookMs"].(float64) != 41 {
		t.Errorf("tookMs 该取最大值，实际 %v", agg["tookMs"])
	}
}

// 命中要带着自己来自哪条会话：点它的人要先找到那条会话
func TestMergeStampsConversationOntoEachHit(t *testing.T) {
	out := []convResult{
		{convId: "7", convName: "同事甲", body: bodyOf("keyword", 3, 5, hitOf("x", "2026-09-30T01:00:00.000Z", "一"), hitOf("y", "2026-09-30T02:00:00.000Z", "二"))},
		{convId: "8", convName: "项目组", body: bodyOf("keyword", 3, 5, hitOf("z", "2026-09-30T03:00:00.000Z", "三"))},
	}
	hits, _ := mergeConvResults(out, 30)
	want := map[string]string{"x": "7", "y": "7", "z": "8"}
	for _, h := range hits {
		id := h["messageId"].(string)
		if h["conversationId"] != want[id] {
			t.Errorf("命中 %s 归错会话：%v（该是 %s）", id, h["conversationId"], want[id])
		}
		if h["conversationName"] == "" || h["conversationName"] == nil {
			t.Errorf("命中 %s 没带会话名，界面上只能显示一个 id", id)
		}
	}
}

// 某条会话没扫动（服务重启、会话被删、超时）不能把整次搜索带走，但必须数出来：
// "翻了 21/22 条会话"和"翻了 22 条"是两句不同的话
func TestMergeCountsSkippedConversations(t *testing.T) {
	out := []convResult{
		{convId: "1", convName: "同事甲", body: bodyOf("keyword", 9, 7, hitOf("a", "2026-09-30T01:00:00.000Z", "在"))},
		{convId: "2", convName: "坏了", err: "search failed: status 500"},
		{convId: "3", convName: "也坏", body: nil},
	}
	hits, agg := mergeConvResults(out, 30)
	if agg["skipped"] != 2 {
		t.Errorf("两条没扫动的会话没数出来：%v", agg["skipped"])
	}
	if agg["conversations"] != 1 {
		t.Errorf("扫动的会话数该是 1，实际 %v", agg["conversations"])
	}
	if len(hits) != 1 {
		t.Fatalf("坏掉的会话把整次搜索带走了：%d 条命中", len(hits))
	}
}

// 超过上限要截，而且 truncated 必须翻真 —— 否则界面上"没有更多了"是假的
func TestMergeTruncatesAtLimit(t *testing.T) {
	out := []convResult{
		{convId: "1", convName: "同事甲", body: bodyOf("keyword", 30, 9,
			hitOf("a", "2026-09-30T01:00:00.000Z", "1"),
			hitOf("b", "2026-09-30T02:00:00.000Z", "2"),
			hitOf("c", "2026-09-30T03:00:00.000Z", "3"))},
	}
	hits, agg := mergeConvResults(out, 2)
	if len(hits) != 2 {
		t.Fatalf("没截到 limit：%d 条", len(hits))
	}
	if agg["truncated"] != true {
		t.Error("截断了却没标 truncated，界面上会谎称已经看全了")
	}
	if hits[0]["messageId"] != "c" {
		t.Errorf("截断要留下最近的那几条，第一行实际是 %v", hits[0]["messageId"])
	}
}

// 一条都没扫到 / 回执形状不对：不能 panic，也不能凭空造命中
func TestMergeSurvivesEmptyAndMalformed(t *testing.T) {
	out := []convResult{
		{convId: "1", convName: "空", body: bodyOf("keyword", 0, 1)},
		{convId: "2", convName: "形状不对", body: map[string]interface{}{"hits": "不是列表"}},
		{convId: "3", convName: "命中不是对象",
			body: map[string]interface{}{"hits": []interface{}{"这格该是对象，回成字符串"}, "scanned": float64(1), "tookMs": float64(2)}},
	}
	hits, agg := mergeConvResults(out, 30)
	if len(hits) != 0 {
		t.Errorf("畸形回执里冒出了命中：%v", hits)
	}
	if agg["provider"] != "keyword" {
		t.Errorf("后端名没带回来：%v", agg["provider"])
	}
}
