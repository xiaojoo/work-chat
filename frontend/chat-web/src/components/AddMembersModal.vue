<template>
  <div v-if="open" class="ov" @click.self="$emit('close')">
    <div class="dlg" role="dialog" aria-modal="true" :aria-label="title">
      <header class="hd">
        <span class="hd-ic" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.7">
            <circle cx="10" cy="8" r="3.3" /><path d="M4 19a6 6 0 0 1 12 0" /><path d="M18 8v6M15 11h6" />
          </svg>
        </span>
        <div class="hd-tx">
          <h2>{{ title }}</h2>
          <p>{{ sub }}</p>
        </div>
        <button class="x" type="button" aria-label="关闭" @click="$emit('close')">✕</button>
      </header>

      <nav class="tabs" role="tablist">
        <button v-for="t in TABS" :key="t.key" type="button" role="tab" class="tab"
                :class="{ on: tab === t.key }" :aria-selected="tab === t.key" @click="tab = t.key">{{ t.name }}</button>
      </nav>

      <!-- 群聊页签还要按群名搜，搜索框留在顶上；组织架构页签的搜索长在树自己头上 -->
      <div v-if="tab === 'group'" class="bar">
        <div class="ipt-wrap grow">
          <input v-model="q" class="ipt" type="text" placeholder="搜索群名" autocomplete="off" />
        </div>
      </div>

      <div v-if="tab !== 'link'" class="split" :class="{ treey: tab === 'org' }">
        <MemberTree v-if="tab === 'org'" :members="orgMembers" :picked="picked" :online="online"
                    @toggle="toggle" @toggle-many="toggleMany" />

        <!-- 群聊页签：左边还是群列表，成员一律出现在右栏 -->
        <aside v-else class="nav">
          <p class="nav-cap">我的群聊</p>
          <button v-for="g in visibleGroups" :key="g.id" type="button" class="node"
                  :class="{ on: pickedGroup === g.id }" @click="pickGroup(g)">
            <span class="nd-ic" aria-hidden="true"><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 6.5h16v10H12l-4 3v-3H4z" /></svg></span>
            <span class="nd-tx">{{ g.name }}</span>
            <span class="nd-n">{{ g.memberCount || 0 }} 人</span>
          </button>
          <p v-if="!visibleGroups.length" class="empty">你还没有可导入的群聊</p>
        </aside>

        <!-- 右：已选（和创建群聊同一个面板）；群聊页签再多一块候选面板；最下面是欢迎消息 -->
        <div class="side" :class="{ two: tab === 'group' }">
          <PickPanel :picked="picked" @remove="toggle" @clear="picked = []" />

          <div v-if="tab === 'group'" class="pane">
            <div class="pane-hd">候选成员 <b>{{ candidates.length }}</b> 人</div>
            <div class="list">
              <button v-for="m in candidates" :key="m.id" type="button" class="mrow" :aria-pressed="has(m)" @click="toggle(m)">
                <span class="cb" :class="{ on: has(m) }" aria-hidden="true">{{ has(m) ? '✓' : '' }}</span>
                <span class="av">{{ initial(m) }}<i v-if="presenceOf(m)" class="dot" :class="presenceOf(m)" :title="presenceOf(m) === 'BUSY' ? '忙碌' : '在线'" /></span>
                <span class="mcol">
                  <b>{{ m.nickname || m.username }}</b>
                  <small>{{ m.position || m.department || '未填写职位' }}</small>
                </span>
              </button>
              <p v-if="!candidates.length" class="empty">{{ listEmpty }}</p>
            </div>
          </div>

          <button v-if="!isFriend && !isConvert" type="button" class="welcome" role="checkbox" :aria-checked="sendWelcome" @click="sendWelcome = !sendWelcome">
            <span class="cb" :class="{ on: sendWelcome }" aria-hidden="true">{{ sendWelcome ? '✓' : '' }}</span>
            <span>发送欢迎消息</span>
          </button>
          <div v-if="!isFriend && !isConvert && sendWelcome" class="ipt-wrap">
            <textarea v-model="welcome" class="ipt ta" maxlength="200" rows="2" placeholder="欢迎加入团队！一起创造更好的内容！" />
            <span class="cnt">{{ welcome.length }}/200</span>
          </div>
        </div>
      </div>

      <!-- 通过链接邀请：后端还没有邀请令牌，先摆明状态 -->
      <div v-else class="shell">
        <h3>通过链接邀请</h3>
        <p>需要服务端签发带有效期的邀请令牌（目前 <code>chat_user</code> 与网关都没有这条链路），
           所以这一页先不做成能点但没反应的假输入。</p>
        <div class="ipt-wrap dis">
          <input class="ipt" type="text" :value="PLACEHOLDER_LINK" readonly aria-disabled="true" />
          <button class="btn mini" type="button" disabled>复制链接</button>
        </div>
      </div>

      <footer class="ft">
        <div class="ft-btns">
          <button class="btn ghost" type="button" @click="$emit('close')">取消</button>
          <button class="btn pri" type="button" :disabled="!picked.length" @click="submit">
            {{ okLabel }}<span v-if="picked.length" class="with">（{{ picked.length }}）</span>
          </button>
        </div>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import MemberTree from './MemberTree.vue'
