<template>
  <div v-if="open" class="ov" @click.self="$emit('close')">
    <div class="dlg" role="dialog" aria-modal="true" aria-label="设置">
      <header class="hd">
        <h2>{{ active.name }}</h2>
        <button class="x" type="button" aria-label="关闭" @click="$emit('close')">✕</button>
      </header>

      <div class="split">
        <!-- 左：分区导航 -->
        <nav class="nav" role="tablist" aria-label="设置分区">
          <button v-for="s in SECTIONS" :key="s.key" type="button" role="tab" class="node"
                  :class="{ on: section === s.key }" :aria-selected="section === s.key" @click="pick(s.key)">
            <span class="nd-ic" aria-hidden="true"><component :is="s.icon" /></span>
            <span class="nd-tx">{{ s.name }}</span>
            <span v-if="s.demo" class="nd-demo" title="后端还没有对应接口">演示</span>
          </button>
        </nav>

        <!-- 右：当前分区 -->
        <div class="pane">
          <p class="lead">{{ active.lead }}</p>

          <!-- 个人信息 -->
          <template v-if="section === 'profile'">
            <div class="av-row">
              <span class="av-big">{{ initial }}</span>
              <div class="av-acts">
                <button type="button" class="btn" disabled :title="AVATAR_NOTE">更换头像</button>
                <p class="hint">头像上传暂未开通，先用名字首字母。</p>
              </div>
            </div>
            <div class="fr"><label for="st-nick">姓名<span class="req">*</span></label>
              <input id="st-nick" v-model.trim="form.nickname" class="ipt" type="text" maxlength="64" placeholder="同事看到的名字" /></div>
            <div class="fr"><label for="st-pos">职位</label>
              <input id="st-pos" v-model.trim="form.position" class="ipt" type="text" maxlength="64" placeholder="如 产品经理" /></div>
            <div class="fr"><label for="st-dept">部门</label>
              <input id="st-dept" v-model.trim="form.department" class="ipt" type="text" maxlength="64" placeholder="如 产品部" /></div>
            <div class="fr top"><label for="st-bio">个人简介</label>
              <span class="ipt-wrap"><textarea id="st-bio" v-model="form.bio" class="ipt ta" maxlength="200" rows="3" placeholder="一句话介绍你在做什么" /><span class="cnt">{{ form.bio.length }}/200</span></span></div>
          </template>

          <!-- 账号设置 -->
          <template v-else-if="section === 'account'">
            <div class="fr"><label for="st-user">用户名</label>
              <input id="st-user" class="ipt" type="text" :value="me.username" readonly aria-disabled="true" /></div>
            <div class="fr"><label for="st-mail">邮箱</label>
              <input id="st-mail" v-model.trim="form.email" class="ipt" type="email" maxlength="128" placeholder="you@company.com" /></div>
            <div class="fr"><label for="st-phone">手机号</label>
              <input id="st-phone" v-model.trim="form.phone" class="ipt" type="tel" maxlength="32" placeholder="选填" /></div>
            <p class="hint">用户名是登录凭据，不能改；要换密码去「安全设置」。</p>
          </template>

          <!-- 通知设置：后端没有这张表，开关只改界面状态，所以整块标演示 -->
          <template v-else-if="section === 'notify'">
            <p class="cap">消息通知</p>
            <div v-for="n in NOTICES" :key="n.key" class="sw-row">
              <span class="sw-name">{{ n.name }}</span>
              <button type="button" class="sw" :class="{ on: n.on }" role="switch" :aria-checked="n.on"
                      :aria-label="n.name" @click="n.on = !n.on"><i /></button>
            </div>
            <p class="cap">通知方式</p>
            <button v-for="c in CHANNELS" :key="c.key" type="button" class="ck-row"
                    role="checkbox" :aria-checked="c.on" @click="c.on = !c.on">
              <span class="cb" :class="{ on: c.on }" aria-hidden="true">{{ c.on ? '✓' : '' }}</span>
              <span>{{ c.name }}</span>
            </button>
          </template>

          <!-- 安全设置 -->
          <template v-else-if="section === 'security'">
            <div class="fr"><label for="st-old">当前密码</label>
              <input id="st-old" v-model="pwd.oldPassword" class="ipt" type="password" autocomplete="current-password" /></div>
            <div class="fr"><label for="st-new">新密码</label>
              <input id="st-new" v-model="pwd.newPassword" class="ipt" type="password" autocomplete="new-password" /></div>
            <div class="fr"><label for="st-cfm">确认新密码</label>
              <input id="st-cfm" v-model="pwd.confirm" class="ipt" type="password" autocomplete="new-password" /></div>
            <p class="hint">密码至少 8 位。改成功后当前登录仍然有效，下次登录用新密码。</p>
          </template>

          <!-- 外观设置：真的生效，只存在这台设备 -->
          <template v-else>
            <div class="ac-grid">
              <button v-for="a in ACCENTS" :key="a.key" type="button" class="ac" :class="{ on: accent === a.key }"
                      role="radio" :aria-checked="accent === a.key" @click="chooseAccent(a.key)">
                <span class="ac-sw" :style="{ background: a.brand }" aria-hidden="true" />
                <span class="ac-tx">{{ a.name }}</span>
                <span v-if="accent === a.key" class="ac-ck" aria-hidden="true">✓</span>
              </button>
            </div>
            <p class="hint">点了立刻生效，存在这台设备的 localStorage 里，不上传服务器。</p>
          </template>

          <p v-if="status.text" class="status" :class="status.kind">{{ status.text }}</p>
        </div>
      </div>

      <footer class="ft">
        <button class="btn ghost" type="button" @click="$emit('close')">{{ section === 'appearance' ? '关闭' : '取消' }}</button>
        <button v-if="section !== 'appearance'" class="btn pri" type="button" :disabled="!canSave || saving" @click="save"
                :title="section === 'notify' ? DEMO_NOTE : ''">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { computed, h, reactive, ref, watch } from 'vue'
