<template>
  <div v-if="open" class="ov" @click.self="$emit('close')">
    <div class="dlg" role="dialog" aria-modal="true" aria-label="创建群聊">
      <header class="hd">
        <span class="hd-ic" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.7">
            <circle cx="9" cy="8" r="3.2" /><path d="M3.5 19a5.5 5.5 0 0 1 11 0" />
            <circle cx="16.5" cy="9.5" r="2.4" /><path d="M15 15.6a4.6 4.6 0 0 1 5.5 3.4" />
          </svg>
        </span>
        <div class="hd-tx">
          <h2>创建群聊</h2>
          <p>创建一个新的群聊，邀请团队成员共同协作。</p>
        </div>
        <button class="x" type="button" aria-label="关闭" @click="$emit('close')">✕</button>
      </header>

      <div class="bd">
        <div class="fld">
          <label class="lb" for="cg-name">群聊名称 <i class="req">*</i></label>
          <div class="ipt-wrap">
            <input id="cg-name" v-model="name" class="ipt" type="text" maxlength="50"
                   placeholder="例如：产品策划讨论群" autocomplete="off" />
            <span class="cnt">{{ name.length }}/50</span>
          </div>
        </div>

        <div class="fld">
          <label class="lb" for="cg-desc">群聊简介 <span class="opt">（选填）</span></label>
          <div class="ipt-wrap">
            <textarea id="cg-desc" v-model="announcement" class="ipt ta" maxlength="200" rows="2"
                      placeholder="简要描述群聊的用途和规则…" />
            <span class="cnt ta-cnt">{{ announcement.length }}/200</span>
          </div>
        </div>

        <div class="fld">
          <span class="lb">群聊类型</span>
          <div class="types">
            <button v-for="t in TYPES" :key="t.value" type="button" class="type"
                    :class="{ on: groupType === t.value }" :aria-pressed="groupType === t.value"
                    @click="groupType = t.value">
              <span class="t-ic" aria-hidden="true">
                <svg v-if="t.value === 1" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7">
                  <path d="M4 6.5h16v10H12l-4 3v-3H4z" />
                </svg>
                <svg v-else-if="t.value === 2" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7">
                  <rect x="4" y="4.5" width="6" height="15" rx="1.4" /><rect x="13.5" y="4.5" width="6.5" height="9" rx="1.4" />
                </svg>
                <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7">
                  <rect x="4.5" y="4.5" width="15" height="15" rx="1.6" /><path d="M9 20v-4h6v4M9 8h.01M15 8h.01M9 12h.01M15 12h.01" />
                </svg>
              </span>
              <span class="t-body">
                <b>{{ t.name }}</b>
                <small>{{ t.desc }}</small>
              </span>
              <span v-if="groupType === t.value" class="t-check" aria-hidden="true">✓</span>
            </button>
          </div>
        </div>

        <div class="fld mem">
          <label class="lb" for="cg-search">选择成员</label>
          <div class="transfer">
            <MemberTree :members="candidates" :picked="picked" @toggle="toggle" @toggle-many="toggleMany" />

<PickPanel :picked="picked" @remove="toggle" @clear="picked = []" />
          </div>
        </div>
      </div>

      <footer class="ft">
        <label class="pin" :title="PIN_NOTE">
          <input type="checkbox" disabled />
          <span>同时置顶到聊天列表</span>
          <em>待接入</em>
        </label>
        <div class="ft-btns">
          <button class="btn ghost" type="button" @click="$emit('close')">取消</button>
          <button class="btn pri" type="button" :disabled="!canSubmit" @click="submit">
            创建群聊<span v-if="picked.length" class="with">（{{ picked.length }} 人）</span>
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
  candidates: { type: Array, default: () => [] }
})
const emit = defineEmits(['close', 'submit'])

const TYPES = [
  { value: 1, name: '普通群', desc: '日常讨论，快速沟通' },
  { value: 2, name: '项目群', desc: '基于项目的协作群' },
  { value: 3, name: '部门群', desc: '跨部门的团队协作' }
]
const PIN_NOTE = '置顶要往 chat_user_conversation 加 pinned 列与排序，接口还没做，先置灰不隐藏'

const name = ref('')
const announcement = ref('')
const groupType = ref(1)
const picked = ref([])

watch(() => props.open, (v) => {
  if (v) { name.value = ''; announcement.value = ''; groupType.value = 1; picked.value = [] }
})

const initial = (m) => String(m.nickname || m.username || '?').charAt(0).toUpperCase()
const isPicked = (m) => picked.value.some(p => String(p.id) === String(m.id))

const canSubmit = computed(() => !!name.value.trim() && picked.value.length > 0)

function toggle(m) {
  picked.value = isPicked(m) ? picked.value.filter(p => String(p.id) !== String(m.id)) : [...picked.value, m]
}

function toggleMany(members, on) {
  if (on) { picked.value = [...picked.value, ...members.filter(m => !isPicked(m))]; return }
  const ids = new Set(members.map(m => String(m.id)))
  picked.value = picked.value.filter(p => !ids.has(String(p.id)))
}

function submit() {
  if (!canSubmit.value) return
  emit('submit', {
    name: name.value.trim(),
    announcement: announcement.value.trim(),
    groupType: groupType.value,
    memberIds: picked.value.map(p => p.id)
  })
}
</script>