import PickPanel from './PickPanel.vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  org: { type: Array, default: () => [] },      // [{department,count,members:[]}]
  groups: { type: Array, default: () => [] },   // 我的群
  online: { type: Array, default: () => [] },   // [{userId,status}]
  excludeIds: { type: Array, default: () => [] },
  groupMembersCache: { type: Object, default: () => ({}) },  // groupId -> 成员数组，由父组件按需拉
  mode: { type: String, default: 'group' }   // group=拉进群 / friend=加为好友
})
const emit = defineEmits(['close', 'add', 'expand-group'])

const ALL_TABS = [{ key: 'org', name: '从组织架构添加' }, { key: 'group', name: '从群聊添加' }, { key: 'link', name: '通过链接邀请' }]
const PLACEHOLDER_LINK = '—（待接入邀请令牌）—'

const isFriend = computed(() => props.mode === 'friend')
// 单聊转群聊走同一棵树：标题和按钮要说清这是"建一个新群"，不是"把人拉进当前群"
const isConvert = computed(() => props.mode === 'convert')
const title = computed(() => (isFriend.value ? '添加好友' : isConvert.value ? '邀请成员建群' : '添加用户'))
const sub = computed(() => (isFriend.value ? '从组织架构或你所在的群里挑人要加为好友'
  : isConvert.value ? '挑进来的人会和对方一起组成一个新群，这条私聊原样保留'
  : '选择成员加入团队或群聊'))
const okLabel = computed(() => (isConvert.value ? '建群' : '添加'))
// 好友模式不给「通过链接邀请」：那条链路根本没有，给了就是个点了没反应的页签
const TABS = computed(() => isFriend.value || isConvert.value ? ALL_TABS.slice(0, 2) : ALL_TABS)
const tab = ref('org')
const q = ref('')
const picked = ref([])
const sendWelcome = ref(true)
// 预填而不是占位：占位符不算值，勾着「发送欢迎消息」却发空串等于摆了个哑开关
const welcome = ref('欢迎加入团队！一起创造更好的内容！')
const pickedGroup = ref(null)

watch(() => props.open, (v) => {
  if (v) {
    tab.value = 'org'; q.value = ''
    picked.value = []; welcome.value = '欢迎加入团队！一起创造更好的内容！'; pickedGroup.value = null
  }
})

const kw = computed(() => q.value.trim().toLowerCase())
const excluded = computed(() => new Set(props.excludeIds.map(String)))
const initial = (m) => String(m.nickname || m.username || '?').charAt(0).toUpperCase()
const presenceOf = (m) => props.online.find(u => String(u.userId) === String(m.id))?.status || ''
const has = (m) => picked.value.some(p => String(p.id) === String(m.id))
const usable = (m) => !excluded.value.has(String(m.id)) && !String(m.id).startsWith('local-')
const kwHit = (m) => !kw.value || [m.nickname, m.username, m.position, m.department, m.email]
  .some(v => String(v || '').toLowerCase().includes(kw.value))

// 树要的是「全部能选的人」，分组和搜索都由 MemberTree 自己做
const orgMembers = computed(() => props.org.flatMap(d => d.members).filter(usable))

const cacheOf = (g) => props.groupMembersCache[String(g.id)] || []

// 群聊页签的候选：选中某个群后列它的成员；组织架构页签由左边的树来勾，不再平铺
const candidates = computed(() => {
  const g = props.groups.find(x => x.id === pickedGroup.value)
  if (!g) return []
  return cacheOf(g).filter(m => usable(m) && kwHit(m))
})

const listEmpty = computed(() => pickedGroup.value
  ? '该群成员正在加载，或都不在可选范围内'
  : '点开左边的群，成员会显示在这里')

const visibleGroups = computed(() => props.groups
  .filter(g => !kw.value || String(g.name || '').toLowerCase().includes(kw.value)))

function pickGroup(g) {
  pickedGroup.value = pickedGroup.value === g.id ? null : g.id
  emit('expand-group', g)
}
function toggle(m) { picked.value = has(m) ? picked.value.filter(p => String(p.id) !== String(m.id)) : [...picked.value, m] }
function toggleMany(members, on) {
  if (on) { picked.value = [...picked.value, ...members.filter(m => !has(m))]; return }
  const ids = new Set(members.map(m => String(m.id)))
  picked.value = picked.value.filter(p => !ids.has(String(p.id)))
}
function submit() {
  if (!picked.value.length) return
  emit('add', { ids: picked.value.map(p => p.id), sendWelcome: sendWelcome.value, welcome: welcome.value.trim() })
}
</script>

