<template>
  <div class="lg">
    <!-- frame:false 的窗口没了标题栏就得自己给一条拖拽区，不然窗口挪不动 -->
    <div class="drag" aria-hidden="true" />

    <div class="card">
      <section class="left">
        <div class="brand">
          <span class="brand-ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 6.5h16v10H12l-4 3v-3H4z" /><path d="M8 10h8M8 13h5" />
            </svg>
          </span>
          <div class="brand-tx">
            <b>Content Chat</b>
            <small>让内容协作更简单</small>
          </div>
        </div>

        <h1 class="hi">{{ mode === 'login' ? '欢迎回来！' : '创建账号' }}</h1>
        <p class="sub">{{ mode === 'login' ? '高效的内容协作平台，连接团队、内容与 AI' : '注册后即可加入团队会话、文档与任务' }}</p>

        <div class="tabs" role="tablist" aria-label="登录或注册">
          <button type="button" role="tab" class="tab" :class="{ on: mode === 'login' }"
                  :aria-selected="mode === 'login'" @click="switchTo('login')">登 录</button>
          <button type="button" role="tab" class="tab" :class="{ on: mode === 'register' }"
                  :aria-selected="mode === 'register'" @click="switchTo('register')">注 册</button>
        </div>

        <!-- 两个表单叠在同一个 grid 单元格里：容器高度=较高那个，切页签整块不会上下跳 -->
        <div class="panes">
        <!-- 登录表单一直挂着（v-show），切回来时输入和校验状态都还在 -->
        <form :class="{ off: mode !== 'login' }" class="fm" @submit.prevent="handleLogin" novalidate>
          <div class="fld" :class="{ bad: errors.account }">
            <span class="fld-ic" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5.5" width="18" height="13" rx="2.5" /><path d="M3.6 7l8.4 6 8.4-6" /></svg>
            </span>
            <input id="lg-acc" ref="accEl" v-model.trim="form.username" name="account" type="text"
                   placeholder="请输入邮箱/手机号" autocomplete="username" @blur="validate('account')" />
          </div>
          <p class="err" :class="{ hide: !errors.account }">{{ errors.account || " " }}</p>

          <div class="fld" :class="{ bad: errors.password }">
            <span class="fld-ic" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="4.5" y="10.5" width="15" height="9.5" rx="2.5" /><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5" /></svg>
            </span>
            <input id="lg-pwd" v-model="form.password" name="password" :type="seen ? 'text' : 'password'"
                   placeholder="请输入密码" autocomplete="current-password" @blur="validate('password')" />
            <button type="button" class="eye" :aria-label="seen ? '隐藏密码' : '显示密码'" @click="seen = !seen">
              <svg v-if="!seen" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2.5 12S6 5.8 12 5.8 21.5 12 21.5 12 18 18.2 12 18.2 2.5 12 2.5 12z" /><circle cx="12" cy="12" r="2.8" /></svg>
              <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 4l16 16" /><path d="M9.6 5.9A9.6 9.6 0 0 1 12 5.8c6 0 9.5 6.2 9.5 6.2a17 17 0 0 1-2.9 3.6M6.6 7.6A16.6 16.6 0 0 0 2.5 12S6 18.2 12 18.2c1.2 0 2.3-.2 3.3-.6" /></svg>
            </button>
          </div>
          <p class="err" :class="{ hide: !errors.password }">{{ errors.password || " " }}</p>

          <div class="row">
            <button type="button" class="ck" role="checkbox" :aria-checked="remember" @click="toggleRemember">
              <span class="cb" :class="{ on: remember }" aria-hidden="true">{{ remember ? '✓' : '' }}</span>
              <span>记住我</span>
            </button>
            <button type="button" class="fgt" disabled :title="FORGOT_NOTE">忘记密码?</button>
          </div>
          <p class="err" :class="{ hide: !loginErr }">{{ loginErr || " " }}</p>
        </form>

        <RegisterForm :class="{ off: mode !== 'register' }" ref="regEl" @done="onRegistered" />
        </div>

        <div class="acts">
          <button type="button" class="cancel" :disabled="!canClose" :title="canClose ? '' : NO_CLOSE_NOTE" @click="cancel">取消</button>
          <button type="button" class="go" :disabled="busy" @click="submit">{{ busy ? '提交中…' : (mode === 'login' ? '登 录' : '注 册') }}</button>
        </div>

      </section>

      <aside class="right" aria-hidden="true">
        <svg class="art" viewBox="0 0 420 460" role="presentation" focusable="false">
          <defs>
            <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0" stop-color="#eef4ff" /><stop offset="1" stop-color="#d9e6ff" />
            </linearGradient>
            <radialGradient id="halo" cx="50%" cy="45%" r="55%">
              <stop offset="0" stop-color="#ffffff" stop-opacity=".9" /><stop offset="1" stop-color="#ffffff" stop-opacity="0" />
            </radialGradient>
          </defs>
          <rect width="420" height="460" fill="url(#bg)" />
          <circle cx="215" cy="215" r="150" fill="url(#halo)" />
          <circle cx="215" cy="215" r="132" fill="none" stroke="#ffffff" stroke-opacity=".7" stroke-width="1.5" />
          <circle cx="215" cy="215" r="176" fill="none" stroke="#ffffff" stroke-opacity=".4" stroke-width="1.5" />
          <g transform="rotate(-6 210 250)">
            <rect x="128" y="150" width="168" height="200" rx="12" fill="#ffffff" opacity=".96" />
            <rect x="146" y="172" width="86" height="9" rx="4.5" fill="#c7d6f2" />
            <rect x="146" y="190" width="132" height="6" rx="3" fill="#e4ebf7" />
            <rect x="146" y="204" width="120" height="6" rx="3" fill="#e4ebf7" />
            <rect x="146" y="218" width="128" height="6" rx="3" fill="#e4ebf7" />
            <rect x="146" y="240" width="70" height="6" rx="3" fill="#eef2f9" />
            <rect x="146" y="254" width="112" height="6" rx="3" fill="#eef2f9" />
            <rect x="146" y="286" width="64" height="20" rx="6" fill="#2b6be8" opacity=".9" />
          </g>
          <g class="t1"><rect width="46" height="46" rx="13" fill="#2b6be8" /><path d="M16 15l16 8-16 8z" fill="#fff" /></g>
          <g class="t2"><rect width="40" height="40" rx="12" fill="#e08a1e" /><text x="20" y="27" text-anchor="middle" font-size="19" font-weight="700" fill="#fff" font-family="Arial">P</text></g>
          <g class="t3"><rect width="42" height="42" rx="12" fill="#1f56c4" /><text x="21" y="28" text-anchor="middle" font-size="18" font-weight="700" fill="#fff" font-family="Arial">W</text></g>
          <g class="t4"><rect width="44" height="44" rx="13" fill="#1f9d55" /><path d="M11 30l7-8 6 6 4-4 6 6z" fill="#fff" /><circle cx="15" cy="15" r="3" fill="#fff" /></g>
          <g class="t5"><rect width="52" height="52" rx="15" fill="#2b6be8" /><text x="26" y="34" text-anchor="middle" font-size="19" font-weight="700" fill="#fff" font-family="Arial">AI</text></g>
        </svg>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { toast } from '../../utils/ui'
