<template>
  <div class="ds" ref="wrap">
    <button ref="btn" type="button" class="ds-btn" :class="{ open }" :aria-expanded="open"
            aria-haspopup="listbox" :aria-label="label" @click="open ? close() : openIt()" @keydown="onBtnKey">
      <span class="ds-tx">{{ modelValue === '' ? allLabel : modelValue }}</span>
      <span class="ds-ar" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9.5l6 5.5 6-5.5" /></svg>
      </span>
    </button>
    <ul v-show="open" ref="menu" class="ds-menu" role="listbox" :aria-label="label" tabindex="-1" @keydown="onMenuKey">
      <li v-for="o in opts" :key="o.value" role="option" :aria-selected="modelValue === o.value"
          @click="choose(o.value)">{{ o.label }}</li>
    </ul>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  // 部门名数组；全部部门这一项由组件自己补，调用方不用记着加
  options: { type: Array, default: () => [] },
  label: { type: String, default: '按部门筛选' },
  allLabel: { type: String, default: '全部部门' }
})
const emit = defineEmits(['update:modelValue'])

const open = ref(false)
const wrap = ref(null)
const btn = ref(null)
const menu = ref(null)

// 选项取全量部门，不取"当前筛完还有人的部门"：后者会在选中后因为计数归零而把自己从列表里删掉
const opts = computed(() => [{ value: '', label: props.allLabel },
  ...props.options.map(d => ({ value: d, label: d }))])

const scrollCur = () => menu.value?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' })
function openIt() { open.value = true; nextTick(() => { menu.value?.focus(); scrollCur() }) }
function close() { open.value = false; nextTick(() => btn.value?.focus()) }
function choose(v) { emit('update:modelValue', v); close() }
function onBtnKey(e) {
  if (e.key === 'Escape' && open.value) { e.preventDefault(); close(); return }
  if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(e.key)) { e.preventDefault(); openIt() }
}
function onMenuKey(e) {
  const o = opts.value
  const at = o.findIndex(x => x.value === props.modelValue)
  const to = n => { e.preventDefault(); emit('update:modelValue', o[n].value); scrollCur() }
  if (e.key === 'ArrowDown') to(Math.min(at + 1, o.length - 1))
  else if (e.key === 'ArrowUp') to(Math.max(at - 1, 0))
  else if (e.key === 'Home') to(0)
  else if (e.key === 'End') to(o.length - 1)
  else if (e.key === 'Enter' || e.key === ' ' || e.key === 'Escape') { e.preventDefault(); close() }
  else if (e.key === 'Tab') open.value = false
}
// 点外面先关下拉。只有落在遮罩上的那一下才顺手吞掉——不吞的话它会命中 .ov 的 @click.self，
// 把整个弹框一起关掉；但弹框里的控件（页签、成员行）必须照常收到这次点击，不然要点两次
function onDocClick(e) {
  if (!open.value || wrap.value?.contains(e.target)) return
  open.value = false
  if (e.target.classList?.contains('ov')) {
    e.preventDefault(); e.stopPropagation()
    btn.value?.focus()
  }
}
onMounted(() => document.addEventListener('click', onDocClick, true))
onUnmounted(() => document.removeEventListener('click', onDocClick, true))
</script>

<style scoped>
.ds { position: relative; }
.ds-btn { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; padding: 7px 10px;
  text-align: left; font: inherit; font-size: 13px; color: var(--nb-text); background: var(--nb-bg-2);
  border: 1px solid var(--nb-line); border-radius: 10px; outline: none; cursor: pointer; }
.ds-btn:hover { border-color: var(--brand); }
.ds-btn.open, .ds-btn:focus-visible { border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-line); }
.ds-tx { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ds-ar { display: grid; place-items: center; flex: 0 0 auto; color: var(--nb-dim); transition: transform .14s; }
.ds-btn.open .ds-ar { transform: rotate(180deg); color: var(--brand); }
/* 面板比触发器宽才装得下长部门名；top 里那 4px 间隙要算进 max-height，不然最后一行会被弹框底边切掉 */
.ds-menu { position: absolute; z-index: 5; top: calc(100% + 4px); left: 0; width: max-content; min-width: 100%;
  max-width: 240px; max-height: 268px; overflow-y: auto; margin: 0; padding: 4px; list-style: none; outline: none;
  background: var(--nb-bg-1); border: 1px solid var(--nb-line); border-radius: 10px;
  box-shadow: 0 10px 26px rgba(16, 24, 40, .16); }
.ds-menu li { padding: 7px 9px; font-size: 13px; color: var(--nb-text); border-radius: 7px; cursor: pointer;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ds-menu li:hover { background: var(--nb-bg-3); }
.ds-menu li[aria-selected='true'] { background: var(--brand-soft); color: var(--brand-strong); font-weight: 600; }
</style>
