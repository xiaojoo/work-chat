/* ============================================================
   原生 UI 工具：Toast 提示 + Confirm 确认框
   替代 Element Plus 的 ElMessage / ElMessageBox
   ============================================================ */

let toastTimer = null
let toastEl = null

/**
 * 轻提示（自动消失）
 * @param {string} message 提示内容
 * @param {'success'|'error'|'warning'|'info'} [type='info']
 */
export function toast(message, type = 'info') {
  if (toastEl) {
    toastEl.remove()
    clearTimeout(toastTimer)
    toastEl = null
  }

  const icons = {
    success: '✓',
    error: '✕',
    warning: '⚠',
    info: '◈'
  }

  toastEl = document.createElement('div')
  toastEl.className = `nebula-toast toast-${type}`
  toastEl.innerHTML = `<span class="nebula-toast-ico">${icons[type] || icons.info}</span><span class="nebula-toast-msg"></span>`
  toastEl.querySelector('.nebula-toast-msg').textContent = message
  document.body.appendChild(toastEl)

  requestAnimationFrame(() => toastEl.classList.add('show'))

  toastTimer = setTimeout(() => {
    toastEl.classList.remove('show')
    setTimeout(() => {
      toastEl?.remove()
      toastEl = null
    }, 260)
  }, 2400)
}

let confirmEl = null

/**
 * 确认框（返回 Promise<boolean>）
 * @param {object} options
 * @param {string} options.message 提示内容
 * @param {string} [options.title='确认'] 标题
 * @param {string} [options.confirmText='确定'] 确认按钮文字
 * @param {string} [options.cancelText='取消'] 取消按钮文字
 * @param {'warning'|'info'} [options.type='info'] 类型
 */
export function confirmBox(options = {}) {
  return new Promise(resolve => {
    const {
      message = '',
      title = '确认',
      confirmText = '确定',
      cancelText = '取消',
      type = 'info'
    } = options

    const close = result => {
      confirmEl?.remove()
      confirmEl = null
      resolve(result)
    }

    const modal = document.createElement('div')
    modal.className = 'nebula-confirm'
    modal.innerHTML = `
      <div class="nebula-confirm-mask"></div>
      <div class="nebula-confirm-panel">
        <div class="nebula-confirm-ico ico-${type}">${type === 'warning' ? '⚠' : '◈'}</div>
        <div class="nebula-confirm-title"></div>
        <div class="nebula-confirm-msg"></div>
        <div class="nebula-confirm-btns">
          <button class="nebula-btn ghost" data-act="cancel"></button>
          <button class="nebula-btn primary" data-act="ok"></button>
        </div>
      </div>`
    modal.querySelector('.nebula-confirm-title').textContent = title
    modal.querySelector('.nebula-confirm-msg').textContent = message
    modal.querySelector('[data-act="cancel"]').textContent = cancelText
    modal.querySelector('[data-act="ok"]').textContent = confirmText
    document.body.appendChild(modal)

    requestAnimationFrame(() => modal.classList.add('show'))

    modal.querySelector('.nebula-confirm-mask').addEventListener('click', () => close(false))
    modal.querySelector('[data-act="cancel"]').addEventListener('click', () => close(false))
    modal.querySelector('[data-act="ok"]').addEventListener('click', () => close(true))
    modal.addEventListener('keydown', e => {
      if (e.key === 'Escape') close(false)
      if (e.key === 'Enter') close(true)
    })
    modal.querySelector('[data-act="ok"]').focus()

    confirmEl = modal
  })
}