import { apiBase, isDesktop } from '../../config'
import RegisterForm from '../../components/RegisterForm.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const FORGOT_NOTE = '找回密码要接邮件重置令牌，后端还没有这条链路，所以先置灰'
const NO_CLOSE_NOTE = '浏览器标签里 JS 关不掉窗口，这条只在桌面客户端生效'

const mode = ref('login')
const seen = ref(false)
const busy = ref(false)
const loginErr = ref('')
const accEl = ref(null)
const regEl = ref(null)
const canClose = isDesktop

const form = reactive({ username: localStorage.getItem('loginAccount') || '', password: '' })
const errors = reactive({ account: '', password: '' })
const remember = ref(localStorage.getItem('loginRemember') === '1')

function validate(field) {
  if (field === 'account') errors.account = form.username.trim() ? '' : '请输入邮箱、手机号或用户名'
  else errors.password = form.password ? '' : '请输入密码'
  return !errors[field]
}

function toggleRemember() {
  remember.value = !remember.value
  localStorage.setItem('loginRemember', remember.value ? '1' : '0')
  if (!remember.value) localStorage.removeItem('loginAccount')
}

function switchTo(next) {
  mode.value = next
  loginErr.value = ''
  if (next === 'login') nextTick(() => accEl.value?.focus())
}

