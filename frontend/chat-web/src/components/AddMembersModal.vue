<template>
  <div v-if="open" class="ov" @click.self="$emit('close')">
    <div class="dlg" role="dialog" aria-modal="true" :aria-label="isFriend ? '添加好友' : '添加用户'">
      <header class="hd">
        <span class="hd-ic" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.7">
            <circle cx="10" cy="8" r="3.3" /><path d="M4 19a6 6 0 0 1 12 0" /><path d="M18 8v6M15 11h6" />
          </svg>
        </span>
        <div class="hd-tx">
          <h2>{{ isFriend ? '添加好友' : '添加用户' }}</h2>
          <p>{{ isFriend ? '从组织架构或你所在的群里挑人要加为好友' : '选择成员加入团队或群聊' }}</p>
        </div>
        <button class="x" type="button" aria-label="关闭" @click="$emit('close')">✕</button>
      </header>

      <nav class="tabs" role="tablist">
        <button v-for="t in TABS" :key="t.key" type="button" role="tab" class="tab"
                :class="{ on: tab === t.key }" :aria-selected="tab === t.key" @click="tab = t.key">{{ t.name }}</button>
      </nav>

      <!-- 搜索与部门筛选跨两栏，稿子里它们在树和列表之上 -->
      <div v-if="tab !== 'link'" class="bar">
        <div class="ipt-wrap grow">
          <input v-model="q" class="ipt" type="text" :placeholder="tab === 'org' ? '搜索姓名、部门或职位' : '搜索群名'" autocomplete="off" />
        </div>
        <div v-if="tab === 'org'" class="sel-wrap" ref="selWrap">
          <button ref="selBtn" type="button" class="sel" :class="{ open: deptOpen }"
                  aria-haspopup="listbox" :aria-expanded="deptOpen" aria-label="按部门筛选"
                  @click="deptOpen ? closeDept() : openDept()" @keydown="onDeptBtnKey">
            <span class="sel-tx">{{ dept || '全部部门' }}</span>
            <span class="sel-ar" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9.5l6 5.5 6-5.5" /></svg>
            </span>
          </button>
          <ul v-show="deptOpen" ref="deptMenu" class="menu" role="listbox" aria-label="部门" tabindex="-1"
              @keydown="onDeptMenuKey">
            <li v-for="o in deptOptions" :key="o.value" role="option" :aria-selected="dept === o.value"
                @click="chooseDept(o.value)">{{ o.label }}</li>
          </ul>
        </div>
      </div>

      <div v-if="tab !== 'link'" class="split">
        <!-- 左：导航。组织架构按部门列，群聊按群列；成员一律出现在右栏 -->
        <aside class="nav">
          <p class="nav-cap">{{ tab === 'org' ? '组织架构' : '我的群聊' }}</p>
          <template v-if="tab === 'org'">
            <button type="button" class="node" :class="{ on: dept === '' }" @click="dept = ''">
              <span class="nd-ic" aria-hidden="true"><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.7"><circle cx="9" cy="8" r="2.8" /><path d="M3.6 18a5.4 5.4 0 0 1 10.8 0" /><circle cx="17" cy="9" r="2.2" /><path d="M15.4 17.4a4.4 4.4 0 0 1 5.2 3.1" /></svg></span>
              <span class="nd-tx">全部成员</span>
              <span class="nd-n">{{ total }}</span>
            </button>
            <button v-for="d in deptNav" :key="d.department" type="button" class="node"
                    :class="{ on: dept === d.department }" @click="dept = d.department">
              <span class="nd-ic" aria-hidden="true"><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3.5 7h5l1.6 2H20.5v9.5h-17z" /></svg></span>
              <span class="nd-tx">{{ d.department }}</span>
              <span class="nd-n">{{ d.count }}</span>
            </button>
            <p v-if="!deptNav.length" class="empty">没有可选的部门</p>
          </template>
          <template v-else>
            <button v-for="g in visibleGroups" :key="g.id" type="button" class="node"
                    :class="{ on: pickedGroup === g.id }" @click="pickGroup(g)">
              <span class="nd-ic" aria-hidden="true"><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 6.5h16v10H12l-4 3v-3H4z" /></svg></span>
              <span class="nd-tx">{{ g.name }}</span>
              <span class="nd-n">{{ g.memberCount || 0 }} 人</span>
            </button>
            <p v-if="!visibleGroups.length" class="empty">你还没有可导入的群聊</p>
          </template>
        </aside>

        <!-- 右：已选摘要 + 候选成员 + 欢迎消息 -->
        <div class="side">
          <div class="rt-hd">
            <span>已选择 <b>{{ picked.length }}</b> 人</span>
            <button v-if="picked.length" class="lnk" type="button" @click="picked = []">清空</button>
          </div>
          <div v-if="picked.length" class="chips">
            <span v-for="p in picked" :key="p.id" class="chip">
              <span class="chip-av">{{ initial(p) }}</span>{{ p.nickname || p.username }}
              <button class="chip-x" type="button" :aria-label="'移除 ' + (p.nickname || p.username)" @click="toggle(p)">✕</button>
            </span>
          </div>

          <div class="list">
            <button v-for="m in candidates" :key="m.id" type="button" class="mrow" :aria-pressed="has(m)" @click="toggle(m)">
              <span class="cb" :class="{ on: has(m) }" aria-hidden="true">{{ has(m) ? '✓' : '' }}</span>
              <span class="av">{{ initial(m) }}<i v-if="isOnline(m)" class="dot" title="在线" /></span>
              <span class="mcol">
                <b>{{ m.nickname || m.username }}</b>
                <small>{{ m.position || m.department || '未填写职位' }}</small>
              </span>
            </button>
            <p v-if="!candidates.length" class="empty">{{ listEmpty }}</p>
          </div>

          <button v-if="!isFriend" type="button" class="welcome" role="checkbox" :aria-checked="sendWelcome" @click="sendWelcome = !sendWelcome">
            <span class="cb" :class="{ on: sendWelcome }" aria-hidden="true">{{ sendWelcome ? '✓' : '' }}</span>
            <span>发送欢迎消息</span>
          </button>
          <div v-if="!isFriend && sendWelcome" class="ipt-wrap">
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
            添加<span v-if="picked.length" class="with">（{{ picked.length }}）</span>
          </button>
        </div>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  org: { type: Array, default: () => [] },      // [{department,count,members:[]}]
  groups: { type: Array, default: () => [] },   // 我的群
  online: { type: Array, default: () => [] },   // 在线用户 id
  excludeIds: { type: Array, default: () => [] },
  groupMembersCache: { type: Object, default: () => ({}) },  // groupId -> 成员数组，由父组件按需拉
  mode: { type: String, default: 'group' }   // group=拉进群 / friend=加为好友
})
const emit = defineEmits(['close', 'add', 'expand-group'])