<style scoped>
.ov { position: fixed; inset: 0; z-index: 90; display: flex; align-items: center; justify-content: center; padding: 24px; background: rgba(16, 24, 40, .42); overflow: hidden; }
.dlg { width: min(720px, 100%); height: min(640px, calc(100vh - 48px)); display: flex; flex-direction: column; background: var(--nb-bg-1); border: 1px solid var(--nb-line); border-radius: 8px; box-shadow: 0 24px 60px rgba(16, 24, 40, .22); overflow: hidden; }
.hd { display: grid; grid-template-columns: auto 1fr auto; gap: 12px; align-items: start; padding: 16px 18px 13px; border-bottom: 1px solid var(--nb-line); }
.hd-ic { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 11px; background: var(--brand-soft); color: var(--brand); }
.hd-tx h2 { margin: 1px 0 2px; font-size: 16px; font-weight: 600; color: var(--nb-text); }
.hd-tx p { margin: 0; font-size: 12.5px; color: var(--nb-dim); }
.x { border: 0; background: none; color: var(--nb-dim); font-size: 14px; cursor: pointer; padding: 4px 6px; border-radius: 8px; }
.x:hover { background: var(--nb-bg-3); color: var(--nb-text); }
.tabs { display: flex; gap: 4px; padding: 10px 18px 0; border-bottom: 1px solid var(--nb-line); }
/* 下划线只画在文字那一段上：按钮左右各有 12px 内边距，用 ::after 卡在这两段内边距之间，
   宽度就等于文字宽（原来整条 border-bottom 会把那 24px 也涂进去）。
   2px 的透明边框留着撑高度，不然换画法会让页签条矮 2px */
.tab { position: relative; padding: 8px 12px 10px; font: inherit; font-size: 13.5px; color: var(--nb-dim); background: none; border: 0; border-bottom: 2px solid transparent; cursor: pointer; }
.tab:hover { color: var(--nb-text); }
.tab.on { color: var(--brand); font-weight: 600; }
.tab.on::after { content: ""; position: absolute; left: 12px; right: 12px; bottom: -2px; height: 2px; background: var(--brand); }

.bar { display: flex; gap: 9px; padding: 12px 18px 0; }
.grow { flex: 1; min-width: 0; }
.ipt-wrap { position: relative; }
.ipt { width: 100%; box-sizing: border-box; padding: 8px 11px; font: inherit; font-size: 13px; color: var(--nb-text); background: var(--nb-bg-2); border: 1px solid var(--nb-line); border-radius: 10px; outline: none; }
.ipt:focus { border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-line); }
.ta { resize: none; min-height: 46px; padding-right: 11px; line-height: 1.5; }
.cnt { position: absolute; right: 10px; bottom: 7px; font-size: 11px; color: var(--nb-dim); pointer-events: none; }

.split { flex: 1; display: grid; grid-template-columns: 176px minmax(0, 1fr); gap: 14px; padding: 12px 18px; min-height: 0; }
/* 组织架构页签左边是树，两栏等宽；群聊页签左边只列群名，窄栏就够 */
.split.treey { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }
.nav { display: flex; flex-direction: column; gap: 2px; min-height: 0; overflow-y: auto; padding: 4px; border: 1px solid var(--nb-line); border-radius: 10px; background: var(--nb-bg-2); }
/* 表头要和 MemberTree / PickPanel 那两栏对齐：同高 34、同字号、带下边框；
   负外边距是为了让边框贴着面板边画（.nav 自己有 4px 内边距） */
.nav-cap { margin: -4px -4px 4px; padding: 8px 9px; font-size: 12.5px; color: var(--nb-text);
  letter-spacing: normal; border-bottom: 1px solid var(--nb-line); }
