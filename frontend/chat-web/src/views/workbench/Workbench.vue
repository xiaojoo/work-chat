<template>
  <div class="wb">
    <!-- ① 导航栏：稿子里的分组结构，消息回聊天页，其余进各静态壳分区 -->
    <nav class="rail">
      <div class="rail-logo" @click="go('chat')">
        <span class="lg-ic" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M4 6.5h16v10H12l-4 3v-3H4z" />
          </svg>
        </span>
        <span class="lg-tx">
          <b>Content Chat</b>
          <small>让内容协作更简单</small>
        </span>
      </div>

      <div class="rail-scroll">
        <button v-for="i in NAV_MAIN" :key="i.key" type="button" class="ri" :class="{ on: section === i.key }" @click="go(i.key)">
          <span class="ri-ic" aria-hidden="true"><component :is="i.icon" /></span>
          <span class="ri-tx">{{ i.name }}</span>
          <span v-if="i.badge" class="ri-n">{{ i.badge }}</span>
        </button>

        <p class="rail-cap">内容</p>
        <button v-for="i in NAV_DOC" :key="i.key" type="button" class="ri" :class="{ on: section === i.key }" @click="go(i.key)">
          <span class="ri-ic" aria-hidden="true"><component :is="i.icon" /></span>
          <span class="ri-tx">{{ i.name }}</span>
        </button>

        <p class="rail-cap">AI</p>
        <button v-for="i in NAV_AI" :key="i.key" type="button" class="ri" :class="{ on: section === i.key }" @click="go(i.key)">
          <span class="ri-ic" aria-hidden="true"><component :is="i.icon" /></span>
          <span class="ri-tx">{{ i.name }}</span>
        </button>
      </div>

      <div class="rail-foot">
        <button type="button" class="ri quit" @click="handleLogout"><span class="ri-ic" aria-hidden="true"><IcGear /></span><span class="ri-tx">退出登录</span></button>
      </div>
    </nav>

    <!-- ② 主区 -->
    <main class="main">
      <header class="mh">
        <span class="mh-ic" :aria-hidden="true"><component :is="PAGE_IC[section]" /></span>
        <div class="mh-t">
          <h1>{{ TITLE[section].name }}</h1>
          <p>{{ TITLE[section].sub }}</p>
        </div>
        <span class="demo" :title="DEMO_NOTE">演示</span>
        <div class="mh-acts">
          <button v-for="a in TITLE[section].acts" :key="a" type="button" class="btn pri" disabled :title="DEMO_NOTE">
            <span class="pri-ic" aria-hidden="true"><IcPlus /></span><span>{{ a }}</span>
          </button>
        </div>
      </header>

      <div class="body">
        <!-- 首页看板 -->
        <section v-if="section === 'home'" class="sec">
          <div class="hello">
            <h2>Good evening, {{ displayName }}</h2>
            <p>今天有 {{ dashStats[0].value }} 个内容任务需要处理</p>
          </div>
          <div class="stats">
            <div v-for="s in dashStats" :key="s.key" class="stat">
              <span class="st-lb">{{ s.label }}</span>
              <b class="st-v">{{ s.value }}</b>
              <span class="st-d" :class="s.key">{{ s.delta }}</span>
            </div>
          </div>
          <div class="cols">
            <div class="card">
              <div class="card-hd"><b>我的任务</b><button type="button" class="lnk" @click="go('tasks')">查看全部</button></div>
              <ul class="lines">
                <li v-for="t in myTasks.slice(0, 4)" :key="t.id">
                  <span class="tick" :class="tickClass(t.status)" aria-hidden="true"></span>
                  <span class="l-nm">{{ t.name }}</span>
                  <span class="l-st" :class="tickClass(t.status)">{{ t.status }}</span>
                  <span class="l-who">{{ t.owner }}</span>
                  <span class="l-due">{{ t.due }}</span>
                </li>
              </ul>
            </div>
            <div class="card">
              <div class="card-hd"><b>最近内容</b><button type="button" class="lnk" @click="go('contents')">查看全部</button></div>
              <ul class="lines">
                <li v-for="c in contents" :key="c.name">
                  <span class="kind" :class="c.kind">{{ c.kind.toUpperCase() }}</span>
                  <span class="l-nm">{{ c.name }}</span>
                  <span class="l-st">{{ c.status }}</span>
                  <span class="l-due">{{ c.at }}</span>
                </li>
              </ul>
            </div>
          </div>
        </section>

        <!-- 任务表 -->
        <section v-else-if="section === 'tasks'" class="sec">
          <div class="tabs">
            <button v-for="f in TASK_FILTERS" :key="f" type="button" class="tab" :class="{ on: taskFilter === f }"
                    @click="taskFilter = f">{{ f }}</button>
          </div>
          <div class="card tbl">
            <table>
              <thead>
                <tr><th class="c-st">状态</th><th>任务</th><th class="c-pr">进度</th><th class="c-who">负责人</th><th class="c-due">截止</th></tr>
              </thead>
              <tbody>
                <tr v-for="t in shownTasks" :key="t.id" @click="openTask(t)">
                  <td><span class="pill" :class="tickClass(t.status)">{{ t.status }}</span></td>
                  <td class="t-name"><span class="t-nm">{{ t.name }}<small>{{ t.project }}</small></span></td>
                  <td><span class="bar"><i :style="{ width: t.progress + '%' }" /></span><em>{{ t.progress }}%</em></td>
                  <td>{{ t.owner }}</td>
                  <td class="t-due">{{ t.due }}</td>
                </tr>
              </tbody>
            </table>
            <p v-if="!shownTasks.length" class="empty">该状态下没有任务</p>
          </div>
        </section>

        <!-- 文档 / 素材 / 知识库：同一张列表骨架，换数据源 -->
        <section v-else-if="['docs', 'materials', 'knowledge'].includes(section)" class="sec">
          <div class="card tbl">
            <!-- 一行装完：左边分组、右边一个固定宽的搜索框。
                 那颗动作按钮撤了——页头 .mh-acts 上本来就有同一颗（文档"新建文档"、素材"上传"、知识库"新建条目"）。
                 类名带 g 前缀（group）——.tabs/.tab 这名字已经被「我的任务」那排筛选占了，
                 上一轮我同名覆盖，把那颗胶囊样式一起改掉了 -->
            <div class="gbar">
              <div class="gtabs">
                <button v-for="g in sideGroups" :key="g" type="button" class="gtab" :class="{ on: sideGroup === g }" @click="sideGroup = g">{{ g }}</button>
              </div>
              <label class="sbox">
                <span class="sbox-ic" aria-hidden="true"><IcSearch /></span>
                <input v-model="q" class="search" type="text" :placeholder="section === 'docs' ? '搜索文档名称或负责人' : '搜索名称'" />
              </label>
            </div>
            <table>
              <thead>
                <tr><th>名称</th><th class="c-st">{{ section === 'knowledge' ? '分类' : '类型' }}</th><th class="c-st">大小</th><th class="c-due">修改时间</th><th class="c-who">负责人</th></tr>
              </thead>
              <tbody>
                <tr v-for="r in shownRows" :key="r.name" @click="openDoc(r)">
                  <td class="t-name"><span class="t-nm">
                    <span class="tile" :style="{ background: tintOf(r), color: inkOf(r) }">
                      <component :is="glyphOf(r)" theme="outline" size="14" />
                    </span>{{ r.name }}</span></td>
                  <td><span class="cpill" :style="{ background: tintOf(r), color: inkOf(r) }">{{ r.type || r.tag }}</span></td>
                  <td>{{ r.size || '—' }}</td>
                  <td class="t-due">{{ r.at }}</td>
                  <td>{{ r.owner }}</td>
                </tr>
              </tbody>
            </table>
            <p v-if="!shownRows.length" class="empty">没有匹配项</p>
          </div>
        </section>

        <!-- AI 三页 -->
        <section v-else-if="['ai', 'generate', 'analysis'].includes(section)" class="sec">
          <div class="cols">
            <div class="card">
              <div class="card-hd"><b>{{ section === 'ai' ? '历史会话' : section === 'generate' ? '生成结果' : '分析维度' }}</b></div>
              <ul class="lines">
                <li v-for="s in aiLeft" :key="s.name || s.title || s.label">
                  <span class="l-nm">{{ s.name || s.title || s.label }}</span>
                  <span class="l-due">{{ s.at || s.preview || ((s.value ?? s.score) + '%') }}</span>
                </li>
              </ul>
            </div>
            <div class="card">
              <div class="card-hd"><b>{{ TITLE[section].name }}</b></div>
              <div class="ai-body">
                <p class="ai-note">{{ AI_NOTE[section] }}</p>
                <div v-if="section === 'analysis'" class="scores">
                  <div v-for="s in docScores" :key="s.label" class="score">
                    <span class="ring" :class="ringClass(s.value)"><b>{{ s.value }}</b></span>
                    <span class="sc-lb">{{ s.label }}</span>
                  </div>
                </div>
                <ul v-else-if="section === 'generate'" class="cands">
                  <li v-for="a in aiGenerated" :key="a.title">
                    <b>{{ a.title }}</b><p>{{ a.body }}</p><span class="sc">{{ a.score }} 分</span>
                  </li>
                </ul>
                <div v-else class="chat-box">
                  <p v-for="(m, i) in aiThread" :key="i" class="msg" :class="m.who">{{ m.text }}</p>
                  <div class="ask">
                    <input class="search" type="text" placeholder="向 AI 助手提问…（未接入模型服务）" disabled />
                    <button type="button" class="btn" disabled :title="DEMO_NOTE">发送</button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- 最近内容 / 收藏 / 项目：同一份内容清单 -->
        <section v-else class="sec">
          <div class="grid">
            <article v-for="c in gridItems" :key="c.name" class="gcard">
              <span class="kind" :class="c.kind">{{ c.kind.toUpperCase() }}</span>
              <b>{{ c.name }}</b>
              <p>{{ c.status }} · {{ c.at }}</p>
              <span class="g-meta">{{ c.views }} 浏览 · {{ c.comments }} 评论</span>
            </article>
          </div>
        </section>
      </div>
    </main>

    <!-- ③ 任务详情抽屉（稿子里的右侧/底部面板，这里统一走右侧抽屉） -->
    <aside v-if="drawer" class="dw" :aria-label="drawer === 'task' ? '任务详情' : '内容详情'">
      <header class="dw-hd">
        <b>{{ drawer === 'task' ? '任务详情' : '内容详情' }}</b>
        <button type="button" class="x" aria-label="收起" @click="drawer = null">✕</button>
      </header>
      <div v-if="drawer === 'task'" class="dw-bd">
        <h2>{{ pickedTask.name }}</h2>
        <div class="dw-meta">
          <span class="pill" :class="tickClass(pickedTask.status)">{{ pickedTask.status }}</span>
          <span>{{ pickedTask.owner }} · {{ pickedTask.due }}</span>
        </div>
        <div class="prog"><i :style="{ width: pickedTask.progress + '%' }" /><em>{{ pickedTask.progress }}%</em></div>
        <p class="dw-cap">任务描述</p>
        <p class="dw-p">{{ taskDetail.desc }}</p>
        <p class="dw-cap">关联内容</p>
        <ul class="lines">
          <li v-for="r in taskDetail.related" :key="r.name"><span class="l-nm">{{ r.name }}</span></li>
        </ul>
        <p class="dw-cap">评论（{{ taskDetail.comments.length }}）</p>
        <ul class="cmts">
          <li v-for="(c, i) in taskDetail.comments" :key="i"><b>{{ c.who }}</b><small>{{ c.at }}</small><p>{{ c.text }}</p></li>
        </ul>
        <div class="ask">
          <input class="search" type="text" placeholder="添加评论…（未接入评论接口）" disabled />
          <button type="button" class="btn" disabled :title="DEMO_NOTE">发送</button>
        </div>
      </div>
      <div v-else class="dw-bd">
        <h2>{{ pickedDoc?.name || docDetail.title }}</h2>
        <p class="dw-p">{{ pickedDoc ? '该条目来自静态壳数据，文档正文接口尚未提供。' : docDetail.sub }}</p>
        <p class="dw-cap">内容预览</p>
        <div class="prev">
          <h3>{{ docPreview.title }}</h3>
          <p>{{ docPreview.lead }}</p>
          <ul><li v-for="b in docPreview.bullets" :key="b">{{ b }}</li></ul>
          <button type="button" class="btn pri" disabled :title="DEMO_NOTE">{{ docPreview.cta }}</button>
        </div>
      </div>
    </aside>
  </div>
