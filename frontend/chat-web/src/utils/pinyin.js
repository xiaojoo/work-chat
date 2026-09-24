/* 中文姓名按拼音首字母归组，给 AlphaList 用。
   单独成模块是为了能在目标运行时里量它：Electron 那侧的 Chromium 和 Node 的 ICU 不是一回事，
   只有在真窗口里跑同一份源码，测出来的归组才算数。 */

/* 每个字母的起点字：拿名字和它们逐一比大小，落在哪一段就是哪个字母。
   这台机器的 Chromium 里 Intl 的 zh 拼音排序实际是 ICU 默认序（-u-co-pinyin 会被静默忽略），
   但汉字确实按拼音排，所以这套边界字可用；I、U、V 普通话没有对应音节，不列。 */
const BOUND = [['A', '阿'], ['B', '八'], ['C', '擦'], ['D', '哒'], ['E', '蛾'], ['F', '发'], ['G', '噶'],
  ['H', '哈'], ['J', '击'], ['K', '喀'], ['L', '垃'], ['M', '妈'], ['N', '拿'], ['O', '哦'], ['P', '啪'],
  ['Q', '期'], ['R', '然'], ['S', '撒'], ['T', '塌'], ['W', '挖'], ['X', '昔'], ['Y', '压'], ['Z', '匝']]

/* 多音字姓氏：排序只认常用读音，这些字当姓时读另一个音，不补就归错组 */
const SURNAME = { 曾: 'Z', 解: 'X', 单: 'S', 区: 'O', 查: 'Z', 仇: 'Q', 会: 'G', 朴: 'P', 覃: 'T', 召: 'S', 乐: 'Y', 尉: 'Y', 折: 'S', 秘: 'B' }

const coll = new Intl.Collator('zh-CN')

export function pinyinInitial(name) {
  const s = String(name || '').trim()
  if (!s) return '#'
  const cp = s.codePointAt(0)
  if (cp >= 97 && cp <= 122) return s[0].toUpperCase()
  if (cp >= 65 && cp <= 90) return s[0]
  if (cp < 128) return '#'
  if (SURNAME[s[0]]) return SURNAME[s[0]]
  let letter = '#'
  for (const [l, b] of BOUND) { if (coll.compare(s, b) >= 0) letter = l }
  return letter
}

export function byPinyin(a, b) { return coll.compare(a, b) }