.node { display: grid; grid-template-columns: 16px minmax(0, 1fr) auto; gap: 8px; align-items: center; width: 100%; padding: 8px 10px; text-align: left; font: inherit; font-size: 13px; color: var(--nb-text); background: none; border: 0; border-radius: 9px; cursor: pointer; }
.node:hover { background: var(--nb-bg-3); }
.node.on { background: var(--brand-soft); color: var(--brand-strong); font-weight: 600; }
.nd-ic { display: grid; place-items: center; color: var(--nb-dim); }
.node.on .nd-ic { color: var(--brand); }
.nd-tx { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nd-n { font-size: 11.5px; color: var(--nb-dim); }

/* 右栏两块面板各占等高的一份：谁的内容多都撑不动对方。
   原来「已选择」是按人数长高的，勾满 4 个人就把候选面板压到只剩一条 */
.side { display: grid; grid-template-rows: minmax(0, 1fr) auto auto; gap: 9px; min-width: 0; min-height: 0; }
.side.two { grid-template-rows: minmax(0, 1fr) minmax(0, 1fr) auto auto; }
.lnk { border: 0; background: none; padding: 0; font: inherit; font-size: 12.5px; color: var(--brand); cursor: pointer; }

/* 候选成员：横向卡片流，一行放得下几张就放几张，放不下自动换行，超出只在面板内滚 */
.list { flex: 1; min-height: 0; overflow-y: auto; padding: 6px; display: grid;
  grid-template-columns: repeat(auto-fill, minmax(158px, 1fr)); grid-auto-rows: max-content; gap: 6px; align-content: start; }
/* 面板外壳和 MemberTree / PickPanel 同一套：边框、圆角、表头 */
.pane { display: flex; flex-direction: column; min-height: 0; border: 1px solid var(--nb-line); border-radius: 10px; background: var(--nb-bg-2); }
.pane-hd { padding: 8px 9px; font-size: 12.5px; color: var(--nb-text); border-bottom: 1px solid var(--nb-line); }
.pane-hd b { color: var(--brand); }
.mrow { display: grid; grid-template-columns: 18px 30px minmax(0, 1fr); gap: 9px; align-items: center;
  min-width: 0; padding: 7px 8px; text-align: left; font: inherit; color: var(--nb-text);
  background: var(--nb-bg-3); border: 0; border-radius: 8px; cursor: pointer; }
.mrow:hover { background: var(--brand-soft); }
/* 选中态用 outline（不占布局），不会像加 border 那样让卡片跳 1px */
.mrow[aria-pressed="true"] { background: var(--brand-soft); outline: 1px solid var(--brand-line); outline-offset: -1px; }
.cb { display: grid; place-items: center; width: 16px; height: 16px; font-size: 11px; color: #fff; border: 1.5px solid var(--nb-line); border-radius: 5px; background: var(--nb-bg-1); }
.cb.on { background: var(--brand); border-color: var(--brand); }
.av { position: relative; display: grid; place-items: center; width: 28px; height: 28px; font-size: 12px; font-weight: 600; color: var(--brand-strong); background: var(--brand-soft); border-radius: 50%; }
.dot { position: absolute; right: -1px; bottom: -1px; width: 8px; height: 8px; background: var(--ok); border: 1.5px solid var(--nb-bg-2); border-radius: 50%; }
.dot.BUSY { background: var(--warn); }
.mcol { min-width: 0; }
.mcol b { display: block; font-size: 13px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mcol small { display: block; font-size: 11.5px; color: var(--nb-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.welcome { display: inline-flex; align-items: center; gap: 7px; padding: 0; font: inherit; font-size: 12.5px; color: var(--nb-text); background: none; border: 0; cursor: pointer; }
.empty { grid-column: 1 / -1; margin: 0; padding: 14px; font-size: 12.5px; color: var(--nb-dim); text-align: center; }

.shell { flex: 1; padding: 20px 18px; display: flex; flex-direction: column; gap: 10px; }
.shell h3 { margin: 0; font-size: 14.5px; color: var(--nb-text); }
.shell p { margin: 0; max-width: 60ch; font-size: 12.5px; line-height: 1.6; color: var(--nb-dim); }
.shell code { padding: 1px 5px; font-size: 11.5px; background: var(--nb-bg-3); border-radius: 5px; }
.dis { max-width: 460px; opacity: .62; }
.dis .ipt { padding-right: 92px; cursor: not-allowed; }
.btn.mini { position: absolute; right: 6px; top: 50%; transform: translateY(-50%); padding: 5px 10px; font-size: 12px; border-radius: 8px; border: 1px solid var(--nb-line); background: var(--nb-bg-1); color: var(--nb-text); }
.ft { display: flex; justify-content: flex-end; padding: 12px 18px; border-top: 1px solid var(--nb-line); background: var(--nb-bg-2); }
.ft-btns { display: flex; gap: 9px; }
.btn { padding: 8px 15px; font: inherit; font-size: 13.5px; border-radius: 10px; cursor: pointer; border: 1px solid var(--nb-line); background: var(--nb-bg-1); color: var(--nb-text); }
.btn.ghost:hover { background: var(--nb-bg-3); }
.btn.pri { border-color: var(--brand); background: var(--brand); color: #fff; font-weight: 600; }
.btn.pri:hover:not(:disabled) { background: var(--brand-strong); border-color: var(--brand-strong); }
.btn:disabled { opacity: .5; cursor: not-allowed; }
/* nebula.css 的全局 .btn 带 letter-spacing:1px，中文按钮字距会被拉开 */
.btn { letter-spacing: normal; }
.with { font-weight: 400; opacity: .9; }
</style>
