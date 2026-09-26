/* ============================================================
   演示数据 —— 只覆盖后端还没有接口的那几块
   规则：真实链路（登录、会话、消息、群成员、已读）一律走接口；
        本文件里的每一块都必须在界面上带「演示」角标，
        接上真实接口时把对应导出删掉即可，不留第二份真相。
   ============================================================ */

// 列表栏顶部的团队概览（无接口：成员数/项目数）
export const workspace = {
  name: '产品团队',
  meta: '24 人 · 3 个项目'
}

export const mockGroups = [
  { id: 'g-design', name: '产品设计组', members: 11, last: '张三：新的设计稿已更新', time: '15:10' },
  { id: 'g-ops', name: '运营小组', members: 8, last: '李四：活动物料已准备完成', time: '14:02' }
]

// 右侧内容详情抽屉（无接口：文档元信息、预览、关联任务、评分、相关文件）
export const docDetail = {
  title: '首页文案 V2.4',
  status: '已发布',
  type: '文档',
  space: '产品官网',
  version: '版本 2.4',
  owner: '张三',
  createdAt: '2025-09-21 20:58',
  updatedAt: '2 分钟前',
  views: '1.2k',
  comments: 12
}

export const docPreview = {
  title: '让企业内容生产\n更高效、更智能',
  body: '基于 AI 的企业内容协作平台，帮助团队更快地完成内容创作、审核与发布。',
  cta: '立即体验'
}

export const docTasks = [
  { id: 't1', text: '首页文案修改', owner: '张三', due: '今天 18:00', done: true },
  { id: 't2', text: '确认 CTA 按钮样式', owner: '李四', due: '今天 20:00', done: false },
  { id: 't3', text: 'SEO 关键词优化', owner: '王五', due: '明天 10:00', done: false }
]

export const docScores = [
  { label: '可读性', value: 92, color: '#1f9d55' },
  { label: '品牌一致性', value: 87, color: '#2b6be8' },
  { label: 'SEO', value: 78, color: '#e08a1e' }
]

export const docRelated = [
  { name: '产品官网-首页设计稿.psd', size: '3.4 MB', type: 'psd' },
  { name: '品牌规范文档.pdf', size: '1.2 MB', type: 'pdf' }
]

// —— 以下为设计稿里"后端还没有对应接口"的界面数据（静态壳）——
// 色值一律走语义键，由 CSS 变量映射，不再往数据里写死十六进制
export const dashStats = [
  { key: 'doing', label: '进行中任务', value: 8, delta: '+2' },
  { key: 'done', label: '已完成任务', value: 24, delta: '+5' },
  { key: 'review', label: '待审核内容', value: 6, delta: '+2' },
  { key: 'team', label: '团队成员', value: 12, delta: '+1' }
]

export const myTasks = [
  { id: 'T-1024', name: '首页文案优化', status: '进行中', progress: 60, owner: '张三', due: '今天 18:00', project: '产品官网' },
  { id: 'T-1025', name: 'Q4 活动策划', status: '待开始', progress: 0, owner: '李四', due: '明天 10:00', project: '市场活动' },
  { id: 'T-1026', name: 'SEO 关键词优化', status: '待开始', progress: 0, owner: '王五', due: '10-08', project: '产品官网' },
  { id: 'T-1027', name: '品牌规范文档', status: '已完成', progress: 100, owner: '赵六', due: '昨天 17:00', project: '内容运营' },
  { id: 'T-1028', name: '产品图设计稿', status: '阻塞', progress: 35, owner: '陈七', due: '10-05', project: '设计评审' }
]

export const taskDetail = {
  id: 'T-1024', name: '首页文案优化', status: '进行中', progress: 60, owner: '张三', due: '今天 18:00',
  project: '产品官网', tags: ['内容优化', '产品官网'],
  desc: '对首页文案进行优化，重点关注信息密度、CTA 按钮和品牌一致性。需要与设计稿 V2.4 对齐。',
  related: [
    { name: '首页文案 V2.4', kind: 'doc' },
    { name: '产品图设计稿.psd', kind: 'psd' }
  ],
  comments: [
    { who: '李四', at: '10:24', text: '标题建议用更简洁一些，突出核心价值。' },
    { who: '张三', at: '11:02', text: '收到，我按 V2.4 的口径再改一版。' }
  ]
}

export const contents = [
  { name: '首页文案 V2.4', kind: 'doc', status: '已发布', at: '2 分钟前', views: '1.2k', comments: 12 },
  { name: 'Q4 活动方案.docx', kind: 'doc', status: '待审核', at: '15 分钟前', views: '—', comments: 3 },
  { name: '产品图设计稿.psd', kind: 'psd', status: '草稿', at: '32 分钟前', views: '—', comments: 0 },
  { name: '品牌规范.pdf', kind: 'pdf', status: '已发布', at: '1 小时前', views: '340', comments: 5 }
]

export const favoriteRows = [
  { name: '品牌规范.pdf', kind: 'pdf', status: '已发布', at: '1 天前', views: '340', comments: 5 },
  { name: '内容协作规范', kind: 'doc', status: '已发布', at: '3 天前', views: '128', comments: 2 }
]

export const docRows = [
  { name: '首页文案 V2.4', type: 'Markdown', size: '3.4 MB', at: '2 分钟前', owner: '张三', group: '产品官网' },
  { name: '产品介绍文档', type: 'Markdown', size: '2.1 MB', at: '1 小时前', owner: '李四', group: '产品官网' },
  { name: '功能演示视频', type: 'MP4', size: '12.6 MB', at: '3 小时前', owner: '王五', group: '市场活动' },
  { name: '产品图设计稿', type: 'PSD', size: '8.3 MB', at: '5 小时前', owner: '陈六', group: '产品官网' },
  { name: '用户调研报告', type: '文档', size: '1.2 MB', at: '昨天', owner: '李七', group: '内容运营' }
]

export const materialRows = [
  { name: 'banner.png', type: '图片', size: '640 KB', at: '10 分钟前', owner: '王五' },
  { name: 'icon.svg', type: '矢量', size: '4 KB', at: '10 分钟前', owner: '王五' },
  { name: 'logo.png', type: '图片', size: '128 KB', at: '2 小时前', owner: '赵六' },
  { name: '功能演示视频.mp4', type: '视频', size: '12.6 MB', at: '3 小时前', owner: '王五' }
]

export const knowledgeRows = [
  { name: '内容协作规范', tag: '流程', at: '3 天前', owner: '张三' },
  { name: '品牌语气指南', tag: '品牌', at: '1 周前', owner: '李四' },
  { name: '前端组件库说明', tag: '研发', at: '2 周前', owner: '陈六' }
]

export const aiSessions = [
  { name: '首页文案三风格', at: '12:36', preview: '已为你生成了 3 份内容建议' },
  { name: '活动方案头脑风暴', at: '昨天', preview: '列出 6 个可执行方向' }
]

export const aiGenerated = [
  { title: '效率导向', body: '让企业内容生产更高效、更智能。', score: 92 },
  { title: '品牌导向', body: '统一口径，一次写好，处处可用。', score: 87 },
  { title: '增长导向', body: '把每一次发布都变成一次转化机会。', score: 78 }
]
