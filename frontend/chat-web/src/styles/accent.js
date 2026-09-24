// 主题色：theme.css 的 :root 里那四个 --brand* 变量是唯一入口，
// 在 documentElement 上写同名内联变量就能整体换色；选「蓝」时把内联变量清掉，
// 回到 theme.css 的默认值，不留第二份真相。
export const ACCENTS = [
  { key: 'blue', name: '企业蓝', brand: '#2b6be8', strong: '#1f56c4', soft: '#eaf1ff' },
  { key: 'teal', name: '青绿', brand: '#0e8f7e', strong: '#0a6f62', soft: '#e4f6f2' },
  { key: 'violet', name: '紫罗兰', brand: '#6b45e0', strong: '#5330b8', soft: '#efeaff' },
  { key: 'amber', name: '琥珀', brand: '#d97a1a', strong: '#a85a0d', soft: '#fdf1e2' }
]

const STORE_KEY = 'accent'

export function currentAccent() {
  const k = localStorage.getItem(STORE_KEY)
  return ACCENTS.some(a => a.key === k) ? k : 'blue'
}

function rgba(hex, alpha) {
  const n = parseInt(hex.slice(1), 16)
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`
}

export function applyAccent(key) {
  const s = document.documentElement.style
  if (key === 'blue') {
    ['--brand', '--brand-strong', '--brand-soft', '--brand-line'].forEach(p => s.removeProperty(p))
  } else {
    const a = ACCENTS.find(x => x.key === key)
    if (!a) return applyAccent('blue')
    s.setProperty('--brand', a.brand)
    s.setProperty('--brand-strong', a.strong)
    s.setProperty('--brand-soft', a.soft)
    s.setProperty('--brand-line', rgba(a.brand, 0.28))
  }
  localStorage.setItem(STORE_KEY, key)
}
