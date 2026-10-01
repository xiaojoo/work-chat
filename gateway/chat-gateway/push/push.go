// Package push 是"这个人一台设备都不在线时，把这条消息经厂商通道送到他手机上"那一层。
//
// 一期只做小米（2026-10-01 定的：其余厂商先不做）。为什么需要它：这套后端没有系统推送，
// 消息只能从网关那条 WebSocket 上拿，手机进程一死就什么都收不到；而前台服务那条常驻通知
// 是 Android 8 起的法定凭证，用户不想看到它，就得有真正的推送顶上来。
//
// 没配凭据 = 不启用（和 LiveKit、CONTROL_PROVIDER 同一口径）：连接、落库、界面全照旧，
// 离线的人回到应用里还是靠 SYNC_MISSING 补发，只是不会有系统通知。
//
// 接口按官方《服务器API地址以及参数》（2026-08-03 那版）：
//   POST https://api.xmpush.xiaomi.com/v2/message/regid
//   Authorization: key=<AppSecret>          ← 文档明写"字符 key 必须小写"
//   表单 registration_id 多个用逗号分隔；payload/title/description/restricted_package_name
//   成功 {"result":"ok","code":0}；失败 {"result":"error","reason":...,"code":...}
//   extra 总长度必须 <2048，超长返回 65015
package push

import (
	"chat-gateway/redis"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const (
	defaultBase = "https://api.xmpush.xiaomi.com"
	// 一条通知的正文给多长：小米对 extra 总长度限 2048，这里先自己收住，
	// 不把整条长消息塞进通知栏（也塞不下）
	maxText = 200
)

var (
	provider  = "none"
	appSecret string
	base      = defaultBase
	pkg       = "com.chat.mobile"
	client    = &http.Client{Timeout: 8 * time.Second}
)

// Init 由 main 在配置读完后调一次。没配 provider/secret 就是没接通通道，
// 这件事只在启动时喊一声，别在每次投递时刷屏。
func Init(p, appPackage, secret, baseURL string) {
	provider, appSecret = p, secret
	if appPackage != "" {
		pkg = appPackage
	}
	base = strings.TrimRight(baseURL, "/")
	if base == "" {
		base = defaultBase
	}
	if Enabled() {
		log.Printf("push enabled: provider=%s pkg=%s endpoint=%s", provider, pkg, base)
	} else {
		// 这一句的口径要一眼读得对：写 secret=%v 打的是"空不空"的判断结果，
		// 上一版打成 secret=true，看着像"密钥配好了"其实是"密钥是空的"
		log.Printf("push disabled: provider=%q 密钥已配=%v（离线的人不会有系统通知，回到应用靠补发）",
			provider, secret != "")
	}
}

func Enabled() bool {
	return provider == "xiaomi" && appSecret != ""
}

// Notify 给某个人的所有已登记设备发一条通知栏消息。
// 失败只喊日志：推送是"锦上添花"的那一路，不能因为它把消息投递本身掐断（消息已落库，
// 他回到应用照样看得到）。
func Notify(userID int64, convID, title, text string) {
	if !Enabled() {
		return
	}
	regs := redis.PushRegIds(userID)
	if len(regs) == 0 {
		// 没登记过 regId：要么这台设备还没装带推送的包，要么客户端那半还没接上
		return
	}
	dead, err := sendXiaomi(regs, title, text, convID)
	for _, reg := range dead {
		redis.PushForget(userID, reg)
	}
	if err != nil {
		log.Printf("push failed: user=%d regs=%d err=%v", userID, len(regs), err)
	}
}

// sendXiaomi 发一次，返回"小米说这个 regId 已经失效"的那几个，让调用方摘掉台账。
// 不摘的话每次投递都会带上这个死号，白占一次请求。
func sendXiaomi(regs []string, title, text, convID string) (dead []string, err error) {
	form := url.Values{}
	form.Set("registration_id", strings.Join(regs, ","))
	form.Set("payload", text)
	form.Set("title", title)
	form.Set("description", clip(text))
	form.Set("restricted_package_name", pkg) // 包名兜底：装错包的设备不会收到
	form.Set("extra._conv", clip(convID))
	req, err := http.NewRequest(http.MethodPost, base+"/v2/message/regid",
		strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	// 文档要求的形式就是 key=<小写 key>=AppSecret，不是 Bearer
	req.Header.Set("Authorization", "key="+appSecret)

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	var r struct {
		Result      string `json:"result"`
		Reason      string `json:"reason"`
		Description string `json:"description"`
		Code        int    `json:"code"`
	}
	if e := json.Unmarshal(body, &r); e != nil {
		return nil, fmt.Errorf("推送服务回的不是 JSON: %s", clip(string(body)))
	}
	if r.Result != "ok" && r.Code != 0 {
		// 10028/10029 一类是 regId 无效或不属于本 App，其余算这次没送出去
		if strings.Contains(r.Reason, "registration_id") || strings.Contains(r.Description, "regId") {
			dead = append(dead, regs...)
		}
		return dead, errors.New("推送被拒: code=" + fmt.Sprint(r.Code) + " " + r.Reason)
	}
	return nil, nil
}

func clip(s string) string {
	if len(s) <= maxText {
		return s
	}
	return s[:maxText]
}
