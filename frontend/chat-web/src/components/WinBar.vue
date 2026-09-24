<template>
  <div v-if="isDesktop" class="winbar" @dblclick="onBlankDblClick">
    <span class="wb-title">星环通讯</span>
    <div class="wb-btns">
      <button class="wb-btn" title="最小化" @click="call('min')">&#xE921;</button>
      <button class="wb-btn" :title="maximized ? '还原' : '最大化'" @click="call('max')">{{ maximized ? '\uE923' : '\uE922' }}</button>
      <button class="wb-btn close" title="关闭" @click="call('close')">&#xE8BB;</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { isDesktop } from '../config'

const maximized = ref(false)
let off = null

function call(kind) {
  window.chatDesktop?.win?.[kind]?.()
}

// 双击空白处最大化/还原；双击按钮区不重复触发
function onBlankDblClick(e) {
  if (e.target.closest('.wb-btns')) return
  call('max')
}

onMounted(() => {
  // 标题栏占的高度用变量交给页面自己减，避免每页各写一套 calc
  document.documentElement.style.setProperty('--winbar-h', '36px')
  window.chatDesktop?.win?.isMaximized?.().then(v => { maximized.value = !!v })
  off = window.chatDesktop?.win?.onState?.(v => { maximized.value = !!v })
})
onUnmounted(() => {
  off?.()
  // 认证页会把本组件整个卸载；不还这个变量的话，页面按 calc(100vh - 36px) 算就白扣 36px
  document.documentElement.style.removeProperty('--winbar-h')
})
</script>

<style>
.winbar {
  flex: 0 0 36px; height: 36px; display: flex; align-items: stretch;
  justify-content: space-between; background: var(--nb-bg-shell);
  -webkit-app-region: drag; user-select: none;
}
.wb-title {
  display: flex; align-items: center; padding: 0 12px;
  font-size: 12px; letter-spacing: 1px; color: var(--nb-dim);
}
.wb-btns { display: flex; -webkit-app-region: no-drag; }
.wb-btn {
  width: 46px; border: 0; background: transparent; color: var(--nb-dim);
  font-family: "Segoe MDL2 Assets", "Segoe Fluent Icons", sans-serif;
  font-size: 10px; cursor: pointer;
}
.wb-btn:hover { background: var(--nb-bg-3); color: var(--nb-text); }
.wb-btn.close:hover { background: var(--danger); color: #fff; }
</style>