const ALL_TABS = [{ key: 'org', name: '从组织架构添加' }, { key: 'group', name: '从群聊添加' }, { key: 'link', name: '通过链接邀请' }]
const PLACEHOLDER_LINK = '—（待接入邀请令牌）—'

const isFriend = computed(() => props.mode === 'friend')
// 好友模式不给「通过链接邀请」：那条链路根本没有，给了就是个点了没反应的页签
const TABS = computed(() => isFriend.value ? ALL_TABS.slice(0, 2) : ALL_TABS)
const tab = ref('org')
const q = ref('')
const dept = ref('')
const picked = ref([])
const sendWelcome = ref(true)
// 预填而不是占位：占位符不算值，勾着「发送欢迎消息」却发空串等于摆了个哑开关
const welcome = ref('欢迎加入团队！一起创造更好的内容！')
const pickedGroup = ref(null)

watch(() => props.open, (v) => {
  if (v) {
    tab.value = 'org'; q.value = ''; dept.value = ''; deptOpen.value = false
    picked.value = []; welcome.value = '欢迎加入团队！一起创造更好的内容！'; pickedGroup.value = null
  }
})

const deptOpen = ref(false)
const selWrap = ref(null)
const selBtn = ref(null)
const deptMenu = ref(null)
// 选项取全部部门，不取 deptNav：deptNav 按当前搜索词过滤，选中一个部门后若计数归零，
// 这个部门就会从下拉里消失，筛选项自己把自己筛掉，右栏空着却没人能改回来
const deptOptions = computed(() => [{ value: '', label: '全部部门' },
  ...props.org.map(d => ({ value: d.department, label: d.department }))])

const scrollCur = () => deptMenu.value?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' })
function openDept() { deptOpen.value = true; nextTick(() => { deptMenu.value?.focus(); scrollCur() }) }
function closeDept() { deptOpen.value = false; nextTick(() => selBtn.value?.focus()) }
function chooseDept(v) { dept.value = v; closeDept() }
function onDeptBtnKey(e) {
  if (e.key === 'Escape' && deptOpen.value) { e.preventDefault(); closeDept(); return }
  if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(e.key)) { e.preventDefault(); openDept() }
}
function onDeptMenuKey(e) {
  const opts = deptOptions.value
  const at = opts.findIndex(o => o.value === dept.value)
  const to = n => { e.preventDefault(); dept.value = opts[n].value; scrollCur() }
  if (e.key === 'ArrowDown') to(Math.min(at + 1, opts.length - 1))
  else if (e.key === 'ArrowUp') to(Math.max(at - 1, 0))
  else if (e.key === 'Home') to(0)
  else if (e.key === 'End') to(opts.length - 1)
  else if (e.key === 'Enter' || e.key === ' ' || e.key === 'Escape') { e.preventDefault(); closeDept() }
  else if (e.key === 'Tab') deptOpen.value = false
}
// 点外面先关下拉。只有落在暗底上的那一下才顺手吞掉——不吞的话它会命中 .ov 的 @click.self，
// 把整个弹框一起关掉；但弹框内的控件（页签、成员行）必须照常收到这次点击，不然要点两次才生效
function onDocClick(e) {
  if (!deptOpen.value || selWrap.value?.contains(e.target)) return
  deptOpen.value = false
  if (!e.target.closest?.('.dlg')) {
    e.preventDefault(); e.stopPropagation()
    selBtn.value?.focus()
  }
}
onMounted(() => document.addEventListener('click', onDocClick, true))
onUnmounted(() => document.removeEventListener('click', onDocClick, true))