</template>

<script setup>
import { computed, h, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '../../stores/user'
import {
  dashStats, myTasks, taskDetail, contents, favoriteRows, docRows, materialRows, knowledgeRows,
  aiSessions, aiGenerated, docDetail, docPreview, docScores
} from '../../mock/workbench'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const DEMO_NOTE = '这块是设计稿的静态壳：后端还没有对应接口，所以按钮先置灰而不是点了没反应'
const AI_NOTE = {
  ai: 'AI 助手要接模型服务（当前后端没有该接口），这里只把会话骨架与输入区摆出来。',
  generate: '生成结果需要真实模型输出；下面是稿子里的示例三条，用于定版式。',
  analysis: '评分维度来自稿子；真实分数要等内容分析接口，这里沿用稿子的 92/87/78。'
}

// 图标用内联 SVG，避免为静态壳再引一套图标依赖
const mk = (paths) => () => h('svg', { viewBox: '0 0 24 24', width: 18, height: 18, fill: 'none', stroke: 'currentColor', 'stroke-width': 1.7 },
  paths.map(d => h('path', { d })))
const Ic = {
  home: mk(['M4 11l8-6.5 8 6.5v8a1 1 0 0 1-1 1h-5v-6h-4v6H5a1 1 0 0 1-1-1z']),
  task: mk(['M5.5 6.5h13M5.5 12h13M5.5 17.5h8']),
  inbox: mk(['M4 13l2.5-7h11L20 13v6H4z', 'M4 13h4l1 2h6l1-2h4']),
  star: mk(['M12 4.5l2.3 4.8 5.2.7-3.8 3.6.9 5.2-4.6-2.5-4.6 2.5.9-5.2L4.5 10l5.2-.7z']),
  folder: mk(['M4 7.5h5l1.5 2H20v9.5H4z']),
  doc: mk(['M7 3.5h7l4 4V20H7z', 'M14 3.5V8h4']),
  image: mk(['M4.5 6.5h15v11h-15z', 'M8 12l2.5 2.5L14 11l3.5 4.5']),
  book: mk(['M5 5.5h6v13H5z', 'M13 5.5h6v13h-6z']),
  spark: mk(['M12 4l1.6 4.4L18 10l-4.4 1.6L12 16l-1.6-4.4L6 10l4.4-1.6z']),
  pen: mk(['M5 19h4l9-9-4-4-9 9z', 'M14 6l4 4']),
  chart: mk(['M5 19V9M11 19V5M17 19v-7M21 19H3']),
  layers: mk(['M12 4l8 4-8 4-8-4z', 'M4 12l8 4 8-4', 'M4 16.5l8 4 8-4']),
  image: mk(['M4 5.5h16v13H4z', 'M4 15l4.5-4.5 3.5 3.5 3-3L20 15']),
  play: mk(['M4 5.5h16v13H4z', 'M10 9l6 3-6 3z']),
  plus: mk(['M12 5v14M5 12h14']),
  search: mk(['M11 18a7 7 0 1 1 0-14 7 7 0 0 1 0 14z', 'M16.2 16.2L21 21']),
  gear: mk(['M12 9.2a2.8 2.8 0 1 0 0 5.6 2.8 2.8 0 0 0 0-5.6z',
    'M12 3.5v2.4M12 18.1v2.4M3.5 12h2.4M18.1 12h2.4M6 6l1.7 1.7M16.3 16.3L18 18M18 6l-1.7 1.7M7.7 16.3L6 18'])
}

const NAV_MAIN = [
  { key: 'home', name: '首页', icon: Ic.home },
  { key: 'tasks', name: '我的任务', icon: Ic.task, badge: myTasks.filter(t => t.status !== '已完成').length },
  { key: 'contents', name: '最近内容', icon: Ic.inbox },
  { key: 'favorites', name: '收藏', icon: Ic.star }
]
const NAV_DOC = [
  { key: 'projects', name: '项目', icon: Ic.folder },
  { key: 'docs', name: '文档', icon: Ic.doc },
  { key: 'materials', name: '素材', icon: Ic.image },
  { key: 'knowledge', name: '知识库', icon: Ic.book }
]
const NAV_AI = [
  { key: 'ai', name: 'AI 助手', icon: Ic.spark },
  { key: 'generate', name: '内容生成', icon: Ic.pen },
  { key: 'analysis', name: '内容分析', icon: Ic.chart }
]

const TITLE = {
  home: { name: '工作台', sub: '今天的任务与内容动态', acts: [] },
  tasks: { name: '我的任务', sub: '按状态筛选，点一行看详情', acts: ['新建任务'] },
  contents: { name: '最近内容', sub: '团队最近改动的内容', acts: [] },
  favorites: { name: '收藏', sub: '你标星的内容', acts: [] },
  projects: { name: '项目', sub: '内容所属项目', acts: ['新建项目'] },
  docs: { name: '项目文档', sub: '产品官网 / 产线例', acts: ['新建文档'] },
  materials: { name: '素材库', sub: '图片、矢量与视频', acts: ['上传'] },
  knowledge: { name: '知识库', sub: '流程、品牌与研发约定', acts: ['新建条目'] },
  ai: { name: 'AI 助手', sub: '内容问答与改写', acts: [] },
  generate: { name: '内容生成', sub: '一次生成多套候选', acts: ['重新生成'] },
  analysis: { name: '内容分析', sub: '可读性 / 品牌一致性 / SEO', acts: ['重新分析'] }
}

const section = computed(() => {
  const s = String(route.params.section || 'home')
  return TITLE[s] ? s : 'home'
})
const displayName = computed(() => userStore.username || '同事')

const taskFilter = ref('全部')
const TASK_FILTERS = ['全部', '进行中', '待开始', '已完成', '阻塞']
const shownTasks = computed(() => myTasks.filter(t => taskFilter.value === '全部' || t.status === taskFilter.value))

const q = ref('')
const sideGroup = ref('全部')
const sideGroups = computed(() => section.value === 'docs' ? ['全部', '产品官网', '市场活动', '内容运营']
  : section.value === 'materials' ? ['全部', '图片', '矢量', '视频'] : ['全部', '流程', '品牌', '研发'])
const rowsOfSection = computed(() => section.value === 'materials' ? materialRows
  : section.value === 'knowledge' ? knowledgeRows : docRows)

// 行图标块和分类胶囊的三色是从稿子上量的（取的是图标中心那个像素）：蓝 #3268FD、绿 #06CE83、紫 #8717F5。
// 底色不另编：同一个色按 14% 落白，实测离稿子的 #DDE8FE 差 7。
const IcPlus = Ic.plus()
const IcSearch = Ic.search()
const IcGear = Ic.gear()
const TILE_INK = ['#3268fd', '#06ce83', '#8717f5']
const PAGE_IC = { home: Ic.home, tasks: Ic.task, contents: Ic.inbox, favorites: Ic.star, projects: Ic.folder,
  docs: Ic.doc, materials: Ic.image, knowledge: Ic.layers, ai: Ic.spark, generate: Ic.pen, analysis: Ic.chart }
const catOf = (r) => String(r.group || r.tag || r.type || '')
const inkOf = (r) => {
  const i = sideGroups.value.indexOf(catOf(r))
  return TILE_INK[(i > 0 ? i - 1 : catOf(r).length) % TILE_INK.length]
}
const tintOf = (r) => `color-mix(in srgb, ${inkOf(r)} 14%, #fff)`
const glyphOf = (r) => {
  const s = catOf(r) + ' ' + String(r.type || '')
  return /图|片|矢量|svg|png|image/i.test(s) ? Ic.image : /视频|mp4|mov|video/i.test(s) ? Ic.play : Ic.doc
}
const shownRows = computed(() => {
  const kw = q.value.trim().toLowerCase()
  // 分组字段三页各叫各的：文档 group、素材 type、知识库 tag
  return rowsOfSection.value.filter(r => (!kw || [r.name, r.owner, r.type, r.tag].some(v => String(v || '').toLowerCase().includes(kw)))
    && (sideGroup.value === '全部' || String(r.group || r.type || r.tag || '') === sideGroup.value))
})

const gridItems = computed(() => section.value === 'favorites' ? favoriteRows
  : section.value === 'projects' ? [
    { name: '产品官网', kind: 'doc', status: '3 个项目', at: '进行中', views: '24', comments: 3 },
    { name: '市场活动', kind: 'pdf', status: '进行中', at: '本周', views: '12', comments: 1 },
    { name: '内容运营', kind: 'psd', status: '草稿', at: '上周', views: '8', comments: 0 }
  ] : contents)

const aiLeft = computed(() => section.value === 'analysis' ? docScores : section.value === 'generate' ? aiGenerated : aiSessions)
const aiThread = [
  { who: 'me', text: '帮我把首页标题改成三句更短的。' },
  { who: 'ai', text: '给你三句：「让内容协作更简单」「一次写好，处处可用」「从草稿到发布，少绕两步」。' }
]

const drawer = ref(null)
const pickedTask = ref(myTasks[0])
const pickedDoc = ref(null)
function openTask(t) { pickedTask.value = { ...taskDetail, ...t }; drawer.value = 'task' }
function openDoc(r) { pickedDoc.value = r; drawer.value = 'doc' }

function go(key) {
  if (key === 'chat') { router.push('/'); return }
  router.push(`/workbench/${key}`)
}
function handleLogout() { userStore.logout(); router.push('/login') }

const tickClass = (s) => ({ '进行中': 'doing', '待开始': 'todo', '已完成': 'done', '阻塞': 'block' }[s] || 'todo')
const ringClass = (v) => v >= 90 ? 'ok' : v >= 85 ? 'brand' : 'warn'

watch(section, () => { drawer.value = null; q.value = ''; sideGroup.value = '全部' })
</script>

<style scoped>
.wb { display: grid; grid-template-columns: 208px minmax(0, 1fr); height: 100vh; background: var(--nb-bg-0); color: var(--nb-text);
  /* 顶部两条栏共用的高与共用那片渐变。--wb-bar = 页头条量出来的现值（内容 42 + 上下内距 28 + 下边框 1）；
     渐变的竖向停靠点写成视口像素（36/58/92/107）是因为四层都挂 background-attachment: fixed，
     坐标原点在整个窗口上 —— 这样页头条和抽屉头是同一场光的两扇窗，不会在列边界处各起一次 */
  --wb-bar: 71px;
  /* 四层，从上往下叠：① 顶边整条钉在他点名的 #e1e5ec，往下 22px 化开；
     ② 三条发丝线 —— 圆心挪到窗口上方 7900px、半径 7963~7998，在这条 71px 高的栏里是起伏 26px 的缓弧，
        从 x=0 一路走到 x=1280 都在栏内（上一版圆心在下方 1400px，弧太弯，左端停在 x≈480，
        所以左边那块要线就没有线）。线仍用品牌蓝低透明度：白线压在浅色那头只有 Δ6 看不出是线；
     ③ 竖向白纱（顶透 → 底 #F0F4FB）；④ 横向蓝坡，最右端 #A9CDFA */
  --wb-wash: linear-gradient(180deg, #e1e5ec 36px, rgba(225, 229, 236, 0) 58px),
    radial-gradient(circle 8100px at 640px -7900px, rgba(255, 255, 255, 0) 0 7963px, rgba(43, 107, 232, .17) 7963px 7966px,
      rgba(255, 255, 255, 0) 7966px 7979px, rgba(43, 107, 232, .15) 7979px 7982px,
      rgba(255, 255, 255, 0) 7982px 7995px, rgba(43, 107, 232, .12) 7995px 7998px, rgba(255, 255, 255, 0) 7998px),
    linear-gradient(180deg, rgba(240, 244, 251, 0) 36px, rgba(240, 244, 251, .92) 92px, #f0f4fb 107px),
    linear-gradient(90deg, #eef4fb 0%, #dfeafb 20%, #a9cdfa 82%); }
.wb:has(.dw) { grid-template-columns: 208px minmax(0, 1fr) 320px; }

/* 左栏顶上那 71px 也盖同一片光（和页头条、抽屉头一模一样，含三条线）：
   第一层是遮罩 —— y<107 全透让下面的 --wb-wash 露出来，y≥107 直接刷成栏底本档，
   所以这条栏往下还是实心，只有 logo 那一截跟着顶栏走 */
.rail { display: flex; flex-direction: column; gap: 2px; min-height: 0; padding: 12px 10px; border-right: 1px solid var(--nb-line);
  background: linear-gradient(180deg, rgba(0, 0, 0, 0) 107px, var(--nb-bg-3) 107px), var(--wb-wash);
  background-attachment: fixed; }
.rail-logo { display: flex; align-items: center; gap: 9px; padding: 6px 8px 12px; cursor: pointer; }
.lg-ic { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 9px; background: var(--brand); color: #fff; }
.lg-tx b { display: block; font-size: 13.5px; line-height: 1.2; }
.lg-tx small { display: block; font-size: 11px; color: var(--nb-dim); }
.rail-scroll { flex: 1; min-height: 0; overflow-y: auto; }
.rail-cap { margin: 12px 8px 4px; font-size: 11px; color: var(--nb-dim); letter-spacing: .04em; }
.ri { display: flex; align-items: center; gap: 9px; width: 100%; padding: 8px 9px; font: inherit; font-size: 13px; color: var(--nb-text); background: none; border: 0; border-radius: 9px; cursor: pointer; text-align: left; }
/* 项与项之间 4px：和「内容 / AI」这两个小标题下面那 4px 同一档，也是项内上下内边距 8px 的一半。
   只写 .ri + .ri，小标题自己那 12/4 的节奏不动 */
.ri + .ri { margin-top: 4px; }
/* 菜单栏现在是 #f0f3f8，hover 那档同色等于没反馈，提到 --nb-line(#e2e7f0)：距栏底 Δ(14,12,8) */
.ri:hover { background: var(--nb-line); }
/* 选中底色：--brand-soft 是品牌蓝 10% 混白，按「深一点 = 混色翻倍」提到 20%。
   只改这一处，不动全局 --brand-soft（聊天页、页签、状态胶囊都在用）。
   顺带把深浅关系摆正：原来选中(234,241,255)比 hover(226,231,240)还浅，hover 别的项反而更重 */
.ri.on { background: color-mix(in srgb, var(--brand) 20%, #fff); color: var(--brand-strong); font-weight: 600; }
.ri-ic { display: grid; place-items: center; width: 18px; color: inherit; }
.ri-tx { flex: 1; }
.ri-n { font-size: 11px; padding: 1px 6px; border-radius: 999px; background: var(--brand); color: #fff; }
.rail-foot { padding-top: 10px; border-top: 1px solid var(--nb-line); }
/* margin-top 那 6px 是原来跟「企业版」卡片之间的缝，卡片删了就跟着删 */
.ri.quit { color: var(--nb-dim); }

.main { display: flex; flex-direction: column; min-width: 0; }
/* 页头条 = 整页那片渐变的左半扇窗：右上角一团蓝往左、往下散，横向蓝坡打底 + 竖向白纱盖在上面。
   锚点是稿子按归一化位置扫出来的：顶边 u=0.61 → (176,211,252)、u=0.95 → (172,206,253)，
   同一条竖线走到条底 → (239,245,253) */
.mh { display: flex; align-items: center; gap: 10px; min-height: var(--wb-bar); padding: 14px 20px; border-bottom: 1px solid var(--nb-line);
  background: var(--wb-wash); background-attachment: fixed; }
.mh-ic { display: grid; place-items: center; flex: none; width: 34px; height: 34px; border-radius: 10px;
  background: var(--brand); color: #fff; }
.mh-ic svg { width: 19px; height: 19px; }
.mh-t { flex: 1; min-width: 0; }
.mh-t h1 { margin: 0; font-size: 16px; font-weight: 600; }
.mh-t p { margin: 2px 0 0; font-size: 12px; color: var(--nb-dim); }
.demo { padding: 2px 8px; font-size: 11px; color: var(--nb-dim); background: var(--nb-bg-3); border: 1px dashed var(--nb-line); border-radius: 999px; cursor: help; }
.mh-acts { display: flex; gap: 8px; }
/* 稿子右上那颗是实心蓝底白字 + 一个加号。它背后没有接口，仍然是 disabled（点了不响应），
   但别用 opacity 把它洗成灰色——那样和稿子对不上；口径交给 cursor 和旁边那颗「演示」 */
.mh-acts .btn.pri { display: inline-flex; align-items: center; gap: 6px; background: var(--brand);
  border-color: var(--brand); color: #fff; font-weight: 600; }
.mh-acts .btn.pri:disabled { opacity: 1; cursor: not-allowed; }
.pri-ic { display: grid; place-items: center; flex: none; width: 14px; }
.pri-ic svg { width: 14px; height: 14px; }
.body { flex: 1; overflow-y: auto; padding: 18px 20px 26px; }

.sec { display: flex; flex-direction: column; gap: 14px; }
.hello h2 { margin: 0; font-size: 18px; }
.hello p { margin: 3px 0 0; font-size: 12.5px; color: var(--nb-dim); }
.stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.stat { padding: 13px 14px; background: var(--nb-bg-1); border: 1px solid var(--nb-line); border-radius: 12px; }
.st-lb { display: block; font-size: 12px; color: var(--nb-dim); }
.st-v { display: inline-block; margin-top: 5px; font-size: 22px; font-weight: 600; }
.st-d { margin-left: 7px; font-size: 11.5px; }
.st-d.doing, .st-d.review { color: var(--brand); }
.st-d.done, .st-d.team { color: var(--ok); }
.cols { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; align-items: start; }
.card { background: var(--nb-bg-1); border: 1px solid var(--nb-line); border-radius: 12px; overflow: hidden; }
.card-hd { display: flex; align-items: center; justify-content: space-between; padding: 11px 14px; border-bottom: 1px solid var(--nb-line); }
.card-hd b { font-size: 13px; }
.lnk { border: 0; background: none; padding: 0; font: inherit; font-size: 12px; color: var(--brand); cursor: pointer; }
.lines { margin: 0; padding: 4px 0; list-style: none; }
.lines li { display: flex; align-items: center; gap: 9px; padding: 9px 14px; font-size: 13px; }
.lines li + li { border-top: 1px solid var(--nb-line); }
.l-nm { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.l-st { flex: 0 0 auto; font-size: 11.5px; color: var(--nb-dim); white-space: nowrap; }
.l-who, .l-due { flex: 0 0 auto; font-size: 11.5px; color: var(--nb-dim); white-space: nowrap; }
.tick { flex: 0 0 9px; width: 9px; height: 9px; border-radius: 50%; border: 2px solid var(--nb-line); }
.tick.doing { border-color: var(--brand); background: var(--brand); }
.tick.done { border-color: var(--ok); background: var(--ok); }
.tick.block { border-color: var(--danger); background: var(--danger); }
.kind { flex: 0 0 auto; font-size: 10.5px; font-weight: 600; color: var(--brand-strong); background: var(--brand-soft); border-radius: 6px; padding: 2px 6px; }

.tabs { display: flex; gap: 4px; }
.tab { padding: 6px 12px; font: inherit; font-size: 12.5px; color: var(--nb-dim); background: none; border: 1px solid transparent; border-radius: 999px; cursor: pointer; }
.tab.on { color: var(--brand-strong); background: var(--brand-soft); border-color: var(--brand-line); font-weight: 600; }

.tbl table { width: 100%; border-collapse: collapse; table-layout: fixed; }
.tbl th, .tbl td { padding: 9px 14px; font-size: 12.5px; text-align: left; border-bottom: 1px solid var(--nb-line); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tbl th { font-weight: 600; color: var(--nb-dim); font-size: 11.5px; background: var(--nb-bg-2); }
.tbl tbody tr { cursor: pointer; }
.tbl tbody tr:hover { background: var(--nb-bg-3); }
/* 末行那道分隔线去掉：卡片自己有一圈边框，两条线中间夹着 1px 白，圆角处还越岔越开 —— 看着就是"底部两条线" */
.tbl tbody tr:last-child td { border-bottom: 0; }
.tbl .c-st { width: 88px; } .tbl .c-who { width: 78px; } .tbl .c-due { width: 92px; } .tbl .c-pr { width: 132px; }
/* td 自己不能是 flex：它一旦不是 table-cell 就不跟着行高撑开，
   自己那道 border-bottom 会画到比邻列高 1px（列表页）/ 高 5px（任务页）的地方 —— 分隔线在列边界上错开一个台阶。
   要 flex 就套一层 span，td 保持 table-cell */
.t-name { font-weight: 500; }
.t-nm { display: flex; align-items: center; }
/* 行名字前的图标色块 + 分类列的彩色胶囊：墨色是稿子上量的三个值，底色按同色 14% 落白 */
.tile { display: grid; place-items: center; flex: none; width: 26px; height: 26px; margin-right: 9px; border-radius: 8px; }
.tile svg { width: 14px; height: 14px; }
/* 分类列的胶囊另起一个类：.pill 这个类「我的任务」的状态列在用，它带一圈 1px --nb-line 灰边，
   我上一轮同名写了一条没带边的，后写的把先写的盖了 —— 灰边套在彩色胶囊上，稿子里没有这一圈 */
.cpill { display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 11.5px; font-weight: 600; }
.t-name small { display: block; margin-left: 0; font-size: 11px; font-weight: 400; color: var(--nb-dim); }
.t-due { color: var(--nb-dim); }
.pill { display: inline-block; padding: 2px 8px; font-size: 11.5px; border-radius: 999px; border: 1px solid var(--nb-line); color: var(--nb-dim); background: var(--nb-bg-2); }
.pill.doing { color: var(--brand-strong); background: var(--brand-soft); border-color: var(--brand-line); }
.pill.done { color: var(--ok); border-color: var(--ok); }
.pill.block { color: var(--danger); border-color: var(--danger); }
.bar { display: inline-block; width: 62px; height: 5px; vertical-align: middle; background: var(--nb-bg-3); border-radius: 999px; overflow: hidden; }
.bar i { display: block; height: 100%; background: var(--brand); }
td em { margin-left: 7px; font-size: 11.5px; font-style: normal; color: var(--nb-dim); }
.empty { margin: 0; padding: 18px; font-size: 12.5px; color: var(--nb-dim); text-align: center; }

/* 一行：分组靠左不吃宽度，搜索框固定 260 贴右。
   .search 这个类还有两处在用（AI 提问框、评论框），宽度和放大镜都只在这条里限定 */
.gbar { display: flex; align-items: center; gap: 9px; padding: 8px 12px; border-bottom: 1px solid var(--nb-line); }
.sbox { position: relative; display: flex; align-items: center; flex: none; width: 260px; margin-left: auto; }
.sbox .search { width: 100%; padding-left: 30px; }
.sbox-ic { position: absolute; left: 10px; display: grid; place-items: center; width: 14px; color: var(--nb-dim); pointer-events: none; }
.sbox-ic svg { width: 14px; height: 14px; }
/* 稿子的分组条是一条浅灰轨道 + 里面实心蓝胶囊（轨道实测 #EAF0F9，与 --nb-bg-3 差 10） */
.gtabs { display: flex; align-items: center; gap: 2px; flex: none; padding: 3px; border-radius: 10px; background: var(--nb-bg-3) }
.gtab { padding: 6px 12px; font: inherit; font-size: 12.5px; color: var(--nb-text); background: none; border: 0;
  border-radius: 7px; cursor: pointer; transition: background-color .12s ease, color .12s ease; }
.gtab:hover { background: var(--nb-line); }
.gtab.on { background: var(--brand); color: #fff; font-weight: 600; }
.search { flex: 1; min-width: 120px; padding: 7px 10px; font: inherit; font-size: 12.5px; color: var(--nb-text); background: var(--nb-bg-2); border: 1px solid var(--nb-line); border-radius: 9px; outline: none; }
.search:focus { border-color: var(--brand); }
.btn { padding: 7px 13px; font: inherit; font-size: 12.5px; border-radius: 9px; border: 1px solid var(--nb-line); background: var(--nb-bg-1); color: var(--nb-text); cursor: pointer; }
.btn.pri { border-color: var(--brand); background: var(--brand); color: #fff; font-weight: 600; }
.btn:disabled { opacity: .55; cursor: not-allowed; }

.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(216px, 1fr)); gap: 12px; }
.gcard { padding: 14px; background: var(--nb-bg-1); border: 1px solid var(--nb-line); border-radius: 12px; display: flex; flex-direction: column; gap: 6px; }
.gcard b { font-size: 13.5px; }
.gcard p { margin: 0; font-size: 12px; color: var(--nb-dim); }
.g-meta { font-size: 11.5px; color: var(--nb-dim); }

.ai-body { padding: 14px; display: flex; flex-direction: column; gap: 13px; }
.ai-note { margin: 0; font-size: 12.5px; line-height: 1.6; color: var(--nb-dim); }
.scores { display: flex; gap: 18px; }
.score { display: flex; flex-direction: column; align-items: center; gap: 6px; }
.ring { display: grid; place-items: center; width: 56px; height: 56px; border-radius: 50%; border: 4px solid var(--brand-soft); }
.ring b { font-size: 16px; }
.ring.ok { border-color: var(--ok); }
.ring.brand { border-color: var(--brand); }
.ring.warn { border-color: var(--warn); }
.sc-lb { font-size: 11.5px; color: var(--nb-dim); }
.cands { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 9px; }
.cands li { padding: 11px 12px; border: 1px solid var(--nb-line); border-radius: 10px; }
.cands b { font-size: 12.5px; }
.cands p { margin: 4px 0 0; font-size: 13px; }
.cands .sc { font-size: 11.5px; color: var(--brand); }
.chat-box { display: flex; flex-direction: column; gap: 9px; }
.msg { margin: 0; max-width: 78%; padding: 9px 11px; font-size: 13px; border-radius: 11px; background: var(--nb-bg-3); }
.msg.me { align-self: flex-end; background: var(--brand); color: #fff; }
.ask { display: flex; gap: 8px; }

/* 抽屉正文换成左边菜单那一档淡蓝 --nb-bg-3(#f0f3f8)。抽屉头自己盖着 --wb-wash（不透明），不受这条影响 */
.dw { background: var(--nb-bg-3); border-left: 1px solid var(--nb-line); display: flex; flex-direction: column; min-height: 0; }
/* 抽屉头和页头条同高、同一片渐变 —— 两条下边框落在同一条线上，顶上那一片读起来是一整块 */
.dw-hd { display: flex; align-items: center; justify-content: space-between; min-height: var(--wb-bar); padding: 14px 16px; border-bottom: 1px solid var(--nb-line);
  background: var(--wb-wash); background-attachment: fixed; }
.dw-hd b { font-size: 13.5px; }
.x { border: 0; background: none; color: var(--nb-dim); font-size: 13px; cursor: pointer; }
.dw-bd { flex: 1; overflow-y: auto; padding: 14px 16px; }
.dw-bd h2 { margin: 0; font-size: 15px; }
.dw-meta { display: flex; align-items: center; gap: 9px; margin-top: 8px; font-size: 12px; color: var(--nb-dim); }
.prog { position: relative; height: 6px; margin: 12px 0 20px; border-radius: 999px; background: var(--nb-bg-3); }
.prog i { display: block; height: 100%; border-radius: 999px; background: var(--brand); }
.prog em { position: absolute; right: 0; bottom: -17px; font-size: 11.5px; font-style: normal; color: var(--nb-dim); }
.dw-cap { margin: 14px 0 5px; font-size: 11.5px; color: var(--nb-dim); }
.dw-p { margin: 0; font-size: 12.5px; line-height: 1.65; }
.cmts { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 9px; }
.cmts b { font-size: 12.5px; }
.cmts small { margin-left: 6px; font-size: 11px; color: var(--nb-dim); }
.cmts p { margin: 3px 0 0; font-size: 12.5px; }
.prev { padding: 12px; border: 1px solid var(--nb-line); border-radius: 11px; background: var(--nb-bg-2); }
.prev h3 { margin: 0 0 6px; font-size: 14px; }
.prev p { margin: 0 0 8px; font-size: 12.5px; color: var(--nb-dim); }
.prev ul { margin: 0 0 10px; padding-left: 18px; font-size: 12.5px; }
/* nebula.css 的全局 .btn 带 letter-spacing:1px，中文按钮字距会被拉开，这条要留 */
.btn { letter-spacing: normal; }
</style>
