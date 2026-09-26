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
        <div class="plan">
          <b>企业版</b>
          <small>专业的内容协作平台</small>
        </div>
        <button type="button" class="ri quit" @click="handleLogout">退出登录</button>
      </div>
    </nav>

    <!-- ② 主区 -->
    <main class="main">
      <header class="mh">
        <div class="mh-t">
          <h1>{{ TITLE[section].name }}</h1>
          <p>{{ TITLE[section].sub }}</p>
        </div>
        <span class="demo" :title="DEMO_NOTE">演示</span>
        <div class="mh-acts">
          <button v-for="a in TITLE[section].acts" :key="a" type="button" class="btn" disabled :title="DEMO_NOTE">{{ a }}</button>
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
                  <td class="t-name">{{ t.name }}<small>{{ t.project }}</small></td>
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
            <!-- 分类原来是左侧那一栏，改成表格顶上一条横 tab；选中态的色板沿用原来那颗，
                 只换排列轴，不换视觉语言 -->
            <div class="tabs">
              <p class="tabs-cap">{{ section === 'docs' ? '产品官网' : section === 'materials' ? '素材分类' : '知识库' }}</p>
              <button v-for="g in sideGroups" :key="g" type="button" class="tab" :class="{ on: sideGroup === g }" @click="sideGroup = g">{{ g }}</button>
            </div>
            <div class="tbl-hd">
              <input v-model="q" class="search" type="text" :placeholder="section === 'docs' ? '搜索文档名称或负责人' : '搜索名称'" />
              <button type="button" class="btn" disabled :title="DEMO_NOTE">{{ section === 'docs' ? '新建文档' : '上传' }}</button>
            </div>
            <table>
              <thead>
                <tr><th>名称</th><th class="c-st">{{ section === 'knowledge' ? '分类' : '类型' }}</th><th class="c-st">大小</th><th class="c-due">修改时间</th><th class="c-who">负责人</th></tr>
              </thead>
              <tbody>
                <tr v-for="r in shownRows" :key="r.name" @click="openDoc(r)">
                  <td class="t-name">{{ r.name }}</td>
                  <td>{{ r.type || r.tag }}</td>
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
  chart: mk(['M5 19V9M11 19V5M17 19v-7M21 19H3'])
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
const shownRows = computed(() => {
  const kw = q.value.trim().toLowerCase()
  return rowsOfSection.value.filter(r => (!kw || [r.name, r.owner, r.type, r.tag].some(v => String(v || '').toLowerCase().includes(kw)))
    && (sideGroup.value === '全部' || String(r.name).includes(sideGroup.value) || String(r.type || r.tag || '') === sideGroup.value))
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
.wb { display: grid; grid-template-columns: 208px minmax(0, 1fr); height: 100vh; background: var(--nb-bg-0); color: var(--nb-text); }
.wb:has(.dw) { grid-template-columns: 208px minmax(0, 1fr) 320px; }

.rail { display: flex; flex-direction: column; gap: 2px; min-height: 0; padding: 12px 10px; background: var(--nb-bg-1); border-right: 1px solid var(--nb-line); }
.rail-logo { display: flex; align-items: center; gap: 9px; padding: 6px 8px 12px; cursor: pointer; }
.lg-ic { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 9px; background: var(--brand); color: #fff; }
.lg-tx b { display: block; font-size: 13.5px; line-height: 1.2; }
.lg-tx small { display: block; font-size: 11px; color: var(--nb-dim); }
.rail-scroll { flex: 1; min-height: 0; overflow-y: auto; }
.rail-cap { margin: 12px 8px 4px; font-size: 11px; color: var(--nb-dim); letter-spacing: .04em; }
.ri { display: flex; align-items: center; gap: 9px; width: 100%; padding: 8px 9px; font: inherit; font-size: 13px; color: var(--nb-text); background: none; border: 0; border-radius: 9px; cursor: pointer; text-align: left; }
.ri:hover { background: var(--nb-bg-3); }
.ri.on { background: var(--brand-soft); color: var(--brand-strong); font-weight: 600; }
.ri-ic { display: grid; place-items: center; width: 18px; color: inherit; }
.ri-tx { flex: 1; }
.ri-n { font-size: 11px; padding: 1px 6px; border-radius: 999px; background: var(--brand); color: #fff; }
.rail-foot { padding-top: 10px; border-top: 1px solid var(--nb-line); }
.plan { padding: 8px 9px; border-radius: 10px; background: var(--nb-bg-3); }
.plan b { display: block; font-size: 12.5px; }
.plan small { display: block; font-size: 11px; color: var(--nb-dim); }
.ri.quit { margin-top: 6px; color: var(--nb-dim); }

.main { display: flex; flex-direction: column; min-width: 0; }
.mh { display: flex; align-items: center; gap: 10px; padding: 14px 20px; border-bottom: 1px solid var(--nb-line); background: var(--nb-bg-1); }
.mh-t { flex: 1; min-width: 0; }
.mh-t h1 { margin: 0; font-size: 16px; font-weight: 600; }
.mh-t p { margin: 2px 0 0; font-size: 12px; color: var(--nb-dim); }
.demo { padding: 2px 8px; font-size: 11px; color: var(--nb-dim); background: var(--nb-bg-3); border: 1px dashed var(--nb-line); border-radius: 999px; cursor: help; }
.mh-acts { display: flex; gap: 8px; }
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
.tbl .c-st { width: 88px; } .tbl .c-who { width: 78px; } .tbl .c-due { width: 92px; } .tbl .c-pr { width: 132px; }
.t-name { font-weight: 500; }
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

/* 分类条：从左侧那一栏搬到表格顶上。选中态的底色/字色用的是原来那同一组 token，只换排列轴 */
.tabs { display: flex; align-items: center; gap: 6px; padding: 8px 12px; border-bottom: 1px solid var(--nb-line); }
.tabs-cap { margin: 0 4px 0 0; font-size: 11.5px; color: var(--nb-dim); }
.tab { flex: none; padding: 7px 9px; font: inherit; font-size: 12.5px; color: var(--nb-text); background: none; border: 0;
  border-radius: 8px; cursor: pointer; transition: background-color .12s ease, color .12s ease; }
.tab:hover { background: var(--nb-bg-3); }
.tab.on { background: var(--brand-soft); color: var(--brand-strong); font-weight: 600; }
.tbl-hd { display: flex; gap: 9px; align-items: center; padding: 10px 12px; border-bottom: 1px solid var(--nb-line); }
.search { flex: 1; padding: 7px 10px; font: inherit; font-size: 12.5px; color: var(--nb-text); background: var(--nb-bg-2); border: 1px solid var(--nb-line); border-radius: 9px; outline: none; }
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

.dw { background: var(--nb-bg-1); border-left: 1px solid var(--nb-line); display: flex; flex-direction: column; min-height: 0; }
.dw-hd { display: flex; align-items: center; justify-content: space-between; padding: 14px 16px; border-bottom: 1px solid var(--nb-line); }
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