const kw = computed(() => q.value.trim().toLowerCase())
const excluded = computed(() => new Set(props.excludeIds.map(String)))
const initial = (m) => String(m.nickname || m.username || '?').charAt(0).toUpperCase()
const isOnline = (m) => props.online.some(id => String(id) === String(m.id))
const has = (m) => picked.value.some(p => String(p.id) === String(m.id))
const usable = (m) => !excluded.value.has(String(m.id)) && !String(m.id).startsWith('local-')
const kwHit = (m) => !kw.value || [m.nickname, m.username, m.position, m.department, m.email]
  .some(v => String(v || '').toLowerCase().includes(kw.value))

const total = computed(() => props.org.reduce((s, d) => s + d.members.filter(usable).length, 0))
// 计数按「真的能选」算，含被排除的自己和已在群里的人会让左栏数字和右栏列表对不上
const deptNav = computed(() => props.org
  .map(d => ({ department: d.department, count: d.members.filter(m => usable(m) && kwHit(m)).length }))
  .filter(d => d.count > 0))

const cacheOf = (g) => props.groupMembersCache[String(g.id)] || []

const candidates = computed(() => {
  if (tab.value === 'group') {
    const g = props.groups.find(x => x.id === pickedGroup.value)
    if (!g) return []
    return cacheOf(g).filter(m => usable(m) && kwHit(m))
  }
  const rows = []
  for (const d of props.org) {
    if (dept.value && d.department !== dept.value) continue
    for (const m of d.members) if (usable(m) && kwHit(m)) rows.push(m)
  }
  return rows
})

const listEmpty = computed(() => {
  if (tab.value === 'group') {
    if (!pickedGroup.value) return '点开左边的群，成员会显示在这里'
    return '该群成员正在加载，或都不在可选范围内'
  }
  return '没有匹配的成员'
})

const visibleGroups = computed(() => props.groups
  .filter(g => !kw.value || String(g.name || '').toLowerCase().includes(kw.value)))

function pickGroup(g) {
  pickedGroup.value = pickedGroup.value === g.id ? null : g.id
  emit('expand-group', g)
}
function toggle(m) { picked.value = has(m) ? picked.value.filter(p => String(p.id) !== String(m.id)) : [...picked.value, m] }
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
.tab { padding: 8px 12px 10px; font: inherit; font-size: 13.5px; color: var(--nb-dim); background: none; border: 0; border-bottom: 2px solid transparent; cursor: pointer; }
.tab:hover { color: var(--nb-text); }
.tab.on { color: var(--brand); font-weight: 600; border-bottom-color: var(--brand); }

.bar { display: flex; gap: 9px; padding: 12px 18px 0; }
.grow { flex: 1; min-width: 0; }
.ipt-wrap { position: relative; }
.ipt { width: 100%; box-sizing: border-box; padding: 8px 11px; font: inherit; font-size: 13px; color: var(--nb-text); background: var(--nb-bg-2); border: 1px solid var(--nb-line); border-radius: 10px; outline: none; }
.ipt:focus { border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-line); }
.ta { resize: none; min-height: 46px; padding-right: 11px; line-height: 1.5; }
.cnt { position: absolute; right: 10px; bottom: 7px; font-size: 11px; color: var(--nb-dim); pointer-events: none; }
.sel-wrap { position: relative; flex: 0 0 128px; }
.sel { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; padding: 7px 10px;
  text-align: left; font: inherit; font-size: 13px; color: var(--nb-text); background: var(--nb-bg-2);
  border: 1px solid var(--nb-line); border-radius: 10px; outline: none; cursor: pointer; }
