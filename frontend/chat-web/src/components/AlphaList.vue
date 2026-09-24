<template>
  <div ref="root" class="al">
    <div ref="body" class="al-body">
      <template v-for="e in flat" :key="e.k">
        <div v-if="e.t === 'h'" class="al-head" :data-l="e.l">{{ e.l }}</div>
        <slot v-else :item="e.it" />
      </template>
    </div>
    <!-- 26 个字母 + # 常驻，没有对应分组的那几个置灰但不消失（消失了会让人以为列表少了东西） -->
    <div class="al-rail" :style="railVars" role="navigation" aria-label="按姓名首字母跳转">
      <button v-for="l in RAIL" :key="l" type="button" class="al-l" :disabled="!has[l]"
              :aria-label="'跳到 ' + l + (has[l] ? '' : '（无）')" @click="jump(l)">{{ l }}</button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { pinyinInitial, byPinyin } from '../utils/pinyin'

const props = defineProps({
  items: { type: Array, default: () => [] },
  nameOf: { type: Function, required: true },
  keyOf: { type: Function, default: i => i.id },
})

const RAIL = [...'ABCDEFGHIJKLMNOPQRSTUVWXYZ', '#']

const groups = computed(() => {
  const by = new Map()
  for (const it of props.items) {
    const l = pinyinInitial(props.nameOf(it))
    if (!by.has(l)) by.set(l, [])
    by.get(l).push(it)
  }
  const out = []
  for (const l of RAIL) {
    if (!by.has(l)) continue
    out.push({ letter: l, list: by.get(l).slice().sort((a, b) => byPinyin(props.nameOf(a), props.nameOf(b))) })
  }
  return out
})

const has = computed(() => {
  const o = {}
  for (const g of groups.value) o[g.letter] = true
  return o
})

const flat = computed(() => {
  const out = []
  for (const g of groups.value) {
    out.push({ t: 'h', l: g.letter, k: 'h' + g.letter })
    for (const it of g.list) out.push({ t: 'i', it, k: String(props.keyOf(it)) })
  }
  return out
})

const body = ref(null)
const root = ref(null)
/* 索引条要装得下当前列表的高度：窗口缩到最小、或输入框被拖高时，列表只剩三百来 px，
   而 27 个字母按 13px 格子 + 3px 间隙就是 429px，会顶出列表。格子有 min-height:auto 兜底
   压不下去（量过：外框被 max-height 夹住、字母照样溢出），所以先收间隙、再收格子。 */
const CELL = 12, GAP = 3, CELL_MIN = 8, GAP_MIN = 1
const railVars = ref({ '--cell': CELL + 'px', '--gap': GAP + 'px' })
let railRO = null
function fitRail(h) {
  const n = RAIL.length
  if (!h) return
  let cell = CELL, gap = GAP
  if (n * cell + (n - 1) * gap > h) {
    gap = Math.max(GAP_MIN, Math.floor(h / n) - cell)
    if (n * cell + (n - 1) * gap > h) cell = Math.max(CELL_MIN, Math.floor((h - (n - 1) * gap) / n))
  }
  railVars.value = { '--cell': cell + 'px', '--gap': gap + 'px' }
}
onMounted(() => {
  fitRail(root.value ? root.value.clientHeight : 0)
  railRO = new ResizeObserver(es => { for (const e of es) fitRail(e.contentRect.height) })
  if (root.value) railRO.observe(root.value)
})
onUnmounted(() => railRO?.disconnect())

function jump(l) {
  const el = body.value && body.value.querySelector(`[data-l="${l}"]`)
  if (!el) return
  body.value.scrollTo({ top: el.offsetTop, behavior: 'smooth' })
}
</script>

<style scoped>
.al { position: relative; height: 100%; }
/* 滚动只这一个容器；右侧留出索引条的位置，别压住行内容 */
.al-body { position: relative; height: 100%; overflow-y: auto; padding-right: 17px; }
/* 这条列表不画滚动条，但照常能滚：overflow 不动，只是不占那 6px、也不显示滑块，位置感交给右侧字母索引。
   别顺手写 scrollbar-width —— 一旦设成非 auto，整组 ::-webkit-scrollbar 规则会被静默废掉 */
.al-body::-webkit-scrollbar { width: 0; height: 0; }
.al-head {
  display: flex; align-items: center; padding: 9px 8px 4px; min-height: 20px;
  font-size: 14px; color: var(--nb-dim-2); letter-spacing: .4px;
}
/* 间隙和格子高度由脚本按容器高度算出来（--gap / --cell），默认就是设计值 3px + 13px */
.al-rail {
  position: absolute; right: 1px; top: 50%; transform: translateY(-50%);
  display: flex; flex-direction: column; align-items: center; gap: var(--gap, 3px); z-index: 2; pointer-events: none;
}
/* 有对应好友的字母直接上品牌蓝（对白底约 5.2:1），没有的那排留在 --nb-dim-2（约 2.9:1）：
   两档差的不只是深浅，还有色相，扫一眼就能分出哪些能点 */
.al-l {
  pointer-events: auto; width: 14px; height: var(--cell, 12px); min-height: 0; padding: 0; border: 0; background: none;
  border-radius: 3px; color: var(--brand); font-size: 12px; line-height: var(--cell, 12px); text-align: center;
  cursor: pointer; transition: color .12s ease, background-color .12s ease;
}
.al-l:hover:not(:disabled) { background: var(--brand-soft); }
.al-l:focus-visible { outline: 2px solid var(--brand); outline-offset: -1px; }
.al-l:disabled { color: var(--nb-dim-2); cursor: default; }
</style>
