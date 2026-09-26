<template>
  <!-- 只有右侧那条索引，不碰列表本身的顺序：分组小标题和时间序都留在调用方那边 -->
  <div ref="root" class="alr" :style="railVars" role="navigation" aria-label="按名称首字母跳转">
    <button v-for="l in RAIL" :key="l" type="button" class="alr-l" :disabled="!has[l]"
            :aria-label="'跳到 ' + l + (has[l] ? '' : '（无）')" @click="emit('jump', l)">{{ l }}</button>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { pinyinInitial } from '../utils/pinyin'

const props = defineProps({
  items: { type: Array, default: () => [] },
  nameOf: { type: Function, required: true },
})
const emit = defineEmits(['jump'])

const RAIL = [...'ABCDEFGHIJKLMNOPQRSTUVWXYZ', '#']

const has = computed(() => {
  const o = {}
  for (const it of props.items) o[pinyinInitial(props.nameOf(it) || '')] = true
  return o
})

/* 格子 12 + 间隙 3、以及装不下时"先收间隙再收格子"的下限，全和 AlphaList 同一套数 ——
   两条索引条要在同一个 x 上、同一档字号，差一个数就会被看出来是两套东西。
   INSET_B 走 CSS 变量交给 bottom，JS 里算可用高度也用同一个数，不留两处 */
const CELL = 12, GAP = 3, CELL_MIN = 8, GAP_MIN = 1, INSET_B = 10
const root = ref(null)
const railVars = ref({ '--cell': CELL + 'px', '--gap': GAP + 'px', '--inset-b': INSET_B + 'px' })
let ro = null
function fitRail(h) {
  const n = RAIL.length
  if (!h) return
  let cell = CELL, gap = GAP
  if (n * cell + (n - 1) * gap > h) {
    gap = Math.max(GAP_MIN, Math.floor(h / n) - cell)
    if (n * cell + (n - 1) * gap > h) cell = Math.max(CELL_MIN, Math.floor((h - (n - 1) * gap) / n))
  }
  railVars.value = { '--cell': cell + 'px', '--gap': gap + 'px', '--inset-b': INSET_B + 'px' }
}
const host = () => root.value && root.value.parentElement
const avail = () => { const h = host(); return h ? h.clientHeight - INSET_B : 0 }
onMounted(() => {
  fitRail(avail())
  ro = new ResizeObserver(() => fitRail(avail()))
  if (host()) ro.observe(host())
})
onUnmounted(() => ro?.disconnect())
</script>

<style scoped>
/* 绝对定位挂在列表外框上（不能放进滚动容器里 —— 放进去会跟着内容一起滚走）。
   bottom 让开 10px：那是宿主 .side-body 的 padding-bottom。AlphaList 的条是按 .al 居中的，
   .al 正好不含这 10px，不让开的话两条索引条换页签时会跳 5px（中线 474.5 vs 469.5，量过） */
.alr {
  position: absolute; right: 9px; top: 0; bottom: var(--inset-b, 10px);
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: var(--gap, 3px); z-index: 3;
}
.alr-l {
  width: 14px; height: var(--cell, 12px); min-height: 0; padding: 0; border: 0; background: none;
  border-radius: 3px; color: var(--brand); font-size: 12px; line-height: var(--cell, 12px); text-align: center;
  cursor: pointer; transition: color .12s ease, background-color .12s ease;
}
.alr-l:hover:not(:disabled) { background: var(--brand-soft); }
.alr-l:focus-visible { outline: 2px solid var(--brand); outline-offset: -1px; }
.alr-l:disabled { color: var(--nb-dim-2); cursor: default; }
</style>
