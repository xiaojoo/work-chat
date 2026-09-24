<template>
  <Teleport to="body">
    <div v-if="visible" class="token-expired-overlay">
      <div class="token-expired-modal">
        <div class="modal-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
          </svg>
        </div>
        <h2>登录已过期</h2>
        <p>您的登录凭证已过期或无效，请重新登录以继续使用</p>
        <button class="modal-btn" @click="goToLogin">
          重新登录
        </button>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'

const router = useRouter()
const userStore = useUserStore()

const visible = ref(false)

// 监听 Token 状态
watch(() => userStore.accessToken, (newToken) => {
  if (!newToken && visible.value) {
    // Token 被清除，隐藏弹窗
    visible.value = false
  }
})

// 暴露显示方法
defineExpose({
  show() {
    visible.value = true
  },
  hide() {
    visible.value = false
  }
})

// 提供给父组件的事件
const emit = defineEmits(['login'])

function goToLogin() {
  visible.value = false
  userStore.logout()
  emit('login')
  router.push('/login')
}
</script>

<style scoped>
.token-expired-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.token-expired-modal {
  background: white;
  border-radius: 16px;
  padding: 40px;
  text-align: center;
  max-width: 400px;
  width: 90%;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  animation: slideUp 0.3s ease;
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.modal-icon {
  width: 80px;
  height: 80px;
  margin: 0 auto 24px;
  background: #fef3cd;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-icon svg {
  width: 48px;
  height: 48px;
  color: #ffc107;
}

h2 {
  margin: 0 0 12px;
  font-size: 24px;
  color: #303133;
}

p {
  margin: 0 0 24px;
  font-size: 14px;
  color: #909399;
  line-height: 1.6;
}

.modal-btn {
  background: #409eff;
  color: white;
  border: none;
  padding: 12px 32px;
  border-radius: 8px;
  font-size: 16px;
  cursor: pointer;
  transition: background 0.2s;
}

.modal-btn:hover {
  background: #337ecc;
}
</style>