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
        <div class="nebula-confirm-hd">
          <div class="nebula-confirm-ico ico-${type}">${type === 'warning' ? '⚠' : '◈'}</div>
          <div class="nebula-confirm-title"></div>
        </div>
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
}
.nebula-confirm-panel {
  position: relative;
  width: 380px;
  max-width: 92vw;
  padding: 22px 24px;
  background: var(--nb-panel-solid, #fff);
  text-align: left;
}
/* 那道边和入场由 nebula.css 的 .modal/.dlg/.nebula-confirm-panel/.token-expired-modal 共用规则给，这里不再各写一份 */
/* 正文上下两道间隙按"看得见的空白"对齐，不是按 margin 对齐：
   标题行盒在墨迹下面还留 2.5px、正文行盒在墨迹上面留 5.5px、下面留 5.4px，
   所以 margin 写 16/16 时实测上 24.0 下 21.5。上边收到 13.5 才是 21.5/21.5。 */
.nebula-confirm-hd { display: flex; align-items: center; gap: 8px; margin-bottom: 13.5px; }
.nebula-confirm-ico { font-size: 18px; line-height: 1; flex: none; }
/* 这两个符号在 YaHei 里的墨迹和汉字墨迹不在一条中线上，flex 的 align-items 只居中盒子居中不了墨迹。
   补偿值按 dpr=2 全高扫描量到（判据：图标墨迹中线 = 标题墨迹中线）：
   ⚠ 自然位高 1.5px 要压下去；◈ 自然位已经正中，不给补偿。换字号或换字体栈要重量 */
.nebula-confirm-ico.ico-warning { color: var(--warn, #b76e10); transform: translateY(1.5px); }
.nebula-confirm-ico.ico-info { color: var(--brand, #2b6be8); }
.nebula-confirm-title {
  font-family: 'Orbitron','Rajdhani','Microsoft YaHei',sans-serif;
  font-size: 16px;
  font-weight: 700;
  color: var(--nb-text, #1b2434);
  letter-spacing: 2px;
  min-width: 0;
}
.nebula-confirm-msg { font-size: 13.5px; color: var(--nb-dim, #69788f); line-height: 1.7; margin-bottom: 16px; }
.nebula-confirm-btns { display: flex; justify-content: flex-end; gap: 12px; }
/* 全部带 .nebula-confirm-btns 前缀：.nebula-btn 这个名字 theme.css 也有一份定义，
   不带前缀改会连带改到别的界面 */
.nebula-confirm-btns .nebula-btn {
  padding: 8px 16px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13.5px;
  letter-spacing: normal;
  font-family: 'Rajdhani','Microsoft YaHei',sans-serif;
  transition: background .2s, border-color .2s;
}
.nebula-confirm-btns .nebula-btn.primary {
  border: 1px solid var(--brand, #2b6be8);
  background: var(--brand, #2b6be8);
  color: #fff;
  font-weight: 600;
  box-shadow: none;
}
.nebula-confirm-btns .nebula-btn.primary:hover { background: var(--brand-strong, #1f56c4); border-color: var(--brand-strong, #1f56c4); filter: none; box-shadow: none; }
.nebula-confirm-btns .nebula-btn.ghost {
  background: var(--nb-bg-1, #fff);
  border: 1px solid var(--nb-line, #e2e7f0);
  color: var(--nb-text, #1b2434);
}
.nebula-confirm-btns .nebula-btn.ghost:hover { background: var(--nb-bg-3, #f0f3f8); color: var(--nb-text, #1b2434); box-shadow: none; }
`
  document.head.appendChild(style)
})()