onMounted(() => {
  // 过期只用 toast 说一次：卡片里再摆一块提示就要一直占着高度，矮窗口会逼出滚动条
  if (route.query.expired === 'true') toast('登录已过期，请重新登录', 'warning')
  if (!form.username) nextTick(() => accEl.value?.focus())
})

async function handleLogin() {
  if (!(validate('account') & validate('password'))) return
  busy.value = true
  loginErr.value = ''
  try {
    await userStore.doLogin({ username: form.username, password: form.password })
    if (remember.value) localStorage.setItem('loginAccount', form.username)
    toast('登录成功', 'success')
    router.push(route.query.redirect || '/')
  } catch (e) {
    // 没有 e.response 说明请求根本没到服务，和「密码不对」是两件事，不能混成一句登录失败
    loginErr.value = e.response ? `${e.response.data?.error || '登录被拒'} (HTTP ${e.response.status})`
                                : `连不上用户服务 ${apiBase('user')}`
    toast(loginErr.value, 'error')
  } finally {
    busy.value = false
  }
}

function submit() {
  if (mode.value === 'login') return handleLogin()
  busy.value = true
  regEl.value?.submit()
  setTimeout(() => { busy.value = false }, 1500)   // 注册表单自己收尾，这里兜底放开，别永久禁用
}

function onRegistered() {
  // doRegister 已经把 token 写进 store，直接进主界面，不用再登一次
  mode.value = 'login'
  router.push('/')
}

function cancel() {
  if (!canClose) return
  window.chatDesktop.win.close()
}
</script>

<style scoped>
/* 微信式满幅：整个窗口就是登录页，没有灰底、外边距、圆角和阴影这一层 */
.lg { position: relative; height: calc(100vh - var(--winbar-h)); overflow: hidden; background: var(--nb-bg-1); }
/* 标题栏没了，窗口只能靠这条空区拖动；可交互控件一律 no-drag */
.drag { position: absolute; top: 0; left: 0; right: 0; height: 36px; -webkit-app-region: drag; z-index: 3; }

.card { width: 100%; height: 100%; display: grid; grid-template-columns: minmax(440px, 46%) minmax(0, 1fr);
  background: var(--nb-bg-1); overflow: hidden; }

/* 首尾 auto 外边距居中：够高时垂直居中，矮到装不下时自动贴顶，
   不像 justify-content:center 那样把内容顶出上边界、还逼出一条右侧滚动条 */
.left { display: flex; flex-direction: column; padding: 15px 46px 15px 56px; min-width: 0; overflow-y: auto; }
.left > :first-child { margin-top: auto; }
.left > :last-child { margin-bottom: auto; }

.tabs { display: flex; gap: 22px; margin-top: 22px; -webkit-app-region: no-drag; }
.tab { padding: 0 2px 10px; font: inherit; font-size: 14.5px; letter-spacing: normal; color: var(--nb-dim);
  background: none; border: 0; border-bottom: 2px solid transparent; cursor: pointer; }
.tab:hover { color: var(--nb-text); }
.tab.on { color: var(--brand-strong); font-weight: 600; border-bottom-color: var(--brand); }