import { getMe, updateProfile, changePassword } from '../api/user'
import { ACCENTS, applyAccent, currentAccent } from '../styles/accent'

const props = defineProps({ open: { type: Boolean, default: false } })
const emit = defineEmits(['close', 'saved'])

// 头像要真显示得让 <img> 能取到图：下载接口在鉴权后面，<img> 带不了 Authorization 头。
// 要么给头像开公共读，要么改 6 处头像渲染走 blob——那是策略决定，先不做成点了没反应的按钮。
const AVATAR_NOTE = '头像上传暂未开通：文件下载接口要带鉴权头，<img> 发不出去，等定好取图方式再开'
const DEMO_NOTE = '通知偏好要存在服务端，chat_user 里还没有这张表，所以这里只是版式'

const mk = (paths) => () => h('svg', { viewBox: '0 0 24 24', width: 16, height: 16, fill: 'none', stroke: 'currentColor', 'stroke-width': 1.7 },
  paths.map(d => h('path', { d })))
const Ic = {
  user: mk(['M12 12a3.6 3.6 0 1 0 0-7.2 3.6 3.6 0 0 0 0 7.2z', 'M4.8 19.5a7.2 7.2 0 0 1 14.4 0']),
  account: mk(['M4 7.5h16v10H4z', 'M4 11h16', 'M14.5 14.5h3.5']),
  bell: mk(['M12 4a4.6 4.6 0 0 0-4.6 4.6c0 3.6-1.4 4.9-1.4 4.9h12s-1.4-1.3-1.4-4.9A4.6 4.6 0 0 0 12 4z', 'M10.2 16.6a2 2 0 0 0 3.6 0']),
  shield: mk(['M12 4l6.5 2.4v5.1c0 4-2.8 6.6-6.5 8-3.7-1.4-6.5-4-6.5-8V6.4z', 'M9.4 12l1.9 1.9 3.6-3.7']),
  look: mk(['M4 12.5h9', 'M4 8h13', 'M4 17h6', 'M16.5 14.5l3.5 3.5-4.6 1.1 1.1-4.6z'])
}

const SECTIONS = [
  { key: 'profile', name: '个人信息', icon: Ic.user, lead: '同事在会话和成员列表里看到的资料' },
  { key: 'account', name: '账号设置', icon: Ic.account, lead: '登录用的账号与联系方式' },
  { key: 'notify', name: '通知设置', icon: Ic.bell, lead: '设置你希望接收的消息通知方式。', demo: true },
  { key: 'security', name: '安全设置', icon: Ic.shield, lead: '修改登录密码' },
  { key: 'appearance', name: '外观设置', icon: Ic.look, lead: '选一个主题色，立刻生效' }
]

const section = ref('profile')
const me = ref({})
const form = reactive({ nickname: '', position: '', department: '', bio: '', email: '', phone: '' })
const pwd = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const saving = ref(false)
const status = reactive({ text: '', kind: 'ok' })
const accent = ref(currentAccent())