.sel:hover { border-color: var(--brand); }
.sel.open, .sel:focus-visible { border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-line); }
.sel-tx { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sel-ar { display: grid; place-items: center; flex: 0 0 auto; color: var(--nb-dim); transition: transform .14s; }
.sel.open .sel-ar { transform: rotate(180deg); color: var(--brand); }
/* 面板比触发器宽一点才装得下长部门名；top 里的 4px 间隙要算进 max-height，不然最后一行会被弹框底边切掉 */
.menu { position: absolute; z-index: 5; top: calc(100% + 4px); left: 0; width: max-content; min-width: 100%;
  max-width: 240px; max-height: 268px; overflow-y: auto; margin: 0; padding: 4px; list-style: none; outline: none;
  background: var(--nb-bg-1); border: 1px solid var(--nb-line); border-radius: 10px;
  box-shadow: 0 10px 26px rgba(16, 24, 40, .16); }
.menu li { padding: 7px 9px; font-size: 12.5px; color: var(--nb-text); border-radius: 7px; cursor: pointer;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.menu li:hover { background: var(--nb-bg-3); }
.menu li[aria-selected='true'] { background: var(--brand-soft); color: var(--brand-strong); font-weight: 600; }

.split { flex: 1; display: grid; grid-template-columns: 176px minmax(0, 1fr); gap: 14px; padding: 12px 18px; min-height: 0; }
.nav { display: flex; flex-direction: column; gap: 2px; min-height: 0; overflow-y: auto; padding-right: 2px; }
.nav-cap { margin: 2px 0 6px; font-size: 11.5px; color: var(--nb-dim); letter-spacing: .04em; }
.node { display: grid; grid-template-columns: 16px minmax(0, 1fr) auto; gap: 8px; align-items: center; width: 100%; padding: 8px 10px; text-align: left; font: inherit; font-size: 13px; color: var(--nb-text); background: none; border: 0; border-radius: 9px; cursor: pointer; }
.node:hover { background: var(--nb-bg-3); }
.node.on { background: var(--brand-soft); color: var(--brand-strong); font-weight: 600; }
.nd-ic { display: grid; place-items: center; color: var(--nb-dim); }
.node.on .nd-ic { color: var(--brand); }
.nd-tx { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nd-n { font-size: 11.5px; color: var(--nb-dim); }

.side { display: flex; flex-direction: column; gap: 9px; min-width: 0; min-height: 0; }
.rt-hd { display: flex; align-items: baseline; justify-content: space-between; font-size: 13px; color: var(--nb-text); }
.rt-hd b { color: var(--brand); }
.lnk { border: 0; background: none; padding: 0; font: inherit; font-size: 12.5px; color: var(--brand); cursor: pointer; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { display: inline-flex; align-items: center; gap: 5px; padding: 2px 7px 2px 3px; font-size: 12.5px; color: var(--brand-strong); background: var(--brand-soft); border: 1px solid var(--brand-line); border-radius: 999px; }
.chip-av { display: grid; place-items: center; width: 18px; height: 18px; font-size: 10px; font-weight: 600; color: #fff; background: var(--brand); border-radius: 50%; }
.chip-x { border: 0; background: none; padding: 0; font-size: 11px; color: inherit; cursor: pointer; opacity: .7; }
.chip-x:hover { opacity: 1; }

.list { flex: 1; min-height: 60px; overflow-y: auto; border: 1px solid var(--nb-line); border-radius: 12px; background: var(--nb-bg-2); }
.mrow { display: grid; grid-template-columns: 18px 30px minmax(0, 1fr); gap: 9px; align-items: center; width: 100%; padding: 8px 11px; text-align: left; font: inherit; background: none; border: 0; border-bottom: 1px solid var(--nb-line); color: var(--nb-text); cursor: pointer; }
.mrow:last-child { border-bottom: 0; }
.mrow:hover { background: var(--nb-bg-3); }
.cb { display: grid; place-items: center; width: 16px; height: 16px; font-size: 11px; color: #fff; border: 1.5px solid var(--nb-line); border-radius: 5px; background: var(--nb-bg-1); }
.cb.on { background: var(--brand); border-color: var(--brand); }
.av { position: relative; display: grid; place-items: center; width: 28px; height: 28px; font-size: 12px; font-weight: 600; color: var(--brand-strong); background: var(--brand-soft); border-radius: 50%; }
.dot { position: absolute; right: -1px; bottom: -1px; width: 8px; height: 8px; background: #22c55e; border: 1.5px solid var(--nb-bg-2); border-radius: 50%; }
.mcol { min-width: 0; }
.mcol b { display: block; font-size: 13px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mcol small { display: block; font-size: 11.5px; color: var(--nb-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.welcome { display: inline-flex; align-items: center; gap: 7px; padding: 0; font: inherit; font-size: 12.5px; color: var(--nb-text); background: none; border: 0; cursor: pointer; }
.empty { margin: 0; padding: 14px; font-size: 12.5px; color: var(--nb-dim); text-align: center; }

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
