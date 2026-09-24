<template>
  <form class="rf" @submit.prevent="submit" novalidate>
    <div class="fld" :class="{ bad: errors.username }">
      <span class="fld-ic" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" /><circle cx="12" cy="7" r="4" /></svg>
      </span>
      <input id="rg-user" v-model.trim="form.username" name="username" type="text"
             placeholder="设置用户名（3-64 位）" autocomplete="username" @blur="validate('username')" />
    </div>
    <p class="err" :class="{ hide: !errors.username }">{{ errors.username || " " }}</p>

    <div class="fld" :class="{ bad: errors.password }">
      <span class="fld-ic" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="4.5" y="10.5" width="15" height="9.5" rx="2.5" /><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5" /></svg>
      </span>
      <input id="rg-pwd" v-model="form.password" name="password" :type="seen ? 'text' : 'password'"
             placeholder="设置密码（至少 6 位）" autocomplete="new-password" @blur="validate('password')" />
      <button type="button" class="eye" :aria-label="seen ? '隐藏密码' : '显示密码'" @click="seen = !seen">
        <svg v-if="!seen" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2.5 12S6 5.8 12 5.8 21.5 12 21.5 12 18 18.2 12 18.2 2.5 12 2.5 12z" /><circle cx="12" cy="12" r="2.8" /></svg>
        <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 4l16 16" /><path d="M9.6 5.9A9.6 9.6 0 0 1 12 5.8c6 0 9.5 6.2 9.5 6.2a17 17 0 0 1-2.9 3.6M6.6 7.6A16.6 16.6 0 0 0 2.5 12S6 18.2 12 18.2c1.2 0 2.3-.2 3.3-.6" /></svg>
      </button>
    </div>
    <p class="err" :class="{ hide: !errors.password }">{{ errors.password || " " }}</p>

    <div class="fld" :class="{ bad: errors.confirmPassword }">
      <span class="fld-ic" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="4.5" y="10.5" width="15" height="9.5" rx="2.5" /><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5" /><path d="M10.4 15.2l1.2 1.2 2.4-2.4" /></svg>
      </span>
      <input id="rg-cfm" v-model="form.confirmPassword" name="confirmPassword" :type="seen ? 'text' : 'password'"
             placeholder="再次输入密码" autocomplete="new-password" @blur="validate('confirmPassword')" />
    </div>
    <p class="err" :class="{ hide: !errors.confirmPassword }">{{ errors.confirmPassword || " " }}</p>

    <p class="err slot" :class="{ hide: !!errText }">{{ errText || " " }}</p>
  </form>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { useUserStore } from '../stores/user'
import { toast } from '../utils/ui'

const emit = defineEmits(['done'])
const userStore = useUserStore()

const seen = ref(false)
const busy = ref(false)
const errText = ref('')
const form = reactive({ username: '', password: '', confirmPassword: '' })
const errors = reactive({ username: '', password: '', confirmPassword: '' })

// 切走再切回来不该留着上次的报错
watch(seen, () => { errText.value = '' })

function validate(field) {
  if (field === 'username') {
    const v = form.username.trim()
    errors.username = !v ? '请输入用户名' : (v.length < 3 || v.length > 64 ? '用户名长度 3-64' : '')
  } else if (field === 'password') {
    const v = form.password
    errors.password = !v ? '请输入密码' : (v.length < 6 || v.length > 128 ? '密码长度 6-128' : '')
  } else if (field === 'confirmPassword') {
    errors.confirmPassword = !form.confirmPassword ? '请确认密码' : (form.confirmPassword !== form.password ? '两次密码不一致' : '')
  }
  return !errors[field]
}

async function submit() {
  if (!(validate('username') & validate('password') & validate('confirmPassword'))) return
  if (busy.value) return
  busy.value = true
  errText.value = ''
  try {
    await userStore.doRegister({ username: form.username, password: form.password, nickname: form.username })
    toast('注册成功，已自动登录', 'success')
    emit('done', form.username)
  } catch (e) {
    // 没有 e.response 说明请求没到服务，和「用户名已存在」是两件事，不能混成一句注册失败
    errText.value = e.response ? (e.response.data?.error || `注册被拒 (HTTP ${e.response.status})`) : '连不上用户服务'
    toast(errText.value, 'error')
  } finally {
    busy.value = false
  }
}

defineExpose({ busy, submit })
</script>

<style scoped>
.rf { display: block; }
.fld { display: flex; align-items: center; gap: 9px; padding: 0 12px; height: 44px; margin-top: 12px;
  background: var(--nb-bg-1); border: 1px solid var(--nb-line); border-radius: 11px;
  transition: border-color .14s, box-shadow .14s; }
/* 16px 要和 Login.vue 的 .fm .fld:first-child 对齐：两个表单叠在同一格，落差会让切换时首框上下跳 */
.fld:first-child { margin-top: 16px; }
.fld:focus-within { border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-line); }
.fld.bad { border-color: var(--danger); }
.fld-ic { display: grid; place-items: center; color: var(--nb-dim); }
.fld input { flex: 1; min-width: 0; border: 0; outline: none; background: transparent;
  font: inherit; font-size: 13.5px; color: var(--nb-text); }
.fld input::placeholder { color: var(--nb-dim); }
.eye { display: grid; place-items: center; width: 26px; height: 26px; padding: 0; border: 0; border-radius: 7px;
  background: none; color: var(--nb-dim); cursor: pointer; -webkit-app-region: no-drag; }
.eye:hover { background: var(--nb-bg-3); color: var(--nb-text); }
.err { margin: 5px 0 0; font-size: 11.5px; line-height: 16px; color: var(--danger); }
/* 位子一直占着、内容按需可见：不然一点注册就长高，整块上下跳 */
.hide { visibility: hidden; }
.err.slot { margin-top: 14px; }
.slot { margin-top: 0; }
</style>