.brand { display: flex; align-items: center; gap: 10px; }
.brand-ic { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 11px; background: var(--brand); color: #fff; }
.brand-tx b { display: block; font-size: 15px; font-weight: 600; color: var(--nb-text); }
.brand-tx small { display: block; margin-top: 1px; font-size: 12px; color: var(--nb-dim); }

.hi { margin: 24px 0 0; font-size: 26px; font-weight: 600; letter-spacing: normal; color: var(--nb-text); }
.sub { margin: 7px 0 0; font-size: 13px; color: var(--nb-dim); }

/* 同一格叠放 + visibility 隐藏（不是 v-show：display:none 会让隐藏那个整个退出 grid，
   容器就只剩当前可见的高度）：谁高容器就多高，切页签时块高恒定 */
.panes { display: grid; }
.panes > * { grid-area: 1 / 1; }
.off { visibility: hidden; pointer-events: none; }
.fm { display: block; }
.fld { display: flex; align-items: center; gap: 9px; padding: 0 12px; height: 44px; margin-top: 12px;
  background: var(--nb-bg-1); border: 1px solid var(--nb-line); border-radius: 11px;
  transition: border-color .14s, box-shadow .14s; }
.fm .fld:first-child { margin-top: 16px; }
.fld:focus-within { border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-line); }
.fld.bad { border-color: var(--danger); }
.fld-ic { display: grid; place-items: center; color: var(--nb-dim); }
.fld input { flex: 1; min-width: 0; border: 0; outline: none; background: transparent;
  font: inherit; font-size: 13.5px; color: var(--nb-text); }
.fld input::placeholder { color: var(--nb-dim); }
.eye { display: grid; place-items: center; width: 26px; height: 26px; padding: 0; border: 0; border-radius: 7px;
  background: none; color: var(--nb-dim); cursor: pointer; -webkit-app-region: no-drag; }
.eye:hover { background: var(--nb-bg-3); color: var(--nb-text); }
/* 位子一直占着、内容按需可见：不然一点就长高，整块上下跳 */
.err { margin: 5px 0 0; font-size: 11.5px; line-height: 16px; color: var(--danger); }
.hide { visibility: hidden; }

.row { display: flex; align-items: center; justify-content: space-between; margin-top: 13px; }
.ck { display: inline-flex; align-items: center; gap: 7px; padding: 0; font: inherit; font-size: 12.5px;
  color: var(--nb-text); background: none; border: 0; cursor: pointer; -webkit-app-region: no-drag; }
.cb { display: grid; place-items: center; width: 15px; height: 15px; font-size: 10px; color: #fff;
  border: 1.5px solid var(--nb-line); border-radius: 4px; background: var(--nb-bg-1); }
.cb.on { background: var(--brand); border-color: var(--brand); }
.fgt { padding: 0; font: inherit; font-size: 12.5px; color: var(--brand); background: none; border: 0; cursor: pointer; }
.fgt:disabled { color: var(--nb-dim); cursor: not-allowed; }

.acts { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.7fr); gap: 12px; margin-top: 18px;
  -webkit-app-region: no-drag; }
.go, .cancel { width: 100%; height: 44px; font: inherit; font-size: 14.5px; border-radius: 11px; cursor: pointer;
  letter-spacing: normal; }
.go { font-weight: 600; color: #fff; background: var(--brand); border: 0; }
.go:hover:not(:disabled) { background: var(--brand-strong); }
.go:disabled, .cancel:disabled { opacity: .6; cursor: not-allowed; }
.cancel { color: var(--nb-text); background: var(--nb-bg-1); border: 1px solid var(--nb-line); }
.cancel:hover:not(:disabled) { background: var(--nb-bg-3); }

.right { position: relative; min-width: 0; background: linear-gradient(150deg, #eef4ff, #d9e6ff); }
.art { position: absolute; inset: 0; width: 100%; height: 100%; }
.t1 { transform: translate(62px, 96px); }
.t2 { transform: translate(330px, 128px); }
.t3 { transform: translate(300px, 214px); }
.t4 { transform: translate(336px, 292px); }
.t5 { transform: translate(74px, 300px); }

@media (max-width: 880px) {
  .card { grid-template-columns: minmax(0, 1fr); }
  .right { display: none; }
  .left { padding: 14px 22px; }
}
</style>