const NOTICES = reactive([
  { key: 'msg', name: '新消息提醒', on: true },
  { key: 'at', name: '@我的消息', on: true },
  { key: 'task', name: '任务分配', on: true },
  { key: 'sys', name: '系统通知', on: false }
])
const CHANNELS = reactive([
  { key: 'desktop', name: '桌面通知', on: true },
  { key: 'mail', name: '邮件通知', on: true },
  { key: 'mobile', name: '移动端推送', on: false }
])

const active = computed(() => SECTIONS.find(s => s.key === section.value) || SECTIONS[0])
const initial = computed(() => String(form.nickname || me.value.username || '?').charAt(0).toUpperCase())

function fill(u) {
  me.value = u || {}
  form.nickname = u?.nickname || u?.username || ''
  form.position = u?.position || ''
  form.department = u?.department || ''
  form.bio = u?.bio || ''
  form.email = u?.email || ''
  form.phone = u?.phone || ''
}

watch(() => props.open, async (v) => {
  if (!v) return
  section.value = 'profile'
  status.text = ''
  pwd.oldPassword = pwd.newPassword = pwd.confirm = ''
  try { fill(await getMe()) } catch (e) { say('资料读取失败：' + msgOf(e), 'bad') }
})

function say(text, kind = 'ok') { status.text = text; status.kind = kind }
function msgOf(e) { return e?.response?.data?.error || e?.message || '未知错误' }

function pick(k) { section.value = k; status.text = '' }

const canSave = computed(() => {
  if (section.value === 'profile') return !!form.nickname
  if (section.value === 'account') return true
  if (section.value === 'notify') return false
  if (section.value === 'security') return !!pwd.oldPassword && !!pwd.newPassword && pwd.newPassword === pwd.confirm
  return false
})

function chooseAccent(k) { accent.value = k; applyAccent(k) }

