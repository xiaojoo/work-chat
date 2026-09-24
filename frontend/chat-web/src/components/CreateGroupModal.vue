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
            <textarea id="cg-desc" v-model="announcement" class="ipt ta" maxlength="200" rows="3"
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

        <div class="fld">
          <label class="lb" for="cg-search">选择成员</label>
          <div class="ipt-wrap">
            <input id="cg-search" v-model="q" class="ipt" type="text" placeholder="搜索成员姓名、部门或邮箱" autocomplete="off" />
          </div>
          <div v-if="picked.length" class="chips">
            <span v-for="p in picked" :key="p.id" class="chip">
              {{ p.nickname || p.username }}
              <button class="chip-x" type="button" :aria-label="'移除 ' + (p.nickname || p.username)" @click="toggle(p)">✕</button>
            </span>
            <button v-if="picked.length > 3" class="chip more" type="button" @click="q = ''">+{{ picked.length - 3 }}</button>
          </div>
          <div class="list" role="listbox">
            <button v-for="m in filtered" :key="m.id" type="button" class="row" role="option"
                    :aria-selected="isPicked(m)" @click="toggle(m)">
              <span class="cb" :class="{ on: isPicked(m) }" aria-hidden="true">{{ isPicked(m) ? '✓' : '' }}</span>
              <span class="av">{{ initial(m) }}</span>
              <span class="nm">{{ m.nickname || m.username }}</span>
              <span class="dp">{{ m.department || '未分配' }}</span>
            </button>
            <p v-if="!filtered.length" class="empty">没有匹配的成员</p>
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
const q = ref('')
const picked = ref([])

watch(() => props.open, (v) => {
  if (v) { name.value = ''; announcement.value = ''; groupType.value = 1; q.value = ''; picked.value = [] }
})

const initial = (m) => String(m.nickname || m.username || '?').charAt(0).toUpperCase()
const isPicked = (m) => picked.value.some(p => String(p.id) === String(m.id))

const filtered = computed(() => {
  const kw = q.value.trim().toLowerCase()
  const list = props.candidates.filter(m => !kw
    || String(m.nickname || '').toLowerCase().includes(kw)
    || String(m.username || '').toLowerCase().includes(kw)
    || String(m.department || '').toLowerCase().includes(kw)
    || String(m.email || '').toLowerCase().includes(kw))
  // 已选的排到最前，方便确认勾上了没有
  return [...list.filter(isPicked), ...list.filter(m => !isPicked(m))]
})

const canSubmit = computed(() => !!name.value.trim() && picked.value.length > 0)

function toggle(m) {
  picked.value = isPicked(m) ? picked.value.filter(p => String(p.id) !== String(m.id)) : [...picked.value, m]
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
.bd { flex: 1; overflow-y: auto; padding: 16px 18px 4px; display: flex; flex-direction: column; gap: 15px; }
.fld { display: flex; flex-direction: column; gap: 7px; }
.lb { font-size: 12.5px; font-weight: 600; color: var(--nb-text); }
.req { color: var(--danger, #d92d20); font-style: normal; }
.opt { font-weight: 400; color: var(--nb-dim); }
.ipt-wrap { position: relative; }
.ipt { width: 100%; box-sizing: border-box; padding: 8px 56px 8px 11px; font: inherit; font-size: 13px; color: var(--nb-text); background: var(--nb-bg-2); border: 1px solid var(--nb-line); border-radius: 10px; outline: none; }
.ipt:focus { border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-line); }
.ipt::placeholder { color: var(--nb-dim-2, var(--nb-dim)); }
.ta { resize: vertical; min-height: 62px; padding-right: 11px; line-height: 1.5; }
.cnt { position: absolute; right: 10px; bottom: 8px; font-size: 11.5px; color: var(--nb-dim); pointer-events: none; }
.ta-cnt { top: 8px; bottom: auto; }
.types { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.type { position: relative; display: flex; flex-direction: column; gap: 7px; padding: 11px 11px 12px; text-align: left; cursor: pointer; background: var(--nb-bg-2); border: 1px solid var(--nb-line); border-radius: 10px; color: var(--nb-text); transition: border-color .14s, background .14s; }
.type:hover { border-color: var(--brand-line); }
.type.on { border-color: var(--brand); background: var(--brand-soft); }
.t-ic { color: var(--brand); }
.t-body b { display: block; font-size: 13px; font-weight: 600; }
.t-body small { display: block; margin-top: 2px; font-size: 11.5px; line-height: 1.4; color: var(--nb-dim); }
.t-check { position: absolute; top: 9px; right: 9px; display: grid; place-items: center; width: 17px; height: 17px; font-size: 11px; color: #fff; background: var(--brand); border-radius: 50%; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { display: inline-flex; align-items: center; gap: 5px; padding: 3px 7px 3px 9px; font-size: 12.5px; color: var(--brand-strong); background: var(--brand-soft); border: 1px solid var(--brand-line); border-radius: 999px; }
.chip.more { color: var(--nb-text); background: var(--nb-bg-3); border-color: var(--nb-line); cursor: pointer; }
.chip-x { border: 0; background: none; padding: 0; font-size: 11px; color: inherit; cursor: pointer; opacity: .7; }
.chip-x:hover { opacity: 1; }
.list { max-height: 190px; overflow-y: auto; border: 1px solid var(--nb-line); border-radius: 12px; background: var(--nb-bg-2); }
.row { display: grid; grid-template-columns: 18px 28px 1fr auto; gap: 9px; align-items: center; width: 100%; padding: 8px 11px; text-align: left; cursor: pointer; background: none; border: 0; border-bottom: 1px solid var(--nb-line); color: var(--nb-text); }
.row:last-child { border-bottom: 0; }
.row:hover { background: var(--nb-bg-3); }
.cb { display: grid; place-items: center; width: 16px; height: 16px; font-size: 11px; color: #fff; border: 1.5px solid var(--nb-line); border-radius: 5px; background: var(--nb-bg-1); }
.cb.on { background: var(--brand); border-color: var(--brand); }
.av { display: grid; place-items: center; width: 28px; height: 28px; font-size: 12px; font-weight: 600; color: var(--brand-strong); background: var(--brand-soft); border-radius: 50%; }
.nm { font-size: 13.5px; }
.dp { font-size: 12px; color: var(--nb-dim); }
.empty { margin: 0; padding: 14px; font-size: 12.5px; color: var(--nb-dim); text-align: center; }
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