/* 独立于组件的静态样式：注入一次 */
;(function injectToastStyles() {
  if (document.getElementById('nebula-ui-style')) return
  const style = document.createElement('style')
  style.id = 'nebula-ui-style'
  style.textContent = `
.nebula-toast {
  position: fixed;
  top: 18px;
  left: 50%;
  transform: translate(-50%, -24px);
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 11px 22px;
  border-radius: 12px;
  background: linear-gradient(150deg, rgba(7,11,32,.97), rgba(18,10,46,.97));
  border: 1px solid rgba(0,240,255,.3);
  box-shadow: 0 12px 40px rgba(0,0,0,.6), 0 0 22px rgba(0,240,255,.15);
  color: #dce6ff;
  font-size: 14px;
  font-family: 'Rajdhani','Microsoft YaHei',sans-serif;
  letter-spacing: .5px;
  z-index: 99999;
  opacity: 0;
  transition: opacity .25s, transform .25s;
  pointer-events: none;
}
.nebula-toast.show { opacity: 1; transform: translate(-50%, 0); }
.nebula-toast-ico {
  font-size: 14px;
  flex-shrink: 0;
}
.nebula-toast.toast-success .nebula-toast-ico { color: #39ff88; text-shadow: 0 0 8px rgba(57,255,136,.8); }
.nebula-toast.toast-error .nebula-toast-ico { color: #ff5f9e; text-shadow: 0 0 8px rgba(255,95,158,.8); }
.nebula-toast.toast-warning .nebula-toast-ico { color: #ffb02e; text-shadow: 0 0 8px rgba(255,176,46,.8); }
.nebula-toast.toast-info .nebula-toast-ico { color: #00f0ff; text-shadow: 0 0 8px rgba(0,240,255,.8); }

.nebula-confirm { position: fixed; inset: 0; z-index: 99998; display: flex; align-items: center; justify-content: center; }
.nebula-confirm-mask {
  position: absolute; inset: 0;
  background: rgba(3,6,18,.72);
  backdrop-filter: blur(2px);
}
.nebula-confirm-panel {
  position: relative;
  width: 380px;
  max-width: 92vw;
  padding: 26px 26px 22px;
  border-radius: 12px;
  background:
    radial-gradient(300px 150px at 85% 6%, rgba(168,85,247,.16), transparent 60%),
    linear-gradient(150deg, #070b20 0%, #0b1030 55%, #120a2e 100%);
  border: 1px solid rgba(0,240,255,.28);
  box-shadow: 0 24px 80px rgba(0,0,0,.65), 0 0 50px rgba(0,240,255,.09);
  text-align: center;
  opacity: 0;
  transform: translateY(14px) scale(.97);
  transition: opacity .22s, transform .22s;
}
.nebula-confirm.show .nebula-confirm-panel { opacity: 1; transform: translateY(0) scale(1); }
.nebula-confirm-ico { font-size: 34px; margin-bottom: 10px; }
.nebula-confirm-ico.ico-warning { color: #ffb02e; filter: drop-shadow(0 0 10px rgba(255,176,46,.7)); }
.nebula-confirm-ico.ico-info { color: #00f0ff; filter: drop-shadow(0 0 10px rgba(0,240,255,.7)); }
.nebula-confirm-title {
  font-family: 'Orbitron','Rajdhani','Microsoft YaHei',sans-serif;
  font-size: 16px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 2px;
  margin-bottom: 8px;
}
.nebula-confirm-msg { font-size: 13.5px; color: #b6c2e6; line-height: 1.7; margin-bottom: 22px; }
.nebula-confirm-btns { display: flex; justify-content: center; gap: 12px; }
.nebula-btn {
  padding: 9px 24px;
  border-radius: 12px;
  cursor: pointer;
  font-size: 13px;
  letter-spacing: 1.5px;
  font-family: 'Rajdhani','Microsoft YaHei',sans-serif;
  transition: all .2s;
}
.nebula-btn.primary {
  border: none;
  background: linear-gradient(90deg, #ff2ec4, #a855f7);
  color: #fff;
  font-weight: 600;
  box-shadow: 0 0 18px rgba(255,46,196,.4);
}
.nebula-btn.primary:hover { filter: brightness(1.15); box-shadow: 0 0 26px rgba(255,46,196,.65); }
.nebula-btn.ghost {
  background: transparent;
  border: 1px solid rgba(255,46,196,.4);
  color: #ff8ad8;
}
.nebula-btn.ghost:hover { background: rgba(255,46,196,.1); color: #fff; box-shadow: 0 0 12px rgba(255,46,196,.4); }
`
  document.head.appendChild(style)
})()
