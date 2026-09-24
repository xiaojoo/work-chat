<template>
  <div class="mt">
    <div class="mt-hd">
      <div class="mt-ipt">
        <input v-model="q" class="ipt" type="text" :placeholder="placeholder" autocomplete="off" />
      </div>
      <span class="mt-n">{{ totalShown }} 人</span>
    </div>
    <div class="mt-body" role="tree">
      <div v-for="d in tree" :key="d.name" class="grp" role="treeitem" :aria-expanded="isOpen(d.name)">
        <div class="l1">
          <button type="button" class="car" :class="{ open: isOpen(d.name) }"
                  :aria-label="(isOpen(d.name) ? '折叠' : '展开') + d.name" @click="fold(d.name)">
            <svg viewBox="0 0 24 24" width="10" height="10" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6" /></svg>
          </button>
          <button type="button" class="cb" role="checkbox"
                  :aria-checked="d.all ? true : (d.some ? 'mixed' : false)"
                  :aria-label="'全选 ' + d.name" @click="$emit('toggle-many', d.members, !d.all)">
            <span aria-hidden="true">{{ d.all ? '✓' : (d.some ? '−' : '') }}</span>
          </button>
          <button type="button" class="gn" @click="fold(d.name)">{{ d.name }}<small>{{ d.members.length }}</small></button>
        </div>
        <ul v-show="isOpen(d.name)" class="kids" role="group">
          <li v-for="m in d.members" :key="m.id">
            <button type="button" class="row" role="checkbox" :aria-checked="has(m)" @click="$emit('toggle', m)">
              <span class="cb" :class="{ on: has(m) }" aria-hidden="true">{{ has(m) ? '✓' : '' }}</span>
              <span class="av">{{ initial(m) }}<i v-if="isOnline(m)" class="dot" title="在线" /></span>
              <span class="nm">{{ m.nickname || m.username }}</span>
              <span class="rl">{{ subOf(m) }}</span>
            </button>
          </li>
        </ul>
      </div>
      <p v-if="!tree.length" class="empty">{{ emptyText }}</p>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

// 树只有两层：部门 → 人。组织架构接口给的就是 [{department, members[]}]，没有第三级，
// 硬造一层假层级的话，勾上去的语义就说不清了
const props = defineProps({
  members: { type: Array, default: () => [] },   // 已经由调用方筛过的候选人（排除自己/已在群里等）
  picked: { type: Array, default: () => [] },
  online: { type: Array, default: () => [] },     // 传了才画在线点
  placeholder: { type: String, default: '搜索姓名、部门或邮箱' },
  emptyText: { type: String, default: '没有匹配的成员' }
})
defineEmits(['toggle', 'toggle-many'])

const q = ref('')
// 默认全部折叠：部门名后面带着人数，收起也不丢信息
const openDepts = ref({})

const initial = (m) => String(m.nickname || m.username || '?').charAt(0).toUpperCase()
const deptOf = (m) => m.department || '未分配'
const subOf = (m) => m.position || m.department || '未填写职位'
const has = (m) => props.picked.some(p => String(p.id) === String(m.id))
const isOnline = (m) => props.online.some(id => String(id) === String(m.id))
const isOpen = d => openDepts.value[d] === true
const fold = d => { openDepts.value[d] = !isOpen(d) }

const tree = computed(() => {
  const kw = q.value.trim().toLowerCase()
  const hit = m => !kw || [m.nickname, m.username, m.department, m.email, m.position]
    .some(v => String(v || '').toLowerCase().includes(kw))
  const byDept = new Map()
  for (const m of props.members) {
    if (!hit(m)) continue
    const d = deptOf(m)
    if (!byDept.has(d)) byDept.set(d, [])
    byDept.get(d).push(m)
  }
  return [...byDept].map(([name, members]) => ({
    name, members, all: members.every(has), some: members.some(has) && !members.every(has)
  }))
})
const totalShown = computed(() => tree.value.reduce((s, d) => s + d.members.length, 0))
// 搜索时把命中的组自动展开，否则关键字明明匹配了人，界面上只有一排行数字，看着像没搜到
watch(q, v => { if (v.trim()) for (const d of tree.value) openDepts.value[d.name] = true })
</script>

<style scoped>
.mt { display: flex; flex-direction: column; min-height: 0; border: 1px solid var(--nb-line); border-radius: 10px; background: var(--nb-bg-2); }
.mt-hd { display: flex; align-items: center; gap: 8px; padding: 8px 9px; border-bottom: 1px solid var(--nb-line); }
.mt-ipt { flex: 1; min-width: 0; }
.ipt { width: 100%; box-sizing: border-box; padding: 8px 11px; font: inherit; font-size: 13px; color: var(--nb-text); background: var(--nb-bg-1); border: 1px solid var(--nb-line); border-radius: 10px; outline: none; }
.ipt:focus { border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-line); }
.ipt::placeholder { color: var(--nb-dim); }
.mt-n { flex: 0 0 auto; font-size: 11.5px; color: var(--nb-dim); }
.mt-body { flex: 1; min-height: 0; overflow-y: auto; padding: 4px; }
.l1 { display: flex; align-items: center; gap: 6px; padding: 5px 6px; border-radius: 8px; }
.l1:hover { background: var(--nb-bg-3); }
.car { display: grid; place-items: center; flex: 0 0 16px; width: 16px; height: 16px; padding: 0; border: 0; border-radius: 4px; background: none; color: var(--nb-dim); cursor: pointer; transition: transform .14s; }
.car.open { transform: rotate(90deg); }
.gn { display: flex; align-items: baseline; gap: 5px; flex: 1; min-width: 0; padding: 0; text-align: left; font: inherit; font-size: 13px; font-weight: 600; color: var(--nb-text); background: none; border: 0; cursor: pointer; overflow: hidden; white-space: nowrap; }
.gn small { font-size: 11.5px; font-weight: 400; color: var(--nb-dim); }
.kids { list-style: none; margin: 0; padding: 0 0 0 22px; }
.row { display: grid; grid-template-columns: 16px 24px minmax(0, 1fr) auto; gap: 7px; align-items: center; width: 100%; padding: 5px 6px; text-align: left; font: inherit; color: var(--nb-text); background: none; border: 0; border-radius: 8px; cursor: pointer; }
.row:hover { background: var(--nb-bg-3); }
.cb { display: grid; place-items: center; width: 16px; height: 16px; padding: 0; font-size: 11px; line-height: 1; color: #fff; border: 1.5px solid var(--nb-line); border-radius: 4px; background: var(--nb-bg-1); cursor: pointer; }
/* 部门框的状态只写在 aria-checked 上，true / mixed 都得在这里上色；
   漏掉 true 的话，全选时那个白色 ✓ 会画在白底上，看着就像没勾 */
.cb.on, .cb[aria-checked='true'], .cb[aria-checked='mixed'] { background: var(--brand); border-color: var(--brand); }
.av { position: relative; display: grid; place-items: center; width: 24px; height: 24px; font-size: 11.5px; font-weight: 600; color: var(--brand-strong); background: var(--brand-soft); border-radius: 50%; }
.dot { position: absolute; right: -1px; bottom: -1px; width: 7px; height: 7px; border-radius: 50%; background: #1f9d55; border: 1.5px solid var(--nb-bg-1); }
.nm { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.rl { max-width: 76px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11.5px; color: var(--nb-dim); }
.empty { margin: 0; padding: 14px; font-size: 12.5px; color: var(--nb-dim); text-align: center; }
</style>