<style scoped>
.ov { position: fixed; inset: 0; z-index: 90; display: flex; align-items: center; justify-content: center; padding: 24px; background: rgba(16, 24, 40, .42); overflow: hidden; }
.dlg { width: min(560px, 100%); height: min(640px, calc(100vh - 48px)); display: flex; flex-direction: column; background: var(--nb-bg-1); border: 1px solid var(--nb-line); border-radius: 8px; box-shadow: 0 24px 60px rgba(16, 24, 40, .22); overflow: hidden; }
.hd { display: grid; grid-template-columns: auto 1fr auto; gap: 12px; align-items: start; padding: 18px 18px 14px; border-bottom: 1px solid var(--nb-line); }
.hd-ic { display: grid; place-items: center; width: 40px; height: 40px; border-radius: 11px; background: var(--brand-soft); color: var(--brand); }
.hd-tx h2 { margin: 1px 0 2px; font-size: 16px; font-weight: 600; color: var(--nb-text); }
.hd-tx p { margin: 0; font-size: 12.5px; color: var(--nb-dim); }
.x { border: 0; background: none; color: var(--nb-dim); font-size: 14px; cursor: pointer; padding: 4px 6px; border-radius: 8px; }
.x:hover { background: var(--nb-bg-3); color: var(--nb-text); }
.bd { flex: 1; overflow-y: auto; padding: 12px 18px; display: flex; flex-direction: column; gap: 11px; min-height: 0; }
.fld { display: flex; flex-direction: column; gap: 7px; }
/* 成员那一栏吃掉剩余高度：整块在 640 里刚好装平时 .bd 就没得滚，只剩列表内部一条 */
.bd > .fld:last-child { flex: 1; min-height: 0; }
.lb { font-size: 12.5px; font-weight: 600; color: var(--nb-text); }
.req { color: var(--danger, #d92d20); font-style: normal; }
.opt { font-weight: 400; color: var(--nb-dim); }
.ipt-wrap { position: relative; }
.ipt { width: 100%; box-sizing: border-box; padding: 8px 56px 8px 11px; font: inherit; font-size: 13px; color: var(--nb-text); background: var(--nb-bg-2); border: 1px solid var(--nb-line); border-radius: 10px; outline: none; }
.ipt:focus { border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-line); }
.ipt::placeholder { color: var(--nb-dim-2, var(--nb-dim)); }
.ta { resize: vertical; min-height: 46px; padding-right: 11px; line-height: 1.5; }
.cnt { position: absolute; right: 10px; bottom: 8px; font-size: 11.5px; color: var(--nb-dim); pointer-events: none; }
.ta-cnt { top: 8px; bottom: auto; }
.types { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
/* 图标和标题同一行（原来竖排一张卡 90px，三张就把整块顶出滚动条了） */
.type { position: relative; display: flex; align-items: flex-start; gap: 8px; padding: 10px 11px; text-align: left; cursor: pointer; background: var(--nb-bg-2); border: 1px solid var(--nb-line); border-radius: 10px; color: var(--nb-text); transition: border-color .14s, background .14s; }
.type:hover { border-color: var(--brand-line); }
.type.on { border-color: var(--brand); background: var(--brand-soft); }
.t-ic { flex: 0 0 auto; padding-top: 1px; color: var(--brand); }
.t-body { min-width: 0; }
.t-body b { display: block; font-size: 13px; font-weight: 600; }
.t-body small { display: block; margin-top: 2px; font-size: 11.5px; line-height: 1.4; color: var(--nb-dim); }
.t-check { position: absolute; top: 9px; right: 9px; display: grid; place-items: center; width: 17px; height: 17px; font-size: 11px; color: #fff; background: var(--brand); border-radius: 50%; }
/* 双栏穿梭：左=部门树（搜索框在树上方），右=已选。两块各自内部滚，弹框主体不滚 */
.transfer { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 10px; }
/* 左右两栏分别搬去 MemberTree.vue 和 PickPanel.vue，这里只剩两栏的栅格 */
.ft { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 13px 18px; border-top: 1px solid var(--nb-line); background: var(--nb-bg-2); }
.pin { display: inline-flex; align-items: center; gap: 7px; font-size: 12.5px; color: var(--nb-dim); cursor: not-allowed; }
.pin input { accent-color: var(--brand); }
.pin em { padding: 1px 6px; font-size: 11px; font-style: normal; color: var(--nb-dim); background: var(--nb-bg-3); border: 1px solid var(--nb-line); border-radius: 999px; }
.ft-btns { display: flex; gap: 9px; }
.btn { padding: 8px 15px; font: inherit; font-size: 13.5px; border-radius: 10px; cursor: pointer; border: 1px solid var(--nb-line); background: var(--nb-bg-1); color: var(--nb-text); }
.btn.ghost:hover { background: var(--nb-bg-3); }
.btn.pri { border-color: var(--brand); background: var(--brand); color: #fff; font-weight: 600; }
.btn.pri:hover:not(:disabled) { background: var(--brand-strong); border-color: var(--brand-strong); }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.btn { letter-spacing: normal; }
.with { font-weight: 400; opacity: .9; }
</style>
