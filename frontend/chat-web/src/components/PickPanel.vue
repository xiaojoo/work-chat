<template>
  <section class="pp">
    <div class="pp-hd">
      <span class="pp-t">已选择 <b>{{ picked.length }}</b> 人</span>
      <button v-if="picked.length" class="lnk" type="button" @click="$emit('clear')">清空</button>
    </div>
    <div class="pp-body">
      <div v-for="p in picked" :key="p.id" class="prow">
        <span class="av">{{ initial(p) }}</span>
        <span class="nm">{{ p.nickname || p.username }}</span>
        <button class="rm" type="button" :aria-label="'移除 ' + (p.nickname || p.username)" @click="$emit('remove', p)">✕</button>
      </div>
      <p v-if="!picked.length" class="empty">{{ emptyText }}</p>
    </div>
  </section>
</template>

<script setup>
defineProps({
  picked: { type: Array, default: () => [] },
  emptyText: { type: String, default: '左边勾的人会出现在这里' }
})
defineEmits(['remove', 'clear'])

const initial = (m) => String(m.nickname || m.username || '?').charAt(0).toUpperCase()
</script>

<style scoped>
/* 和 MemberTree 那栏同一套外壳：边框、圆角、表头高度、内边距都对齐 */
.pp { display: flex; flex-direction: column; min-height: 0; border: 1px solid var(--nb-line); border-radius: 10px; background: var(--nb-bg-2); }
.pp-hd { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 9px; border-bottom: 1px solid var(--nb-line); }
.pp-t { font-size: 12.5px; color: var(--nb-text); }
.pp-t b { color: var(--brand); }
.lnk { border: 0; background: none; padding: 0; font: inherit; font-size: 12.5px; color: var(--brand); cursor: pointer; }
/* 已选也改成横向卡片流：一行放得下几张放几张，容器高度由外面那栏定死，超出只在这里面滚。
   原来是一行一个人的长列表，人数一多整块面板跟着长高，把下面的候选面板压扁 */
.pp-body { flex: 1; min-height: 0; overflow-y: auto; padding: 6px; display: grid;
  grid-template-columns: repeat(auto-fill, minmax(126px, 1fr)); grid-auto-rows: max-content; gap: 6px; align-content: start; }
.prow { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; gap: 6px; align-items: center;
  min-width: 0; padding: 6px 7px; border-radius: 8px; background: var(--nb-bg-3); }
.prow:hover { background: var(--brand-soft); }
.av { display: grid; place-items: center; width: 24px; height: 24px; font-size: 11.5px; font-weight: 600; color: var(--brand-strong); background: var(--brand-soft); border-radius: 50%; }
.nm { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; color: var(--nb-text); }
.rm { border: 0; background: none; padding: 0; font-size: 11px; color: var(--nb-dim); cursor: pointer; }
.rm:hover { color: var(--danger, #d92d20); }
.empty { grid-column: 1 / -1; margin: 0; padding: 14px; font-size: 12.5px; color: var(--nb-dim); text-align: center; }
</style>