async function save() {
  if (!canSave.value || saving.value) return
  saving.value = true
  say('')
  try {
    if (section.value === 'security') {
      await changePassword(pwd.oldPassword, pwd.newPassword)
      pwd.oldPassword = pwd.newPassword = pwd.confirm = ''
      say('密码已修改')
    } else {
      const body = section.value === 'account'
        ? { email: form.email, phone: form.phone }
        : { nickname: form.nickname, position: form.position, department: form.department, bio: form.bio }
      const u = await updateProfile(body)
      fill({ ...me.value, ...u })
      emit('saved', u)
      say(section.value === 'account' ? '账号信息已保存' : '个人信息已保存')
    }
  } catch (e) {
    say('保存失败：' + msgOf(e), 'bad')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.ov { position: fixed; inset: 0; z-index: 95; display: flex; align-items: center; justify-content: center; padding: 24px; background: rgba(16, 24, 40, .42); overflow: hidden; }
.dlg { width: min(680px, 100%); height: min(640px, calc(100vh - 48px)); display: flex; flex-direction: column; background: var(--nb-bg-1); border: 1px solid var(--nb-line); border-radius: 8px; box-shadow: 0 24px 60px rgba(16, 24, 40, .22); overflow: hidden; }
.hd { display: flex; align-items: center; justify-content: space-between; padding: 15px 18px; border-bottom: 1px solid var(--nb-line); }
.hd h2 { margin: 0; font-size: 15.5px; font-weight: 600; color: var(--nb-text); }
.x { border: 0; background: none; color: var(--nb-dim); font-size: 14px; cursor: pointer; padding: 4px 6px; border-radius: 8px; }
.x:hover { background: var(--nb-bg-3); color: var(--nb-text); }

.split { flex: 1; display: grid; grid-template-columns: 158px minmax(0, 1fr); gap: 16px; padding: 14px 18px; min-height: 0; }
.nav { display: flex; flex-direction: column; gap: 2px; min-height: 0; }
.node { display: grid; grid-template-columns: 18px minmax(0, 1fr) auto; gap: 8px; align-items: center; width: 100%; padding: 9px 10px; text-align: left; font: inherit; font-size: 13px; color: var(--nb-text); background: none; border: 0; border-radius: 9px; cursor: pointer; }
.node:hover { background: var(--nb-bg-3); }
.node.on { background: var(--brand-soft); color: var(--brand-strong); font-weight: 600; }
.nd-ic { display: grid; place-items: center; color: var(--nb-dim); }
.node.on .nd-ic { color: var(--brand); }
.nd-tx { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nd-demo { font-size: 10px; color: var(--warn); border: 1px dashed currentColor; border-radius: 999px; padding: 0 5px; cursor: help; }

.pane { display: flex; flex-direction: column; gap: 12px; min-width: 0; overflow-y: auto; padding-right: 2px; }
.lead { margin: 0; font-size: 12.5px; color: var(--nb-dim); }
.cap { margin: 6px 0 0; font-size: 12px; color: var(--nb-dim); letter-spacing: .04em; }

.av-row { display: flex; align-items: center; gap: 16px; }
.av-big { display: grid; place-items: center; width: 58px; height: 58px; font-size: 22px; font-weight: 600; color: #fff; background: var(--brand); border-radius: 50%; }
.av-acts { display: flex; flex-direction: column; align-items: flex-start; gap: 5px; }

.fr { display: grid; grid-template-columns: 78px minmax(0, 1fr); gap: 12px; align-items: center; }
.fr.top { align-items: start; }
.fr label { font-size: 12.5px; color: var(--nb-dim); }
.req { margin-left: 2px; color: var(--danger); }
.ipt-wrap { position: relative; display: block; }
.ipt { width: 100%; box-sizing: border-box; padding: 8px 11px; font: inherit; font-size: 13px; color: var(--nb-text); background: var(--nb-bg-2); border: 1px solid var(--nb-line); border-radius: 10px; outline: none; }
.ipt:focus { border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-line); }
.ipt[readonly] { color: var(--nb-dim); cursor: not-allowed; }
.ta { resize: none; min-height: 62px; line-height: 1.55; padding-bottom: 20px; }
.cnt { position: absolute; right: 10px; bottom: 7px; font-size: 11px; color: var(--nb-dim); pointer-events: none; }
.hint { margin: 0; font-size: 11.5px; line-height: 1.5; color: var(--nb-dim); }

.sw-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 7px 0; font-size: 13px; }
.sw { position: relative; flex: 0 0 38px; width: 38px; height: 21px; padding: 0; border: 0; border-radius: 999px; background: var(--nb-bg-3); cursor: pointer; transition: background .16s; }
.sw i { position: absolute; top: 2px; left: 2px; width: 17px; height: 17px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px rgba(16, 24, 40, .3); transition: transform .16s; }
.sw.on { background: var(--brand); }
.sw.on i { transform: translateX(17px); }
.ck-row { display: flex; align-items: center; gap: 8px; padding: 0; font: inherit; font-size: 13px; text-align: left; color: var(--nb-text); background: none; border: 0; cursor: pointer; }
.cb { display: grid; place-items: center; flex: 0 0 auto; width: 16px; height: 16px; font-size: 11px; color: #fff; border: 1.5px solid var(--nb-line); border-radius: 5px; background: var(--nb-bg-1); }
.cb.on { background: var(--brand); border-color: var(--brand); }

.ac-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.ac { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 9px; align-items: center; padding: 10px 12px; font: inherit; font-size: 13px; text-align: left; color: var(--nb-text); background: var(--nb-bg-2); border: 1px solid var(--nb-line); border-radius: 11px; cursor: pointer; }
.ac:hover { border-color: var(--brand-line); }
.ac.on { border-color: var(--brand); background: var(--brand-soft); color: var(--brand-strong); font-weight: 600; }
.ac-sw { width: 20px; height: 20px; border-radius: 6px; }
.ac-ck { color: var(--brand); }

.status { margin: 0; padding: 8px 11px; font-size: 12.5px; border-radius: 9px; }
.status.ok { color: var(--ok); background: rgba(31, 157, 85, .1); }
.status.bad { color: var(--danger); background: rgba(217, 72, 96, .1); }

.ft { display: flex; justify-content: flex-end; gap: 9px; padding: 12px 18px; border-top: 1px solid var(--nb-line); background: var(--nb-bg-2); }
.btn { padding: 8px 15px; font: inherit; font-size: 13.5px; letter-spacing: normal; border-radius: 10px; cursor: pointer; border: 1px solid var(--nb-line); background: var(--nb-bg-1); color: var(--nb-text); }
.btn.ghost:hover { background: var(--nb-bg-3); }
.btn.pri { border-color: var(--brand); background: var(--brand); color: #fff; font-weight: 600; }
.btn.pri:hover:not(:disabled) { background: var(--brand-strong); border-color: var(--brand-strong); }
.btn:disabled { opacity: .5; cursor: not-allowed; }
</style>
