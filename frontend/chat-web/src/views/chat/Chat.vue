<template>
  <div class="app4">
    <!-- ① 图标栏 -->
    <nav class="rail">
      <div class="rail-logo" :title="userStore.username || 'Chat'" @click="showProfile = true">
        {{ (userStore.username || '?').charAt(0).toUpperCase() }}
      </div>
      <div class="rail-group">
        <button class="rail-btn" :class="{ active: activeTab === 'chat' }" title="消息" @click="activeTab = 'chat'">
          <MessageUnread theme="outline" size="20" />
          <span v-if="chatUnread" class="rail-badge">{{ chatUnread > 99 ? '99+' : chatUnread }}</span>
        </button>
        <button class="rail-btn" :class="{ active: activeTab === 'friend' }" title="通讯录" @click="activeTab = 'friend'">
          <People theme="outline" size="20" />
        </button>
        <button class="rail-btn" :class="{ active: activeTab === 'group' }" title="项目群组" @click="activeTab = 'group'">
          <Windows theme="outline" size="20" />
        </button>
      </div>
      <div class="rail-group wb-group">
        <button v-for="w in WB_LINKS" :key="w.key" class="rail-btn wb-btn" :title="'工作台 · ' + w.name"
                :aria-label="w.name" @click="router.push('/workbench/' + w.key)">
          {{ w.short }}
        </button>
      </div>
      <div class="rail-foot">
        <span class="rail-conn" :class="{ online: connected }" :title="connected ? '已连接' : '已断开'"></span>
        <button class="rail-btn" title="设置资料" @click="showProfile = true">⚙</button>
        <button class="rail-btn quit" title="退出登录" @click="handleLogout">退出</button>
      </div>
    </nav>

    <!-- ② 列表栏 -->
    <aside class="side">
      <header class="side-head">
        <div class="side-title">
          <span>{{ activeTab === 'chat' ? '消息' : activeTab === 'friend' ? '通讯录' : '项目群组' }}</span>
          <span class="side-meta">{{ listSub }}</span>
        </div>
        <div class="side-acts">
          <button v-if="activeTab === 'friend'" class="side-act" title="添加好友" @click="showAddFriend = true">＋</button>
          <button v-else-if="activeTab === 'group'" class="side-act" title="创建群" @click="showCreateGroup = true">＋</button>
        </div>
      </header>

      <div class="side-tabs">
        <button class="stab" :class="{ on: activeTab === 'chat' }" @click="activeTab = 'chat'">全部</button>
        <button class="stab" :class="{ on: activeTab === 'friend' }" @click="activeTab = 'friend'">联系人</button>
        <button class="stab" :class="{ on: activeTab === 'group' }" @click="activeTab = 'group'">群组</button>
        <span class="stab demo">＋ 文件 · 任务 · AI</span>
      </div>

      <div class="side-search">
        <span class="ss-ico">⌕</span>
        <input v-model="listSearch" :id="listSearchId" name="list-search" class="ss-input" type="text"
               :placeholder="listSearchPlaceholder" autocomplete="off" />
        <span v-if="listSearch" class="ss-clear" @click="listSearch = ''">✕</span>
      </div>

      <div class="side-body">
        <!-- 真实会话 -->
        <template v-if="activeTab === 'chat'">
          <div v-for="conv in filteredConversations" :key="conv.id" class="row"
               :class="{ active: currentConversation?.id === conv.id }"
               @click="selectConversation(conv)" @contextmenu.prevent.stop="openConvMenu($event, conv)">
            <div class="ava" :class="{ group: conv.type === 2 }">
              <img v-if="conv.avatar" :src="conv.avatar" alt="" />
              <span v-else>{{ conv.name?.charAt(0)?.toUpperCase() }}</span>
            </div>
            <div class="row-main">
              <div class="row-top">
                <span class="row-name">{{ conv.name }}</span>
                <span class="row-time">{{ formatConvTime(conv.lastMessageTime) }}</span>
              </div>
              <div class="row-last">{{ getConvLastMessage(conv) }}</div>
            </div>
            <div v-if="conv.unreadCount > 0" class="row-badge">{{ conv.unreadCount > 99 ? '99+' : conv.unreadCount }}</div>
          </div>

          <div class="sec">
            <span>项目频道</span><span class="sec-demo">演示</span>
          </div>
          <div v-for="ch in mockChannels" :key="ch.id" class="row is-demo">
            <div class="ava group">{{ ch.name.charAt(0) }}</div>
            <div class="row-main">
              <div class="row-top">
                <span class="row-name">{{ ch.name }}</span>
                <span class="row-time">{{ ch.time }}</span>
              </div>
              <div class="row-last">{{ ch.last }}</div>
            </div>
            <div v-if="ch.unread" class="row-badge">{{ ch.unread }}</div>
          </div>

          <div class="sec">
            <span>最近联系人</span><span class="sec-demo">演示</span>
          </div>
          <div v-for="c in mockContacts" :key="c.id" class="row is-demo">
            <div class="ava">{{ c.name.charAt(0) }}</div>
            <div class="row-main">
              <div class="row-top">
                <span class="row-name">{{ c.name }}</span>
                <span class="row-time">{{ c.time }}</span>
              </div>
              <div class="row-last">{{ c.last }}</div>
            </div>
            <div v-if="c.unread" class="row-badge">{{ c.unread }}</div>
          </div>
        </template>

        <!-- 真实好友 -->
        <template v-else-if="activeTab === 'friend'">
          <div v-for="friend in filteredFriends" :key="friend.friendId" class="row" @click="startChatWithFriend(friend)">
            <div class="ava">
              <img v-if="friend.avatar" :src="friend.avatar" alt="" />
              <span v-else>{{ friend.nickname?.charAt(0)?.toUpperCase() }}</span>
            </div>
            <div class="row-main">
              <div class="row-top"><span class="row-name">{{ friend.nickname || friend.username }}</span></div>
              <div class="row-last">@{{ friend.username }}</div>
            </div>
            <button class="row-act danger" @click.stop="handleRemoveFriend(friend)">删除</button>
          </div>
          <div v-if="filteredFriends.length === 0" class="list-empty">暂无好友</div>
        </template>

        <!-- 真实群组 -->
        <template v-else>
          <div v-for="group in filteredGroups" :key="group.id" class="row" @click="startChatWithGroup(group)">
            <div class="ava group">
              <img v-if="group.avatar" :src="group.avatar" alt="" />
              <span v-else>{{ group.name?.charAt(0)?.toUpperCase() }}</span>
            </div>
            <div class="row-main">
              <div class="row-top"><span class="row-name">{{ group.name }}</span></div>
              <div class="row-last">{{ group.memberCount }} 人</div>
            </div>
            <button class="row-act" @click.stop="openGroupDetail(group)">详情</button>
          </div>
          <div v-if="filteredGroups.length === 0" class="list-empty">暂无群组</div>
        </template>
      </div>
    </aside>

    <!-- ③ 会话主区 -->
    <main class="main">
      <header class="m-head">
        <div class="mh-title">
          <span class="mh-hash">#</span>
          <span class="mh-name">{{ currentConversation?.name || '产品内容' }}</span>
          <span v-if="!currentConversation" class="tag-demo">演示</span>
        </div>
        <div class="mh-sub">
          {{ currentConversation?.type === 2 && groupMembers.length ? groupMembers.length + ' 位成员' : '24 人 · 3 个项目' }}
        </div>
        <div class="mh-stack">
          <span v-for="m in memberStack" :key="m.id" class="stack-ava" :style="{ background: m.color }">{{ m.name.charAt(0) }}</span>
          <span class="stack-more">+8</span>
        </div>
        <div class="mh-acts">
          <button v-if="currentConversation?.type === 2" class="mh-btn" @click="toggleGroupDetail(currentConversation.targetId)">群设置</button>
          <button v-if="currentConversation?.type === 1" class="mh-btn" @click="toggleFriendDetail">详情</button>
          <button class="mh-btn icon" @click="drawerOpen = !drawerOpen" :title="drawerOpen ? '收起详情' : '内容详情'">☰</button>
        </div>
      </header>

      <div class="m-tabs">
        <button v-for="t in convTabs" :key="t.key" class="mtab" :class="{ on: convTab === t.key }" @click="convTab = t.key">
          {{ t.label }}<span v-if="t.count" class="mtab-n">{{ t.count }}</span>
          <span v-if="!t.real" class="tag-demo">演示</span>
        </button>
      </div>

      <div v-if="convTab === 'chat'" class="m-body" ref="messagesRef" @contextmenu.prevent="openBgMenu($event)">
        <!-- 有真实消息走真实 -->
        <template v-if="currentConversation && messages.length">
          <template v-for="(msg, index) in messages" :key="msg.messageId">
            <div v-if="shouldShowTime(msg, index)" class="day-split"><span>{{ formatTimeDivider(msg.timestamp || msg.createTime) }}</span></div>
            <div class="msg" :class="{ self: String(msg.senderId) === String(userStore.userId) }">
              <div v-if="String(msg.senderId) !== String(userStore.userId)" class="msg-ava" @click="showUserInfo(msg.senderId)">
                <img v-if="currentConversation.avatar" :src="currentConversation.avatar" alt="" />
                <span v-else>{{ currentConversation.name?.charAt(0)?.toUpperCase() }}</span>
              </div>
              <div class="msg-main">
                <div class="msg-who">
                  <span class="who-name">{{ msgSenderName(msg) }}</span>
                  <span class="msg-time">{{ formatConvTime(msg.timestamp || msg.createTime) }}</span>
                </div>
                <div class="msg-line">
                  <div class="bubble" @contextmenu.prevent.stop="openMsgMenu($event, msg)">
                    <span v-if="msg.messageType === 'DELETED'" class="b-del">{{ msg.content }}</span>
                    <template v-else-if="msg.messageType === 'IMAGE'">
                      <img v-if="mediaSrc(msg)" :src="mediaSrc(msg)" class="b-img" @click="previewImage(mediaSrc(msg))" />
                      <span v-else class="b-wait">{{ mediaState(msg) === 'err' ? '图片加载失败' : '图片加载中…' }}</span>
                    </template>
                    <a v-else-if="msg.messageType === 'FILE'" class="b-file" @click="downloadFile(msg)">
                      <span class="b-file-nm">{{ (fileRefOf(msg) || {}).name || '文件' }}</span>
                      <span class="b-file-sz">{{ sizeLabel((fileRefOf(msg) || {}).size) }}</span>
                    </a>
                    <span v-else class="b-txt">{{ msg.content }}</span>
                  </div>
                  <span v-if="msg.status === 'FAILED'" class="msg-fail" title="发送失败：这条没有存进服务器">!</span>
                </div>
              </div>
              <div v-if="String(msg.senderId) === String(userStore.userId)" class="msg-ava self">
                {{ userStore.username?.charAt(0)?.toUpperCase() }}
              </div>
            </div>
          </template>
        </template>

        <!-- 没有真实会话时，用演示线程把版式铺开 -->
        <div v-else class="demo-thread">
          <div class="day-split"><span>九月廿二 · 周二</span></div>
          <div class="msg">
            <div class="msg-ava" style="background:#2b6be8">张</div>
            <div class="msg-main">
              <div class="msg-who">张三 <span class="msg-time">21:08</span></div>
              <div class="msg-line"><div class="bubble"><span class="b-txt">我已经更新了首页的文案，版本是 V2.4，具体内容见附件。</span></div></div>
              <div class="file-card">
                <div class="fc-top">
                  <span class="fc-ico">MD</span>
                  <div class="fc-meta">
                    <div class="fc-name">{{ mockFileMessage.name }}</div>
                    <div class="fc-sub">{{ mockFileMessage.size }} · {{ mockFileMessage.kind }} · {{ mockFileMessage.updated }}</div>
                  </div>
                  <span class="tag-demo">演示</span>
                </div>
                <div class="fc-acts">
                  <button class="fc-btn" @click="drawerOpen = true">查看内容</button>
                  <button class="fc-btn">创建任务</button>
                  <button class="fc-btn icon">···</button>
                </div>
              </div>
            </div>
          </div>
          <div class="msg">
            <div class="msg-ava" style="background:#1f9d55">李</div>
            <div class="msg-main">
              <div class="msg-who">李四 <span class="msg-time">21:11</span></div>
              <div class="msg-line"><div class="bubble"><span class="b-txt"><span class="mention">@张三</span> 这个版本的首页文案感觉还可以再优化一下，第二段感觉有点长，需要再压缩一下。</span></div></div>
            </div>
          </div>
          <div class="msg">
            <div class="msg-ava ai">AI</div>
            <div class="msg-main">
              <div class="msg-who">AI 助手 <span class="msg-time">21:12</span></div>
              <div class="ai-card">
                <div class="ac-head"><span class="ac-title">内容优化建议</span><span class="tag-demo">演示</span></div>
                <ol class="ac-list"><li v-for="(s, i) in mockAiMessage.items" :key="i">{{ s }}</li></ol>
                <div class="ac-acts"><button class="ac-btn primary">优化内容</button><button class="ac-btn">查看分析</button></div>
              </div>
            </div>
          </div>
          <div class="msg">
            <div class="msg-ava" style="background:#e08a1e">王</div>
            <div class="msg-main">
              <div class="msg-who">王五 <span class="msg-time">21:15</span></div>
              <div class="msg-line"><div class="bubble"><span class="b-txt">我同意李四的观点，建议我们再加一个数据支撑，这样会更有说服力。</span></div></div>
            </div>
          </div>
          <div class="msg self">
            <div class="msg-main">
              <div class="msg-who"><span class="who-name">{{ userStore.username || '我' }}</span><span class="msg-time">21:22</span></div>
              <div class="msg-line"><div class="bubble"><span class="b-txt"><span class="mention">@AI 助手</span> 帮我生成 3 个不同风格的首页文案版本，保持品牌调性一致。</span></div></div>
            </div>
            <div class="msg-ava self">{{ (userStore.username || '我').charAt(0).toUpperCase() }}</div>
          </div>
        </div>
      </div>

      <!-- 文件 / 任务 / AI结果：后端没有列表接口，只把版式摆出来 -->
      <div v-else class="m-panel">
        <div class="panel-demo">
          <span class="tag-demo big">演示</span>
          <p>「{{ convTabs.find(t => t.key === convTab)?.label }}」还没有后端接口，这里只有版式。</p>
        </div>
      </div>

      <footer class="m-input" @dragover.prevent="isDragging = true" @dragleave.prevent="isDragging = false" @drop.prevent="handleDrop">
        <div v-if="isDragging" class="drop-mask"><div class="drop-ico">📎</div><div>松开鼠标上传文件</div></div>
        <textarea id="chat-message-input" v-model="inputMessage" name="message" class="area"
                  placeholder="输入消息，或使用 / 触发 AI 功能…"
                  @keydown.enter.exact.prevent="sendMessage" @contextmenu.prevent.stop="openInputMenu($event)"
                  @paste="handlePaste" :disabled="!connected || !currentConversation"></textarea>
        <div class="bar">
          <div class="bar-tools" @mouseenter="onEmojiEnter" @mouseleave="onEmojiLeave">
            <button class="tool" title="文件" @click="$refs.fileInput.click()"><FolderUpload theme="outline" size="17" /></button>
            <button class="tool" title="图片" @click="$refs.imageInput.click()"><PictureOne theme="outline" size="17" /></button>
            <div class="tool-wrap">
              <button class="tool" title="表情"><MessageEmoji theme="outline" size="17" /></button>
              <div v-if="showEmojiPicker" class="emoji-pop" @mouseenter="onEmojiEnter" @mouseleave="onEmojiLeave">
                <div class="emoji-grid">
                  <span v-for="item in emojiList" :key="item.e" class="emoji-cell" @click="insertEmoji(item.e)"
                        @mouseenter="hoveredEmoji = item" @mouseleave="hoveredEmoji = null">{{ item.e }}</span>
                </div>
                <div class="emoji-hint">{{ hoveredEmoji ? hoveredEmoji.e + ' ' + hoveredEmoji.l : '选择表情' }}</div>
              </div>
            </div>
            <button class="tool at" title="提及">@</button>
            <button class="tool ai" title="AI 结果">✦</button>
            <input ref="imageInput" id="image-upload" type="file" accept="image/*" style="display:none" @change="handleImageUpload" />
            <input ref="fileInput" id="file-upload" type="file" style="display:none" @change="handleFileUpload" />
          </div>
          <div class="bar-right">
            <span v-if="!connected" class="bar-warn">连接已断开，正在重连…</span>
            <span v-else-if="!currentConversation" class="bar-warn">选择一个会话后才能发送</span>
            <button class="send" @click="sendMessage" :disabled="!connected || !currentConversation || !inputMessage.trim()">发送</button>
          </div>
        </div>
      </footer>
    </main>

    <!-- ④ 右侧内容详情抽屉 -->
    <aside v-if="drawerOpen" class="drawer">
      <div class="dw-tabs">
        <button class="dwt" :class="{ on: dwTab === 'doc' }" @click="dwTab = 'doc'">内容详情</button>
        <button class="dwt" :class="{ on: dwTab === 'member' }" @click="dwTab = 'member'">成员</button>
        <button class="dw-close" @click="drawerOpen = false" title="收起">✕</button>
      </div>

      <div v-if="dwTab === 'doc'" class="dw-body">
        <div class="doc-card">
          <span class="dc-ico">MD</span>
          <div class="dc-main">
            <div class="dc-title">{{ docDetail.title }}<span class="tag-demo">演示</span></div>
            <div class="dc-sub">{{ docDetail.type }} · {{ docDetail.space }} · {{ docDetail.version }}</div>
          </div>
          <span class="dc-status">{{ docDetail.status }}</span>
        </div>
        <div class="doc-owner">
          <span class="do-ava" style="background:#2b6be8">{{ docDetail.owner.charAt(0) }}</span>
          <div><div class="do-name">{{ docDetail.owner }}</div><div class="do-sub">创建于 {{ docDetail.createdAt }}</div></div>
        </div>
        <div class="doc-facts">
          <span>最后修改 {{ docDetail.updatedAt }}</span>
          <span class="dot">·</span><span>👁 {{ docDetail.views }}</span>
          <span class="dot">·</span><span>💬 {{ docDetail.comments }}</span>
        </div>

        <div class="dw-sec">内容预览</div>
        <div class="preview">
          <div class="pv-title" v-html="docPreview.title.replace('\n', '<br>')"></div>
          <div class="pv-body">{{ docPreview.body }}</div>
          <div class="pv-cta">{{ docPreview.cta }}</div>
        </div>

        <div class="dw-sec">关联任务<span class="dw-sec-n">{{ docTasks.filter(t => !t.done).length }} 项待办</span></div>
        <div v-for="t in docTasks" :key="t.id" class="task">
          <span class="tk-box" :class="{ done: t.done }">{{ t.done ? '✓' : '' }}</span>
          <span class="tk-text" :class="{ done: t.done }">{{ t.text }}</span>
          <span class="tk-owner">{{ t.owner }}</span><span class="tk-due">{{ t.due }}</span>
        </div>

        <div class="dw-sec">内容分析<span class="dw-sec-n">AI · 2 分钟前</span></div>
        <div class="scores">
          <div v-for="s in docScores" :key="s.label" class="score">
            <svg viewBox="0 0 36 36" class="ring">
              <circle cx="18" cy="18" r="15.5" class="ring-bg" />
              <circle cx="18" cy="18" r="15.5" class="ring-fg"
                      :stroke="s.color" :stroke-dasharray="97.4 * s.value / 100 + ' 97.4'" />
            </svg>
            <div class="sc-num">{{ s.value }}</div>
            <div class="sc-label">{{ s.label }}</div>
          </div>
        </div>

        <div class="dw-sec">相关内容</div>
        <div v-for="r in docRelated" :key="r.name" class="rel">
          <span class="rel-ico" :class="r.type">{{ r.type.toUpperCase() }}</span>
          <div><div class="rel-name">{{ r.name }}</div><div class="rel-size">{{ r.size }}</div></div>
          <span class="rel-caret">⌄</span>
        </div>
      </div>

      <div v-else class="dw-body">
        <div class="dw-sec">群成员<span class="dw-sec-n">{{ groupMembers.length }} 人</span></div>
        <div v-if="!groupMembers.length" class="list-empty">选择一个群聊会话后这里会有真实成员</div>
        <div v-for="m in groupMembers" :key="m.userId" class="mem" @click="showUserInfo(m.userId)">
          <span class="mem-ava">{{ (m.nickname || m.username || ('U' + m.userId)).charAt(0).toUpperCase() }}</span>
          <span class="mem-name">{{ m.nickname || m.username || ('用户' + m.userId) }}</span>
          <span v-if="m.role === 2" class="mem-role">群主</span>
          <span v-else-if="m.role === 1" class="mem-role">管理员</span>
        </div>
      </div>
    </aside>


    <!-- 群设置面板（第三栏） -->
    <div v-if="showGroupDetail && selectedGroup" class="group-panel">
      <div class="group-panel-header">
        <span class="group-panel-title">群设置</span>
        <button class="btn btn-link btn-sm" @click="showGroupDetail = false">✕</button>
      </div>
      <div class="group-panel-body">
        <div class="group-detail-header">
          <div class="avatar s40">{{ selectedGroup.name?.charAt(0)?.toUpperCase() }}</div>
          <div>
            <h4>{{ selectedGroup.name }}</h4>
            <p>群成员: {{ selectedGroup.memberCount }}人</p>
          </div>
        </div>

        <div class="divider"></div>

        <div class="group-actions">
          <button class="btn btn-primary btn-sm" @click="openAddMembers">邀请成员</button>
          <button v-if="String(selectedGroup.ownerId) === String(userStore.userId)" class="btn btn-danger btn-sm" @click="handleDissolveGroup">解散群聊</button>
          <button v-else class="btn btn-danger btn-sm" @click="handleLeaveGroup">退出群</button>
        </div>

        <div class="divider"></div>

        <div class="group-member-header">
          <h4>群成员</h4>
          <div class="input-wrap group-member-search">
            <span class="input-ico">⌕</span>
            <input
              v-model="groupMemberSearchText"
              id="member-search-inline"
              name="memberSearchInline"
              class="input"
              type="text"
              placeholder="搜索成员..."
              autocomplete="off"
            />
            <span v-if="groupMemberSearchText" class="input-clear" @click="groupMemberSearchText = ''">✕</span>
          </div>
        </div>
        <div class="group-member-list">
          <div v-for="member in filteredGroupMembers" :key="member.userId" class="group-member-item">
            <div class="avatar s32 member-avatar-btn" @click="showMemberInfo(member)">{{ getMemberNameSync(member.userId)?.charAt(0)?.toUpperCase() || '?' }}</div>
            <span class="member-name">{{ getMemberNameSync(member.userId) || '用户 ' + member.userId }}</span>
            <span v-if="member.role === 2" class="tag tag-warning">群主</span>
            <span v-else-if="member.role === 1" class="tag tag-success">管理员</span>
            <div
              v-if="canRemoveMember(member)"
              class="member-remove-wrap"
              @click="handleRemoveMember(member)"
            >
              <Minus theme="outline" size="14" class="member-remove-icon" />
              <span class="nb-tooltip">移出群聊</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 消息操作菜单 -->
    <div
      v-if="msgMenuVisible"
      class="msg-context-menu"
      :style="{ left: msgMenuX + 'px', top: msgMenuY + 'px' }"
    >
      <div class="menu-item" @click="copyMessage">复制</div>
      <div v-if="canDeleteMsg(selectedMsg)" class="menu-item danger" @click="handleDeleteMessage">撤回</div>
    </div>

    <!-- 背景右键菜单（清屏） -->
    <div
      v-if="bgMenuVisible"
      class="msg-context-menu"
      :style="{ left: bgMenuX + 'px', top: bgMenuY + 'px' }"
    >
      <template v-if="currentConversation && isConversationCleared(currentConversation.id)">
        <div class="menu-item" @click="restoreScreen">恢复显示</div>
      </template>
      <template v-else>
        <div class="menu-item" @click="clearScreen">清屏</div>
      </template>
    </div>

    <!-- 会话右键菜单 -->
    <div
      v-if="convMenuVisible"
      class="conv-context-menu"
      :style="{ left: convMenuX + 'px', top: convMenuY + 'px' }"
    >
      <div class="conv-menu-head">
        <span class="conv-menu-title">{{ selectedConv?.name }}</span>
      </div>
      <div class="conv-menu-list">
        <div class="conv-menu-item" @click="handleConvTop">
          <Pin theme="outline" size="16" />
          <span>置顶</span>
        </div>
        <div class="conv-menu-item" @click="handleConvUnread">
          <MessageUnread theme="outline" size="16" />
          <span>标为未读</span>
        </div>
        <div class="conv-menu-item" @click="handleConvMute">
          <Mute theme="outline" size="16" />
          <span>消息免打扰</span>
        </div>
        <div class="conv-menu-item" @click="handleConvWindow">
          <Windows theme="outline" size="16" />
          <span>独立窗口显示</span>
        </div>
        <div class="conv-menu-item" @click="handleConvHide">
          <PreviewClose theme="outline" size="16" />
          <span>不显示</span>
        </div>
        <div class="conv-menu-divider"></div>
        <div class="conv-menu-item danger" @click="handleConvDelete">
          <Delete theme="outline" size="16" />
          <span>删除</span>
        </div>
      </div>
    </div>

    <!-- 输入框右键菜单（复制/粘贴/剪切） -->
    <div
      v-if="inputMenuVisible"
      class="conv-context-menu"
      :style="{ left: inputMenuX + 'px', top: inputMenuY + 'px' }"
    >
      <div class="conv-menu-list">
        <div v-if="inputHasSelection" class="conv-menu-item" @click="handleInputCopy">
          <Copy theme="outline" size="16" />
          <span>复制</span>
        </div>
        <div class="conv-menu-item" @click="handleInputPaste">
          <Clipboard theme="outline" size="16" />
          <span>粘贴</span>
        </div>
        <div v-if="inputHasSelection" class="conv-menu-item" @click="handleInputCut">
          <Scissors theme="outline" size="16" />
          <span>剪切</span>
        </div>
      </div>
    </div>

    <!-- 单聊详情面板 -->
    <div v-if="showFriendDetail && selectedFriend" class="group-panel">
      <div class="group-panel-header">
        <span class="group-panel-title">好友信息</span>
        <button class="btn btn-link btn-sm" @click="showFriendDetail = false">✕</button>
      </div>
      <div class="group-panel-body">
        <div class="group-detail-header">
          <div class="avatar s60">{{ selectedFriend.name?.charAt(0)?.toUpperCase() }}</div>
          <div>
            <h4>{{ selectedFriend.name }}</h4>
            <p style="color: var(--nb-dim); font-size: 13px;">单聊</p>
          </div>
        </div>

        <div class="divider"></div>

        <div class="group-actions">
          <button class="btn btn-primary btn-sm" @click="convertToGroup">邀请成员建群</button>
        </div>

        <div class="divider"></div>

        <div class="group-member-header">
          <h4>成员</h4>
        </div>
        <div class="group-member-list">
          <div class="group-member-item">
            <div class="avatar s32">{{ userStore.username?.charAt(0)?.toUpperCase() }}</div>
            <span class="member-name">{{ userStore.username }}（我）</span>
          </div>
          <div class="group-member-item">
            <div class="avatar s32">{{ selectedFriend.name?.charAt(0)?.toUpperCase() }}</div>
            <span class="member-name">{{ selectedFriend.name }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 添加好友：复用「添加用户」的组织树选择器（旧的关键词搜索弹窗已删） -->
    <AddMembersModal :open="showAddFriend" mode="friend" :org="orgData" :groups="groups" :online="onlineIds"
                     :exclude-ids="friendExcludeIds" :group-members-cache="groupMemberCache"
                     @close="showAddFriend = false" @add="onAddFriendsSubmit" @expand-group="onExpandGroup" />

        <CreateGroupModal :open="showCreateGroup" :candidates="orgCandidates"
                        @close="showCreateGroup = false" @submit="onCreateGroupSubmit" />

    <AddMembersModal :open="showAddMembers" :org="orgData" :groups="groups" :online="onlineIds"
                     :exclude-ids="groupMemberIds" :group-members-cache="groupMemberCache"
                     @close="showAddMembers = false" @add="onAddMembersSubmit" @expand-group="onExpandGroup" />

    <!-- 邀请成员对话框 -->
    <div v-if="showInviteMember" class="modal-overlay" @click.self="closeInviteModal">
      <div class="modal invite-dialog">
        <div class="modal-head">
          <div class="invite-dlg-head">
            <div class="invite-dlg-title">{{ isConvertToGroup ? '邀请成员建群' : '邀请成员' }}</div>
            <div class="invite-dlg-kicker">{{ isConvertToGroup ? 'CREATE GROUP · 选择成员创建群聊' : 'INVITE MEMBER · 群成员接入协议' }}</div>
          </div>
          <button class="btn btn-link btn-sm" @click="closeInviteModal">✕</button>
        </div>

        <div class="modal-body">
          <div class="invite-scope">
            <!-- 搜索框：图标 + 输入 + SCAN 按钮一体 -->
            <div class="invite-search-box">
              <span class="invite-s-ico">⌕</span>
              <input
                id="invite-user-search"
                v-model="inviteUserId"
                name="inviteUserId"
                class="invite-s-input"
                placeholder="用户ID / 昵称 / 用户名，多个ID用逗号分隔"
                @keyup.enter="searchInviteUsers"
                @input="onInviteIdInput"
              />
              <button class="invite-s-btn" :class="{ loading: inviteSearching }" @click="searchInviteUsers">
                {{ inviteSearching ? '···' : 'SCAN' }}
              </button>
            </div>

            <div v-if="inviteSearchResults.length > 0" class="invite-results">
              <div v-for="user in inviteSearchResults" :key="user.id" class="invite-item">
                <div class="invite-avatar">
                  <div class="avatar s36">{{ (user.nickname || user.username)?.charAt(0)?.toUpperCase() }}</div>
                </div>
                <div class="invite-info">
                  <div class="invite-name">{{ user.nickname || user.username }}</div>
                  <div class="invite-meta">ID:{{ user.id }} · @{{ user.username }}</div>
                </div>
                <span v-if="isUserInGroup(user)" class="invite-tag">已在群中</span>
                <span v-else-if="invitedIds.has(user.id)" class="invite-tag done">已邀请 ✓</span>
                <button v-else class="invite-btn" @click="inviteSingleUser(user)">＋ 邀请</button>
              </div>
            </div>
            <div v-else-if="inviteSearched" class="invite-empty">
              <span class="invite-empty-ico">✕</span>
              // NO SIGNAL · 未找到匹配用户
            </div>
            <div v-else class="invite-hint">
              输入用户ID / 昵称 / 用户名开始扫描
            </div>
          </div>
        </div>

        <div class="modal-foot">
          <div class="invite-footer">
            <button class="invite-ghost" @click="showInviteMember = false">取消</button>
            <button class="invite-primary" @click="handleInviteMember">全部邀请</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 设置：个人信息 / 账号 / 通知 / 安全 / 外观 -->
    <SettingsModal :open="showProfile" @close="showProfile = false" @saved="onSettingsSaved" />

    <!-- 成员信息弹框 -->
    <div v-if="selectedMember" class="modal-overlay" @click.self="selectedMember = null">
      <div class="modal member-info-modal">
        <div class="modal-head">
          <div>
            <div class="modal-title">成员信息</div>
            <div class="modal-kicker">MEMBER INFO · 群成员详情</div>
          </div>
          <button class="btn btn-link btn-sm" @click="selectedMember = null">✕</button>
        </div>
        <div class="modal-body">
          <div class="member-info-card">
            <div class="avatar s72">{{ getMemberNameSync(selectedMember.userId)?.charAt(0)?.toUpperCase() || '?' }}</div>
            <div class="member-info-detail">
              <div class="member-info-name">{{ getMemberNameSync(selectedMember.userId) || '用户 ' + selectedMember.userId }}</div>
              <div class="member-info-meta">ID: {{ selectedMember.userId }}</div>
              <span v-if="selectedMember.role === 2" class="tag tag-warning">群主</span>
              <span v-else-if="selectedMember.role === 1" class="tag tag-success">管理员</span>
              <span v-else class="tag tag-info">成员</span>
            </div>
          </div>
          <div class="member-info-fields">
            <div class="member-info-row">
              <span class="member-info-label">所在地</span>
              <span class="member-info-value">{{ memberProfile.location || '—' }}</span>
            </div>
            <div class="member-info-row">
              <span class="member-info-label">备注</span>
              <span class="member-info-value">{{ memberProfile.remark || '—' }}</span>
            </div>
            <div class="member-info-row">
              <span class="member-info-label">电话</span>
              <span class="member-info-value">{{ memberProfile.phone || '—' }}</span>
            </div>
          </div>
        </div>
        <div class="modal-foot">
          <button class="btn btn-ghost" @click="handleShareMember">分享</button>
          <button class="btn btn-ghost" @click="handleCallMember">音视频通话</button>
          <button class="btn btn-primary" @click="handleSendMessage(selectedMember)">发消息</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { useWebSocket } from '../../websocket/client'
import { useTokenValidation } from '../../composables/useTokenValidation'
import { notifyDesktop } from '../../config'
import { getConversationList, createConversation, clearUnread, deleteConversation } from '../../api/conversation'
import { getFriendList, addFriend, removeFriend, checkFriend } from '../../api/friend'
import { createGroup, getMyGroups, getGroupMembers, inviteMembers, removeMember, leaveGroup, dissolveGroup } from '../../api/group'
import { getUserProfile, searchUser, searchUsersByKeyword, updateProfile, getMe, getOrg, getOnline } from '../../api/user'
import { uploadFile, fileObjectUrl, parseFileRef, previewOf } from '../../api/file'
import CreateGroupModal from '../../components/CreateGroupModal.vue'
import AddMembersModal from '../../components/AddMembersModal.vue'
import SettingsModal from '../../components/SettingsModal.vue'
import { toast, confirmBox } from '../../utils/ui'
import { Minus, PictureOne, FolderUpload, MessageEmoji, Scissors, Mail, MicrophoneOne, People, History, Down, Pin, MessageUnread, Mute, Windows, PreviewClose, Delete, Copy, Clipboard } from '@icon-park/vue-next'
import {
  mockChannels, mockContacts, mockFileMessage, mockAiMessage,
  convTabs, memberStack, docDetail, docPreview, docTasks, docScores, docRelated
} from '../../mock/workbench'

const router = useRouter()

// 工作台入口（静态壳分区，数据未接后端，界面里带"演示"标）
const WB_LINKS = [
  { key: 'home', name: '首页看板', short: '首' },
  { key: 'tasks', name: '我的任务', short: '任' },
  { key: 'docs', name: '项目文档', short: '文' },
  { key: 'ai', name: 'AI 助手', short: 'AI' }
]
const userStore = useUserStore()
const { connected, connect, disconnect, send, sendWhenConnected, onMessage } = useWebSocket()
const { isTokenValid, validateToken, redirectToLogin } = useTokenValidation()

const messagesRef = ref(null)
const inputMessage = ref('')
const showEmojiPicker = ref(false)
const hoveredEmoji = ref(null)
const isDragging = ref(false)
let emojiHideTimer = null

function onEmojiEnter() {
  clearTimeout(emojiHideTimer)
  showEmojiPicker.value = true
}

function onEmojiLeave() {
  clearTimeout(emojiHideTimer)
  emojiHideTimer = setTimeout(() => {
    showEmojiPicker.value = false
  }, 200)
}

const emojiList = [
  { e: '😀', l: '开心' }, { e: '😂', l: '笑哭' }, { e: '🤣', l: '爆笑' }, { e: '😊', l: '微笑' }, { e: '😍', l: '花痴' },
  { e: '🥰', l: '喜欢' }, { e: '😘', l: '飞吻' }, { e: '😜', l: '调皮' }, { e: '🤪', l: '搞怪' }, { e: '😎', l: '酷' },
  { e: '🥳', l: '庆祝' }, { e: '🤩', l: '哇塞' }, { e: '😇', l: '天使' }, { e: '🤗', l: '拥抱' }, { e: '🤔', l: '思考' },
  { e: '😏', l: '坏笑' }, { e: '😌', l: '放松' }, { e: '😴', l: '困了' }, { e: '🤤', l: '流口水' }, { e: '😋', l: '好吃' },
  { e: '😛', l: '吐舌' }, { e: '🤭', l: '偷笑' }, { e: '🤫', l: '嘘' }, { e: '🤥', l: '说谎' }, { e: '😶', l: '无语' },
  { e: '😐', l: '面无表情' }, { e: '😑', l: '无语' }, { e: '😬', l: '尴尬' }, { e: '🙄', l: '翻白眼' }, { e: '😮', l: '惊讶' },
  { e: '😲', l: '震惊' }, { e: '🤯', l: '炸裂' }, { e: '😳', l: '害羞' }, { e: '🥺', l: '委屈' }, { e: '😢', l: '难过' },
  { e: '😭', l: '大哭' }, { e: '😤', l: '生气' }, { e: '😠', l: '愤怒' }, { e: '😡', l: '暴怒' }, { e: '🤬', l: '骂人' },
  { e: '😈', l: '恶魔' }, { e: '👿', l: '坏蛋' }, { e: '💀', l: '骷髅' }, { e: '☠️', l: '危险' }, { e: '💩', l: '便便' },
  { e: '🤡', l: '小丑' }, { e: '👹', l: '妖怪' }, { e: '👺', l: '天狗' }, { e: '👻', l: '幽灵' }, { e: '👽', l: '外星人' },
  { e: '👾', l: '游戏' }, { e: '🤖', l: '机器人' }, { e: '🎃', l: '万圣节' }, { e: '😺', l: '猫咪' }, { e: '😸', l: '开心猫' },
  { e: '😻', l: '爱心猫' }, { e: '😼', l: '坏笑猫' }, { e: '😽', l: '亲亲猫' }, { e: '🙀', l: '惊吓猫' }, { e: '😿', l: '伤心猫' },
  { e: '😹', l: '笑哭猫' }, { e: '👍', l: '赞' }, { e: '👎', l: '踩' }, { e: '👏', l: '鼓掌' }, { e: '🙌', l: '举手' },
  { e: '🤝', l: '握手' }, { e: '🙏', l: '祈祷' }, { e: '✌️', l: '胜利' }, { e: '🤞', l: '祈求好运' }, { e: '🤟', l: '爱你' },
  { e: '🤘', l: '摇滚' }, { e: '👌', l: 'OK' }, { e: '🤏', l: '一点点' }, { e: '👈', l: '左' }, { e: '👉', l: '右' },
  { e: '👆', l: '上' }, { e: '👇', l: '下' }, { e: '☝️', l: '上面' }, { e: '✋', l: '停' }, { e: '🤚', l: '举手' },
  { e: '🖐', l: '手掌' }, { e: '🖖', l: '瓦肯礼' }, { e: '👋', l: '挥手' }, { e: '🤙', l: '打电话' }, { e: '💪', l: '强壮' },
  { e: '🦾', l: '机械臂' }, { e: '❤️', l: '红心' }, { e: '🧡', l: '橙心' }, { e: '💛', l: '黄心' }, { e: '💚', l: '绿心' },
  { e: '💙', l: '蓝心' }, { e: '💜', l: '紫心' }, { e: '🖤', l: '黑心' }, { e: '🤍', l: '白心' }, { e: '🤎', l: '棕心' },
  { e: '💔', l: '心碎' }, { e: '❣️', l: '爱心' }, { e: '💕', l: '两颗心' }, { e: '💞', l: '心动' }, { e: '💓', l: '心跳' },
  { e: '💗', l: '增长的爱' }, { e: '💖', l: '闪亮的爱' }, { e: '💘', l: '丘比特' }, { e: '💝', l: '礼物心' }, { e: '🔥', l: '火' },
  { e: '⭐', l: '星星' }, { e: '🌟', l: '闪星' }, { e: '✨', l: '闪光' }, { e: '💫', l: '流星' }, { e: '🎉', l: '庆祝' },
  { e: '🎊', l: '彩球' }, { e: '🏆', l: '奖杯' }, { e: '💯', l: '满分' }, { e: '💢', l: '愤怒' }, { e: '💥', l: '爆炸' },
  { e: '💦', l: '水滴' }, { e: '💨', l: '吹气' }
]
const currentConversation = ref(null)
const conversations = ref([])
const messages = ref([])
const friends = ref([])
const groups = ref([])
const activeTab = ref(localStorage.getItem('chat_activeTab') || 'chat')
const searchText = ref('')
const friendSearchText = ref('')
const groupSearchText = ref('')

// ---- 四栏骨架：列表栏只有一个搜索框和一个标题，按当前页签代理到各自的 ref ----
const listSearch = computed({
  get: () => activeTab.value === 'chat' ? searchText.value
    : activeTab.value === 'friend' ? friendSearchText.value : groupSearchText.value,
  set: (v) => {
    if (activeTab.value === 'chat') searchText.value = v
    else if (activeTab.value === 'friend') friendSearchText.value = v
    else groupSearchText.value = v
  }
})
const listSearchId = computed(() => `list-search-${activeTab.value}`)
const listSearchPlaceholder = computed(() =>
  activeTab.value === 'chat' ? '搜索会话…' : activeTab.value === 'friend' ? '搜索好友…' : '搜索群组…')
const listTitle = computed(() =>
  activeTab.value === 'chat' ? '消息' : activeTab.value === 'friend' ? '好友' : '群组')
const listSub = computed(() => {
  if (activeTab.value === 'chat') return `${filteredConversations.value.length} 个会话`
  if (activeTab.value === 'friend') return `${filteredFriends.value.length} 位好友`
  return `${filteredGroups.value.length} 个群`
})
const chatUnread = computed(() => conversations.value.reduce((n, c) => n + (c.unreadCount || 0), 0))
const headSub = computed(() => {
  const conv = currentConversation.value
  if (!conv) return ''
  if (conv.type === 2) return groupMembers.value.length ? `${groupMembers.value.length} 位成员` : '群聊'
  return '私聊'
})

// 会话页签与右侧抽屉：文件/任务/AI结果 三块还没有接口，只用于铺开版式
const convTab = ref('chat')
const drawerOpen = ref(true)
const dwTab = ref('doc')

// 添加好友（用 AddMembersModal 的组织树选择器）
const showAddFriend = ref(false)

// 创建群
const showCreateGroup = ref(false)

// 群详情
const showGroupDetail = ref(false)
const selectedGroup = ref(null)
const groupMembers = ref([])
const groupMemberSearchText = ref('')
const filteredGroupMembers = computed(() => {
  const q = groupMemberSearchText.value.trim().toLowerCase()
  if (!q) return groupMembers.value
  return groupMembers.value.filter(m => {
    const name = (getMemberNameSync(m.userId) || '').toLowerCase()
    return name.includes(q) || String(m.userId).includes(q)
  })
})
const showInviteMember = ref(false)
const inviteUserId = ref('')
const inviteSearchResults = ref([])
const inviteSearching = ref(false)
const inviteSearched = ref(false)
const invitedIds = ref(new Set())
const isConvertToGroup = ref(false) // 是否处于「单聊转群聊」模式
let inviteSearchTimer = null

// 设置弹窗
const showProfile = ref(false)

// 单聊详情面板
const showFriendDetail = ref(false)
const selectedFriend = ref(null)

// 成员信息弹框
const selectedMember = ref(null)
const memberProfile = ref({})

// 群成员搜索
async function showMemberInfo(member) {
  selectedMember.value = member
  memberProfile.value = {}
  try {
    const profile = await getUserProfile(member.userId)
    if (profile && !profile.error) {
      memberProfile.value = profile
    }
  } catch (e) {
    // ignore
  }
  // Mock：后端字段暂无数据时填充占位
  if (!memberProfile.value.location) memberProfile.value.location = '北京市海淀区'
  if (!memberProfile.value.phone) memberProfile.value.phone = '138****8888'
  if (!memberProfile.value.remark) memberProfile.value.remark = '这个人很懒，什么都没写'
}

// 从消息头像点击：根据 senderId 查找群成员信息，非群聊则创建最小对象
function showUserInfo(senderId) {
  const member = groupMembers.value.find(m => String(m.userId) === String(senderId))
  if (member) {
    showMemberInfo(member)
  } else {
    showMemberInfo({ userId: senderId, role: 0 })
  }
}

async function handleSendMessage(member) {
  if (!member) return
  selectedMember.value = null
  await startChatWithFriend({ friendId: member.userId, nickname: getMemberNameSync(member.userId) })
}

function handleShareMember() {
  if (!selectedMember.value) return
  const name = getMemberNameSync(selectedMember.value.userId) || '用户 ' + selectedMember.value.userId
  const text = `[名片] ${name} (ID: ${selectedMember.value.userId})`
  navigator.clipboard.writeText(text).then(() => {
    toast('已复制成员名片', 'success')
  }).catch(() => {
    toast('复制失败', 'error')
  })
}

function handleCallMember() {
  toast('音视频通话功能开发中', 'info')
}

// 消息右键菜单
// 会话右键菜单
const convMenuVisible = ref(false)
const convMenuX = ref(0)
const convMenuY = ref(0)
const selectedConv = ref(null)

// 输入框右键菜单
const inputMenuVisible = ref(false)
const inputMenuX = ref(0)
const inputMenuY = ref(0)
const inputHasSelection = ref(false)

const msgMenuVisible = ref(false)
const msgMenuX = ref(0)
const msgMenuY = ref(0)
const selectedMsg = ref(null)

// 背景右键菜单（清屏）
const bgMenuVisible = ref(false)
const bgMenuX = ref(0)
const bgMenuY = ref(0)

// 已清屏的会话（本地标记，仅影响自己这边的显示，对方不受影响）
// 存储格式：localStorage 'chat_cleared_conversations' = JSON数组
const clearedConversations = ref(new Set(loadClearedConversations()))

function loadClearedConversations() {
  try {
    const raw = localStorage.getItem('chat_cleared_conversations')
    return raw ? JSON.parse(raw) : []
  } catch (e) {
    return []
  }
}

function saveClearedConversations() {
  try {
    localStorage.setItem('chat_cleared_conversations', JSON.stringify([...clearedConversations.value]))
  } catch (e) {
    // ignore
  }
}

function isConversationCleared(convId) {
  return clearedConversations.value.has(String(convId))
}

// 标记会话已清屏
function markConversationCleared(convId) {
  clearedConversations.value.add(String(convId))
  saveClearedConversations()
}

// 取消清屏标记（恢复历史消息显示）
function unmarkConversationCleared(convId) {
  clearedConversations.value.delete(String(convId))
  saveClearedConversations()
}

// 过滤会话
const filteredConversations = computed(() => {
  const list = conversations.value.filter(c => !c._hidden)
  if (!searchText.value) return list
  const keyword = searchText.value.toLowerCase()
  return list.filter(c => c.name?.toLowerCase().includes(keyword))
})

// 过滤好友
const filteredFriends = computed(() => {
  if (!friendSearchText.value) return friends.value
  const keyword = friendSearchText.value.toLowerCase()
  return friends.value.filter(f =>
    (f.nickname || '').toLowerCase().includes(keyword) ||
    (f.username || '').toLowerCase().includes(keyword)
  )
})

// 过滤群组
const filteredGroups = computed(() => {
  if (!groupSearchText.value) return groups.value
  const keyword = groupSearchText.value.toLowerCase()
  return groups.value.filter(g => (g.name || '').toLowerCase().includes(keyword))
})

onMounted(async () => {
  // 验证 Token
  if (!validateToken()) {
    redirectToLogin(true)
    return
  }

  if (userStore.accessToken) {
    connect(userStore.accessToken)
  }

  await Promise.all([loadConversations(), loadFriends(), loadGroups(), loadMyName(), loadOrg(), refreshOnline()])

  // 恢复上次打开的会话（等待 WebSocket 连接）
  const savedConvId = localStorage.getItem('chat_currentConversation')
  if (savedConvId) {
    const conv = conversations.value.find(c => String(c.id) === savedConvId)
    if (conv) {
      currentConversation.value = conv
      // 等待连接建立后发送
      sendWhenConnected('LOAD_MESSAGES', { conversationId: String(conv.id), limit: 50 })
        .catch(() => console.warn('Failed to load messages: WebSocket not connected'))
    }
  }

  onMessage((msg) => {
    switch (msg.type) {
      case 'MESSAGE_RECEIVE': {
        const data = msg.data
        // 窗口不在前台或不是当前会话时交给系统通知；网页端 notifyDesktop 是空操作
        if (document.hidden || !currentConversation.value
            || String(data.conversationId) !== String(currentConversation.value.id)) {
          notifyDesktop('收到新消息', String(data.content || ''))
        }
        // 如果是当前会话的消息，追加到列表
        if (currentConversation.value && String(data.conversationId) === String(currentConversation.value.id)) {
          messages.value.push(data)
          ensureMedia(data)
          scrollToBottom()
        }
        // 更新会话列表的最后消息
        const conv = conversations.value.find(c => String(c.id) === String(data.conversationId))
        if (conv) {
          conv.lastMessage = previewOf(data.content)
          conv.lastMessageTime = data.timestamp
        }
        break
      }
      case 'MESSAGE_ACK': {
        const local = pendingSends.get(msg.requestId)
        if (!local) break
        pendingSends.delete(msg.requestId)
        const data = msg.data || {}
        if (data.status === 'SENT' && data.messageId) {
          local.messageId = data.messageId      // 换成服务端 timeuuid，撤回与分页都要用它
          local.status = 'SENT'
        } else {
          local.status = 'FAILED'
        }
        break
      }
      case 'MESSAGE_DELETED': {
        // 别人（或自己另一个窗口）撤回了这条
        const data = msg.data
        const targetMsg = messages.value.find(m => String(m.messageId) === String(data.messageId))
        markRevoked(targetMsg)
        break
      }
      case 'MESSAGE_DELETE_ACK': {
        const pending = pendingRevoke.get(msg.requestId)
        if (!pending) break
        pendingRevoke.delete(msg.requestId)
        if (msg.data && msg.data.status === 'ok') {
          markRevoked(pending)
        } else {
          toast((msg.data && msg.data.error) || '撤回失败', 'error')
        }
        break
      }
      case 'MESSAGE_READ': {
        // 对方已读
        const data = msg.data
        messages.value.forEach(m => {
          if (String(m.senderId) === String(userStore.userId)) {
            m.status = 'READ'
          }
        })
        break
      }
      case 'SYNC_CONVERSATIONS': {
        const data = typeof msg.data === 'string' ? JSON.parse(msg.data) : msg.data
        if (Array.isArray(data) && data.length > 0) {
          // 合并而不是覆盖，保留已有的会话
          const existingIds = new Set(conversations.value.map(c => String(c.id)))
          for (const conv of data) {
            if (!existingIds.has(String(conv.id))) {
              conversations.value.push(conv)
            }
          }
        }
        break
      }
      case 'LOAD_MESSAGES': {
        const data = typeof msg.data === 'string' ? JSON.parse(msg.data) : msg.data
        if (Array.isArray(data)) {
          // 如果当前会话已清屏，忽略历史消息加载（保持清屏状态）
          if (currentConversation.value && isConversationCleared(currentConversation.value.id)) {
            messages.value = []
            return
          }
          messages.value = data.reverse()
          // 只预热最近这段，否则一屏历史里有图就会并发拉回全部原图
          messages.value.slice(-12).forEach(ensureMedia)
          // 更新当前会话的最后消息
          if (messages.value.length > 0 && currentConversation.value) {
            const lastMsg = messages.value[messages.value.length - 1]
            const conv = conversations.value.find(c => String(c.id) === String(currentConversation.value.id))
            if (conv) {
              conv.lastMessage = previewOf(lastMsg.content || '')
              conv.lastMessageTime = lastMsg.createTime || lastMsg.timestamp
            }
          }
          scrollToBottom()
        }
        break
      }
    }
  })
})

onUnmounted(() => {
  disconnect()
})

async function loadConversations() {
  try {
    const list = await getConversationList()
    console.log('Loaded conversations:', list)
    if (!Array.isArray(list) || list.length === 0) {
      console.log('No conversations returned from API')
      return
    }
    // 按会话ID去重
    const seen = new Set()
    conversations.value = list.filter(c => {
      const key = String(c.id)
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
  } catch (e) {
    console.error('Load conversations error:', e)
  }
}

async function loadFriends() {
  try {
    friends.value = await getFriendList()
  } catch (e) {
    console.error('Load friends error:', e)
  }
}

async function loadGroups() {
  try {
    groups.value = await getMyGroups()

    // 更新会话列表中的群名（防御性：后端已返回真实群名，这里兜底）
    groups.value.forEach(group => {
      const conv = conversations.value.find(c =>
        c.type === 2 && String(c.targetId) === String(group.id)
      )
      if (conv && group.name) {
        conv.name = group.name
      }
    })
  } catch (e) {
    console.error('Load groups error:', e)
  }
}

async function selectConversation(conv) {
  currentConversation.value = conv
  messages.value = []
  localStorage.setItem('chat_currentConversation', String(conv.id))
  // 清除未读数
  conv.unreadCount = 0
  clearUnread(String(conv.id))
  send('LOAD_MESSAGES', { conversationId: String(conv.id), limit: 50 })

  // 三栏布局：切换会话时同步群设置栏（默认关闭，用户手动打开）
  if (conv.type === 2) {
    showFriendDetail.value = false
    const group = groups.value.find(g => String(g.id) === String(conv.targetId))
    if (group) {
      selectedGroup.value = group
      showGroupDetail.value = false
      // 静默加载成员数据，以便点击头像时有信息
      try { groupMembers.value = await getGroupMembers(group.id) } catch (e) { /* ignore */ }
    }
  } else {
    showGroupDetail.value = false
    showFriendDetail.value = false
  }
}

async function startChatWithFriend(friend) {
  // 切换到好友会话，收起群设置栏
  showGroupDetail.value = false

  try {
    // 先检查是否已有与该好友的一对一会话
    const existing = conversations.value.find(c =>
      c.type === 1 && String(c.targetId) === String(friend.friendId)
    )
    if (existing) {
      selectConversation(existing)
      activeTab.value = 'chat'
      return
    }

    const result = await createConversation(friend.friendId)
    const convId = result.conversationId

    // 再次检查列表中是否已有该会话（防止并发请求导致重复）
    const duplicate = conversations.value.find(c => String(c.id) === String(convId))
    if (duplicate) {
      selectConversation(duplicate)
      activeTab.value = 'chat'
      return
    }

    const conv = {
      id: convId,
      type: 1,
      name: friend.nickname || friend.username,
      avatar: friend.avatar,
      targetId: friend.friendId,
      lastMessage: ''
    }
    conversations.value.unshift(conv)

    currentConversation.value = conv
    messages.value = []
    activeTab.value = 'chat'
    localStorage.setItem('chat_currentConversation', String(convId))
    localStorage.setItem('chat_activeTab', 'chat')
    send('LOAD_MESSAGES', { conversationId: String(convId), limit: 50 })
  } catch (e) {
    toast('创建会话失败', 'error')
  }
}

async function startChatWithGroup(group) {
  const convId = `g${group.id}`

  let conv = conversations.value.find(c => String(c.id) === convId)
  if (!conv) {
    conv = {
      id: convId,
      type: 2,
      name: group.name || `群 ${group.id}`,
      avatar: group.avatar,
      targetId: group.id,
      lastMessage: ''
    }
    conversations.value.unshift(conv)
  } else {
    // 始终更新群名，确保显示正确的名称
    conv.name = group.name || conv.name || `群 ${group.id}`
  }

  currentConversation.value = conv
  messages.value = []
  activeTab.value = 'chat'
  localStorage.setItem('chat_currentConversation', convId)
  localStorage.setItem('chat_activeTab', 'chat')
  send('LOAD_MESSAGES', { conversationId: convId, limit: 50 })

  // 群设置栏默认关闭，用户手动打开
  selectedGroup.value = group
  showGroupDetail.value = false
  // 静默加载成员数据
  try { groupMembers.value = await getGroupMembers(group.id) } catch (e) { /* ignore */ }
}

function sendMessage() {
  if (!inputMessage.value.trim() || !currentConversation.value || !connected.value) return

  const content = inputMessage.value.trim()
  const convId = String(currentConversation.value.id)
  const messageId = `local-${Date.now()}`
  const reqId = nextReqId('send')

  const sent = send('MESSAGE_SEND', {
    conversationId: convId,
    messageType: 'TEXT',
    content
  }, reqId)

  // 本地立即显示
  const newMsg = {
    messageId,
    conversationId: convId,
    senderId: userStore.userId,
    messageType: 'TEXT',
    content,
    timestamp: Date.now(),
    status: sent ? 'SENT' : 'FAILED'
  }
  messages.value.push(newMsg)
  // 必须存数组里那个响应式代理：改原始对象不会触发重渲染
  if (sent) pendingSends.set(reqId, messages.value[messages.value.length - 1])

  // 如果发送失败，3秒后自动重试
  if (!sent) {
    setTimeout(() => {
      if (newMsg.status === 'FAILED' && connected.value) {
        const retrySent = send('MESSAGE_SEND', {
          conversationId: convId,
          messageType: 'TEXT',
          content
        }, reqId)
        if (retrySent) {
          pendingSends.set(reqId, messages.value.find(m => String(m.messageId) === String(newMsg.messageId)) || newMsg)
          newMsg.status = 'SENT'
        }
      }
    }, 3000)
  }

  // 更新会话列表的最后消息
  const conv = conversations.value.find(c => String(c.id) === convId)
  if (conv) {
    conv.lastMessage = content
    conv.lastMessageTime = Date.now()
  }

  inputMessage.value = ''
  scrollToBottom()
}

// 插入表情
function insertEmoji(emoji) {
  inputMessage.value += emoji
}

// 上传图片（转 base64 发送）
function handleImageUpload(e) {
  const file = e.target.files[0]
  if (!file || !currentConversation.value || !connected.value) return
  e.target.value = ''
  sendImageFile(file)
}

// 拖拽上传
function handleDrop(e) {
  isDragging.value = false
  const files = e.dataTransfer?.files
  if (!files || files.length === 0 || !currentConversation.value || !connected.value) return

  for (const file of files) {
    if (file.type.startsWith('image/')) {
      sendImageFile(file)
    } else {
      sendFile(file)
    }
  }
}

// 粘贴上传（截图 / 复制图片直接粘贴发送）
function handlePaste(e) {
  const items = e.clipboardData?.items
  if (!items || items.length === 0 || !currentConversation.value || !connected.value) return

  // 查找剪贴板中的图片
  let imageFile = null
  let regularFile = null
  for (const item of items) {
    if (item.kind === 'file') {
      const file = item.getAsFile()
      if (!file) continue
      if (file.type.startsWith('image/') && !imageFile) {
        imageFile = file
      } else if (!regularFile) {
        regularFile = file
      }
    }
  }

  if (imageFile) {
    e.preventDefault()
    sendImageFile(imageFile)
  } else if (regularFile) {
    e.preventDefault()
    sendFile(regularFile)
  }
}

// 图片/文件：先 REST 上传拿对象键，WS 帧里只放 JSON 引用
const mediaSrcs = ref({})
const mediaBad = ref({})

function fileRefOf(msg) {
  const ref = parseFileRef(msg.content)
  if (ref) return ref
  const c = String(msg.content || '')
  if (c.startsWith('data:')) {
    // 历史消息：整张图 base64 塞在 content 里
    let meta = {}
    try { meta = JSON.parse(msg.extra || '{}') } catch (e) { /* 老消息没有 extra */ }
    return { fileId: '', dataUrl: c, name: meta.name || '文件', size: meta.size || 0, contentType: c.slice(5, c.indexOf(';')) }
  }
  return null
}

function mediaSrc(msg) {
  const r = fileRefOf(msg)
  if (!r) return ''
  return r.dataUrl || mediaSrcs.value[r.fileId] || ''
}

function mediaState(msg) {
  const r = fileRefOf(msg)
  if (!r) return 'none'
  if (r.dataUrl || mediaSrcs.value[r.fileId]) return 'ready'
  return mediaBad.value[r.fileId] ? 'err' : 'loading'
}

function sizeLabel(n) {
  const v = Number(n)
  if (!v || v < 0) return ''
  if (v < 1024) return v + ' B'
  if (v < 1024 * 1024) return (v / 1024).toFixed(0) + ' KB'
  return (v / 1024 / 1024).toFixed(1) + ' MB'
}

// 只有图片需要提前拉缩略，文件等点开再取
async function ensureMedia(msg) {
  if (msg.messageType !== 'IMAGE') return
  const r = fileRefOf(msg)
  if (!r || !r.fileId || mediaSrcs.value[r.fileId] || mediaBad.value[r.fileId]) return
  try {
    const url = await fileObjectUrl(r.fileId)
    mediaSrcs.value = { ...mediaSrcs.value, [r.fileId]: url }
  } catch (e) {
    mediaBad.value = { ...mediaBad.value, [r.fileId]: true }
  }
}

async function downloadFile(msg) {
  const r = fileRefOf(msg)
  if (!r) return
  const url = r.dataUrl || await fileObjectUrl(r.fileId)
  const a = document.createElement('a')
  a.href = url
  a.download = r.name || '文件'
  a.click()
}

async function sendMediaFile(file, type) {
  if (!currentConversation.value || !connected.value) return
  const convId = String(currentConversation.value.id)
  let info
  try {
    info = await uploadFile(file)
  } catch (e) {
    const why = e?.response?.status === 413 ? '文件超过 20MB' : (e?.message || '上传失败')
    toast(`发送失败：${why}`, 'error')
    return
  }
  const payload = JSON.stringify({
    fileId: info.fileId, name: info.name, size: info.size, contentType: info.contentType
  })
  const reqId = nextReqId('send')
  const sent = send('MESSAGE_SEND', { conversationId: convId, messageType: type, content: payload }, reqId)
  const local = {
    messageId: `local-${Date.now()}`,
    conversationId: convId,
    senderId: userStore.userId,
    messageType: type,
    content: payload,
    timestamp: Date.now(),
    status: sent ? 'SENT' : 'FAILED'
  }
  messages.value.push(local)
  if (sent) pendingSends.set(reqId, messages.value[messages.value.length - 1])
  ensureMedia(local)
  const conv = conversations.value.find(c => String(c.id) === convId)
  if (conv) { conv.lastMessage = previewOf(payload); conv.lastMessageTime = Date.now() }
  scrollToBottom()
}

function sendImageFile(file) {
  sendMediaFile(file, 'IMAGE')
}

// 上传文件（转 base64 发送）
function handleFileUpload(e) {
  const file = e.target.files[0]
  if (!file || !currentConversation.value || !connected.value) return
  e.target.value = ''
  sendFile(file)
}

function sendFile(file) {
  sendMediaFile(file, 'FILE')
}

// 消息操作菜单
// 关闭所有右键菜单
function closeAllMenus() {
  msgMenuVisible.value = false
  bgMenuVisible.value = false
  convMenuVisible.value = false
  inputMenuVisible.value = false
}

function openMsgMenu(event, msg) {
  event.stopPropagation()
  closeAllMenus()

  selectedMsg.value = msg
  // 菜单显示在点击位置附近，确保不超出屏幕
  const x = Math.min(event.clientX, window.innerWidth - 120)
  const y = Math.min(event.clientY, window.innerHeight - 80)
  msgMenuX.value = x
  msgMenuY.value = y
  msgMenuVisible.value = true
}

const REVOKE_WINDOW_MS = 2 * 60 * 1000

function canDeleteMsg(msg) {
  if (!msg || msg.messageType === 'DELETED') return false
  if (String(msg.senderId) !== String(userStore.userId)) return false
  if (!msg.messageId || String(msg.messageId).startsWith('local-')) return false
  const ts = Number(msg.timestamp) || (msg.createTime ? Date.parse(msg.createTime) : 0)
  if (!ts) return false
  // 服务端同样按 2 分钟判；这里只是不把必然失败的入口摆出来
  return Date.now() - ts <= REVOKE_WINDOW_MS
}

const pendingRevoke = new Map()
// requestId -> 本地乐观气泡：靠它把服务端的 timeuuid 换回来，否则刚发的消息带着 local- id 撤不掉
const pendingSends = new Map()
const nextReqId = (p) => `${p}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

function handleDeleteMessage() {
  if (!selectedMsg.value || !currentConversation.value) return
  const msg = selectedMsg.value
  msgMenuVisible.value = false
  // 还没拿到服务端 messageId 的消息（发失败/在途）没有可撤回的对象
  if (!msg.messageId || String(msg.messageId).startsWith('local-')) {
    toast('这条消息还没存到服务器，无法撤回', 'error')
    return
  }
  const reqId = `revoke-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  pendingRevoke.set(reqId, msg)
  send('MESSAGE_DELETE', {
    conversationId: String(currentConversation.value.id),
    messageId: msg.messageId
  }, reqId)
  // 不等 ACK 就把气泡改成"已撤回"，服务端拒绝时界面会一直骗人，所以这里只标记在途
}

function markRevoked(msg) {
  if (!msg) return
  msg.messageType = 'DELETED'
  msg.content = '该消息已撤回'
}

function copyMessage() {
  if (!selectedMsg.value) return
  navigator.clipboard.writeText(selectedMsg.value.content || '')
  toast('已复制', 'success')
  msgMenuVisible.value = false
}

// 背景右键菜单（清屏）
function openBgMenu(event) {
  event.preventDefault()
  event.stopPropagation()
  closeAllMenus()

  const menuWidth = 100
  const menuHeight = 50
  const padding = 10

  let x = event.clientX
  let y = event.clientY

  if (x + menuWidth > window.innerWidth) {
    x = window.innerWidth - menuWidth - padding
  }
  if (y + menuHeight > window.innerHeight) {
    y = window.innerHeight - menuHeight - padding
  }
  if (x < padding) x = padding
  if (y < padding) y = padding

  bgMenuX.value = x
  bgMenuY.value = y
  bgMenuVisible.value = true
}

// 清屏功能：本地标记该会话已清屏，仅隐藏自己的历史消息显示，对方不受影响
function clearScreen() {
  bgMenuVisible.value = false
  if (!currentConversation.value) return
  markConversationCleared(currentConversation.value.id)
  messages.value = []
  toast('已清屏，历史消息已隐藏', 'success')
}

// 恢复显示：取消清屏标记，重新加载历史消息
function restoreScreen() {
  bgMenuVisible.value = false
  if (!currentConversation.value) return
  unmarkConversationCleared(currentConversation.value.id)
  messages.value = []
  send('LOAD_MESSAGES', { conversationId: String(currentConversation.value.id), limit: 50 })
  toast('已恢复显示历史消息', 'success')
}

// 点击空白处关闭菜单
document.addEventListener('click', () => {
  closeAllMenus()
})
document.addEventListener('contextmenu', (e) => {
  // 如果右键点在输入框上，由 openInputMenu 处理；否则关闭所有菜单
  if (!e.target.closest('.chat-textarea')) {
    closeAllMenus()
  }
})

/** 已有好友不再出现在候选里：/friend/add 对重复添加会抛「已经是好友」，不如一开始就不给选 */
const friendExcludeIds = computed(() => friends.value.map(f => f.friendId ?? f.id).filter(Boolean))

async function onAddFriendsSubmit({ ids }) {
  // 后端是直接互加（两边各写一条 status=1），不是请求-同意，所以文案说「已添加」
  let ok = 0
  const failed = []
  for (const id of ids) {
    try { await addFriend(id); ok++ } catch (e) { failed.push(e.response?.data?.error || "未知错误") }
  }
  await loadFriends()
  if (failed.length) toast(`已添加 ${ok} 人，${failed.length} 人失败（${failed[0]}）`, 'warning')
  else toast(`已添加 ${ok} 人为好友`, 'success')
  if (ok) showAddFriend.value = false
}

async function handleRemoveFriend(friend) {
  const ok = await confirmBox({
    message: `确定删除好友 ${friend.nickname || friend.username}？`,
    title: '确认'
  })
  if (!ok) return
  try {
    await removeFriend(friend.friendId)
    toast('已删除', 'success')
    await loadFriends()
  } catch (e) {
    toast(e.response?.data?.error || '删除失败', 'error')
  }
}

// —— 组织架构 / 在线状态：创建群聊与添加用户两个弹窗的数据源 ——
const orgData = ref([])
const onlineIds = ref([])
const showAddMembers = ref(false)

const orgCandidates = computed(() => orgData.value
  .flatMap(d => d.members.map(m => ({ ...m, department: m.department || d.department })))
  .filter(m => String(m.id) !== String(userStore.userId)))
const groupMemberIds = computed(() => groupMembers.value.map(m => String(m.userId)))

async function loadOrg() {
  try {
    orgData.value = await getOrg()
  } catch (e) {
    orgData.value = []
  }
}

async function refreshOnline() {
  try {
    onlineIds.value = await getOnline()
  } catch (e) {
    // Redis 不可用时接口返回空集合，界面按全部离线画，不画假绿点
    onlineIds.value = []
  }
}

async function onCreateGroupSubmit(form) {
  try {
    const group = await createGroup(form.name, null, form.memberIds, {
      announcement: form.announcement, groupType: form.groupType
    })
    toast('群已创建', 'success')
    showCreateGroup.value = false
    await Promise.all([loadGroups(), loadConversations()])
    startChatWithGroup(group)
  } catch (e) {
    toast(e.response?.data?.error || '创建失败', 'error')
  }
}

function openAddMembers() {
  refreshOnline()
  showAddMembers.value = true
}

// 「从群聊添加」页签点开某个群时才去拉成员；成员 DTO 只有 userId，名字靠组织架构补
const groupMemberCache = ref({})
async function onExpandGroup(g) {
  if (!g || groupMemberCache.value[String(g.id)]) return
  try {
    const members = await getGroupMembers(g.id)
    groupMemberCache.value = {
      ...groupMemberCache.value,
      [String(g.id)]: members
        .map(m => orgCandidates.value.find(u => String(u.id) === String(m.userId)))
        .filter(Boolean)
    }
  } catch (e) {
    groupMemberCache.value = { ...groupMemberCache.value, [String(g.id)]: [] }
  }
}

async function onAddMembersSubmit({ ids, sendWelcome, welcome }) {
  if (!selectedGroup.value) return
  try {
    await inviteMembers(selectedGroup.value.id, ids)
    toast(`已添加 ${ids.length} 人`, 'success')
    showAddMembers.value = false
    groupMembers.value = await getGroupMembers(selectedGroup.value.id)
    if (selectedGroup.value) selectedGroup.value.memberCount = groupMembers.value.length
    if (sendWelcome && welcome) {
      const convId = `g${selectedGroup.value.id}`
      const reqId = nextReqId('send')
      const ok = send('MESSAGE_SEND', { conversationId: convId, messageType: 'TEXT', content: welcome }, reqId)
      // 走和本地发消息一样的通道：气泡进列表、ACK 对号、失败能看见
      if (String(currentConversation.value?.id) === convId) {
        messages.value.push({
          messageId: `local-${Date.now()}`, conversationId: convId, senderId: userStore.userId,
          messageType: 'TEXT', content: welcome, timestamp: Date.now(), status: ok ? 'SENT' : 'FAILED'
        })
        if (ok) pendingSends.set(reqId, messages.value[messages.value.length - 1])
        scrollToBottom()
      }
      if (!ok) toast('欢迎消息没发出去：连接不可用', 'error')
    }
  } catch (e) {
    toast(e.response?.data?.error || '添加失败', 'error')
  }
}

// 群设置面板：打开并加载成员（三栏布局第三栏）
async function showGroupPanel(group) {
  selectedGroup.value = group
  showGroupDetail.value = true
  try {
    groupMembers.value = await getGroupMembers(group.id)
    // 预加载成员名称
    const memberIds = groupMembers.value.map(m => m.userId)
    await loadMemberNames(memberIds)
  } catch (e) {
    groupMembers.value = []
  }
}

async function openGroupDetail(group) {
  await showGroupPanel(group)
}

async function openGroupDetailById(groupId) {
  const group = groups.value.find(g => g.id === groupId)
  if (group) {
    await openGroupDetail(group)
  }
}

// 群设置按钮：同一群再次点击收起，否则打开
async function toggleGroupDetail(groupId) {
  if (showGroupDetail.value && selectedGroup.value && String(selectedGroup.value.id) === String(groupId)) {
    showGroupDetail.value = false
    return
  }
  showFriendDetail.value = false
  await openGroupDetailById(groupId)
}

// 单聊详情面板：切换显示
function toggleFriendDetail() {
  showGroupDetail.value = false
  if (showFriendDetail.value) {
    showFriendDetail.value = false
    return
  }
  if (currentConversation.value && currentConversation.value.type === 1) {
    selectedFriend.value = {
      id: currentConversation.value.targetId,
      name: currentConversation.value.name
    }
    showFriendDetail.value = true
  }
}

// 单聊转群聊：打开邀请框，确认后创建群
function convertToGroup() {
  if (!selectedFriend.value) return
  isConvertToGroup.value = true
  showFriendDetail.value = false
  showInviteMember.value = true
}

function isUserInGroup(user) {
  return groupMembers.value.some(m => String(m.userId) === String(user.id))
}

// 输入时防抖搜索
function onInviteIdInput() {
  clearTimeout(inviteSearchTimer)
  inviteSearchTimer = setTimeout(() => {
    searchInviteUsers()
  }, 300)
}

// 根据输入的 ID / 昵称 / 用户名搜索用户，结果显示在下方
async function searchInviteUsers() {
  const raw = (inviteUserId.value || '').trim()
  if (!raw) {
    inviteSearchResults.value = []
    inviteSearched.value = false
    return
  }
  const parts = raw.split(',').map(s => s.trim()).filter(Boolean)
  // 全部为纯数字 → 按 ID 精确查（支持逗号分隔多个）；否则 → 按昵称/用户名模糊搜索
  const allIds = parts.length > 0 && parts.every(s => /^\d+$/.test(s))
  inviteSearching.value = true
  try {
    let results = []
    if (allIds) {
      for (const id of parts.slice(0, 10)) {
        try {
          const user = await getUserProfile(id)
          if (user && !user.error) {
            results.push(user)
          }
        } catch (e) {
          // 该 ID 用户不存在，跳过
        }
      }
    } else {
      const users = await searchUsersByKeyword(raw)
      if (Array.isArray(users)) {
        results = users.slice(0, 20)
      }
    }
    // 去重并过滤掉自己
    const seen = new Set()
    inviteSearchResults.value = results.filter(u => {
      if (String(u.id) === String(userStore.userId)) return false
      if (seen.has(String(u.id))) return false
      seen.add(String(u.id))
      return true
    })
    inviteSearched.value = true
  } finally {
    inviteSearching.value = false
  }
}

// 单独邀请搜索结果中的某位用户
async function inviteSingleUser(user) {
  if (!selectedGroup.value) return
  try {
    await inviteMembers(selectedGroup.value.id, [user.id])
    invitedIds.value.add(user.id)
    toast(`已邀请 ${user.nickname || user.username}`, 'success')
    groupMembers.value = await getGroupMembers(selectedGroup.value.id)
    if (selectedGroup.value) {
      selectedGroup.value.memberCount = groupMembers.value.length
    }
  } catch (e) {
    toast(e.response?.data?.error || '邀请失败', 'error')
  }
}

// 关闭弹窗时重置
function resetInviteDialog() {
  inviteUserId.value = ''
  inviteSearchResults.value = []
  inviteSearched.value = false
  invitedIds.value = new Set()
  isConvertToGroup.value = false
  clearTimeout(inviteSearchTimer)
}

// 点击遮罩关闭邀请弹窗（复用原生模态框）
function closeInviteModal() {
  showInviteMember.value = false
  resetInviteDialog()
}

async function handleInviteMember() {
  // 单聊转群聊模式：创建群并邀请选中的用户
  if (isConvertToGroup.value && selectedFriend.value) {
    const targets = inviteSearchResults.value.filter(u => !invitedIds.value.has(u.id) && !isUserInGroup(u))
    const extraIds = targets.length > 0
      ? targets.map(u => Number(u.id))
      : inviteUserId.value.split(',').map(id => Number(id.trim())).filter(id => !isNaN(id))
    // 好友 ID 始终包含在内
    const friendId = Number(selectedFriend.value.id)
    const memberIds = [...new Set([friendId, ...extraIds])]

    if (memberIds.length <= 1) {
      toast('请至少选择一位成员', 'warning')
      return
    }

    try {
      const friendName = selectedFriend.value.name || '好友'
      const groupName = `${userStore.username}、${friendName}` + (extraIds.length > 0 ? ` 等${memberIds.length}人` : '')
      const group = await createGroup(groupName, null, memberIds)
      toast('群聊已创建', 'success')
      showInviteMember.value = false
      isConvertToGroup.value = false
      resetInviteDialog()
      await loadGroups()
      startChatWithGroup(group)
    } catch (e) {
      toast(e.response?.data?.error || '创建群失败', 'error')
    }
    return
  }

  // 普通群聊邀请模式
  if (!selectedGroup.value) return
  const targets = inviteSearchResults.value.filter(u => !invitedIds.value.has(u.id) && !isUserInGroup(u))
  const ids = targets.length > 0
    ? targets.map(u => u.id)
    : inviteUserId.value.split(',').map(id => Number(id.trim())).filter(id => !isNaN(id))
  if (ids.length === 0) {
    toast('请输入要邀请的用户ID', 'warning')
    return
  }
  try {
    await inviteMembers(selectedGroup.value.id, ids)
    toast('已邀请', 'success')
    targets.forEach(u => invitedIds.value.add(u.id))
    groupMembers.value = await getGroupMembers(selectedGroup.value.id)
    if (selectedGroup.value) {
      selectedGroup.value.memberCount = groupMembers.value.length
    }
  } catch (e) {
    toast(e.response?.data?.error || '邀请失败', 'error')
  }
}

// 当前用户是否有权移出指定成员
function canRemoveMember(member) {
  if (!selectedGroup.value || !member) return false
  // 不能移出自己
  if (String(member.userId) === String(userStore.userId)) return false
  const currentMember = groupMembers.value.find(m => String(m.userId) === String(userStore.userId))
  if (!currentMember) return false
  const myRole = Number(currentMember.role)
  // 群主可移出任何人；管理员只能移出普通成员
  if (myRole === 2) return true
  if (myRole === 1) return Number(member.role) === 0
  return false
}

async function handleRemoveMember(member) {
  if (!selectedGroup.value || !member) return
  const name = getMemberNameSync(member.userId) || '用户 ' + member.userId
  const ok = await confirmBox({
    message: `确定将 ${name} 移出群聊？`,
    title: '移出成员',
    confirmText: '移出',
    cancelText: '取消',
    type: 'warning'
  })
  if (!ok) return
  try {
    await removeMember(selectedGroup.value.id, member.userId)
    toast(`已将 ${name} 移出群聊`, 'success')
    groupMembers.value = await getGroupMembers(selectedGroup.value.id)
    // 同步群成员数量
    if (selectedGroup.value) {
      selectedGroup.value.memberCount = groupMembers.value.length
      const conv = conversations.value.find(c => c.type === 2 && String(c.targetId) === String(selectedGroup.value.id))
      if (conv) {
        conv.memberCount = groupMembers.value.length
      }
    }
  } catch (e) {
    toast(e.response?.data?.error || '移出失败', 'error')
  }
}

async function handleLeaveGroup() {
  if (!selectedGroup.value) return
  const ok = await confirmBox({ message: '确定退出该群？', title: '确认' })
  if (!ok) return
  try {
    await leaveGroup(selectedGroup.value.id)
    toast('已退出', 'success')
    showGroupDetail.value = false
    await loadGroups()
    if (currentConversation.value && currentConversation.value.targetId === selectedGroup.value.id) {
      currentConversation.value = null
      messages.value = []
    }
  } catch (e) {
    toast(e.response?.data?.error || '退出失败', 'error')
  }
}

async function handleDissolveGroup() {
  if (!selectedGroup.value) return
  
  // 检查用户是否登录
  const userId = localStorage.getItem('userId')
  const accessToken = localStorage.getItem('accessToken')
  
  if (!userId || !accessToken) {
    toast('用户未登录，请先登录', 'error')
    router.push('/login')
    return
  }
  
  // 检查用户是否是群主
  if (String(selectedGroup.value.ownerId) !== String(userId)) {
    toast('只有群主可以解散群', 'error')
    return
  }
  
  const ok = await confirmBox({
    message: '解散群聊后，所有成员将被移除，且不可恢复。确定解散？',
    title: '解散群聊',
    type: 'warning'
  })
  if (!ok) return
  try {
    await dissolveGroup(selectedGroup.value.id)
    toast('群聊已解散', 'success')
    showGroupDetail.value = false
    await loadGroups()
    // 移除对应会话
    const convId = `g${selectedGroup.value.id}`
    conversations.value = conversations.value.filter(c => String(c.id) !== convId)
    if (currentConversation.value && String(currentConversation.value.id) === convId) {
      currentConversation.value = null
      messages.value = []
    }
  } catch (e) {
    console.error('dissolveGroup error:', e)
    toast(e.response?.data?.error || e.message || '解散失败', 'error')
  }
}

function onSettingsSaved() {
  // 昵称改了要立刻反映到气泡署名和头像，不重新拉一次就会停在旧值；
  // 不动 userStore.username——那是登录名，WebSocket 连接参数在用
  loadMyName()
}

function handleLogout() {
  localStorage.removeItem('chat_currentConversation')
  localStorage.removeItem('chat_activeTab')
  disconnect()
  userStore.logout()
  router.push('/login')
}

async function handleDeleteConversation(conv) {
  const ok = await confirmBox({
    message: `删除与「${conv.name}」的会话？`,
    title: '删除会话',
    confirmText: '删除',
    cancelText: '取消',
    type: 'warning'
  })
  if (!ok) return
  try {
    await deleteConversation(conv.id)
    // 从列表移除
    conversations.value = conversations.value.filter(c => c.id !== conv.id)
    // 如果删除的是当前打开的会话，清空聊天窗口
    if (currentConversation.value && currentConversation.value.id === conv.id) {
      currentConversation.value = null
      messages.value = []
      localStorage.removeItem('chat_currentConversation')
    }
    toast('已删除会话', 'success')
  } catch (e) {
    toast(e.response?.data?.error || '删除失败', 'error')
  }
}

// ===== 会话右键菜单 =====
function openConvMenu(event, conv) {
  closeAllMenus()

  selectedConv.value = conv
  const x = Math.min(event.clientX, window.innerWidth - 200)
  const y = Math.min(event.clientY, window.innerHeight - 320)
  convMenuX.value = x
  convMenuY.value = y
  convMenuVisible.value = true
}

function handleConvTop() {
  if (!selectedConv.value) return
  const conv = selectedConv.value
  conv._pinned = !conv._pinned
  // 置顶：移到列表最前面
  conversations.value = [
    ...conversations.value.filter(c => c._pinned),
    ...conversations.value.filter(c => !c._pinned)
  ]
  toast(conv._pinned ? '已置顶' : '已取消置顶', 'success')
  convMenuVisible.value = false
}

function handleConvUnread() {
  if (!selectedConv.value) return
  selectedConv.value.unreadCount = (selectedConv.value.unreadCount || 0) + 1 || 1
  toast('已标为未读', 'success')
  convMenuVisible.value = false
}

function handleConvMute() {
  if (!selectedConv.value) return
  selectedConv.value._muted = !selectedConv.value._muted
  toast(selectedConv.value._muted ? '已开启免打扰' : '已关闭免打扰', 'success')
  convMenuVisible.value = false
}

function handleConvWindow() {
  if (!selectedConv.value) return
  toast('独立窗口功能开发中', 'info')
  convMenuVisible.value = false
}

function handleConvHide() {
  if (!selectedConv.value) return
  selectedConv.value._hidden = true
  toast('已隐藏会话，可在设置中恢复', 'success')
  convMenuVisible.value = false
}

async function handleConvDelete() {
  convMenuVisible.value = false
  if (selectedConv.value) {
    await handleDeleteConversation(selectedConv.value)
  }
}

// ===== 输入框右键菜单 =====
function openInputMenu(event) {
  closeAllMenus()

  const textarea = document.querySelector('.chat-textarea')
  inputHasSelection.value = textarea
    ? textarea.selectionStart !== textarea.selectionEnd
    : false

  const x = Math.min(event.clientX, window.innerWidth - 160)
  const y = Math.min(event.clientY, window.innerHeight - 150)
  inputMenuX.value = x
  inputMenuY.value = y
  inputMenuVisible.value = true
}

async function handleInputCopy() {
  const textarea = document.querySelector('.chat-textarea')
  if (textarea) {
    const selected = textarea.value.substring(textarea.selectionStart, textarea.selectionEnd)
    if (selected) {
      await navigator.clipboard.writeText(selected)
      toast('已复制', 'success')
    }
  }
  inputMenuVisible.value = false
}

async function handleInputPaste() {
  try {
    const text = await navigator.clipboard.readText()
    if (text) {
      const textarea = document.querySelector('.chat-textarea')
      if (textarea) {
        const start = textarea.selectionStart
        const end = textarea.selectionEnd
        inputMessage.value = inputMessage.value.substring(0, start) + text + inputMessage.value.substring(end)
        nextTick(() => {
          textarea.selectionStart = textarea.selectionEnd = start + text.length
          textarea.focus()
        })
      }
    }
  } catch (e) {
    toast('无法访问剪贴板', 'error')
  }
  inputMenuVisible.value = false
}

async function handleInputCut() {
  const textarea = document.querySelector('.chat-textarea')
  if (textarea) {
    const selected = textarea.value.substring(textarea.selectionStart, textarea.selectionEnd)
    if (selected) {
      await navigator.clipboard.writeText(selected)
      const start = textarea.selectionStart
      const end = textarea.selectionEnd
      inputMessage.value = inputMessage.value.substring(0, start) + inputMessage.value.substring(end)
      nextTick(() => {
        textarea.selectionStart = textarea.selectionEnd = start
        textarea.focus()
      })
      toast('已剪切', 'success')
    }
  }
  inputMenuVisible.value = false
}

// 会话列表：获取最后消息显示文本
function getConvLastMessage(conv) {
  const msg = conv.lastMessage
  if (!msg) return '暂无消息'
  // 如果是图片类型
  if (msg === '[图片]') return '[图片]'
  // 截断过长文本
  if (msg.length > 30) return msg.substring(0, 30) + '...'
  return msg
}

// 获取群成员名称（同步版本，用于模板）
const memberNames = ref({})
function getMemberNameSync(userId) {
  // 如果已经缓存，直接返回
  if (memberNames.value[userId]) {
    return memberNames.value[userId]
  }

  // 如果是当前用户，返回用户名
  if (String(userId) === String(userStore.userId)) {
    return userStore.username
  }

  // 从好友列表中查找
  const friend = friends.value.find(f => String(f.friendId) === String(userId) || String(f.id) === String(userId))
  if (friend) {
    const name = friend.nickname || friend.username
    memberNames.value[userId] = name
    return name
  }

  // 返回 null，模板会显示 "用户 xxx"
  return null
}

// 消息气泡上的发送者名字：优先昵称，查不到的 id 异步补一次资料，别让满屏都是裸 ID
const myName = ref('')
const pendingNames = new Set()

async function loadMyName() {
  try {
    const me = await getMe()
    myName.value = me?.nickname || me?.username || ''
  } catch (e) {
    // 拿不到资料就退回登录名
  }
}

async function resolveSenderName(id) {
  if (!id || pendingNames.has(String(id))) return
  pendingNames.add(String(id))
  try {
    const u = await getUserProfile(id)
    if (u && !u.error) memberNames.value[id] = u.nickname || u.username
  } catch (e) {
    // 查不到就继续显示 "用户 id"
  }
}

function msgSenderName(msg) {
  if (String(msg.senderId) === String(userStore.userId)) {
    return myName.value || userStore.username || '我'
  }
  return getMemberNameSync(msg.senderId) || msg.senderName || (resolveSenderName(msg.senderId), '用户' + msg.senderId)
}

// 异步加载群成员名称
async function loadMemberNames(memberIds) {
  // 已经在缓存中的跳过
  const idsToLoad = memberIds.filter(id => !memberNames.value[id] && String(id) !== String(userStore.userId))

  // 从好友列表中查找
  idsToLoad.forEach(id => {
    const friend = friends.value.find(f => String(f.friendId) === String(id) || String(f.id) === String(id))
    if (friend) {
      memberNames.value[id] = friend.nickname || friend.username
    }
  })
}

// 会话列表：格式化时间
function formatConvTime(ts) {
  if (!ts) return ''
  const d = typeof ts === 'number' ? new Date(ts) : new Date(ts)
  const now = new Date()
  const isToday = d.toDateString() === now.toDateString()
  if (isToday) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  const yesterday = new Date(now)
  yesterday.setDate(yesterday.getDate() - 1)
  if (d.toDateString() === yesterday.toDateString()) {
    return '昨天'
  }
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}

// 判断是否应该显示时间分组（5分钟内的消息不重复显示时间）
function shouldShowTime(msg, index) {
  if (index === 0) return true

  const currentTime = msg.timestamp || msg.createTime
  const prevTime = messages.value[index - 1].timestamp || messages.value[index - 1].createTime

  if (!currentTime || !prevTime) return true

  const current = new Date(currentTime)
  const prev = new Date(prevTime)

  // 5分钟内的消息不显示时间
  const diffMinutes = (current - prev) / (1000 * 60)
  return diffMinutes >= 5
}

// 格式化时间分组显示
function formatTimeDivider(ts) {
  if (!ts) return ''
  const d = typeof ts === 'number' ? new Date(ts) : new Date(ts)
  const now = new Date()

  // 今天
  if (d.toDateString() === now.toDateString()) {
    return `今天 ${d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
  }

  // 昨天
  const yesterday = new Date(now)
  yesterday.setDate(yesterday.getDate() - 1)
  if (d.toDateString() === yesterday.toDateString()) {
    return `昨天 ${d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
  }

  // 今年
  if (d.getFullYear() === now.getFullYear()) {
    return `${d.getMonth() + 1}月${d.getDate()}日 ${d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
  }

  // 其他年份
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()} ${d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
}

function formatTime(ts) {
  if (!ts) return ''
  const d = typeof ts === 'number' ? new Date(ts) : new Date(ts)
  const now = new Date()
  const isToday = d.toDateString() === now.toDateString()
  if (isToday) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }) + ' ' +
    d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}

function previewImage(url) {
  window.open(url, '_blank')
}
</script>

<style scoped>
/* ===== 布局 + 深色主题（合并，不再由 nebula.css 覆盖） ===== */

.chat-container {
  display: flex;
  height: 100vh;
  background: transparent;
}

/* ---- 左侧边栏 ---- */
.chat-sidebar {
  width: 320px;
  border-right: 1px solid rgba(43, 107, 232, 0.16);
  display: flex;
  flex-direction: column;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(6px);
}

.sidebar-header {
  padding: 7.5px 16px;
  border-bottom: 1px solid rgba(43, 107, 232, 0.14);
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(90deg, rgba(43, 107, 232, 0.06), transparent 70%);
}

.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}

.username {
  font-weight: 500;
  color: var(--nb-text);
}

.sidebar-tabs {
  display: flex;
  border-bottom: 1px solid rgba(43, 107, 232, 0.14);
}

.tab-item {
  flex: 1;
  padding: 10px;
  text-align: center;
  cursor: pointer;
  font-size: 14px;
  letter-spacing: 2px;
  font-weight: 500;
  color: var(--nb-dim);
  transition: all 0.2s;
}

.tab-item:hover {
  background: rgba(43, 107, 232, 0.05);
  color: #b6c2e6;
}

.tab-item.active {
  color: var(--nb-cyan);
  border-bottom: 2px solid var(--nb-cyan);
  text-shadow: 0 0 12px rgba(43, 107, 232, 0.7);
}

.sidebar-actions {
  padding: 12px 16px;
  border-bottom: 1px solid rgba(43, 107, 232, 0.14);
}

.sidebar-action-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sidebar-search-wrap {
  flex: 1;
  min-width: 0;
}

.sidebar-add-btn {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: var(--nb-cyan);
  background: rgba(255, 255, 255, 0.8);
  border: 1px solid rgba(43, 107, 232, 0.4);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
  line-height: 1;
}

.sidebar-add-btn:hover {
  background: rgba(43, 107, 232, 0.1);
  border-color: var(--nb-cyan);
  box-shadow: 0 0 12px rgba(43, 107, 232, 0.3);
}

/* ---- 会话列表 ---- */
.conversation-list {
  flex: 1;
  overflow-y: auto;
}

.conversation-item {
  padding: 8px 16px;
  cursor: pointer;
  border-bottom: 1px solid rgba(43, 107, 232, 0.07);
  display: flex;
  align-items: center;
  gap: 12px;
  transition: background 0.15s;
}

.conversation-item:hover {
  background: rgba(43, 107, 232, 0.05);
}

.conversation-item.active {
  background: linear-gradient(90deg, rgba(43, 107, 232, 0.1), rgba(43, 107, 232, 0.06));
  box-shadow: inset 2px 0 0 var(--nb-cyan);
}

.conv-info {
  flex: 1;
  min-width: 0;
}

.conv-name-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.conv-name {
  font-weight: 500;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 140px;
  color: var(--nb-text);
}

.conv-time {
  font-size: 11px;
  color: var(--nb-dim);
  flex-shrink: 0;
}

.conv-last-msg,
.conv-last {
  font-size: 12px;
  color: var(--nb-dim-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.unread-badge {
  background: linear-gradient(135deg, #ff4d4f, #cf1322);
  color: white;
  font-size: 10px;
  font-weight: 700;
  min-width: 16px;
  height: 16px;
  line-height: 16px;
  border-radius: 8px;
  text-align: center;
  padding: 0 4px;
  box-shadow: 0 0 8px rgba(217, 72, 96, 0.5);
  flex-shrink: 0;
  transform: translateY(1px);
}

.conv-delete {
  opacity: 0;
  color: var(--nb-dim);
  font-size: 14px;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 4px;
  transition: all 0.15s;
  flex-shrink: 0;
}

.conversation-item:hover .conv-delete {
  opacity: 1;
}

.conv-delete:hover {
  color: #2b6be8;
  background: rgba(217, 72, 96, 0.1);
}

.no-data,
.no-messages {
  padding: 40px 16px;
  text-align: center;
  color: var(--nb-dim);
  font-family: 'Share Tech Mono', monospace;
  letter-spacing: 2px;
}

/* ---- 聊天主区域 ---- */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: rgba(255, 255, 255, 0.55);
}

.chat-header {
  height: 56px;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(43, 107, 232, 0.16);
  font-weight: 500;
  background: rgba(255, 255, 255, 0.85);
  display: flex;
  align-items: center;
  color: var(--nb-text);
  letter-spacing: 1px;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  background: transparent;
}

.message-item {
  margin-bottom: 16px;
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.message-item.self {
  flex-direction: row;
  justify-content: flex-end;
}

.msg-avatar {
  flex-shrink: 0;
}

.self-avatar {
  order: 2;
}

.message-wrapper {
  display: flex;
  align-items: center;
  gap: 6px;
  max-width: 65%;
}

.message-body {
  min-width: 60px;
}

.message-sender {
  font-size: 12px;
  color: var(--nb-cyan);
  opacity: 0.8;
  margin-bottom: 4px;
  font-family: 'Rajdhani', 'Microsoft YaHei', sans-serif;
  letter-spacing: 1px;
}

.message-content {
  padding: 8px 14px;
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(43, 107, 232, 0.14);
  color: var(--nb-text);
  word-break: break-word;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.35);
  line-height: 1.5;
  position: relative;
}

.message-item:not(.self) .message-content::before {
  content: '';
  position: absolute;
  left: -12px;
  top: 15px;
  width: 0;
  height: 0;
  border: 6px solid transparent;
  border-right-color: rgba(255, 255, 255, 0.92);
}

.message-item.self .message-content {
  background: linear-gradient(135deg, rgba(43, 107, 232, 0.92), rgba(43, 107, 232, 0.9));
  color: #fff;
  border: none;
  box-shadow: 0 2px 14px rgba(43, 107, 232, 0.25);
}

.message-item.self .message-content::before {
  content: '';
  position: absolute;
  right: -12px;
  top: 15px;
  width: 0;
  height: 0;
  border: 6px solid transparent;
  border-left-color: rgba(43, 107, 232, 0.92);
}

.msg-text {
  white-space: pre-wrap;
  font-size: 14px;
  color: inherit;
}

.message-image {
  max-width: 240px;
  max-height: 240px;
  border-radius: 8px;
  cursor: pointer;
  display: block;
}

.message-content:has(.message-image) {
  padding: 4px;
  background: transparent;
  box-shadow: none;
}

.message-file {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--nb-text);
}

.message-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
}

.message-item.self .message-meta {
  justify-content: flex-end;
}

.message-time {
  font-size: 11px;
  color: var(--nb-dim);
}

.message-status {
  font-size: 11px;
  color: var(--nb-green);
}

.msg-deleted {
  color: var(--nb-dim);
  font-style: italic;
}

.msg-failed-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  background: #d94860;
  color: white;
  border-radius: 4px;
  font-size: 11px;
  font-weight: bold;
  margin-left: 6px;
  flex-shrink: 0;
  cursor: pointer;
  box-shadow: 0 0 8px rgba(217, 72, 96, 0.6);
}

.msg-failed-icon:hover {
  background: #d94860;
}

.message-time-divider {
  text-align: center;
  margin: 20px 0 12px;
}

.message-time-divider span {
  background: rgba(255, 255, 255, 0.9);
  color: var(--nb-dim);
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 5px;
  border: 1px solid rgba(43, 107, 232, 0.12);
  font-family: 'Share Tech Mono', monospace;
}

.msg-context-menu {
  position: fixed;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid rgba(43, 107, 232, 0.2);
  border-radius: 8px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.65), 0 0 20px rgba(43, 107, 232, 0.1);
  backdrop-filter: blur(6px);
  padding: 4px 0;
  z-index: 9999;
  min-width: 100px;
}

.menu-item {
  padding: 8px 16px;
  font-size: 13px;
  cursor: pointer;
  color: var(--nb-text);
  transition: background 0.15s;
  line-height: 1;
}

.menu-item:hover {
  background: rgba(43, 107, 232, 0.08);
}

.menu-item.danger {
  color: #2b6be8;
}

.menu-item.danger:hover {
  background: rgba(217, 72, 96, 0.1);
}

/* ===== 会话右键菜单 ===== */
.conv-context-menu {
  position: fixed;
  background: rgba(255, 255, 255, 0.97);
  border: 1px solid rgba(43, 107, 232, 0.25);
  border-radius: 12px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.6), 0 0 24px rgba(43, 107, 232, 0.08);
  backdrop-filter: blur(8px);
  z-index: 9999;
  min-width: 200px;
  overflow: hidden;
  animation: convMenuIn 0.15s ease-out;
}

@keyframes convMenuIn {
  from { opacity: 0; transform: scale(0.95); }
  to { opacity: 1; transform: none; }
}

.conv-menu-head {
  padding: 12px 16px 8px;
  border-bottom: 1px solid rgba(43, 107, 232, 0.1);
}

.conv-menu-title {
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  letter-spacing: 0.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
  max-width: 180px;
}

.conv-menu-list {
  padding: 4px 0;
}

.conv-menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 16px;
  font-size: 13px;
  color: var(--nb-text);
  cursor: pointer;
  transition: background 0.15s;
  letter-spacing: 0.3px;
  line-height: 1;
}

.conv-menu-item .i-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transform: translateY(-1px);
}

.conv-menu-item span:not(.i-icon) {
  line-height: 1;
}

.conv-menu-item:hover {
  background: rgba(43, 107, 232, 0.07);
}

.conv-menu-item.danger {
  color: #d94860;
}

.conv-menu-item.danger:hover {
  background: rgba(217, 72, 96, 0.08);
}

.conv-menu-divider {
  height: 1px;
  margin: 4px 12px;
  background: rgba(43, 107, 232, 0.1);
}

.chat-input {
  border-top: 1px solid rgba(43, 107, 232, 0.16);
  background: rgba(255, 255, 255, 0.85);
  position: relative;
  display: flex;
  flex-direction: column;
}

/* ---- 拖拽上传覆盖层 ---- */
.drop-overlay {
  position: absolute;
  inset: 0;
  z-index: 50;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: rgba(43, 107, 232, 0.08);
  border: 2px dashed rgba(43, 107, 232, 0.5);
  border-radius: 8px;
  backdrop-filter: blur(4px);
  pointer-events: none;
}
.drop-icon {
  font-size: 36px;
  filter: drop-shadow(0 0 12px rgba(43, 107, 232, 0.6));
}
.drop-text {
  font-family: 'Share Tech Mono', monospace;
  font-size: 13px;
  color: var(--nb-cyan);
  letter-spacing: 2px;
  text-shadow: 0 0 8px rgba(43, 107, 232, 0.5);
}

/* ---- 顶部工具栏 ---- */
.chat-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px 2px;
}

.chat-toolbar-left {
  display: flex;
  align-items: center;
  gap: 1px;
}

.chat-toolbar-right {
  display: flex;
  align-items: center;
}

.chat-icon-wrap {
  position: relative;
}

.chat-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: none;
  background: transparent;
  color: #8a97ab;
  cursor: pointer;
  border-radius: 4px;
  transition: color 0.15s;
  position: relative;
}

.chat-icon-btn:hover {
  color: var(--nb-cyan);
}

/* 自定义气泡提示 */
.chat-tooltip {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 50%;
  transform: translateX(-50%);
  padding: 4px 10px;
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid rgba(43, 107, 232, 0.3);
  border-radius: 6px;
  color: var(--nb-text);
  font-size: 12px;
  white-space: nowrap;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.15s;
  z-index: 60;
  font-family: 'Rajdhani', 'Microsoft YaHei', sans-serif;
  letter-spacing: 0.5px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.4);
}

.chat-tooltip::after {
  content: "";
  position: absolute;
  top: 100%;
  left: 50%;
  transform: translateX(-50%);
  border: 5px solid transparent;
  border-top-color: rgba(43, 107, 232, 0.3);
}

.chat-icon-wrap:hover .chat-tooltip {
  opacity: 1;
}

/* 右侧锚点：气泡向左展开，不超出右边界 */
.chat-tooltip-anchor-right .chat-tooltip {
  left: auto;
  right: 0;
  transform: none;
}

.chat-tooltip-anchor-right .chat-tooltip::after {
  left: auto;
  right: 12px;
  transform: none;
}

/* 表情选择器打开时隐藏气泡 */
.chat-icon-wrap:has(.emoji-picker) .chat-tooltip {
  opacity: 0;
}

.chat-icon-dropdown {
  gap: 1px;
  width: auto;
  padding: 0 6px;
}

.chat-icon-arrow {
  color: var(--nb-dim);
  opacity: 0.6;
}

/* ---- 消息输入区 ---- */
.chat-textarea {
  width: 100%;
  min-height: 100px;
  padding: 8px 12px;
  background: transparent;
  border: none;
  outline: none;
  resize: none;
  color: var(--nb-text);
  font-size: 14px;
  font-family: 'Rajdhani', 'Microsoft YaHei', sans-serif;
  line-height: 1.6;
  box-sizing: border-box;
}

.chat-textarea::placeholder {
  color: var(--nb-dim-2);
}

.chat-textarea:disabled {
  opacity: 0.5;
}

/* ---- 底部发送栏 ---- */
.chat-send-bar {
  display: flex;
  justify-content: flex-end;
  padding: 4px 12px 8px;
}

.chat-send-btn {
  display: inline-flex;
  align-items: center;
  gap: 0;
  padding: 6px 10px 6px 16px;
  border: none;
  border-radius: 4px;
  background: #1890ff;
  color: #fff;
  font-size: 14px;
  font-family: 'Rajdhani', 'Microsoft YaHei', sans-serif;
  cursor: pointer;
  transition: all 0.15s;
  line-height: 1.4;
}

.chat-send-btn:hover:not(:disabled) {
  background: #40a9ff;
}

.chat-send-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.chat-send-divider {
  display: inline-block;
  width: 1px;
  height: 16px;
  background: rgba(255, 255, 255, 0.35);
  margin: 0 6px;
}

.chat-send-arrow {
  color: rgba(255, 255, 255, 0.8);
}

/* ---- 表情选择器 ---- */
.emoji-picker {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 0;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid rgba(43, 107, 232, 0.25);
  border-radius: 10px;
  padding: 14px 18px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.6), 0 0 16px rgba(43, 107, 232, 0.08);
  backdrop-filter: blur(6px);
  z-index: 200;
  min-width: 400px;
}

.emoji-picker::after {
  content: "";
  position: absolute;
  top: 100%;
  left: 8px;
  border: 7px solid transparent;
  border-top-color: rgba(43, 107, 232, 0.25);
}

.emoji-picker::before {
  content: "";
  position: absolute;
  top: calc(100% - 1px);
  left: 8px;
  border: 7px solid transparent;
  border-top-color: rgba(255, 255, 255, 0.96);
  z-index: 1;
}

.emoji-grid {
  display: grid;
  grid-template-columns: repeat(10, 1fr);
  gap: 4px;
  max-height: 200px;
  overflow-y: auto;
  overflow-x: hidden;
}

.emoji-item {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  font-size: 18px;
  cursor: pointer;
  border-radius: 6px;
  transition: background 0.15s;
}

.emoji-item:hover {
  background: rgba(43, 107, 232, 0.1);
}

.emoji-status {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid rgba(43, 107, 232, 0.12);
  min-height: 20px;
  text-align: center;
}

.emoji-status-text {
  font-size: 13px;
  color: var(--nb-text);
  letter-spacing: 0.5px;
}

.emoji-status-hint {
  font-size: 11px;
  color: var(--nb-dim);
  letter-spacing: 1px;
}

.chat-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  color: var(--nb-dim);
  gap: 12px;
  font-family: 'Share Tech Mono', monospace;
  letter-spacing: 2px;
}

.empty-icon {
  font-size: 48px;
}

/* ---- 群详情 ---- */
.group-detail-header {
  display: flex;
  align-items: center;
  gap: 16px;
}

.group-detail-header h3 {
  color: var(--nb-text);
}

.group-detail-header p {
  margin: 0;
  color: var(--nb-dim);
  font-size: 14px;
}

.group-actions {
  display: flex;
  gap: 8px;
}

/* ---- 群成员 ---- */
.group-member-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.group-member-header h4 {
  margin: 0;
  color: var(--nb-text);
  letter-spacing: 2px;
  flex-shrink: 0;
}

.group-member-search {
  flex: 1;
  min-width: 0;
}

.group-member-search .input {
  padding-left: 34px;
  padding-right: 30px;
}

.member-remove-icon {
  flex-shrink: 0;
  opacity: 0.5;
  transition: opacity 0.15s;
  color: #2b6be8;
}

.group-member-item:hover .member-remove-icon {
  opacity: 1;
}

.member-remove-wrap {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  padding: 2px;
}

.nb-tooltip {
  position: absolute;
  top: 50%;
  right: calc(100% + 8px);
  transform: translateY(-50%);
  padding: 4px 10px;
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid rgba(43, 107, 232, 0.3);
  border-radius: 6px;
  color: var(--nb-text);
  font-size: 12px;
  white-space: nowrap;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.15s;
  z-index: 60;
  font-family: 'Rajdhani', 'Microsoft YaHei', sans-serif;
  letter-spacing: 0.5px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.4);
}

.nb-tooltip::after {
  content: "";
  position: absolute;
  left: 100%;
  top: 50%;
  transform: translateY(-50%);
  border: 5px solid transparent;
  border-left-color: rgba(43, 107, 232, 0.3);
}

.member-remove-wrap:hover .nb-tooltip {
  opacity: 1;
}

.group-member-list {
  max-height: 300px;
  overflow-y: auto;
}

.group-member-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid rgba(43, 107, 232, 0.08);
}

.group-member-item:hover {
  background: rgba(43, 107, 232, 0.04);
}

.member-name {
  flex: 1;
  color: var(--nb-text);
}

.member-avatar-btn {
  cursor: pointer;
  transition: box-shadow 0.2s;
}

.member-avatar-btn:hover {
  box-shadow: 0 0 12px rgba(43, 107, 232, 0.5);
}

/* ---- 成员信息弹框 ---- */
.member-info-modal {
  max-width: 340px;
}
.member-info-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 8px 0;
}
.avatar.s72 {
  width: 72px;
  height: 72px;
  font-size: 28px;
}
.member-info-detail {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.member-info-name {
  font-size: 18px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 1px;
}
.member-info-meta {
  font-family: 'Share Tech Mono', monospace;
  font-size: 12px;
  color: var(--nb-dim);
  letter-spacing: 1px;
}
.member-info-fields {
  margin-top: 16px;
  border-top: 1px solid rgba(43, 107, 232, 0.12);
  padding-top: 14px;
}
.member-info-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
}
.member-info-label {
  width: 56px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--nb-dim);
  text-align: right;
}
.member-info-value {
  font-size: 14px;
  color: var(--nb-text);
}

/* ---- 群设置面板（第三栏） ---- */
.group-panel {
  width: 320px;
  border-left: 1px solid rgba(43, 107, 232, 0.16);
  background: rgba(255, 255, 255, 0.88);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  backdrop-filter: blur(6px);
}

.group-panel-header {
  height: 56px;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(43, 107, 232, 0.14);
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 500;
  flex-shrink: 0;
  background: linear-gradient(90deg, rgba(43, 107, 232, 0.06), transparent 70%);
  color: var(--nb-text);
}

.group-panel-title {
  font-size: 14px;
  color: var(--nb-text);
  letter-spacing: 2px;
}

.group-panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
}

/* ---- 创建群 / 成员搜索 ---- */
.selected-members {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.member-search-results {
  margin-top: 10px;
  max-height: 200px;
  overflow-y: auto;
  border: 1px dashed rgba(43, 107, 232, 0.18);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.4);
}

.member-search-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  cursor: pointer;
  color: var(--nb-text);
  transition: background 0.15s;
}

.member-search-item:hover {
  background: rgba(43, 107, 232, 0.07);
}
</style>

<style scoped>
/* ============================================================
   添加好友弹窗（复用邀请成员的搜索框和结果样式）
   ============================================================ */
.search-modal {
  width: var(--invite-width, 480px);
  max-width: 92vw;
}
.search-modal-scope {
  padding: 20px;
}
.modal-kicker {
  font-family: 'Share Tech Mono', monospace;
  font-size: 10px;
  letter-spacing: 3px;
  color: #69788f;
  margin-top: 4px;
}

/* ============================================================
   邀请成员弹窗 · 星环科幻风（NEBULA 风格）
   弹窗通过 teleport 挂载到 body，需使用非 scoped 样式
   ============================================================ */
.invite-dialog {
  position: relative;
  width: var(--invite-width);
  max-width: 92vw;
  background:
    radial-gradient(420px 200px at 85% 8%, rgba(43, 107, 232, 0.18), transparent 60%),
    radial-gradient(360px 220px at 10% 95%, rgba(43, 107, 232, 0.10), transparent 60%),
    linear-gradient(150deg, #ffffff 0%, #ffffff 55%, #f0f3f8 100%);
  border: 1px solid var(--invite-line);
  border-radius: 8px;
  box-shadow:
    0 0 0 1px rgba(43, 107, 232, 0.05),
    0 24px 80px rgba(0, 0, 0, 0.65),
    0 0 60px rgba(43, 107, 232, 0.08),
    inset 0 0 46px rgba(43, 107, 232, 0.03);
  overflow: hidden;
}

/* 四角科技角标 */
.invite-dialog::before,
.invite-dialog::after {
  content: "";
  position: absolute;
  width: 24px;
  height: 24px;
  pointer-events: none;
  z-index: 3;
}
.invite-dialog::before {
  top: 0;
  left: 0;
  border-top: 2px solid var(--invite-cyan);
  border-left: 2px solid var(--invite-cyan);
  border-top-left-radius: 10px;
  filter: drop-shadow(0 0 6px rgba(43, 107, 232, 0.8));
}
.invite-dialog::after {
  bottom: 0;
  right: 0;
  border-bottom: 2px solid var(--invite-magenta);
  border-right: 2px solid var(--invite-magenta);
  border-bottom-right-radius: 10px;
  filter: drop-shadow(0 0 6px rgba(217, 72, 96, 0.8));
}

/* ===== 头部 ===== */
.invite-dialog .modal-head {
  margin: 0;
  padding: 12px;
  border-bottom: 1px solid rgba(43, 107, 232, 0.14);
  background: linear-gradient(90deg, rgba(43, 107, 232, 0.07), transparent 65%);
}
.invite-dlg-title {
  font-family: 'Orbitron', 'Rajdhani', 'Microsoft YaHei', sans-serif;
  font-weight: 700;
  font-size: var(--invite-font-title);
  color: #fff;
  letter-spacing: 3px;
}
.invite-dlg-kicker {
  font-family: 'Share Tech Mono', monospace;
  font-size: var(--invite-font-kicker);
  letter-spacing: 3px;
  color: #69788f;
  margin-top: 4px;
}

/* ===== 主体 ===== */
.invite-dialog .modal-body {
  padding: 0;
}
.invite-scope {
  padding: 20px;
}

/* 搜索框：⌕ 图标 + 输入 + SCAN 按钮一体（参考 NEBULA MART 头部搜索框） */
.invite-search-box {
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid rgba(43, 107, 232, 0.35);
  border-radius: var(--invite-radius-box);
  padding: 5px 6px 5px 18px;
  transition: border-color 0.25s, box-shadow 0.25s;
  position: relative;
}
.invite-search-box:focus-within {
  border-color: var(--invite-cyan);
  box-shadow: 0 0 18px rgba(43, 107, 232, 0.35), inset 0 0 12px rgba(43, 107, 232, 0.05);
}
.invite-s-ico {
  color: var(--invite-cyan);
  font-size: var(--invite-font-icon);
  flex-shrink: 0;
  text-shadow: 0 0 8px rgba(43, 107, 232, 0.9);
}
.invite-s-input {
  flex: 1;
  min-width: 0;
  background: transparent;
  border: none;
  outline: none;
  color: #1b2434;
  font-size: var(--invite-font-input);
  padding: 8px 0;
  letter-spacing: 0.5px;
  font-family: 'Rajdhani', 'Microsoft YaHei', sans-serif;
}
.invite-s-input::placeholder {
  color: #8a97ab;
  letter-spacing: 1px;
}
.invite-s-btn {
  flex-shrink: 0;
  border: none;
  border-radius: 5px;
  background: linear-gradient(90deg, #2b6be8, #2b6be8);
  color: #ffffff;
  font-family: 'Orbitron', 'Share Tech Mono', monospace;
  font-size: var(--invite-font-scan);
  font-weight: 700;
  letter-spacing: 2px;
  padding: 8px 18px;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 0 14px rgba(43, 107, 232, 0.35);
}
.invite-s-btn:hover {
  filter: brightness(1.18);
  box-shadow: 0 0 22px rgba(43, 107, 232, 0.6);
}
.invite-s-btn.loading {
  animation: invite-blink 0.9s steps(2) infinite;
}
@keyframes invite-blink {
  50% { opacity: 0.45; }
}

/* ===== 搜索结果 ===== */
.invite-results {
  margin-top: 14px;
  max-height: 300px;
  overflow-y: auto;
  border: 1px solid rgba(43, 107, 232, 0.14);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.55);
  scrollbar-width: thin;
  scrollbar-color: rgba(43, 107, 232, 0.35) transparent;
}
.invite-results::-webkit-scrollbar {
  width: 6px;
}
.invite-results::-webkit-scrollbar-thumb {
  background: rgba(43, 107, 232, 0.3);
  border-radius: 3px;
}
.invite-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid rgba(43, 107, 232, 0.08);
  transition: background 0.2s;
}
.invite-item:last-child {
  border-bottom: none;
}
.invite-item:hover {
  background: rgba(43, 107, 232, 0.06);
}
.invite-avatar .avatar {
  background: linear-gradient(135deg, var(--invite-cyan), var(--invite-purple));
  color: #ffffff;
  font-weight: 700;
  border: 1px solid rgba(43, 107, 232, 0.5);
  box-shadow: 0 0 10px rgba(43, 107, 232, 0.35);
  width: var(--invite-avatar) !important;
  height: var(--invite-avatar) !important;
  font-size: calc(var(--invite-avatar) / 2.4) !important;
}
.invite-info {
  flex: 1;
  min-width: 0;
}
.invite-name {
  color: #fff;
  font-size: var(--invite-font-name);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.invite-meta {
  font-family: 'Share Tech Mono', monospace;
  font-size: var(--invite-font-meta);
  color: var(--invite-cyan);
  opacity: 0.75;
  letter-spacing: 1px;
  margin-top: 2px;
}
.invite-tag {
  flex-shrink: 0;
  font-size: var(--invite-font-tag);
  padding: 3px 10px;
  border-radius: 5px;
  border: 1px solid rgba(43, 107, 232, 0.35);
  color: #8a97ab;
  font-family: 'Share Tech Mono', monospace;
  letter-spacing: 1px;
  background: rgba(43, 107, 232, 0.06);
}
.invite-tag.done {
  border-color: rgba(31, 157, 85, 0.45);
  color: var(--invite-green);
  background: rgba(31, 157, 85, 0.08);
  text-shadow: 0 0 8px rgba(31, 157, 85, 0.5);
}
.invite-btn {
  flex-shrink: 0;
  border: 1px solid rgba(43, 107, 232, 0.55);
  background: linear-gradient(90deg, rgba(43, 107, 232, 0.18), rgba(43, 107, 232, 0.18));
  color: var(--invite-cyan);
  border-radius: 5px;
  padding: 6px 12px;
  cursor: pointer;
  font-family: 'Share Tech Mono', monospace;
  font-size: var(--invite-font-btn);
  letter-spacing: 1px;
  transition: all 0.2s;
}
.invite-btn:hover {
  box-shadow: 0 0 14px rgba(43, 107, 232, 0.55);
  background: linear-gradient(90deg, var(--invite-cyan), var(--invite-purple));
  color: #03101f;
}

/* 空状态 / 提示 */
.invite-empty,
.invite-hint {
  margin-top: 14px;
  padding: 22px 10px;
  text-align: center;
  color: #8a97ab;
  font-family: 'Share Tech Mono', monospace;
  font-size: var(--invite-font-hint);
  letter-spacing: 2px;
  border: 1px dashed rgba(43, 107, 232, 0.18);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.4);
}
.invite-empty-ico {
  color: var(--invite-cyan);
  margin-right: 6px;
  text-shadow: 0 0 8px rgba(43, 107, 232, 0.7);
}

/* ===== 底部按钮 ===== */
.invite-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
.invite-ghost {
  background: transparent;
  border: 1px solid rgba(217, 72, 96, 0.4);
  color: #2b6be8;
  padding: 9px 22px;
  border-radius: 5px;
  cursor: pointer;
  font-size: var(--invite-font-footer);
  letter-spacing: 2px;
  transition: all 0.2s;
  font-family: 'Rajdhani', 'Microsoft YaHei', sans-serif;
}
.invite-ghost:hover {
  box-shadow: 0 0 14px rgba(217, 72, 96, 0.4);
  background: rgba(217, 72, 96, 0.1);
  color: #fff;
}
.invite-primary {
  border: none;
  background: linear-gradient(90deg, var(--invite-magenta), var(--invite-purple));
  color: #fff;
  padding: 10px 24px;
  border-radius: 5px;
  cursor: pointer;
  font-size: var(--invite-font-footer);
  font-weight: 600;
  letter-spacing: 2px;
  box-shadow: 0 0 20px rgba(217, 72, 96, 0.45);
  transition: all 0.25s;
  font-family: 'Rajdhani', 'Microsoft YaHei', sans-serif;
}
.invite-primary:hover {
  filter: brightness(1.15);
  box-shadow: 0 0 30px rgba(217, 72, 96, 0.7);
}


/* ============================================================
   四栏骨架 · 设计稿图3（浅色蓝）
   栏宽走 --rail-w / --list-w / --drawer-w；
   三栏 chrome 贴视口边、上下无缝、radius 0，只有内容元素带小圆角。
   ============================================================ */
.app4 { display: flex; height: calc(100vh - var(--winbar-h)); flex: 1 1 auto; min-height: 0; background: var(--nb-bg-0); font-size: 14px; }

.tag-demo {
  display: inline-block; margin-left: 6px; padding: 0 5px; border-radius: 3px;
  background: rgba(224, 138, 30, 0.12); color: var(--warn);
  font-size: 10px; line-height: 15px; font-weight: 400; vertical-align: 1px;
}
.tag-demo.big { font-size: 11px; line-height: 20px; }

/* ---- ① 图标栏 ---- */
.rail {
  width: var(--rail-w); flex: 0 0 var(--rail-w); background: #fff;
  border-right: 1px solid var(--nb-line);
  display: flex; flex-direction: column; align-items: center; padding: 12px 0 10px;
}
.rail-logo {
  width: 34px; height: 34px; border-radius: 10px; background: var(--brand); color: #fff;
  display: grid; place-items: center; font-weight: 600; cursor: pointer;
}
.rail-group { display: flex; flex-direction: column; gap: 6px; margin-top: 18px; flex: 1; }
.rail-btn {
  position: relative; width: 38px; height: 36px; border: 0; border-radius: 9px;
  background: transparent; color: var(--nb-dim); display: grid; place-items: center;
  cursor: pointer; font-size: 17px; line-height: 1;
}
.rail-btn:hover { background: var(--nb-bg-3); color: var(--nb-text); }
.rail-btn.quit { font-size: 11px; }
.rail-group.wb-group { margin-top: 6px; padding-top: 8px; border-top: 1px solid var(--nb-line); }
.rail-btn.wb-btn { font-size: 11.5px; font-weight: 600; }
.rail-btn.active { background: var(--brand-soft); color: var(--brand); }
.rail-badge {
  position: absolute; top: 2px; right: 0; min-width: 16px; height: 16px; padding: 0 4px;
  border-radius: 8px; background: var(--danger); color: #fff;
  font-size: 10px; line-height: 16px; text-align: center;
}
.rail-foot { display: flex; flex-direction: column; align-items: center; gap: 8px; }
.rail-conn { width: 8px; height: 8px; border-radius: 50%; background: var(--nb-dim-2); }
.rail-conn.online { background: var(--ok); }

/* ---- ② 列表栏 ---- */
.side {
  width: var(--list-w); flex: 0 0 var(--list-w); background: #fff;
  border-right: 1px solid var(--nb-line); display: flex; flex-direction: column;
}
.side-head { display: flex; align-items: flex-start; padding: 14px 14px 10px; }
.side-title { flex: 1; min-width: 0; }
.side-title > span:first-child { font-size: 16px; font-weight: 600; color: var(--nb-text); }
.side-meta { display: block; margin-top: 2px; font-size: 12px; color: var(--nb-dim); }
.side-acts { display: flex; gap: 6px; }
.side-act {
  width: 26px; height: 26px; border: 1px solid var(--nb-line); border-radius: 6px;
  background: #fff; color: var(--brand); font-size: 15px; line-height: 1; cursor: pointer;
}
.side-act:hover { background: var(--brand-soft); }
.side-tabs { display: flex; gap: 4px; padding: 0 12px 10px; flex-wrap: nowrap; overflow: hidden; }
.stab {
  border: 0; background: transparent; color: var(--nb-dim); font-size: 12px;
  padding: 3px 9px; border-radius: 20px; cursor: pointer; white-space: nowrap; flex: 0 0 auto;
}
.stab:hover { background: var(--nb-bg-3); }
.stab.on { background: var(--brand); color: #fff; }
.stab.demo { cursor: default; color: var(--nb-dim-2); font-size: 11px; background: transparent; padding-left: 4px; }
.side-search {
  display: flex; align-items: center; gap: 6px; height: 32px; margin: 0 12px 8px; padding: 0 10px;
  background: var(--nb-bg-3); border: 1px solid transparent; border-radius: 7px;
}
.side-search:focus-within { background: #fff; border-color: var(--brand-line); }
.ss-ico { color: var(--nb-dim); font-size: 14px; }
.ss-input { flex: 1; min-width: 0; border: 0; outline: none; background: transparent; color: var(--nb-text); font: inherit; font-size: 13px; }
.ss-clear { color: var(--nb-dim); cursor: pointer; font-size: 12px; }
.side-body { flex: 1; overflow-y: auto; padding: 0 8px 10px; }
.sec {
  display: flex; align-items: center; gap: 6px; padding: 12px 8px 6px;
  font-size: 11px; color: var(--nb-dim-2); letter-spacing: .4px;
}
.sec-demo { margin-left: auto; color: rgba(224, 138, 30, .9); font-size: 10px; }
.row {
  position: relative; display: flex; align-items: center; gap: 10px;
  padding: 8px; border-radius: 8px; cursor: pointer;
}
.row:hover { background: var(--nb-bg-3); }
.row.active { background: var(--brand-soft); }
.row.is-demo { opacity: .92; }
.ava {
  width: 38px; height: 38px; flex: 0 0 38px; border-radius: 9px; background: var(--brand);
  color: #fff; display: grid; place-items: center; font-size: 14px; overflow: hidden;
}
.ava.group { background: #4f86f5; border-radius: 11px; }
.ava img { width: 100%; height: 100%; object-fit: cover; }
.row-main { flex: 1; min-width: 0; }
.row-top { display: flex; align-items: baseline; gap: 8px; }
.row-name { flex: 1; min-width: 0; font-size: 14px; color: var(--nb-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row-time { flex: 0 0 auto; font-size: 11px; color: var(--nb-dim-2); }
.row-last { margin-top: 2px; font-size: 12px; color: var(--nb-dim); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row-badge {
  flex: 0 0 auto; min-width: 18px; height: 18px; padding: 0 5px; border-radius: 9px;
  background: var(--danger); color: #fff; font-size: 11px; line-height: 18px; text-align: center;
}
.row-act { border: 1px solid var(--nb-line); background: #fff; color: var(--nb-dim); border-radius: 5px; font-size: 12px; padding: 2px 7px; cursor: pointer; }
.row-act:hover { color: var(--brand); border-color: var(--brand-line); }
.row-act.danger:hover { color: var(--danger); border-color: rgba(217, 72, 96, .4); }
.list-empty { padding: 22px 8px; text-align: center; color: var(--nb-dim-2); font-size: 13px; }

/* ---- ③ 会话主区 ---- */
.main { flex: 1; min-width: 0; display: flex; flex-direction: column; background: #fff; }
.m-head { display: flex; align-items: center; gap: 12px; padding: 12px 18px 10px; border-bottom: 1px solid var(--nb-line); }
.mh-title { display: flex; align-items: center; gap: 4px; min-width: 0; }
.mh-hash { color: var(--nb-dim-2); font-size: 17px; }
.mh-name { font-size: 17px; font-weight: 600; color: var(--nb-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mh-sub { font-size: 12px; color: var(--nb-dim); flex: 1; min-width: 0; }
.mh-stack { display: flex; align-items: center; }
.stack-ava {
  width: 24px; height: 24px; border-radius: 50%; color: #fff; display: grid; place-items: center;
  font-size: 11px; border: 2px solid #fff; margin-left: -7px;
}
.stack-ava:first-child { margin-left: 0; }
.stack-more {
  width: 24px; height: 24px; border-radius: 50%; background: var(--nb-bg-3); color: var(--nb-dim);
  display: grid; place-items: center; font-size: 10px; border: 2px solid #fff; margin-left: -7px;
}
.mh-acts { display: flex; align-items: center; gap: 6px; }
.mh-btn { border: 1px solid var(--nb-line); background: #fff; color: var(--nb-dim); border-radius: 6px; font-size: 12px; padding: 4px 10px; cursor: pointer; }
.mh-btn:hover { color: var(--brand); border-color: var(--brand-line); }
.mh-btn.icon { padding: 4px 8px; }
.m-tabs { display: flex; gap: 18px; padding: 0 18px; border-bottom: 1px solid var(--nb-line); }
.mtab {
  position: relative; border: 0; background: transparent; padding: 9px 0; cursor: pointer;
  font-size: 13px; color: var(--nb-dim);
}
.mtab.on { color: var(--brand); font-weight: 500; }
.mtab.on::after {
  content: ""; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px;
  background: var(--brand); border-radius: 2px;
}
.mtab-n { margin-left: 4px; color: var(--nb-dim-2); font-size: 11px; }
.m-body { flex: 1; overflow-y: auto; padding: 16px 18px; background: var(--nb-bg-0); }
.day-split { text-align: center; margin: 6px 0 16px; }
.day-split span {
  display: inline-block; padding: 2px 10px; border-radius: 10px; background: #fff;
  border: 1px solid var(--nb-line); color: var(--nb-dim-2); font-size: 11px;
}
.msg { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 16px; }
/* 自己的消息：DOM 里已经是「气泡在前、头像在后」，所以只靠右对齐，
   不能再 row-reverse —— 两次反转会把头像甩到左边。 */
.msg.self { justify-content: flex-end; }
.msg-ava {
  width: 34px; height: 34px; flex: 0 0 34px; border-radius: 50%; background: var(--brand); color: #fff;
  display: grid; place-items: center; font-size: 13px; overflow: hidden; cursor: pointer;
}
.msg-ava.ai { background: linear-gradient(135deg, #4f86f5, #7c5cf0); cursor: default; }
.msg-ava.self { background: var(--brand-strong); cursor: default; }
.msg-ava img { width: 100%; height: 100%; object-fit: cover; }
.msg-main { min-width: 0; max-width: 70%; }
.msg-who { margin: 0 0 5px 2px; font-size: 12px; color: var(--nb-dim); }
.msg.self .msg-who { text-align: right; margin: 0 2px 5px 0; }
.who-name { color: var(--nb-dim); }
.msg-time { margin-left: 8px; color: var(--nb-dim-2); font-size: 11px; }
.msg-line { display: flex; align-items: flex-start; gap: 6px; }
.msg.self .msg-line { flex-direction: row-reverse; }
.bubble {
  padding: 10px 13px; border-radius: 10px; background: #fff; border: 1px solid var(--nb-line);
  color: var(--nb-text); line-height: 1.65; word-break: break-word; white-space: pre-wrap;
  box-shadow: var(--shadow-1);
}
.msg.self .bubble { background: var(--brand); border-color: var(--brand); color: #fff; }
.mention { color: var(--brand); font-weight: 500; }
.msg.self .mention { color: #dbe7ff; }
.b-del { color: var(--nb-dim-2); font-style: italic; }
.b-img { display: block; max-width: 280px; border-radius: 8px; cursor: zoom-in; }
.b-wait { display: inline-block; min-width: 96px; font-size: 12px; color: var(--nb-dim); }
.b-file { display: inline-flex; align-items: center; gap: 8px; max-width: 240px; padding: 6px 10px; border: 1px solid var(--nb-line); border-radius: 8px; background: var(--nb-bg-3); color: inherit; text-decoration: none; cursor: pointer; }
.b-file:hover { border-color: var(--brand); }
.b-file-nm { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 13px; }
.b-file-sz { flex: none; font-size: 11px; color: var(--nb-dim); }
.msg-fail {
  width: 16px; height: 16px; flex: 0 0 16px; margin-top: 6px; border-radius: 50%;
  background: var(--danger); color: #fff; font-size: 11px; line-height: 16px; text-align: center; cursor: help;
}
.file-card {
  margin-top: 8px; width: 320px; max-width: 100%; background: #fff;
  border: 1px solid var(--nb-line); border-radius: 10px; overflow: hidden; box-shadow: var(--shadow-1);
}
.fc-top { display: flex; align-items: center; gap: 10px; padding: 11px 12px 9px; }
.fc-ico {
  width: 34px; height: 34px; flex: 0 0 34px; border-radius: 8px; background: var(--brand-soft);
  color: var(--brand); display: grid; place-items: center; font-size: 10px; font-weight: 600;
}
.fc-meta { flex: 1; min-width: 0; }
.fc-name { font-size: 13px; color: var(--nb-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.fc-sub { font-size: 11px; color: var(--nb-dim); margin-top: 2px; }
.fc-acts { display: flex; gap: 8px; padding: 0 12px 11px; }
.fc-btn { border: 1px solid var(--nb-line); background: #fff; color: var(--nb-dim); border-radius: 6px; font-size: 12px; padding: 4px 12px; cursor: pointer; }
.fc-btn:hover { color: var(--brand); border-color: var(--brand-line); }
.fc-btn.icon { padding: 4px 9px; }
.ai-card {
  margin-top: 2px; width: 380px; max-width: 100%; padding: 12px 14px;
  background: linear-gradient(180deg, #f4f8ff, #fff); border: 1px solid var(--brand-line);
  border-radius: 10px; box-shadow: var(--shadow-1);
}
.ac-head { display: flex; align-items: center; }
.ac-title { font-size: 13px; font-weight: 600; color: var(--brand-strong); }
.ac-list { margin: 8px 0 10px; padding-left: 18px; color: var(--nb-text); font-size: 13px; line-height: 1.9; }
.ac-acts { display: flex; gap: 8px; }
.ac-btn { border: 1px solid var(--nb-line); background: #fff; color: var(--nb-dim); border-radius: 6px; font-size: 12px; padding: 4px 12px; cursor: pointer; }
.ac-btn.primary { background: var(--brand); border-color: var(--brand); color: #fff; }
.ac-btn.primary:hover { background: var(--brand-strong); }
/* 消息列不设 max-width、也不居中：左右间隙就是 .m-body 的 18px padding，恒定不变，
   窗口多宽就铺多宽。别再给 .demo-thread 加回 900px 上限——那会在宽屏右边留一大片死白。 */
.m-panel { flex: 1; display: grid; place-content: center; justify-items: center; background: var(--nb-bg-0); color: var(--nb-dim); }
.panel-demo { text-align: center; font-size: 13px; }

/* ---- 输入区 ---- */
.m-input { position: relative; border-top: 1px solid var(--nb-line); background: #fff; padding: 10px 16px 12px; }
.drop-mask {
  position: absolute; inset: 0; z-index: 2; display: grid; place-content: center; justify-items: center; gap: 6px;
  background: rgba(43, 107, 232, .08); border: 1px dashed var(--brand-line); color: var(--brand);
}
.area {
  width: 100%; min-height: 56px; max-height: 160px; border: 0; outline: none; resize: none;
  font: inherit; color: var(--nb-text); background: transparent;
}
.bar { display: flex; align-items: center; justify-content: space-between; border-top: 1px solid var(--nb-line); padding-top: 8px; }
.bar-tools { display: flex; align-items: center; gap: 2px; }
.tool-wrap { position: relative; display: flex; }
.tool {
  width: 30px; height: 30px; border: 0; border-radius: 7px; background: transparent;
  color: var(--nb-dim); display: grid; place-items: center; cursor: pointer; font-size: 15px;
}
.tool:hover { background: var(--brand-soft); color: var(--brand); }
.tool.at { font-size: 17px; }
.tool.ai { color: var(--brand); }
.emoji-pop {
  position: absolute; bottom: 34px; left: 0; z-index: 20; width: 272px; padding: 8px;
  background: #fff; border: 1px solid var(--nb-line); border-radius: 10px; box-shadow: var(--shadow-2);
}
/* 轨道原来缩在弹框里 8px（.emoji-pop 的 padding），右边露出一条缝、看着像滚动条跑偏。
   把格子的盒子往外推 8px 让轨道贴住边框，再用 padding 把格子本身留回 8px 不贴条 */
.emoji-grid { display: grid; grid-template-columns: repeat(8, 1fr); gap: 2px; margin-right: -8px; padding-right: 8px; }
/* 全局那条 42% 的深色 thumb 是为了让消息区的条在灰底上看得见；表情弹框是白底，
   满高的深色 thumb 会糊成一块板，这里按他要求回到原来的半透明蓝 */
.emoji-grid::-webkit-scrollbar-thumb { background: rgba(43, 107, 232, .22); }
.emoji-grid::-webkit-scrollbar-thumb:hover { background: rgba(43, 107, 232, .45); }
.emoji-cell { padding: 3px; font-size: 18px; text-align: center; cursor: pointer; border-radius: 4px; }
.emoji-cell:hover { background: var(--brand-soft); }
.emoji-hint { margin-top: 4px; padding-top: 6px; border-top: 1px solid var(--nb-line); font-size: 11px; color: var(--nb-dim); }
.bar-right { display: flex; align-items: center; gap: 10px; }
.bar-warn { font-size: 12px; color: var(--warn); }
.send {
  height: 30px; min-width: 78px; padding: 0 16px; border: 0; border-radius: 7px;
  background: var(--brand); color: #fff; font-size: 13px; letter-spacing: 2px; cursor: pointer;
}
.send:hover { background: var(--brand-strong); }
.send:disabled { background: #c3ccdb; cursor: not-allowed; }

/* ---- ④ 右侧抽屉 ---- */
.drawer {
  width: var(--drawer-w); flex: 0 0 var(--drawer-w); background: #fff;
  border-left: 1px solid var(--nb-line); display: flex; flex-direction: column;
}
.dw-tabs { display: flex; align-items: center; gap: 18px; padding: 12px 16px 7px; border-bottom: 1px solid var(--nb-line); }
.dwt { position: relative; border: 0; background: transparent; padding: 0 0 10px; font-size: 13px; color: var(--nb-dim); cursor: pointer; }
.dwt.on { color: var(--nb-text); font-weight: 500; }
.dwt.on::after { content: ""; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px; background: var(--brand); }
.dw-close { margin-left: auto; border: 0; background: transparent; color: var(--nb-dim-2); cursor: pointer; font-size: 13px; padding: 0 0 10px; }
/* 上下内边距从 14/20 收到 12/8：内容 714px、窗口 820 时可用只有 700，
   原来那 6px（页签加高后变 13px）的溢出会撑出一条几乎满高的 thumb，看着像根不能动的僵尸条。
   窗口再矮就真的装不下了，那时出滚动条是对的。 */
.dw-body { flex: 1; overflow-y: auto; padding: 12px 16px 8px; }
.doc-card { display: flex; align-items: center; gap: 10px; }
.dc-ico { width: 38px; height: 44px; flex: 0 0 38px; border-radius: 6px; background: var(--brand); color: #fff; display: grid; place-items: center; font-size: 11px; font-weight: 600; }
.dc-main { flex: 1; min-width: 0; }
.dc-title { font-size: 14px; font-weight: 600; color: var(--nb-text); }
.dc-sub { font-size: 11px; color: var(--nb-dim); margin-top: 3px; }
.dc-status { flex: 0 0 auto; font-size: 11px; color: var(--ok); background: rgba(31, 157, 85, .1); border-radius: 4px; padding: 2px 6px; }
.doc-owner { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
.do-ava { width: 26px; height: 26px; border-radius: 50%; color: #fff; display: grid; place-items: center; font-size: 11px; }
.do-name { font-size: 12px; color: var(--nb-text); }
.do-sub { font-size: 11px; color: var(--nb-dim-2); }
.doc-facts { margin-top: 8px; font-size: 11px; color: var(--nb-dim); display: flex; flex-wrap: wrap; gap: 4px; }
.doc-facts .dot { color: var(--nb-dim-2); }
.dw-sec {
  display: flex; align-items: baseline; gap: 8px; margin: 16px 0 8px;
  font-size: 12px; font-weight: 600; color: var(--nb-text);
}
.dw-sec-n { margin-left: auto; font-weight: 400; font-size: 11px; color: var(--nb-dim-2); }
.preview {
  padding: 14px; border-radius: 10px; border: 1px solid var(--nb-line);
  background: linear-gradient(160deg, #f2f7ff, #fff 60%);
}
.pv-title { font-size: 15px; font-weight: 700; color: var(--nb-text); line-height: 1.5; }
.pv-body { margin-top: 8px; font-size: 12px; color: var(--nb-dim); line-height: 1.7; }
.pv-cta {
  display: inline-block; margin-top: 10px; padding: 5px 14px; border-radius: 6px;
  background: var(--brand); color: #fff; font-size: 12px;
}
.task { display: flex; align-items: center; gap: 8px; padding: 6px 0; font-size: 12px; }
.tk-box {
  width: 15px; height: 15px; flex: 0 0 15px; border: 1px solid var(--nb-line); border-radius: 4px;
  display: grid; place-items: center; color: #fff; font-size: 10px;
}
.tk-box.done { background: var(--brand); border-color: var(--brand); }
.tk-text { flex: 1; min-width: 0; color: var(--nb-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tk-text.done { color: var(--nb-dim-2); text-decoration: line-through; }
.tk-owner { color: var(--nb-dim); flex: 0 0 auto; }
.tk-due { color: var(--nb-dim-2); flex: 0 0 auto; font-size: 11px; }
.scores { display: flex; gap: 6px; }
.score { position: relative; flex: 1; text-align: center; padding-bottom: 4px; }
.ring { width: 100%; max-width: 66px; height: auto; transform: rotate(-90deg); }
.ring-bg { fill: none; stroke: var(--nb-bg-3); stroke-width: 3.5; }
.ring-fg { fill: none; stroke-width: 3.5; stroke-linecap: round; }
.sc-num { position: absolute; top: 26px; left: 0; right: 0; font-size: 14px; font-weight: 600; color: var(--nb-text); }
.sc-label { margin-top: 2px; font-size: 11px; color: var(--nb-dim); }
.rel { display: flex; align-items: center; gap: 9px; padding: 8px 0; border-top: 1px solid var(--nb-line); }
.rel-ico { width: 30px; height: 30px; flex: 0 0 30px; border-radius: 6px; display: grid; place-items: center; color: #fff; font-size: 9px; font-weight: 600; }
.rel-ico.psd { background: #4f86f5; }
.rel-ico.pdf { background: var(--danger); }
.rel-name { font-size: 12px; color: var(--nb-text); }
.rel-size { font-size: 11px; color: var(--nb-dim-2); }
.rel-caret { margin-left: auto; color: var(--nb-dim-2); }
.mem { display: flex; align-items: center; gap: 8px; padding: 6px 0; font-size: 13px; cursor: pointer; }
.mem-ava { width: 26px; height: 26px; border-radius: 50%; background: var(--brand-soft); color: var(--brand); display: grid; place-items: center; font-size: 11px; }
.mem-name { flex: 1; min-width: 0; color: var(--nb-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mem-role { font-size: 11px; color: var(--nb-dim); border: 1px solid var(--nb-line); border-radius: 4px; padding: 0 5px; }
</style>
