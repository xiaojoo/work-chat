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
        <button v-for="w in WB_LINKS" :key="w.key" class="rail-btn" :title="'工作台 · ' + w.name"
                :aria-label="w.name" @click="router.push('/workbench/' + w.key)">
          <component :is="w.icon" theme="outline" size="20" />
        </button>
      </div>
      <div class="rail-foot">
        <span class="rail-conn" :class="shownPresence" :title="presenceName"></span>
        <!-- 故意不给 title：uaTip 是全局接管 title 的，挂了它悬停就会飘气泡。
             ☰ 的含义已经由弹层自己的「在线状态」表头说明，再飘一次是重复 -->
        <button class="rail-btn" :class="{ open: railMenuOpen }" aria-label="在线状态 / 退出登录"
                aria-haspopup="menu" :aria-expanded="railMenuOpen" @click.stop="toggleRailMenu">☰</button>
      </div>
    </nav>

    <!-- ②③④ 三栏合起来是一张圆角卡片：靠外层底色透出 5px 右边/下边缝隙，栏与栏之间不再画线 -->
    <div class="body-row">
    <!-- ② 列表栏 -->
    <aside class="side">
      <header class="side-head">
        <div class="side-title">
          <span>{{ activeTab === 'chat' ? '消息' : activeTab === 'friend' ? '通讯录' : '项目群组' }}</span>
          <span class="side-meta">{{ listSub }}</span>
        </div>
        <div class="side-acts">
          <button v-if="activeTab === 'friend'" class="side-act" title="添加好友" @click="openFriendPicker">＋</button>
          <button v-else-if="activeTab === 'group'" class="side-act" title="创建群" @click="showCreateGroup = true">＋</button>
        </div>
      </header>

      <div class="side-search">
        <span class="ss-ico">⌕</span>
        <input v-model="listSearch" :id="listSearchId" name="list-search" class="ss-input" type="text"
               :placeholder="listSearchPlaceholder" autocomplete="off" />
        <span v-if="listSearch" class="ss-clear" @click="listSearch = ''">✕</span>
      </div>

      <!-- 列表区外框：让索引条按"列表区"居中，而不是按整条侧栏居中（联系人页那条就是按列表区居中的）。
           索引条不能放进 .side-body —— 放进滚动容器里会跟着内容一起滚走 -->
      <div class="side-list">
      <!-- 消息 tab 上右侧要留一条 A-Z 索引的位置（.has-rail 多留 17px），滚动条藏掉但照常能滚 -->
      <div ref="sideBody" class="side-body" :class="{ 'has-rail': activeTab === 'chat' && filteredConversations.length }">
        <!-- 真实会话：项目频道=群，最近联系人=单聊，都来自后端会话列表，不再摆演示数据 -->
        <template v-if="activeTab === 'chat'">
          <template v-for="sec in convSections" :key="sec.title">
            <div v-if="sec.list.length" class="sec"><span>{{ sec.title }}</span></div>
            <div v-for="conv in sec.list" :key="conv.id" class="row" :data-al="convInitial(conv)"
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
          </template>
          <div v-if="!filteredConversations.length" class="list-empty">暂无会话</div>
        </template>

        <!-- 真实好友：按姓名首字母分组，右侧常驻 A-Z 索引（和群组共用 AlphaList） -->
        <template v-else-if="activeTab === 'friend'">
          <AlphaList v-if="filteredFriends.length" :items="filteredFriends" :name-of="friendName" :key-of="f => f.friendId">
            <template #default="{ item: friend }">
              <div class="row" @click="startChatWithFriend(friend)">
                <div class="ava">
                  <img v-if="friend.avatar" :src="friend.avatar" alt="" />
                  <span v-else>{{ friendName(friend).charAt(0).toUpperCase() }}</span>
                </div>
                <div class="row-main">
                  <div class="row-top"><span class="row-name">{{ friendName(friend) }}</span></div>
                  <div class="row-last">@{{ friend.username }}</div>
                </div>
              </div>
            </template>
          </AlphaList>
          <div v-else class="list-empty">暂无好友</div>
        </template>

        <!-- 真实群组：和联系人同一套 AlphaList（按群名首字母分组 + 右侧索引），行右侧同样换成 ⋯ -->
        <template v-else>
          <AlphaList v-if="filteredGroups.length" :items="filteredGroups" :name-of="g => g.name" :key-of="g => g.id">
            <template #default="{ item: group }">
              <div class="row" @click="startChatWithGroup(group)">
                <div class="ava group">
                  <img v-if="group.avatar" :src="group.avatar" alt="" />
                  <span v-else>{{ group.name?.charAt(0)?.toUpperCase() }}</span>
                </div>
                <div class="row-main">
                  <div class="row-top"><span class="row-name">{{ group.name }}</span></div>
                  <div class="row-last">{{ group.memberCount }} 人</div>
                </div>
                <button class="row-more" type="button" aria-label="查看群组信息" title="群组信息"
                        @click.stop="openGroupCard(group)">⋯</button>
              </div>
            </template>
          </AlphaList>
          <div v-else class="list-empty">暂无群组</div>
        </template>
      </div>

      <!-- 消息列表的 A-Z 跳转条：列表顺序不动（分组小标题 + 时间序照旧），点某个字母滚到
           当前顺序里第一个名字以它开头的会话。放在 .side-body 外面 —— 放进去会跟着内容滚走 -->
      <AlphaRail v-if="activeTab === 'chat' && filteredConversations.length"
                 :items="filteredConversations" :name-of="c => c.name || ''" @jump="jumpConv" />
      </div>
    </aside>

    <!-- ③ 会话主区 -->
    <main class="main" :class="{ 'no-drawer': !drawerShown }">
      <header v-if="currentConversation" class="m-head">
        <div class="mh-title">
          <span v-if="currentConversation?.type === 2" class="mh-hash">#</span>
          <span class="mh-name">{{ currentConversation?.name || '' }}</span>
        </div>
        <div class="mh-sub">{{ headSub }}</div>
        <!-- 叠放头像取真实群成员（名字走 loadMemberNames 的缓存）；单聊没成员可摆，整块不出现，
             原来那三个假头像和写死的 +8 一起删了 -->
        <div v-if="memberStackReal.length" class="mh-stack">
          <span v-for="m in memberStackReal" :key="m.userId" class="stack-ava">{{ m.ch }}</span>
          <span v-if="memberStackMore" class="stack-more">+{{ memberStackMore }}</span>
        </div>
        <div class="mh-acts">
          <button class="mh-btn icon" @click="drawerOpen = !drawerOpen"
                  :title="drawerOpen ? '收起详情' : currentConversation?.type === 2 ? '内容详情' : '会话详情'">☰</button>
        </div>
      </header>

      <div class="m-body" ref="messagesRef" @contextmenu.prevent="openBgMenu($event)">
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
                  <div v-if="bodyText(msg)" class="bubble" :class="{ 'b-media': msg.messageType === 'IMAGE', 'b-doc': msg.messageType === 'FILE' }"
                       @contextmenu.prevent.stop="openMsgMenu($event, msg)">
                    <span v-if="msg.messageType === 'DELETED'" class="b-del">{{ msg.content }}</span>
                    <template v-else-if="msg.messageType === 'IMAGE'">
                      <img v-if="mediaSrc(msg)" :src="mediaSrc(msg)" class="b-img" @click="previewImage(mediaSrc(msg))" />
                      <span v-else class="b-wait">{{ mediaState(msg) === 'err' ? '图片加载失败' : '图片加载中…' }}</span>
                    </template>
                    <a v-else-if="msg.messageType === 'FILE'" class="b-file" @click="openFileRef(fileRefOf(msg))">
                      <span class="b-file-nm">{{ (fileRefOf(msg) || {}).name || '文件' }}</span>
                      <span class="b-file-sz">{{ sizeLabel((fileRefOf(msg) || {}).size) }}</span>
                    </a>
                    <span v-else class="b-txt">{{ bodyText(msg) }}</span>
                  </div>
                  <span v-if="msg.status === 'FAILED'" class="msg-fail" title="发送失败：这条没有存进服务器">!</span>
                </div>
                <!-- 引用是气泡下面那一行灰字＋左竖线（照参考图），不再把 "> 谁：" 混在气泡正文里。
                     引用的是图片/文件时按类型渲染：图片出缩略图（点开走大图查看器），
                     文件出和内容里一样的那张文件片（点开和本地默认应用走同一条路），不再只写 [图片] 三个字 -->
                <div v-if="quoteOf(msg)" class="msg-quote" :class="{ 'q-media': quoteOf(msg).ref }"
                     @contextmenu.prevent.stop="openMsgMenu($event, msg)">
                  <span class="mq-name">{{ quoteOf(msg).name }}:</span>
                  <template v-if="quoteOf(msg).ref && quoteOf(msg).ref.kind === 'IMAGE'">
                    <img v-if="srcOfRef(quoteOf(msg).ref)" :src="srcOfRef(quoteOf(msg).ref)" class="mq-th" alt=""
                         @click.stop="previewImage(srcOfRef(quoteOf(msg).ref))" />
                    <span v-else class="mq-wait">{{ stateOfRef(quoteOf(msg).ref) === 'err' ? '图片加载失败' : '图片加载中…' }}</span>
                  </template>
                  <a v-else-if="quoteOf(msg).ref" class="b-file mq-file" @click.stop="openFileRef(quoteOf(msg).ref)">
                    <span class="b-file-nm">{{ quoteOf(msg).ref.name }}</span>
                    <span class="b-file-sz">{{ sizeLabel(quoteOf(msg).ref.size) }}</span>
                  </a>
                  <span v-else class="mq-tx">{{ quoteOf(msg).text }}</span>
                </div>
              </div>
              <div v-if="String(msg.senderId) === String(userStore.userId)" class="msg-ava self">
                {{ userStore.username?.charAt(0)?.toUpperCase() }}
              </div>
            </div>
          </template>
        </template>

        <!-- 没有可显示的消息：只放一个灰色占位图形，不再铺演示对话 -->
        <div v-else class="m-empty">
          <svg viewBox="0 0 84 66" width="84" height="66" aria-hidden="true">
            <rect x="2" y="6" width="52" height="38" rx="12" />
            <path d="M16 44v12l13-12z" />
            <circle class="eye" cx="16" cy="25" r="3.4" /><circle class="eye" cx="26" cy="25" r="3.4" /><circle class="eye" cx="36" cy="25" r="3.4" />
            <rect x="40" y="24" width="42" height="30" rx="10" />
            <path d="M68 54v10l-11-10z" />
            <circle class="eye" cx="53" cy="39" r="3" /><circle class="eye" cx="61" cy="39" r="3" /><circle class="eye" cx="69" cy="39" r="3" />
          </svg>
        </div>
      </div>

      <!-- 消息定位尺：只标我发出去的消息，一条一刻度；尺子横排在消息区底部居中、不随内容滚动，
           点一下把消息区滚到那条。消息少到不溢出时刻度置灰但保留，不让它凭空消失 -->
      <div v-if="msgMarks.length" class="ovr" role="group" aria-label="我发出的消息位置导航"
           :style="{ top: railTop + 'px', width: railW + 'px', '--tw': TICK_W + 'px' }">
        <button v-for="(m, i) in msgMarks" :key="i" type="button" class="ovr-t"
                :style="{ left: i * tickStep + TICK_W / 2 + 'px' }" :disabled="!msgCanScroll"
                :aria-label="'跳到 ' + m.time + ' 发的' + m.kind"
                @mouseenter="hoverIdx = i" @mouseleave="hoverIdx = -1"
                @focus="hoverIdx = i" @blur="hoverIdx = -1" @click="jumpToTick(m)"></button>
        <div v-show="hoverIdx >= 0" class="ovr-tip" :class="{ on: hoverIdx >= 0 }">
          <span class="tip-t">{{ msgMarks[hoverIdx]?.time }}</span><span class="tip-x">{{ msgMarks[hoverIdx]?.text }}</span>
        </div>
      </div>

      <footer v-if="currentConversation" class="m-input" :class="{ rzging: rzOn }" @dragover.prevent="isDragging = true" @dragleave.prevent="leaveDrag" @drop.prevent="handleDrop">
        <div v-if="isDragging" class="drop-mask"><div class="drop-ico">📎</div><div>松开鼠标上传文件</div></div>
        <!-- 待发送附件：选完/粘完/拖进来先落在这条上，点「发送」才真的发出去。
             文件名不给省略号，让它折行——截断了等于这张卡片没做完 -->
        <div v-if="pendingFiles.length" class="pend">
          <div v-for="f in pendingFiles" :key="f.id" class="pc" :class="{ busy: f.sending, err: f.failed }">
            <img v-if="f.kind === 'IMAGE'" :src="f.url" class="pc-th" alt="" />
            <span v-else class="pc-ic" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7">
                <path d="M14 3v5h5" /><path d="M6 3h8l5 5v13H6z" />
              </svg>
            </span>
            <span class="pc-tx">
              <b class="pc-nm">{{ f.name }}</b>
              <small class="pc-sz">{{ f.sending ? '上传中…' : (f.failed ? '没发出去，再点一次发送重试' : sizeLabel(f.size)) }}</small>
            </span>
            <button class="pc-x" type="button" title="移除" :aria-label="'移除 ' + f.name"
                    @click="dropPending(f.id)">✕</button>
          </div>
        </div>
        <div class="rz">
          <span class="rz-grip" role="separator" aria-orientation="horizontal" tabindex="0"
                title="拖动调整输入框高度，双击复原" @pointerdown="rzStart" @keydown="rzKey" @dblclick="rzSet(AREA_MIN)"></span>
        </div>
        <!-- 群聊里打一个 @ 会浮出成员名单（微信那条）：弹框挂在 .m-input 上，向上开 -->
        <div v-if="mentionOpen" class="mention-pop" @mousedown.prevent>
          <p class="mp-cap">提到</p>
          <div class="mp-list">
            <button v-for="(m, i) in mentionList" :key="m.userId" type="button" class="mp-row"
                    :class="{ on: i === mentionIndex }" @click="pickMention(m)">
              <span class="mp-av">{{ m.name.charAt(0).toUpperCase() }}</span>
              <span class="mp-nm">{{ m.name }}</span>
            </button>
            <p v-if="!mentionList.length" class="mp-empty">没有匹配的成员</p>
          </div>
        </div>
        <textarea id="chat-message-input" ref="areaRef" v-model="inputMessage" name="message" class="area" :style="{ height: areaH + 'px' }"
                  placeholder="输入消息，或使用 / 触发 AI 功能…"
                  @keydown="onAreaKey" @contextmenu.prevent.stop="openInputMenu($event)"
                  @input="onAreaInput" @click="syncMention" @blur="closeMention"
                  @paste="handlePaste" :disabled="!connected || !currentConversation"></textarea>
        <div class="bar">
          <div class="bar-tools">
            <button class="tool" title="文件" @click="$refs.fileInput.click()"><FolderUpload theme="outline" size="17" /></button>
            <button class="tool" title="图片" @click="$refs.imageInput.click()"><PictureOne theme="outline" size="17" /></button>
            <!-- 截图只有桌面壳有：抓屏、遮罩窗、写剪贴板都得主进程出手，网页端没有这条路，所以不摆出来 -->
            <button v-if="canShot" class="tool" title="截图" @click="startShot"><CameraOne theme="outline" size="17" /></button>
            <div class="tool-wrap" @mouseenter="openEmojiPicker" @mouseleave="showEmojiPicker = false">
              <!-- 不挂 title：uaTip 全局接管 title，挂了就会在弹框旁边再飘一个重复气泡；名字改挂 aria-label。
                   悬停区域绑在 .tool-wrap 上而不是按钮上：弹框是这个容器的后代，从按钮移进弹框
                   不算离开，所以不用留"宽限计时器"去赌那几像素的缝 -->
              <button class="tool" aria-label="表情" aria-haspopup="true" :aria-expanded="showEmojiPicker"
                      :class="{ open: showEmojiPicker }" @click="openEmojiPicker"><MessageEmoji theme="outline" size="17" /></button>
              <div v-if="showEmojiPicker" class="emoji-pop">
                <div class="emoji-grid">
                  <!-- .stop：选一个就把弹层关掉的话，想连选得反复点开；点外面才收（document 那个监听走 closeAllMenus） -->
                  <span v-for="item in emojiList" :key="item.e" class="emoji-cell" @click.stop="insertEmoji(item.e)"
                        @mouseenter="hoveredEmoji = item" @mouseleave="hoveredEmoji = null">{{ item.e }}</span>
                </div>
                <div class="emoji-hint">{{ hoveredEmoji ? hoveredEmoji.e + ' ' + hoveredEmoji.l : '选择表情' }}</div>
              </div>
            </div>
            <!-- 原来这颗是死的（没有任何 handler）。现在它在光标处补一个 @，把成员名单顶出来；
                 私聊没有"群里的人"可 @，所以置灰不消失。开面板的控件不挂 title，名字走 aria-label -->
            <button class="tool at" :class="{ open: mentionOpen }" :disabled="!canMention"
                    aria-label="提及群成员" @click="insertMentionAt">@</button>
            <button class="tool ai" title="AI 结果">✦</button>
            <input ref="imageInput" id="image-upload" type="file" accept="image/*" multiple style="display:none" @change="handleImageUpload" />
            <input ref="fileInput" id="file-upload" type="file" multiple style="display:none" @change="handleFileUpload" />
          </div>
          <div class="bar-right">
            <span v-if="!connected" class="bar-warn">连接已断开，正在重连…</span>
            <span v-else-if="!currentConversation" class="bar-warn">选择一个会话后才能发送</span>
            <span v-else-if="pendingFiles.length" class="pend-n">{{ pendingFiles.length }} 个附件待发送</span>
            <!-- 字数一直占着那条位子（隐藏不显示是 visibility，不是 v-if）：
                 它一出现就把「发送」往左推的话，打字时按钮会跳 -->
            <span class="bar-cnt" :class="{ show: !!inputMessage.length, over: inputMessage.length > TEXT_FILE_MIN }"
                  aria-hidden="true">{{ inputMessage.length }} 字{{ inputMessage.length > TEXT_FILE_MIN ? ' · 将以 txt 发送' : '' }}</span>
            <button class="send" @click="sendMessage"
                    :disabled="!connected || !currentConversation || pendingSending || (!inputMessage.trim() && !pendingFiles.length)">
              {{ pendingSending ? '发送中…' : '发送' }}<span v-if="pendingFiles.length && !pendingSending" class="snd-n">（{{ (inputMessage.trim() ? 1 : 0) + pendingFiles.length }}）</span>
            </button>
          </div>
        </div>
      </footer>
    </main>

    <!-- ④ 右侧内容详情抽屉 -->
    <aside v-if="drawerShown" class="drawer">
      <div class="dw-tabs">
        <button v-if="currentConversation?.type === 2" class="dwt" :class="{ on: dwTab === 'doc' }"
                @click="dwTab = 'doc'">内容详情</button>
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
        </div>
      </div>

      <div v-else class="dw-body gs">
        <!-- 布局照微信「聊天信息」那一页：搜索 → 成员宫格（末尾添加/移出两块）→ 标签+值+箭头的行 → 居中的文字按钮。
             只列后端真有的能力：参考图里的 备注 / 我在本群的昵称 / 群二维码 / 进群验证 / 置顶 / 免打扰 /
             保存到通讯录 / 显示群成员昵称 都没有接口（置顶和免打扰只是内存里的标记，刷新就没了），
             所以不照抄文案摆一排假开关 -->
        <template v-if="currentConversation">
          <div class="gs-search">
            <span class="gs-ico">⌕</span>
            <input v-model="groupMemberSearchText" id="member-search-inline" name="memberSearchInline"
                   type="text" :placeholder="selectedGroup ? '搜索群成员' : '搜索成员'" autocomplete="off" />
            <span v-if="groupMemberSearchText" class="gs-clear" @click="groupMemberSearchText = ''">✕</span>
          </div>

          <div class="gs-grid">
            <div v-for="m in filteredGroupMembers" :key="m.userId" class="gs-cell"
                 :class="{ picking: removeMode && canRemoveMember(m) }" @click="onMemberCell(m)">
              <div class="gs-ava">
                <span class="gs-init">{{ memberName(m).charAt(0).toUpperCase() }}</span>
                <span v-if="removeMode && canRemoveMember(m)" class="gs-minus"><Minus theme="outline" size="11" /></span>
                <span v-else-if="!removeMode && m.role === 2" class="gs-tag owner">群主</span>
                <span v-else-if="!removeMode && m.role === 1" class="gs-tag admin">管理员</span>
              </div>
              <div class="gs-name">{{ memberName(m) }}</div>
            </div>
            <button type="button" class="gs-tile" :title="selectedGroup ? '邀请成员' : '添加成员建群（连我满 3 人转群聊）'"
                    @click="onAddMemberTile">
              <span class="gs-box">＋</span><em>添加</em>
            </button>
            <button v-if="selectedGroup" type="button" class="gs-tile" :class="{ on: removeMode }" title="移出成员"
                    :disabled="!groupMembers.some(m => canRemoveMember(m))" @click="removeMode = !removeMode">
              <span class="gs-box">－</span><em>移出</em>
            </button>
          </div>
          <div v-if="!filteredGroupMembers.length" class="list-empty">
            {{ drawerMembers.length ? '没有匹配的成员' : (selectedGroup ? '这个群还没有成员' : '这个会话没有成员') }}
          </div>

          <template v-if="selectedGroup">
            <!-- 群聊名称 / 群公告：PUT /group/{id} 真的收 name、announcement。
                 做成常驻表单而不是"点行展开"：名称必填带红星，两个框各带字数计数器 -->
            <div class="gs-form">
              <div class="gs-field">
                <div class="gs-lab">群聊名称<span class="gs-req">＊</span></div>
                <div class="gs-field-box">
                  <input v-model="groupNameDraft" class="gs-in" type="text" maxlength="50" autocomplete="off"
                         :disabled="!canEditGroupInfo" placeholder="例如：产品策划讨论群" />
                  <span class="gs-count">{{ (groupNameDraft || '').length }}/50</span>
                </div>
              </div>
              <div class="gs-field">
                <div class="gs-lab">群公告<span class="gs-opt">（选填）</span></div>
                <div class="gs-field-box ta">
                  <textarea v-model="groupAnnDraft" class="gs-in" rows="3" maxlength="200"
                            :disabled="!canEditGroupInfo" placeholder="简要描述群聊的用途和规则..."></textarea>
                  <span class="gs-count">{{ (groupAnnDraft || '').length }}/200</span>
                </div>
              </div>
              <!-- 没改动、或者没权限（后端要 role>=1）时置灰不消失 -->
              <button class="btn btn-primary gs-save" :disabled="!canEditGroupInfo || !groupDirty" @click="saveGroupInfo">保存</button>
              <div v-if="!canEditGroupInfo" class="gs-note">只有群主和管理员能改群名称和群公告</div>
            </div>

            <div class="gs-btns">
              <button v-if="isConversationCleared(currentConversation.id)"
                      class="btn btn-neutral" @click="restoreGroupHistory">恢复聊天记录</button>
              <button v-else class="btn btn-neutral" @click="clearGroupHistory">清空聊天记录</button>
              <button v-if="String(selectedGroup.ownerId) === String(userStore.userId)"
                      class="btn btn-danger" @click="handleDissolveGroup">解散群聊</button>
              <button v-else class="btn btn-danger" @click="handleLeaveGroup">退出群聊</button>
            </div>
          </template>
        </template>
        <div v-else class="list-empty">还没有选中会话</div>
      </div>
    </aside>


    <!-- 消息操作菜单：照参考图摆十项，后端没接口的置灰不消失；图片/文件多一行「另存为…」 -->
    <div
      v-if="msgMenuVisible"
      class="conv-context-menu msg-menu-pop"
      :style="{ left: msgMenuX + 'px', top: msgMenuY + 'px' }"
    >
      <div class="conv-menu-list">
        <template v-for="it in msgMenu" :key="it.k">
          <div v-if="it.sep" class="conv-menu-divider"></div>
          <div class="conv-menu-item" :class="{ off: it.off, danger: it.danger }" @click="runMsgMenu(it)">
            <component :is="it.icon" theme="outline" size="16" />
            <span>{{ it.name }}</span>
          </div>
        </template>
      </div>
    </div>

    <!-- 消息区空白右键：清屏/恢复显示 + 刷新 + 消息保存（最后这项只预留显示） -->
    <div
      v-if="bgMenuVisible"
      class="conv-context-menu bg-menu-pop"
      :style="{ left: bgMenuX + 'px', top: bgMenuY + 'px' }"
    >
      <div class="conv-menu-list">
        <div v-for="it in bgMenu" :key="it.k" class="conv-menu-item" :class="{ off: it.off }" @click="runBgMenu(it)">
          <component :is="it.icon" theme="outline" size="16" />
          <span>{{ it.name }}</span>
        </div>
      </div>
    </div>

    <!-- 会话右键菜单 -->
    <div
      v-if="convMenuVisible"
      class="conv-context-menu"
      :style="{ left: convMenuX + 'px', top: convMenuY + 'px' }"
    >
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

    <!-- 输入框右键菜单：照参考图六项，右边带快捷键。没选区/空框的那几项置灰不消失 -->
    <div
      v-if="inputMenuVisible"
      class="conv-context-menu input-menu-pop"
      :style="{ left: inputMenuX + 'px', top: inputMenuY + 'px' }"
    >
      <div class="conv-menu-list">
        <div v-for="it in inputMenu" :key="it.k" class="conv-menu-item" :class="{ off: it.off }"
             @click="runInputMenu(it)">
          <component :is="it.icon" theme="outline" size="16" />
          <span>{{ it.name }}</span>
          <span class="cm-kb">{{ it.kb }}</span>
        </div>
      </div>
    </div>

    <!-- 图标栏 ☰ 菜单：状态三档 + 退出。用 bottom 定位所以向上长，不用先量菜单高度；
         点别处由挂在 document 上的那个 click 监听（closeAllMenus）收掉 -->
    <div v-if="railMenuOpen" class="rail-menu" role="menu"
         :style="{ left: railMenuX + 'px', bottom: railMenuBottom + 'px' }">
      <div class="rail-menu-hd">在线状态</div>
      <!-- 结构和右键菜单对齐：外壳不内缩，列表给 4px，行自己给 9px 16px（原来行贴着圆角边、hover 不满行） -->
      <div class="rail-menu-list">
        <button v-for="s in PRESENCE" :key="s.key" type="button" role="menuitemradio"
                class="rail-menu-item" :class="{ on: presence === s.key }" :aria-checked="presence === s.key"
                @click="setPresence(s.key)">
          <i class="pm-dot" :class="s.key" /><span>{{ s.name }}</span>
          <span v-if="presence === s.key" class="pm-tick">✓</span>
        </button>
        <div class="rail-menu-sep"></div>
        <button type="button" role="menuitem" class="rail-menu-item quit" @click="pickLogout">
          <span>退出登录</span>
        </button>
      </div>
    </div>

    </div>

    <!-- 添加好友：复用「添加用户」的组织树选择器（旧的关键词搜索弹窗已删） -->
    <AddMembersModal :open="showAddFriend" mode="friend" :org="orgData" :groups="groups" :online="onlineUsers"
                     :exclude-ids="friendExcludeIds" :group-members-cache="groupMemberCache"
                     @close="showAddFriend = false" @add="onAddFriendsSubmit" @expand-group="onExpandGroup" />

        <CreateGroupModal :open="showCreateGroup" :candidates="orgCandidates"
                        @close="showCreateGroup = false" @submit="onCreateGroupSubmit" />

    <!-- 同一棵树两种用途：往当前群里加人，或者单聊转群聊时挑人建群（mode/exclude 跟着换） -->
    <AddMembersModal :open="showAddMembers" :mode="isConvertToGroup ? 'convert' : 'group'"
                     :org="orgData" :groups="groups" :online="onlineUsers"
                     :exclude-ids="isConvertToGroup ? convertExcludeIds : groupMemberIds"
                     :group-members-cache="groupMemberCache"
                     @close="closeAddMembers" @add="onAddMembersSubmit" @expand-group="onExpandGroup" />


    <!-- 设置：个人信息 / 账号 / 通知 / 安全 / 外观 -->
    <SettingsModal :open="showProfile" @close="showProfile = false" @saved="onSettingsSaved" />

    <!-- 成员信息弹框 -->
    <div v-if="selectedMember" class="modal-overlay" @click.self="selectedMember = null">
      <div class="modal member-info-modal">
        <div class="modal-head">
          <div>
            <div class="modal-title">成员信息</div>
            <div class="modal-kicker">MEMBER INFO · 会话成员详情</div>
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
            <div v-for="r in memberInfoRows" :key="r.k" class="member-info-row">
              <span class="member-info-label">{{ r.label }}</span>
              <span class="member-info-value">{{ r.v }}</span>
            </div>
            <div v-if="!memberInfoRows.length" class="member-info-row">
              <span class="member-info-label">资料</span>
              <span class="member-info-value">对方资料都还没填</span>
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

    <!-- 群组信息：从群组行上的 ⋯ 进来，同样只列接口真给的字段 -->
    <div v-if="groupCard" class="modal-overlay" @click.self="groupCard = null">
      <div class="modal member-info-modal">
        <div class="modal-head">
          <div>
            <div class="modal-title">群组信息</div>
            <div class="modal-kicker">GROUP INFO</div>
          </div>
          <button class="btn btn-link btn-sm" aria-label="关闭" @click="groupCard = null">✕</button>
        </div>
        <div class="modal-body">
          <div class="member-info-card">
            <div class="avatar s72 group-ava">
              <img v-if="groupCard.avatar" :src="groupCard.avatar" alt="" />
              <span v-else>{{ (groupCard.name || '?').charAt(0).toUpperCase() }}</span>
            </div>
            <div class="member-info-detail">
              <div class="member-info-name">{{ groupCard.name }}</div>
              <div class="member-info-meta">ID {{ groupCard.id }}</div>
            </div>
          </div>
          <div class="member-info-fields">
            <div v-for="r in groupCardRows" :key="r.k" class="member-info-row">
              <span class="member-info-label">{{ r.label }}</span>
              <span class="member-info-value">{{ r.v }}</span>
            </div>
          </div>
        </div>
        <div class="modal-foot">
          <button class="btn btn-ghost" @click="settingsFromGroupCard">群设置</button>
          <button class="btn btn-primary" @click="chatFromGroupCard">进入群聊</button>
        </div>
      </div>
    </div>

    <!-- 图片查看器：页内弹框，滚轮/±按钮缩放，放大后能拖着看，ESC 或点空白处关。
         「编辑」是查看器自己的就地标注（底图 + 一层透明画布），不走桌面壳那套截图遮罩：
         那套是给"冻住的整屏"用的（框选、放大镜、取色），编辑一张已有的图只要画几笔 -->
    <div v-if="imgView" ref="ivRoot" class="img-view"
         @click.self="ivEdit || closeImageView()" @wheel.prevent="!ivEdit && onImgWheel($event)">
      <img v-if="!ivEdit" ref="ivImg" class="iv-img" :src="imgView" alt="" draggable="false"
           :style="{ transform: `translate(${imgPan.x}px, ${imgPan.y}px) scale(${imgZoom})` }"
           @pointerdown="imgDown" @pointermove="imgMove" @pointerup="imgUp" @pointercancel="imgUp"
           @dblclick="zoomReset" />
      <div v-else class="iv-stage" :style="{ width: ivBox.w + 'px', height: ivBox.h + 'px' }">
        <img class="iv-base" :src="imgView" alt="" draggable="false" />
        <canvas ref="ivCv" class="iv-cv" :class="{ draw: !!ivTool }" :width="ivNat.w" :height="ivNat.h"
                @mousedown.prevent @pointerdown="ivDown" @pointermove="ivMove" @pointerup="ivUp" @pointercancel="ivUp"></canvas>
        <input v-if="ivTyping" ref="ivTxt" v-model="ivText" class="iv-txt"
               :style="{ left: ivTyping.x + 'px', top: ivTyping.y + 'px' }" placeholder="输入文字，回车确认"
               @keydown.enter.prevent="ivCommitText" @keydown.esc.stop.prevent="ivCancelText" @blur="ivCommitText" />
      </div>
      <div class="iv-bar" @wheel.stop @click.stop>
        <template v-if="!ivEdit">
          <button class="iv-btn" type="button" title="缩小" :disabled="imgZoom <= 0.2" @click="zoomBy(1 / 1.25)">－</button>
          <span class="iv-pct">{{ Math.round(imgZoom * 100) }}%</span>
          <button class="iv-btn" type="button" title="放大" :disabled="imgZoom >= 6" @click="zoomBy(1.25)">＋</button>
          <button class="iv-btn wide" type="button" title="回到适应窗口" @click="zoomReset">复原</button>
          <button class="iv-btn wide" type="button" title="在查看器里标注这张图" @click="enterIvEdit">编辑</button>
        </template>
        <template v-else>
          <button v-for="t in IV_TOOLS" :key="t.k" class="iv-btn" :class="{ on: ivTool === t.k }" type="button"
                  :title="t.name" :aria-label="t.name" :aria-pressed="ivTool === t.k" @click="pickIvTool(t.k)">{{ t.i }}</button>
          <span class="iv-sp"></span>
          <button v-for="c in IV_COLORS" :key="c" class="iv-dot" :class="{ on: ivColor === c }" type="button"
                  :style="{ background: c }" :aria-label="'颜色 ' + c" @click="ivColor = c"></button>
          <span class="iv-sp"></span>
          <button class="iv-btn" type="button" title="撤销上一笔" aria-label="撤销" :disabled="!ivOps.length" @click="ivUndo">↶</button>
          <span class="iv-sp"></span>
          <button class="iv-btn wide" type="button" title="把标注后的整张图写进剪贴板" @click="ivCopy">复制</button>
          <button class="iv-btn wide" type="button" title="回到看图" @click="exitIvEdit">退出编辑</button>
        </template>
      </div>
      <button class="iv-x" type="button" title="关闭" @click="closeImageView">✕</button>
    </div>

  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { useWebSocket } from '../../websocket/client'
import { useTokenValidation } from '../../composables/useTokenValidation'
import { notifyDesktop } from '../../config'
import { getConversationList, createConversation, clearUnread, deleteConversation } from '../../api/conversation'
import { getFriendList, addFriend, checkFriend } from '../../api/friend'
import { createGroup, getMyGroups, getGroup, getGroupMembers, inviteMembers, removeMember, leaveGroup, dissolveGroup, updateGroup } from '../../api/group'
import { getUserProfile, searchUser, updateProfile, getMe, getOrg, getOnline } from '../../api/user'
import { uploadFile, fileObjectUrl, parseFileRef, previewOf } from '../../api/file'
import CreateGroupModal from '../../components/CreateGroupModal.vue'
import AddMembersModal from '../../components/AddMembersModal.vue'
import SettingsModal from '../../components/SettingsModal.vue'
import AlphaList from '../../components/AlphaList.vue'
import AlphaRail from '../../components/AlphaRail.vue'
import { pinyinInitial } from '../../utils/pinyin'
import { toast, confirmBox } from '../../utils/ui'
import { Minus, PictureOne, FolderUpload, MessageEmoji, Scissors, Mail, MicrophoneOne, People, History, Down, Pin, MessageUnread, Mute, Windows, PreviewClose, Delete, Copy, Clipboard, Undo, Redo, FullSelection, ZoomIn, Translate, Search, Share, Star, Selected, AlarmClock, Quote, Save, Refresh, Clear, PreviewOpen, CameraOne, Home, Checklist, FileText, Robot } from '@icon-park/vue-next'
import { docDetail, docTasks, docScores, docRelated } from '../../mock/workbench'

const router = useRouter()

// 工作台入口（静态壳分区，数据未接后端，界面里带"演示"标）
const WB_LINKS = [
  { key: 'home', name: '首页看板', icon: Home },
  { key: 'tasks', name: '我的任务', icon: Checklist },
  { key: 'docs', name: '项目文档', icon: FileText },
  { key: 'ai', name: 'AI 助手', icon: Robot }
]
const userStore = useUserStore()
const { connected, connect, disconnect, send, sendWhenConnected, onMessage } = useWebSocket()
const { isTokenValid, validateToken, redirectToLogin } = useTokenValidation()

const messagesRef = ref(null)
const inputMessage = ref('')
const AREA_MIN = 56, AREA_MAX = 320, AREA_KEY = 'chat-input-h'
const rzOn = ref(false)
const clampArea = h => Math.round(Math.min(AREA_MAX, Math.max(AREA_MIN, h)))
const areaH = ref(clampArea(Number(localStorage.getItem(AREA_KEY)) || AREA_MIN))
let rzY0 = 0, rzH0 = 0
function rzSet(h) {
  areaH.value = clampArea(h)
  localStorage.setItem(AREA_KEY, String(areaH.value))
}
function rzMove(e) { if (rzOn.value) rzSet(rzH0 + (rzY0 - e.clientY)) }
function rzEnd() {
  rzOn.value = false
  document.body.style.userSelect = ''
  window.removeEventListener('pointermove', rzMove)
  window.removeEventListener('pointerup', rzEnd)
}
function rzStart(e) {
  rzY0 = e.clientY; rzH0 = areaH.value; rzOn.value = true
  document.body.style.userSelect = 'none'
  window.addEventListener('pointermove', rzMove)
  window.addEventListener('pointerup', rzEnd)
  e.preventDefault()
}
function rzKey(e) {
  if (e.key === 'ArrowUp') { e.preventDefault(); rzSet(areaH.value + 24) }
  else if (e.key === 'ArrowDown') { e.preventDefault(); rzSet(areaH.value - 24) }
  else if (e.key === 'Home') { e.preventDefault(); rzSet(AREA_MIN) }
}
const showEmojiPicker = ref(false)
const hoveredEmoji = ref(null)
const isDragging = ref(false)

// ===== 待发送附件：图片/文件先进这条，点「发送」才上传并发出去 =====
// 原来选完图/粘完图是直接发出去的：误粘一张就进会话了，删都删不回来（撤回有 2 分钟窗口，
// 而且对方已经看到）。上传也放到点发送之后，取消掉的文件不会在 MinIO 留孤儿对象。
const pendingFiles = ref([])
const pendingSending = ref(false)
let pendingSeq = 0

function stageFile(file) {
  if (!file || !currentConversation.value) return
  const kind = String(file.type || '').startsWith('image/') ? 'IMAGE' : 'FILE'
  const att = {
    id: ++pendingSeq,
    kind,
    name: file.name || (kind === 'IMAGE' ? '图片' : '文件'),
    size: file.size || 0,
    file,
    url: '',
    sending: false
  }
  // 缩略图用本地 objectURL，不上传就取不到服务器地址；发出去或移掉时要 revoke，不然整张图留在内存里
  if (kind === 'IMAGE') att.url = URL.createObjectURL(file)
  pendingFiles.value.push(att)
}

function stageFiles(files) {
  const list = Array.from(files || [])
  if (!list.length || !currentConversation.value) return
  if (!connected.value) { toast('连接已断开，正在重连…先不发文件', 'warning'); return }
  list.forEach(stageFile)
}

function dropPending(id) {
  const i = pendingFiles.value.findIndex(a => a.id === id)
  if (i < 0) return
  const [a] = pendingFiles.value.splice(i, 1)
  if (a.url) URL.revokeObjectURL(a.url)
}

function clearPending() {
  for (const a of pendingFiles.value) if (a.url) URL.revokeObjectURL(a.url)
  pendingFiles.value = []
}

/* ===== 群聊里的 @：光标前那一段打一个 @ 就顶出成员名单（照微信）=====
   判据是"那一段里只有一个 @、@ 后面还没打空格"：所以 "@张@三"（一段里两个 @）顶不出来，
   打完 @张三 再敲空格之后那段也没有 @ —— 多个 @ 挤在一起没有指代对象，就不给名单。
   私聊没有"群里的人"可 @，一律不顶。 */
const areaRef = ref(null)
const mentionOpen = ref(false)
const mentionQ = ref('')
const mentionIndex = ref(0)
const MENTION_RE = /(?:^|\s)@([^@\s]*)$/
const canMention = computed(() => currentConversation.value?.type === 2 && connected.value)
const mentionList = computed(() => {
  const kw = mentionQ.value.trim().toLowerCase()
  const src = (groupMembers.value || []).map(m => ({ userId: m.userId, name: memberName(m) }))
  return kw ? src.filter(m => m.name.toLowerCase().includes(kw) || String(m.userId).includes(kw)) : src
})
function caretOf() {
  const el = areaRef.value
  return el && typeof el.selectionStart === 'number' ? el.selectionStart : 0
}
// 真敲键盘/粘贴/删除才走这里：程序改 inputMessage.value 不会触发 input 事件，
// 所以"引用"插进去那一行、发完清空这些都不算"动过手"
function onAreaInput() {
  inputDirty.value = true
  syncMention()
}
function syncMention() {
  const el = areaRef.value
  const m = el && canMention.value ? MENTION_RE.exec(el.value.slice(0, caretOf())) : null
  if (!m) { mentionOpen.value = false; return }
  // 关键词没变就别把上下选中的那一项重置回第一条（按方向键时也会走到这里）
  if (mentionQ.value !== m[1]) { mentionQ.value = m[1]; mentionIndex.value = 0 }
  if (mentionIndex.value >= mentionList.value.length) mentionIndex.value = 0
  mentionOpen.value = true
}
function closeMention() {
  mentionOpen.value = false
  // 下次顶出来要从第一条开始，别留着上次方向键选中的那个下标
  mentionQ.value = ''
  mentionIndex.value = 0
}
function scrollMentionRow() {
  nextTick(() => {
    const box = document.querySelector('.mp-list')
    const row = box && box.children[mentionIndex.value]
    if (!box || !row) return
    if (row.offsetTop < box.scrollTop) box.scrollTop = row.offsetTop
    else if (row.offsetTop + row.offsetHeight > box.scrollTop + box.clientHeight)
      box.scrollTop = row.offsetTop + row.offsetHeight - box.clientHeight
  })
}
function moveMention(d) {
  const n = mentionList.value.length
  if (!n) return
  mentionIndex.value = (mentionIndex.value + d + n) % n
  scrollMentionRow()
}
function pickMention(m) {
  const el = areaRef.value
  if (!el || !m) { closeMention(); return }
  const end = caretOf()
  const at = el.value.lastIndexOf('@', end - 1)
  if (at < 0) { closeMention(); return }
  inputMessage.value = el.value.slice(0, at) + '@' + m.name + ' ' + el.value.slice(end)
  closeMention()
  const p = at + m.name.length + 2
  nextTick(() => { el.focus(); el.setSelectionRange(p, p) })
}
// 工具栏那颗 @：在光标处补一个 @，名单就顶出来了。@ 前面补个空格才认（判据要行首或空白）
function insertMentionAt() {
  const el = areaRef.value
  if (!el || !canMention.value) return
  el.focus()
  const s = typeof el.selectionStart === 'number' ? el.selectionStart : el.value.length
  const e2 = typeof el.selectionEnd === 'number' ? el.selectionEnd : s
  const gap = s > 0 && !/\s/.test(el.value[s - 1]) ? ' ' : ''
  inputMessage.value = el.value.slice(0, s) + gap + '@' + el.value.slice(e2)
  const p = s + gap.length + 1
  nextTick(() => { el.setSelectionRange(p, p); syncMention() })
}
// 回车原来绑的是 @keydown.enter.exact.prevent，名单顶出来时回车得改成"选人"，
// 所以整段收进这一个 handler 里；顺带挡掉输入法选词那一下回车（原来会直接发出去）
function onAreaKey(e) {
  if (e.key === 'Enter' && e.isComposing) return
  if (mentionOpen.value) {
    if (e.key === 'ArrowDown') { e.preventDefault(); moveMention(1); return }
    if (e.key === 'ArrowUp') { e.preventDefault(); moveMention(-1); return }
    if (e.key === 'Escape') { e.preventDefault(); closeMention(); return }
    if (e.key === 'Enter') {
      e.preventDefault()
      const pick = mentionList.value[mentionIndex.value]
      if (pick) pickMention(pick); else closeMention()
      return
    }
  }
  if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey) {
    e.preventDefault()
    sendMessage()
  }
}

// 一个一个来：uploadFile 是 REST，多个大文件一起传会把这条链挤满；没发出去的留在条上可重试
async function flushPendingFiles() {
  pendingSending.value = true
  for (const att of [...pendingFiles.value]) {
    if (att.sending) continue
    att.sending = true
    att.failed = false
    const ok = await sendMediaFile(att.file, att.kind)
    att.sending = false
    if (ok) dropPending(att.id)
    else att.failed = true
  }
  pendingSending.value = false
}

// 原来不是点开的，是 .bar-tools 整条工具栏 mouseenter 开的 —— 鼠标扫过文件/图片/@/AI 任何一个
// 都会把表情框弹出来，而「表情」那颗按钮自己反而没有 click。现在只认这一颗（连同它的弹框所在的那个容器）。
function openEmojiPicker() {
  closeAllMenus()
  showEmojiPicker.value = true
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

/* 会话列表两段：项目频道=群会话，最近联系人=单聊，都来自后端 /conversation/list，
   顺序沿用后端返回（按最后一条时间），搜索框过滤后哪段空了就不出现哪段 */
const convSections = computed(() => [
  { title: '项目频道', list: filteredConversations.value.filter(c => c.type === 2) },
  { title: '最近联系人', list: filteredConversations.value.filter(c => c.type !== 2) },
])
/* 右侧 A-Z 条只负责跳转：行的 data-al 和索引的 has[] 都走同一个 pinyinInitial，
   点某个字母滚到"当前顺序里"第一个以它开头的会话（顺序仍是后端的时间序，没重排） */
const sideBody = ref(null)
const convInitial = c => pinyinInitial(c?.name || '')
function jumpConv(l) {
  const box = sideBody.value
  if (!box) return
  const el = box.querySelector(`[data-al="${l}"]`)
  if (!el) return
  box.scrollTo({ top: el.offsetTop, behavior: 'smooth' })
}
/* 头部叠放头像：取真实群成员前 3 个，超过 3 个才出现 +N（原来那三个假头像和写死的 +8 已删） */
const memberStackReal = computed(() => {
  if (currentConversation.value?.type !== 2) return []
  return groupMembers.value.slice(0, 3).map(m => ({
    userId: m.userId, ch: (getMemberNameSync(m.userId) || '?').charAt(0).toUpperCase()
  }))
})
const memberStackMore = computed(() => Math.max(0, groupMembers.value.length - memberStackReal.value.length))
const drawerOpen = ref(false) // 默认折叠：进来先看消息，要看成员/详情再点标题栏那颗 ☰
/* 没选中会话时：标题栏、输入框、右侧抽屉一起不出现，主区只剩那枚占位图形。
   抽屉跟着关还有个原因——☰ 在标题栏里，标题栏没了就只剩抽屉自己那个 ✕ 能关它，开不了也回不来 */
const drawerShown = computed(() => drawerOpen.value && !!currentConversation.value)
const dwTab = ref('doc')
// 「内容详情」只给群聊（那是项目文档那一套），所以选中单聊时页签必须落到「成员」，
// 否则抽屉开出来是一页没有页签的空壳
watch(currentConversation, c => { if (c && c.type !== 2) dwTab.value = 'member' }, { immediate: true })

// 添加好友（用 AddMembersModal 的组织树选择器）
const showAddFriend = ref(false)

// 创建群
const showCreateGroup = ref(false)

// 群详情（抽屉的「成员」页签用）
const selectedGroup = ref(null)
const groupMembers = ref([])
const groupMemberSearchText = ref('')
// 单聊没有群成员表，但这个页签要照群一样摆：会话双方就是这两个人。
// 「添加」格子在单聊下走建群流程，所以 selectedFriend 直接从当前会话推出来
const selectedFriend = computed(() => {
  const c = currentConversation.value
  return c && c.type === 1 && c.targetId ? { id: c.targetId, name: c.name } : null
})
const privateMembers = computed(() => {
  const f = selectedFriend.value
  if (!f) return []
  return [
    { userId: userStore.userId, name: userStore.username, role: 0 },
    { userId: f.id, name: f.name, role: 0 }
  ]
})
const drawerMembers = computed(() => (selectedGroup.value ? groupMembers.value : privateMembers.value))
// 群成员的名字走 memberNames 缓存；单聊那两格已经带名字
const memberName = m => m.name || getMemberNameSync(m.userId) || '用户 ' + m.userId
const filteredGroupMembers = computed(() => {
  const q = groupMemberSearchText.value.trim().toLowerCase()
  if (!q) return drawerMembers.value
  return drawerMembers.value.filter(m => {
    const name = memberName(m).toLowerCase()
    return name.includes(q) || String(m.userId).includes(q)
  })
})
const isConvertToGroup = ref(false) // 同一棵树的两种用途：false=往当前群加人，true=单聊转群聊

// 设置弹窗
const showProfile = ref(false)

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
}

// 成员信息弹框：只列 GET /api/user/{id} 真给且非空的字段。
// 原来这三行在后端返回空串时会填假值（海淀 / 138****8888 / 这个人很懒），那是编出来的，删了
const MEMBER_FIELDS = [['department', '部门'], ['position', '职务'], ['email', '邮箱'], ['phone', '电话'], ['bio', '个性签名']]
const memberInfoRows = computed(() => MEMBER_FIELDS
  .map(([k, label]) => ({ k, label, v: String(memberProfile.value[k] || '').trim() }))
  .filter(r => r.v))

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
// 只记"这个框里动过手没有"，不是"还有没有可撤的"：Chromium 不暴露 canUndo，后者探不到。
// 所以撤到底之后再点撤销，仍是原生的空操作——灰只保证"一个字都没输过的时候不骗你"。
const inputDirty = ref(false)

const msgMenuVisible = ref(false)
const msgMenuX = ref(0)
const msgMenuY = ref(0)

// ---- 图标栏 ☰ 菜单：在线状态 + 退出 ----
// 网关只认 ONLINE / BUSY 两种状态（写进 Redis 的 user:online:{id}）。"离线"不发 OFFLINE，
// 而是断开 WebSocket —— 网关在最后一台设备断开时把整个键删掉，别人读 /user/online 就查不到你。
// 若真发 OFFLINE，会出现"标着离线但连接还在、消息照收"的假状态，所以宁可断连来表达。
// 也因此 OFFLINE 不写进 localStorage：下次开应用本来就重连了，留着它等于界面在说谎。
const PRESENCE = [
  { key: 'ONLINE', name: '在线' },
  { key: 'BUSY', name: '忙碌' },
  { key: 'OFFLINE', name: '离线' }
]
const presence = ref(localStorage.getItem('chat_presence') || 'ONLINE')
// 外面那个点画的是"别人看到的我"：连接一断，网关就把 Redis 键删了、在线表里已经没有你，
// 这时无论上一次选的是什么都得画离线，否则点会一直绿着说谎
const shownPresence = computed(() => (connected.value ? presence.value : 'OFFLINE'))
const presenceName = computed(() => ({ ONLINE: '在线', BUSY: '忙碌', OFFLINE: '离线' })[shownPresence.value])
const railMenuOpen = ref(false)
const railMenuX = ref(0)
const railMenuBottom = ref(0)

function toggleRailMenu() {
  // 先记下要开还是关，再关掉别的（包括自己）—— 否则 closeAllMenus 会把这次意图一起抹掉，永远只能开不能关
  const next = !railMenuOpen.value
  closeAllMenus()
  railMenuOpen.value = next
  if (!next) return
  // 锚线用会话列表那块面板：左边贴它的左边、底边贴它的底边。
  // 原来量的是 ☰ 那颗按钮（右 +6、底对齐），所以左留了 3px、底留了 5px 的缝
  const side = document.querySelector('.side').getBoundingClientRect()
  railMenuX.value = Math.round(side.left)
  railMenuBottom.value = Math.round(window.innerHeight - side.bottom)
}

function setPresence(key) {
  railMenuOpen.value = false
  presence.value = key
  if (key === 'OFFLINE') {
    localStorage.removeItem('chat_presence')
    disconnect()
    return
  }
  localStorage.setItem('chat_presence', key)
  if (!connected.value) connect(userStore.accessToken)
  sendWhenConnected('PRESENCE_SET', { status: key }, 8000)
    .catch(() => toast('状态没发出去：连接未就绪', 'error'))
}

function pickLogout() {
  railMenuOpen.value = false
  handleLogout()
}

// 忙碌要能扛过重连：网关每次连接都按 ONLINE 写，掉线自动重连后要把状态补回去，
// 否则别人看到的你是在线，界面里你自己选的却是忙碌。
watch(connected, (up) => {
  if (up && presence.value === 'BUSY') send('PRESENCE_SET', { status: 'BUSY' })
})
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

// —— 消息定位尺：刻度位置一律从真实渲染量出来（rect + scrollTop），不自己按气泡高度算，
//    这样日分隔、图片加载完撑高、文件卡片换行都不用跟着改 ---
const msgMarks = ref([])
const msgCanScroll = ref(false)
const railBox = ref({ w: 0 })
const railTop = ref(0)
// 尺子离底缘的距离：刻度底边 = RAIL_LIFT - 4，取 20 就是底边留 16px，跟 .msg 的 16px 下边距同一个节奏
const RAIL_LIFT = 20
// 横排：刻度宽只在 TICK_W 一处定义（CSS 读 --tw，下面的 railW 和每条的 left 都按它算，
//    三处不同步会把整排挤偏）；hover 只换色不涨尺寸，所以每格 20px 时条与条之间空 12px
const TICK_W = 8
const tickStep = computed(() => {
  const n = msgMarks.value.length
  if (n < 2) return 0
  return Math.min(20, Math.max(TICK_W + 4, Math.round((railBox.value.w - 40) / (n - 1))))
})
const railW = computed(() => tickStep.value * (msgMarks.value.length - 1) + TICK_W)
let msgRO = null, msgMO = null, msgRaf = 0

const kindOf = el => el.querySelector('.b-img') ? '图片'
  : el.querySelector('.b-file') ? '文件'
  : el.querySelector('.msg-fail') ? '发送失败' : '消息'
const textOf = el => (el.querySelector('.b-txt')?.textContent
  || el.querySelector('.b-file-nm')?.textContent
  || el.querySelector('.b-del')?.textContent
  || (el.querySelector('.b-img') ? '[图片]' : '') || '').trim()
const hoverIdx = ref(-1)

function measureMsgs() {
  const el = messagesRef.value
  if (!el) { msgMarks.value = []; return }
  const base = el.getBoundingClientRect().top - el.scrollTop
  const box = el.getBoundingClientRect()
  railBox.value = { w: Math.round(box.width) }
  railTop.value = Math.round(el.offsetTop + box.height - RAIL_LIFT)
  // 只标我发出去的消息：一条一刻度，尺子横排在消息区底部居中、不随内容滚动
  msgMarks.value = [...el.querySelectorAll('.msg.self')].map(m => {
    const b = m.getBoundingClientRect()
    return { top: Math.round(b.top - base), h: Math.round(b.height), kind: kindOf(m),
             text: textOf(m), time: (m.querySelector('.msg-time')?.textContent || '').trim() }
  })
  msgCanScroll.value = el.scrollHeight - el.clientHeight > 1
}
function jumpToTick(m) {
  const el = messagesRef.value
  if (!el || !msgCanScroll.value) return
  const max = el.scrollHeight - el.clientHeight
  el.scrollTo({ top: Math.max(0, Math.min(m.top - (el.clientHeight - m.h) / 2, max)), behavior: 'smooth' })
}
const queueMeasure = () => { if (msgRaf) return; msgRaf = requestAnimationFrame(() => { msgRaf = 0; measureMsgs() }) }

watch([messages, currentConversation], () => nextTick(measureMsgs))

onMounted(() => {
  nextTick(measureMsgs)
  const el = messagesRef.value
  if (!el) return
  msgRO = new ResizeObserver(queueMeasure)
  msgRO.observe(el)
  // 图片是加载完才撑高的，既不动 computed 也不改属性，只能再听 load
  el.addEventListener('load', queueMeasure, true)
  msgMO = new MutationObserver(queueMeasure)
  msgMO.observe(el, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] })
})
onUnmounted(() => {
  msgRO?.disconnect(); msgMO?.disconnect()
  messagesRef.value?.removeEventListener('load', queueMeasure, true)
  if (msgRaf) cancelAnimationFrame(msgRaf)
  clearPending()   // 没发出去的缩略图也是整张图在内存里，离开页面要还掉
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

  // 遮罩窗复制完/钉完由主进程回一句，好让这边知道那一下到底成了没有
  if (window.chatDesktop?.onShotResult) {
    offShotResult = window.chatDesktop.onShotResult(r => {
      if (!r?.ok) toast('截图没送到：' + (r?.error || '未知原因'), 'error')
      else toast(r.action === 'pin' ? '已钉到屏幕上' : '已复制到剪贴板，去输入框 Ctrl+V 贴上，点发送才发出去', 'success')
    })
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
  rzEnd()   // 拖到一半被卸载的话，window 上的 pointermove 会一直留着
  offShotResult?.()
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
  closeMention()   // 名单是"这个群的人"，换会话不能还挂着上一份
  // 暂存的附件不跨会话：切会话把它清掉。留着的话下一次点发送会静默发到新会话里，
  // 发错人比丢一张还没发出去的图严重得多（文字草稿可以继续留着，那是他自己打的）
  clearPending()
  closeMention()
  // 抽屉「成员」页签的两个临时态不该跟着换会话留下来：正在勾选移出、正在改群名
  removeMode.value = false
  groupMemberSearchText.value = ''
  localStorage.setItem('chat_currentConversation', String(conv.id))
  // 清除未读数
  conv.unreadCount = 0
  clearUnread(String(conv.id))
  send('LOAD_MESSAGES', { conversationId: String(conv.id), limit: 50 })

  // 抽屉「成员」页签现在承担群设置，所以切会话时要把群信息和成员名单一起同步过来
  if (conv.type === 2) {
    const group = groups.value.find(g => String(g.id) === String(conv.targetId))
    selectedGroup.value = group || null
    if (group) {
      // 静默加载成员数据，以便点击头像时有信息
      try { groupMembers.value = await getGroupMembers(group.id) } catch (e) { /* ignore */ }
    } else groupMembers.value = []
  } else {
    // 单聊必须把 selectedGroup 和成员一起清掉：不清的话这个页签还挂着上一个群的设置和名单
    selectedGroup.value = null
    groupMembers.value = []
  }
}

async function startChatWithFriend(friend) {
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

  // 群会话要把群信息和成员名单准备好，抽屉的「成员」页签直接用
  selectedGroup.value = group
  // 静默加载成员数据
  try { groupMembers.value = await getGroupMembers(group.id) } catch (e) { /* ignore */ }
}

// 发送这一步现在做两件事：先发文字，再把暂存的图片/文件按挑的顺序逐个传上去发出去。
// 回车和点按钮走同一条路，所以暂存的东西不会被"顺手"发出去——只有这一句会被触发。
// 文字超过 1000 字不再当普通消息发，转成一张 txt 走文件那条路（他给的分界）。
const TEXT_FILE_MIN = 1000
async function sendMessage() {
  if (!currentConversation.value || !connected.value || pendingSending.value) return
  const text = inputMessage.value.trim()
  if (!text && !pendingFiles.value.length) return
  if (text && text.length > TEXT_FILE_MIN) {
    // 先等这张 txt 发完再冲暂存附件：两件事抢同一个"发送中"标记会把按钮提前放开
    pendingSending.value = true
    await sendLongTextAsFile(text)
    pendingSending.value = false
  } else if (text) sendTextMessage()
  if (pendingFiles.value.length) flushPendingFiles()
}

function stampNow() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}${p(d.getMinutes())}`
}

// 长文本转成 txt 发出去：走的就是"上传→发 FILE 消息"那条现成的路，
// 所以对方收到的、右键能另存的，都跟一张真文件片一模一样
async function sendLongTextAsFile(text) {
  const name = `长文本 ${stampNow()} (${text.length}字).txt`
  inputMessage.value = ''
  inputDirty.value = false
  pendingQuote.value = null
  closeMention()
  const file = new File([text], name, { type: 'text/plain;charset=utf-8' })
  if (await sendMediaFile(file, 'FILE')) toast(`超过 ${TEXT_FILE_MIN} 字，已发成 txt 文件`, 'success')
}

function sendTextMessage() {
  const content = inputMessage.value.trim()
  const convId = String(currentConversation.value.id)
  const messageId = `local-${Date.now()}`
  const reqId = nextReqId('send')
  // 引用体只在输入框第一行还是那条引用的时候才带上：那行被删掉就是不想引用了
  const extra = pendingQuote.value && content.startsWith(pendingQuote.value.head)
    ? JSON.stringify({ quote: pendingQuote.value.ref })
    : ''
  const payload = { conversationId: convId, messageType: 'TEXT', content, extra }

  const sent = send('MESSAGE_SEND', payload, reqId)

  // 本地立即显示
  const newMsg = {
    messageId,
    conversationId: convId,
    senderId: userStore.userId,
    messageType: 'TEXT',
    content,
    extra,
    timestamp: Date.now(),
    status: sent ? 'SENT' : 'FAILED'
  }
  messages.value.push(newMsg)
  ensureMedia(newMsg)
  // 必须存数组里那个响应式代理：改原始对象不会触发重渲染
  if (sent) pendingSends.set(reqId, messages.value[messages.value.length - 1])

  // 如果发送失败，3秒后自动重试
  if (!sent) {
    setTimeout(() => {
      if (newMsg.status === 'FAILED' && connected.value) {
        const retrySent = send('MESSAGE_SEND', payload, reqId)
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
  inputDirty.value = false
  pendingQuote.value = null
  closeMention()   // 程序改 value 不会触发 input，名单得自己收，不然发完还挂着
  scrollToBottom()
}

// 插入表情
function insertEmoji(emoji) {
  inputMessage.value += emoji
}

// 图片选择器：只放进待发送条，不直接发
function handleImageUpload(e) {
  const files = e.target.files
  if (files && files.length) stageFiles(files)
  e.target.value = ''
}

// 拖进来的：图片和文件混着来都行，各自按类型落条
function handleDrop(e) {
  isDragging.value = false
  stageFiles(e.dataTransfer?.files)
}

/* 指针从输入区移进它自己的子元素（输入框、工具条那排）时，容器也会收到一次 dragleave，
   照原来那样直接置 false，就和紧跟着冒泡上来的 dragover 来回翻。
   只有"要去的地方已经不在输入区里"才算真的离开 */
function leaveDrag(e) {
  const box = e.currentTarget
  if (e.relatedTarget && box && box.contains(e.relatedTarget)) return
  isDragging.value = false
}

// 粘贴：剪贴板里有文件（截图、复制的图片、复制的文件）就走附件条；纯文字照旧进输入框
function handlePaste(e) {
  const items = e.clipboardData?.items
  if (!items || items.length === 0) return
  const files = []
  for (const item of items) {
    if (item.kind !== 'file') continue
    const f = item.getAsFile()
    if (f) files.push(f)
  }
  if (!files.length) return
  e.preventDefault()
  stageFiles(files)
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

function srcOfRef(r) {
  if (!r) return ''
  return r.dataUrl || (r.fileId ? mediaSrcs.value[r.fileId] : '') || ''
}
function stateOfRef(r) {
  if (!r || (!r.fileId && !r.dataUrl)) return 'none'
  if (srcOfRef(r)) return 'ready'
  return mediaBad.value[r.fileId] ? 'err' : 'loading'
}

function mediaSrc(msg) { return srcOfRef(fileRefOf(msg)) }
function mediaState(msg) { return stateOfRef(fileRefOf(msg)) }

function sizeLabel(n) {
  const v = Number(n)
  if (!v || v < 0) return ''
  if (v < 1024) return v + ' B'
  if (v < 1024 * 1024) return (v / 1024).toFixed(0) + ' KB'
  return (v / 1024 / 1024).toFixed(1) + ' MB'
}

// 图片本体要提前拉缩略，引用体（图片/文件）也要——它俩走同一份 objectURL 缓存；文件等点开再取
async function ensureMedia(msg) {
  const refs = []
  if (msg.messageType === 'IMAGE') refs.push(fileRefOf(msg))
  const q = quoteOf(msg)
  if (q && q.ref && q.ref.kind === 'IMAGE') refs.push(q.ref)
  for (const r of refs) {
    if (!r || !r.fileId || mediaSrcs.value[r.fileId] || mediaBad.value[r.fileId]) continue
    try {
      const url = await fileObjectUrl(r.fileId)
      mediaSrcs.value = { ...mediaSrcs.value, [r.fileId]: url }
    } catch (e) {
      mediaBad.value = { ...mediaBad.value, [r.fileId]: true }
    }
  }
}

async function downloadRef(r) {
  if (!r) return
  const url = r.dataUrl || await fileObjectUrl(r.fileId)
  const a = document.createElement('a')
  a.href = url
  a.download = r.name || '文件'
  a.click()
}

async function sendMediaFile(file, type) {
  if (!currentConversation.value || !connected.value) return false
  const convId = String(currentConversation.value.id)
  let info
  try {
    info = await uploadFile(file)
  } catch (e) {
    const why = e?.response?.status === 413 ? '文件超过 20MB' : (e?.message || '上传失败')
    toast(`发送失败：${why}`, 'error')
    return false
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
  return sent
}

// 文件选择器：跟图片一样只进待发送条
function handleFileUpload(e) {
  const files = e.target.files
  if (files && files.length) stageFiles(files)
  e.target.value = ''
}

// 消息操作菜单
// 页面上所有弹层的开关都登记在这张表里：closeAllMenus 遍历它，
// 所以新增一个弹层只要往这里加一行，不会出现"忘了关别的、两个叠着显示"
const popups = {
  msg: msgMenuVisible, bg: bgMenuVisible, conv: convMenuVisible,
  input: inputMenuVisible, rail: railMenuOpen, emoji: showEmojiPicker
}

// 关闭所有右键菜单
function closeAllMenus() {
  for (const k in popups) popups[k].value = false
}

function openMsgMenu(event, msg) {
  event.stopPropagation()
  closeAllMenus()

  selectedMsg.value = msg
  // 位置先按点击点放，弹出来再按真实尺寸夹（宽和高都不按项数猜）
  msgMenuX.value = event.clientX
  msgMenuY.value = event.clientY
  msgMenuVisible.value = true
  clampMenuY(msgMenuY, '.msg-menu-pop', msgMenuX)
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

// 十项按参考图的顺序摆。off=true 是"这条我们真做不了"（后端没接口/没有本地能力），
// 置灰不消失；撤回仍然只在"自己发的、两分钟内"才出现，不把必然失败的入口摆出来
const msgMenu = computed(() => {
  const m = selectedMsg.value
  const type = String(m?.messageType || 'TEXT')
  const isText = type === 'TEXT'
  const isMedia = type === 'IMAGE' || type === 'FILE'
  const live = type !== 'DELETED'
  return [
    { k: 'copy', name: '复制', icon: Copy, off: !isText },
    { k: 'zoom', name: '放大阅读', icon: ZoomIn, off: true },
    { k: 'translate', name: '翻译', icon: Translate, off: true },
    { k: 'search', name: '搜一搜', icon: Search, off: true },
    { k: 'forward', name: '转发…', icon: Share, off: true, sep: true },
    { k: 'fav', name: '收藏', icon: Star, off: true },
    { k: 'multi', name: '多选', icon: Selected, off: true },
    { k: 'remind', name: '提醒', icon: AlarmClock, off: true, sep: true },
    { k: 'quote', name: '引用', icon: Quote, off: !live },
    ...(isMedia ? [{ k: 'save', name: '另存为…', icon: Save }] : []),
    { k: 'del', name: '删除', icon: Delete, off: true, sep: true },
    ...(m && canDeleteMsg(m) ? [{ k: 'revoke', name: '撤回', icon: Undo, danger: true }] : [])
  ]
})

function runMsgMenu(it) {
  if (it.off) return
  msgMenuVisible.value = false
  if (it.k === 'copy') return copyMessage()
  if (it.k === 'quote') return quoteMessage()
  if (it.k === 'save') return saveAsMessage()
  if (it.k === 'revoke') return handleDeleteMessage()
}

/* 引用没有后端列（chat_message 里找不到 quote/replyTo/parent），所以走纯文本约定：
   首行 "> 谁：原文"，剩下的才是自己写的。渲染时把首行拆出来放到气泡下面那行灰字里。
   代价：这是快照，原文之后被撤回/编辑这里不会跟着变，也点不回原消息。
   引用的是图片/文件时那行拿不回对象键（只有 "[图片]" 三个字），所以引用体塞在 extra 里：
   网关和 messages 表本来就带 extra 这一列，历史消息没这段就退回纯文字引用。 */
const QUOTE_RE = /^>\s*([^：:]{1,30})[：:]\s*([\s\S]*)$/
function quoteRefOf(msg) {
  try {
    const q = JSON.parse(msg.extra || '{}').quote
    return q && q.fileId ? q : null
  } catch (e) {
    return null   // extra 不是 JSON（老消息的 {name,size} 之外还有别的写法）就当没有引用体
  }
}
function quoteOf(msg) {
  if (!msg || msg.messageType !== 'TEXT') return null
  const c = String(msg.content || '')
  const nl = c.indexOf('\n')
  const m = QUOTE_RE.exec(nl < 0 ? c : c.slice(0, nl))
  return m ? { name: m[1].trim(), text: m[2].trim(), ref: quoteRefOf(msg) } : null
}
// 去掉引用行之后的正文；非文本消息（图片/文件/撤回）原样返回
function bodyText(msg) {
  const c = String(msg?.content || '')
  if (!quoteOf(msg)) return c
  const nl = c.indexOf('\n')
  return nl < 0 ? '' : c.slice(nl + 1)
}

// 引用：把原文按「> 谁：内容」放到输入框最前面，自己写的接在它下面
// 引用体（图片/文件的对象键）另存一份，发送时塞进 extra——那行灰字里放不下 fileId
const pendingQuote = ref(null)
function quoteMessage() {
  const m = selectedMsg.value
  if (!m) return
  const r = m.messageType === 'IMAGE' || m.messageType === 'FILE' ? fileRefOf(m) : null
  const body = m.messageType === 'TEXT' ? m.content
    : m.messageType === 'IMAGE' ? '[图片]'
    : `[文件] ${r?.name || ''}`
  const line = `> ${msgSenderName(m)}：${String(body || '').replace(/\s*\n\s*/g, ' ')}\n`
  // 老图片是整张 base64 塞在 content 里的，没有对象键可带，那种就退回纯文字引用
  pendingQuote.value = r && r.fileId
    ? { head: line.trimEnd(), ref: { kind: m.messageType, fileId: r.fileId, name: r.name, size: r.size, contentType: r.contentType } }
    : null
  inputMessage.value = line + (inputMessage.value ? inputMessage.value.replace(/^\n?/, '') : '')
  nextTick(() => {
    const el = document.querySelector('#chat-message-input')
    if (!el) return
    el.focus()
    el.setSelectionRange(el.value.length, el.value.length)
  })
}

// 另存为：桌面壳走主进程弹系统对话框（渲染端在沙箱里弹不出、也拿不到用户选的路径），
// 网页端没有这条路，退回浏览器的 <a download>，落点是浏览器自己的设置。
// 参数是一个"文件引用"（消息里的、引用行里的都是同一种形状）
async function saveAsRef(r) {
  if (!r) { toast('这条消息里没有可保存的文件', 'error'); return }
  try {
    if (!window.chatDesktop?.save) { downloadRef(r); toast('已交给浏览器下载', 'success'); return }
    const res = await window.chatDesktop.save({ name: r.name || '文件', bytes: await refBytes(r) })
    if (res?.ok) toast(`已保存（${res.size} 字节）`, 'success')
    else if (!res?.canceled) toast('保存失败：' + (res?.error || '未知错误'), 'error')
  } catch (e) {
    toast('另存为失败：' + (e?.message || e), 'error')
  }
}
function saveAsMessage() {
  return saveAsRef(selectedMsg.value ? fileRefOf(selectedMsg.value) : null)
}

// 截图：抓屏 / 遮罩窗 / 剪贴板都在桌面壳主进程，这边只发起和收结果。
// 遮罩是"冻住的那张屏幕"，所以主窗口不用隐藏也看不到重影。
const canShot = !!window.chatDesktop?.shot
async function startShot() {
  try {
    const r = await window.chatDesktop.shot()
    // busy 以前是静默的：截图窗还开着的时候点那颗按钮，看着就像"点了没反应"
    if (r && !r.ok) toast((r.busy ? '' : '截图没起来：') + (r.error || '未知原因'), r.busy ? 'warning' : 'error')
  } catch (e) {
    toast('截图没起来：' + (e?.message || e), 'error')
  }
}
let offShotResult = null

// 弹层的越界夹取按真实尺寸算，不按项数猜。
// 必须用 offsetHeight：getBoundingClientRect 量的是变换后的盒子，而弹层带 0.15s 的
// scale(0.95→1) 入场动画，nextTick 时正处在 0.95 —— 高度会少读 5%（443 读成 421），
// 留的余量不够，最后一行就被窗口底边切掉。offsetHeight 是布局值，不受 transform 影响。
// above=true：底边贴到光标上方（输入框在窗口最底下，往下开会把输入框和工具栏整块盖住）
async function clampMenuY(posRef, sel, xRef, above) {
  await nextTick()
  const el = document.querySelector(sel)
  if (!el) return
  if (above) posRef.value = Math.max(8, Math.round(posRef.value - el.offsetHeight - 6))
  const maxY = window.innerHeight - el.offsetHeight - 8
  if (posRef.value > maxY) posRef.value = Math.max(8, Math.round(maxY))
  if (xRef !== undefined) {
    const maxX = window.innerWidth - el.offsetWidth - 8
    if (xRef.value > maxX) xRef.value = Math.max(8, Math.round(maxX))
  }
}

// 背景右键菜单（清屏）
function openBgMenu(event) {
  event.preventDefault()
  event.stopPropagation()
  closeAllMenus()

  // 原来这里写死 menuWidth=100 / menuHeight=50 来夹取，那是清屏只有一项时的尺寸；
  // 现在三项、图标行，改成弹出来按真实尺寸夹
  bgMenuX.value = event.clientX
  bgMenuY.value = event.clientY
  bgMenuVisible.value = true
  clampMenuY(bgMenuY, '.bg-menu-pop', bgMenuX)
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

// 空白右键的三项。消息保存按你说的只预留显示：后端没有导出/存档接口，
// 做成能点但什么都不发生，比摆一个灰行更坏
const bgMenu = computed(() => {
  const conv = currentConversation.value
  const cleared = !!conv && isConversationCleared(conv.id)
  return [
    cleared
      ? { k: 'restore', name: '恢复显示', icon: PreviewOpen, off: !conv }
      : { k: 'clear', name: '清屏', icon: Clear, off: !conv },
    { k: 'refresh', name: '刷新', icon: Refresh, off: !conv },
    { k: 'archive', name: '消息保存', icon: Save, off: true }
  ]
})

function runBgMenu(it) {
  if (it.off) return
  bgMenuVisible.value = false
  if (it.k === 'clear') return clearScreen()
  if (it.k === 'restore') return restoreScreen()
  if (it.k === 'refresh') return refreshMessages()
}

// 刷新：重拉这个会话的历史。已清屏的会话不会因此"偷偷恢复"—— LOAD_MESSAGES 那条
// 分支里本来就守着清屏标记，这里不碰标记，所以刷新后仍是空的
function refreshMessages() {
  const conv = currentConversation.value
  if (!conv) return
  messages.value = []
  send('LOAD_MESSAGES', { conversationId: String(conv.id), limit: 50 })
  toast('已刷新', 'success')
}

// 点击空白处关闭菜单
document.addEventListener('click', () => {
  closeAllMenus()
})
document.addEventListener('contextmenu', (e) => {
  // 如果右键点在输入框上，由 openInputMenu 处理；否则关闭所有菜单
  if (!e.target.closest('#chat-message-input')) {
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

const friendName = f => f.nickname || f.username || ''

/* 群组信息弹框：字段一律取 GET /group/{id} 的真实返回（GroupDTO 只有
   id / name / avatar / ownerId / announcement / memberCount / groupType），
   没有创建时间、没有群人数上限，就不列这两行；群主名字再拿 /api/user/{ownerId} 补 */
const GROUP_TYPES = { 1: '普通群', 2: '项目群', 3: '部门群' }
const groupCard = ref(null)
const groupDetail = ref({})
const groupOwner = ref('')
const groupCardRows = computed(() => {
  const g = { ...groupCard.value, ...groupDetail.value }
  const out = [{ k: 'n', label: '成员', v: `${g.memberCount ?? 0} 人` }]
  const t = GROUP_TYPES[g.groupType]
  if (t) out.push({ k: 't', label: '类型', v: t })
  out.push({ k: 'o', label: '群主', v: groupOwner.value || `ID ${g.ownerId ?? '—'}` })
  const a = String(g.announcement || '').trim()
  if (a) out.push({ k: 'a', label: '公告', v: a })
  return out
})
async function openGroupCard(group) {
  groupCard.value = group
  groupDetail.value = {}
  groupOwner.value = ''
  try {
    const d = await getGroup(group.id)
    if (d && !d.error) groupDetail.value = d
  } catch (e) { /* 拉不到就退回列表行上已有的字段 */ }
  const oid = groupDetail.value.ownerId ?? group.ownerId
  if (!oid) return
  try {
    const p = await getUserProfile(oid)
    if (p && !p.error) groupOwner.value = `${p.nickname || p.username}（@${p.username}）`
  } catch (e) { /* 取不到群主就显示 ID */ }
}
async function chatFromGroupCard() {
  const g = groupCard.value
  groupCard.value = null
  if (g) await startChatWithGroup(g)
}
async function settingsFromGroupCard() {
  const g = groupCard.value
  groupCard.value = null
  if (!g) return
  // 「群设置」那一栏已经删掉，这些功能现在在抽屉的「成员」页签里：先切进这个群，再把页签指过去
  await startChatWithGroup(g)
  dwTab.value = 'member'
  drawerOpen.value = true
}

// —— 组织架构 / 在线状态：创建群聊与添加用户两个弹窗的数据源 ——
const orgData = ref([])
const onlineUsers = ref([])   // [{ userId, status: 'ONLINE'|'BUSY' }]，不在里面就是离线
const showAddMembers = ref(false)

// 弹框和右键菜单不能叠着出现。联系人/群组行上那颗 ⋯ 带 @click.stop，打开弹框的那一下点击
// 不会冒到 document，全局那条 closeAllMenus 兜底收不到，于是先右键过的菜单会活着浮到弹框上面
// （菜单 z-index 9999 > 遮罩 95）。这段必须放在所有被引用 ref 之后 —— 放前面是 TDZ，
// setup 直接抛错，整页空白
watch(
  [showProfile, showAddFriend, showAddMembers, showCreateGroup, groupCard],
  (vals) => { if (vals.some(Boolean)) closeAllMenus() }
)

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
    onlineUsers.value = await getOnline()
  } catch (e) {
    // Redis 不可用时接口返回空集合，界面按全部离线画，不画假绿点
    onlineUsers.value = []
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

// 添加好友吃的是同一份在线表。原来这里只置 visible，面板画的是进页面那次拉到的旧状态——
// 刚在 ☰ 里把自己换成忙碌，点开面板那行点还是绿的。和 openAddMembers 一样先刷再开。
function openFriendPicker() {
  refreshOnline()
  showAddFriend.value = true
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
  // 同一棵树，两种落法：转群模式下是"建一个新群"，否则是"往当前群里加人"
  if (isConvertToGroup.value) { createGroupFromPrivate(ids); return }
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
/* ---- 抽屉「成员」页签：移出模式 + 群名/群公告表单 ---- */
const removeMode = ref(false)
const groupNameDraft = ref('')
const groupAnnDraft = ref('')
// 后端 PUT /group/{id} 要求操作人 role>=1，普通成员点了只会吃一句"没有权限修改群信息"
// → 框做成只读，值照样看得境
const canEditGroupInfo = computed(() => groupMembers.value.some(m =>
  String(m.userId) === String(userStore.userId) && Number(m.role) >= 1))

// 草稿和真实群信息对齐：换群、换会话、保存成功之后都要调
function syncGroupDrafts() {
  groupNameDraft.value = selectedGroup.value?.name || ''
  groupAnnDraft.value = selectedGroup.value?.announcement || ''
}
// 换群走的是 selectedGroup 整个换对象，用 watch 兜住所有赋值点（保存后的原地改不走这里）
watch(selectedGroup, syncGroupDrafts)
const groupDirty = computed(() => {
  const g = selectedGroup.value
  if (!g) return false
  return groupNameDraft.value.trim() !== (g.name || '') || groupAnnDraft.value.trim() !== (g.announcement || '')
})

// 移出模式下点格子就是移出这个人；否则还是看这个人是谁
function onMemberCell(m) {
  if (removeMode.value && canRemoveMember(m)) { handleRemoveMember(m); return }
  showMemberInfo(m)
}

async function saveGroupInfo() {
  const g = selectedGroup.value
  if (!g || !canEditGroupInfo.value) return
  const name = groupNameDraft.value.trim()
  if (!name) { toast('群名称不能为空', 'error'); return }
  // 只把改过的字段放进 payload：后端按 null 跳过，没改的群公告就不该被写成空串
  const body = {}
  if (name !== (g.name || '')) body.name = name
  const ann = groupAnnDraft.value.trim()
  if (ann !== (g.announcement || '')) body.announcement = ann
  if (!Object.keys(body).length) return
  try {
    await updateGroup(g.id, body)
    // selectedGroup 就是 groups 里那个对象，改它列表和页签会一起跟上；会话名要另外同步
    Object.assign(g, body)
    if (body.name) {
      const conv = conversations.value.find(c => c.type === 2 && String(c.targetId) === String(g.id))
      if (conv) conv.name = body.name
      if (currentConversation.value && String(currentConversation.value.targetId) === String(g.id)) {
        currentConversation.value.name = body.name
      }
    }
    syncGroupDrafts()
    toast('已保存', 'success')
  } catch (e) { toast(e.response?.data?.error || '保存失败', 'error') }
}

// 清屏只是本机隐藏历史，措辞照真实行为写，不写成别人也看不到
async function clearGroupHistory() {
  const ok = await confirmBox({
    title: '清空聊天记录',
    message: '只清你这台机器上的显示，其他成员不受影响；清完可以再点一次恢复。',
    confirmText: '清空', cancelText: '取消', type: 'warning'
  })
  if (ok) clearScreen()
}
function restoreGroupHistory() { restoreScreen() }

// 抽屉「成员」页签那颗 ＋ ：群聊走邀请，单聊走转群
function onAddMemberTile() {
  if (selectedGroup.value) openAddMembers()
  else convertToGroup()
}

// 单聊转群聊：走「添加好友」那棵组织架构树挑人，确定后新建一个群会话跳过去
// （这条私聊和它的记录原样留着）
function convertToGroup() {
  if (!selectedFriend.value) return
  isConvertToGroup.value = true
  refreshOnline()
  showAddMembers.value = true
}

// 建群时把自己和对方从树上排掉：自己不用选（后端落成群主），对方已经在群里了
const convertExcludeIds = computed(() => [
  String(userStore.userId || ''),
  String(selectedFriend.value?.id ?? '')
].filter(Boolean))

function closeAddMembers() {
  showAddMembers.value = false
  isConvertToGroup.value = false
}

// 成员 = 对方 + 树上挑中的人；加上我自己（群主）才是总人数
async function createGroupFromPrivate(ids) {
  const f = selectedFriend.value
  showAddMembers.value = false
  isConvertToGroup.value = false
  if (!f) return
  const memberIds = [...new Set([Number(f.id), ...ids.map(Number)])]
  if (memberIds.length < 2) {
    toast('再至少选 1 人：加上你和对方满 3 人才能转成群聊', 'warning')
    return
  }
  try {
    const group = await createGroup(`${userStore.username}、${f.name || '好友'} 等${memberIds.length + 1}人`, null, memberIds)
    toast('群聊已创建', 'success')
    await Promise.all([loadGroups(), loadConversations()])
    startChatWithGroup(group)
  } catch (e) {
    toast(e.response?.data?.error || '创建群失败', 'error')
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
// 原来这几个 handler 查的都是 .chat-textarea，可 textarea 的真实类是 .area（id=chat-message-input），
// 选择器 0 命中 —— 整条菜单点了什么都不发生。统一走 inputEl() 一个口子
const inputEl = () => document.querySelector('#chat-message-input')

const inputMenu = computed(() => [
  { k: 'undo', name: '撤销', kb: 'Ctrl+Z', icon: Undo, off: !inputDirty.value },
  { k: 'redo', name: '重做', kb: 'Ctrl+Y', icon: Redo, off: !inputDirty.value },
  { k: 'cut', name: '剪切', kb: 'Ctrl+X', icon: Scissors, off: !inputHasSelection.value },
  { k: 'copy', name: '复制', kb: 'Ctrl+C', icon: Copy, off: !inputHasSelection.value },
  { k: 'paste', name: '粘贴', kb: 'Ctrl+V', icon: Clipboard },
  { k: 'all', name: '全选', kb: 'Ctrl+A', icon: FullSelection, off: !inputMessage.value.length }
])

function openInputMenu(event) {
  closeAllMenus()

  const textarea = inputEl()
  inputHasSelection.value = textarea
    ? textarea.selectionStart !== textarea.selectionEnd
    : false

  inputMenuX.value = event.clientX
  inputMenuY.value = event.clientY
  inputMenuVisible.value = true
  clampMenuY(inputMenuY, '.input-menu-pop', inputMenuX, true)
}

function runInputMenu(it) {
  if (it.off) return
  ({ undo: handleInputUndo, redo: handleInputRedo, cut: handleInputCut,
     copy: handleInputCopy, paste: handleInputPaste, all: handleInputSelectAll })[it.k]()
}

/* 撤销/重做走浏览器原生那条编辑栈。Chromium 不暴露 canUndo，"还有没有可撤的"探不到，
   所以这两项只在"这个框里一个字都没动过"时置灰（inputDirty），动过手就常亮；
   撤到底之后再点仍是原生空操作。要连那一步都准，得自建输入历史栈并接管 Ctrl+Z，那是另一件事 */
function execOnInput(cmd) {
  const textarea = inputEl()
  if (!textarea) return
  textarea.focus()
  document.execCommand(cmd)
}
function handleInputUndo() { execOnInput('undo'); inputMenuVisible.value = false }
function handleInputRedo() { execOnInput('redo'); inputMenuVisible.value = false }

function handleInputSelectAll() {
  const textarea = inputEl()
  if (textarea) {
    textarea.focus()
    textarea.setSelectionRange(0, textarea.value.length)
  }
  inputMenuVisible.value = false
}

async function handleInputCopy() {
  const textarea = inputEl()
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
  const textarea = inputEl()
  inputMenuVisible.value = false
  if (!textarea) return
  let text = ''
  try { text = await navigator.clipboard.readText() } catch (e) { toast('无法访问剪贴板', 'error'); return }
  if (!text) return
  // 必须走编辑接口，不能自己拼 inputMessage.value：程序改 value 不进 Chromium 的原生撤销栈，
  // 粘贴完按 Ctrl+Z 就什么也撤不回来（他报的就是这个）。insertText 既进撤销栈，
  // 也会触发 input 事件，v-model 和 @input 那条（字数、@ 名单）都跟着走
  textarea.focus()
  document.execCommand('insertText', false, text)
}

async function handleInputCut() {
  const textarea = inputEl()
  inputMenuVisible.value = false
  if (!textarea) return
  const start = textarea.selectionStart
  const end = textarea.selectionEnd
  if (start === end) return
  const selected = textarea.value.substring(start, end)
  try { await navigator.clipboard.writeText(selected) } catch (e) { toast('无法写入剪贴板', 'error'); return }
  // 同上：删这一步交给原生 delete 命令，撤销栈里有这一笔，Ctrl+Z 能把那段字找回来
  textarea.focus()
  document.execCommand('delete')
  toast('已剪切', 'success')
}

// 会话列表：获取最后消息显示文本
function getConvLastMessage(conv) {
  let msg = conv.lastMessage
  if (!msg) return '暂无消息'
  // 如果是图片类型
  if (msg === '[图片]') return '[图片]'
  // 列表里不露 "> " 这个约定标记：有正文就显示正文，只有引用那行就显示被引用的那句
  const t = String(msg), nl = t.indexOf('\n'), q = QUOTE_RE.exec(nl < 0 ? t : t.slice(0, nl))
  if (q) msg = (nl < 0 ? '' : t.slice(nl + 1).trim()) || `${q[1].trim()}: ${q[2].trim()}`
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

/* 群成员里不是好友的那一位，名字只能靠 getUserProfile 补：原来只有消息渲染那条路径会去取，
   没在群里发过言的成员在头部叠放头像上就长期是个 "?" */
watch(groupMembers, ms => {
  ms.forEach(m => {
    if (!memberNames.value[m.userId] && String(m.userId) !== String(userStore.userId)) resolveSenderName(m.userId)
  })
})

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
      return
    }
    // 不在好友列表里的成员，走和消息发送者同一条异步取名路径；
    // 原来这里只查好友、查不到就不管，头像位长期是 "?"
    resolveSenderName(id)
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

/* 图片查看：以前是 window.open 到新窗口，桌面壳里那条路打不开（app:// 页开不了新窗，
   网页端也是跳走一个标签页）。改成页内弹框，两端同一条路。
   缩放用 transform + 拖动平移：套滚动容器会多出一条滚动条，全站只留一处滚动条那条规矩 */
const imgView = ref('')
const ivImg = ref(null)
const imgZoom = ref(1)
const imgPan = ref({ x: 0, y: 0 })
const ZOOM_MIN = 0.2, ZOOM_MAX = 6, ZOOM_STEP = 1.25
function previewImage(url) {
  if (!url) return
  imgView.value = url
  imgZoom.value = 1
  imgPan.value = { x: 0, y: 0 }
}
function closeImageView() { imgView.value = ''; exitIvEdit() }
function zoomBy(f) {
  const z = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, imgZoom.value * f))
  imgZoom.value = Math.round(z * 1000) / 1000
  if (imgZoom.value <= 1) imgPan.value = { x: 0, y: 0 }
}
function zoomReset() { imgZoom.value = 1; imgPan.value = { x: 0, y: 0 } }
function onImgWheel(e) { zoomBy(e.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP) }
let imgDrag = null
function imgDown(e) {
  if (imgZoom.value <= 1) return
  imgDrag = { x: e.clientX - imgPan.value.x, y: e.clientY - imgPan.value.y }
  // 捕获指针：拖出图片边界还能继续跟手；合成事件下 pointerId 可能不存在，抓不到就算了
  try { e.currentTarget.setPointerCapture(e.pointerId) } catch (err) { /* ignore */ }
}
function imgMove(e) {
  if (!imgDrag) return
  imgPan.value = { x: e.clientX - imgDrag.x, y: e.clientY - imgDrag.y }
}
function imgUp() { imgDrag = null }
/* ---- 查看器里的就地标注 ----
   不走桌面壳那套截图遮罩：那套是给"冻住的整屏"用的（框选、放大镜、取色），
   编辑一张已经存在的图只要画几笔。底图 + 一层透明画布，画布后备像素 = 图片原始尺寸，
   所以坐标直接用图片像素、导出就是两张叠一块，不经过任何缩放映射。 */
const ivRoot = ref(null)
const ivCv = ref(null)
const ivTxt = ref(null)
const ivEdit = ref(false)
const ivOps = ref([])
const ivTool = ref(null)
const ivColor = ref('#ff3b30')
const ivNat = ref({ w: 0, h: 0 })
const ivBox = ref({ w: 0, h: 0 })
const ivTyping = ref(null)
const ivText = ref('')
const IV_COLORS = ['#ff3b30', '#ffb300', '#2b6be8']
const IV_TOOLS = [{ k: 'rect', i: '▢', name: '方框' }, { k: 'arrow', i: '↗', name: '箭头' },
  { k: 'text', i: 'T', name: '文字' }, { k: 'pen', i: '✎', name: '涂鸦' }]
let ivSrc = null
let ivLive = null

// 描边和字号按「图片像素 / 屏幕上看到的像素」放大：4K 的图缩到 800 宽来看，3px 的线细得看不见
function ivScale() { return ivBox.value.w > 0 && ivNat.value.w > 0 ? ivNat.value.w / ivBox.value.w : 1 }
function ivFontPx() { return Math.max(14, Math.round(16 * ivScale())) }
async function enterIvEdit() {
  const el = ivImg.value
  if (!el) return
  const im = new Image()
  im.src = el.src
  try { await im.decode() } catch (e) { toast('这张图解码不了，编辑没打开', 'error'); return }
  ivSrc = im
  ivNat.value = { w: im.naturalWidth, h: im.naturalHeight }
  ivOps.value = []; ivTool.value = null; ivTyping.value = null; ivLive = null
  zoomReset()
  ivEdit.value = true
  await nextTick()
  ivFit()
  ivDraw()
}
function exitIvEdit() {
  ivEdit.value = false
  ivOps.value = []; ivTool.value = null; ivTyping.value = null; ivLive = null
}
// 装得下就用原尺寸，装不下等比缩；四周要留给工具条和边让出余量
function ivFit() {
  const root = ivRoot.value
  if (!root || !ivNat.value.w) return
  const k = Math.min(1, (root.clientWidth - 96) / ivNat.value.w, (root.clientHeight - 150) / ivNat.value.h)
  ivBox.value = { w: Math.max(80, Math.round(ivNat.value.w * k)), h: Math.max(60, Math.round(ivNat.value.h * k)) }
}
function ivDraw() {
  const cv = ivCv.value
  if (!cv || !ivSrc) return
  const g = cv.getContext('2d')
  g.clearRect(0, 0, cv.width, cv.height)
  g.lineCap = 'round'
  g.lineJoin = 'round'
  ivOps.value.forEach(o => ivDrawOp(g, o))
}
function ivDrawOp(g, o) {
  g.strokeStyle = o.color
  g.fillStyle = o.color
  g.lineWidth = o.w
  if (o.t === 'rect') {
    g.strokeRect(Math.min(o.x0, o.x1), Math.min(o.y0, o.y1), Math.abs(o.x1 - o.x0), Math.abs(o.y1 - o.y0))
  } else if (o.t === 'arrow') {
    const a = Math.atan2(o.y1 - o.y0, o.x1 - o.x0)
    const L = Math.max(o.w * 3, Math.hypot(o.x1 - o.x0, o.y1 - o.y0) * 0.16)
    g.beginPath(); g.moveTo(o.x0, o.y0); g.lineTo(o.x1, o.y1); g.stroke()
    g.beginPath(); g.moveTo(o.x1, o.y1)
    g.lineTo(o.x1 - L * Math.cos(a - 0.42), o.y1 - L * Math.sin(a - 0.42))
    g.lineTo(o.x1 - L * Math.cos(a + 0.42), o.y1 - L * Math.sin(a + 0.42))
    g.closePath(); g.fill()
  } else if (o.t === 'pen') {
    g.beginPath()
    o.pts.forEach((p, i) => i ? g.lineTo(p.x, p.y) : g.moveTo(p.x, p.y))
    g.stroke()
  } else if (o.t === 'text') {
    g.font = o.size + 'px "Microsoft YaHei", system-ui, sans-serif'
    g.textBaseline = 'top'
    g.fillText(o.text, o.x, o.y)
  }
}
function pickIvTool(k) {
  if (ivTyping.value) ivCommitText()
  ivTool.value = ivTool.value === k ? null : k
}
function ivPt(e) {
  const cv = ivCv.value
  const r = cv.getBoundingClientRect()
  const k = r.width ? cv.width / r.width : 1
  return { x: (e.clientX - r.left) * k, y: (e.clientY - r.top) * k }
}
function ivDown(e) {
  if (e.button !== 0 || !ivTool.value) return
  // 必须挡下 mousedown 的默认动作：它会把焦点抢回 body，刚 focus 的文字输入框立刻 blur，
  // 空提交一次就等于输入框一闪就没（量出来的：点文字 → 输入框在:false）
  e.preventDefault()
  const cv = ivCv.value
  if (ivTool.value === 'text') {
    const r = cv.getBoundingClientRect(), p = ivPt(e)
    ivTyping.value = { x: e.clientX - r.left, y: e.clientY - r.top, px: p.x, py: p.y }
    ivText.value = ''
    nextTick(() => ivTxt.value && ivTxt.value.focus())
    return
  }
  const p = ivPt(e), w = Math.max(2, Math.round(3 * ivScale()))
  ivLive = ivTool.value === 'pen'
    ? { t: 'pen', pts: [p], w, color: ivColor.value }
    : { t: ivTool.value, x0: p.x, y0: p.y, x1: p.x, y1: p.y, w, color: ivColor.value }
  ivOps.value.push(ivLive)
}
function ivMove(e) {
  if (!ivLive) return
  const p = ivPt(e)
  if (ivLive.t === 'pen') ivLive.pts.push(p)
  else { ivLive.x1 = p.x; ivLive.y1 = p.y }
  ivDraw()
}
function ivUp() { ivLive = null }
function ivCommitText() {
  const t = ivTyping.value
  const v = ivText.value.trim()
  ivTyping.value = null
  if (!t || !v) return
  ivOps.value.push({ t: 'text', x: t.px, y: t.py, text: v, size: ivFontPx(), color: ivColor.value })
  ivDraw()
}
function ivCancelText() { ivText.value = ''; ivTyping.value = null }
function ivUndo() { ivOps.value.pop(); ivDraw() }
// 复制 = 底图和标注两张画到一块再写剪贴板。只到剪贴板，不替你发进会话
async function ivCopy() {
  const cv = ivCv.value
  if (!cv || !ivSrc) return
  const out = document.createElement('canvas')
  out.width = cv.width
  out.height = cv.height
  const g = out.getContext('2d')
  g.drawImage(ivSrc, 0, 0)
  g.drawImage(cv, 0, 0)
  try {
    const blob = await new Promise(res => out.toBlob(res, 'image/png'))
    if (!blob) throw new Error('画布导出失败')
    await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })])
    toast('已复制到剪贴板，去输入框 Ctrl+V 贴上，点发送才发出去', 'success')
  } catch (e) {
    toast('复制失败：' + (e?.message || e), 'error')
  }
}
// 编辑态里窗口变了要重新算适配尺寸（画布后备像素不变，所以已画的标注不会糊）
let ivOnResize = null
watch(ivEdit, v => {
  if (v) {
    ivOnResize = () => { ivFit(); ivDraw() }
    window.addEventListener('resize', ivOnResize)
  } else if (ivOnResize) {
    window.removeEventListener('resize', ivOnResize)
    ivOnResize = null
  }
})
// ESC：编辑态里先退编辑（一下把整层关掉太狠，画的那几笔还在），看图态里才关窗口。
// 监听跟着 imgView 的开/关挂和摘——原来只在按 ESC 那条路径上摘，点 ✕ 关就留一个钩子在外面。
// 顺带收掉右键菜单——菜单 z-index 9999 会浮到查看器上面（那段统一的弹框 watcher 在 imgView 之前，够不着）
function ivEsc(e) {
  if (e.key !== 'Escape') return
  if (ivEdit.value) { exitIvEdit(); return }
  closeImageView()
}
watch(imgView, v => {
  if (!v) { document.removeEventListener('keydown', ivEsc); return }
  closeAllMenus()
  document.addEventListener('keydown', ivEsc)
})

/* ===== 文件气泡点开：一律交给本地，不再自绘预览弹框 =====
   软件 / 压缩包 → 直接弹系统那个「另存为」；其余 → 落到「设置 → 文档路径」那个目录后用系统默认应用打开；
   没有默认应用时由主进程兜底（200KB 以内交记事本，超出只落文件、不开）。
   网页端没有本地应用这条路，点开就是浏览器下载。 */
const canOpenApp = !!window.chatDesktop?.openFile
// 判"有没有默认应用"在主进程查注册表，不拿 shell.openPath 的返回值当判据（它没关联也返回空串）
const SAVE_EXT = new Set(['zip', 'rar', '7z', 'tar', 'gz', 'tgz', 'bz2', 'xz', 'iso',
  'exe', 'msi', 'apk', 'ipa', 'deb', 'rpm', 'appx', 'msix', 'jar', 'bat', 'cmd', 'sh'])
function extOf(r) { return String(r?.name || '').split('.').pop().toLowerCase() }

async function openFileRef(r) {
  if (!r) return
  closeAllMenus()
  if (SAVE_EXT.has(extOf(r))) { saveAsRef(r); return }
  if (!canOpenApp) { downloadRef(r); toast('已交给浏览器下载', 'success'); return }
  openWithApp(r)
}
// 字节两头都从同一个地方取：消息里带的 dataUrl，否则按 fileId 换 objectURL
async function refBytes(r) {
  const url = r.dataUrl || await fileObjectUrl(r.fileId)
  return new Uint8Array(await (await fetch(url)).arrayBuffer())
}
// 落到「文档路径」那个目录 + 交系统默认应用打开；写盘、查关联、兜底都在主进程，这边只报它做了什么。
// 第一趟不带字节：主进程按 fileId 认得出"这条已经存过了"，那就直接开，那趟下载整个省掉
let openingFile = false
async function openWithApp(r) {
  if (!r || openingFile) return
  openingFile = true
  try {
    const ask = { name: r.name || '文件', size: Number(r.size) || 0, fileId: r.fileId || '' }
    let res = await window.chatDesktop.openFile(ask)
    if (res?.need) res = await window.chatDesktop.openFile({ ...ask, bytes: await refBytes(r) })
    if (!res?.ok) { toast('打开失败：' + (res?.error || '未知错误'), 'error'); return }
    const byHow = {
      default: '正在用默认应用打开',
      notepad: '没有默认应用，已用记事本打开',
      'saved-only': '没有默认应用且超过 200KB，没有打开',
      refused: '是可直接运行的文件，没有运行'
    }
    toast(`${r.name} ${res.existed ? `已在 ${res.dir}，` : `已存到 ${res.dir}，`}${byHow[res.how] || '已打开'}`, 'success')
  } catch (e) {
    toast('打开失败：' + (e?.message || e), 'error')
  } finally {
    openingFile = false
  }
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

/* ===== 会话右键菜单 ===== */
/* 会话/输入框/消息区/背景这几个右键菜单 + 图标栏 ☰ 弹层共用的外壳。
   ☰ 原来自己带 --shadow-2 和不透明白底，跟这几个不是一类；现在两边的值逐项相同，
   合成一条规则，免得以后各改一边又对不齐 */
.conv-context-menu, .rail-menu {
  position: fixed;
  background: rgba(255, 255, 255, 0.97);
  border: 0;
  border-radius: 12px;
  /* 同上：参考图量出来的边缘 13% 黑、14px 铺开 */
  box-shadow: 0 2px 14px rgba(20, 32, 56, 0.13);
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

/* 快捷键那一列：靠右、淡一档，只是提示不是第二个按钮 */
.cm-kb { margin-left: auto; padding-left: 24px; font-size: 11.5px; color: var(--nb-dim-2); letter-spacing: 0; }
/* 置灰不消失：没选区的剪切/复制、空框的全选点了不该有反应，hover 底色也不该骗人 */
.conv-menu-item.off { color: var(--nb-dim-2); cursor: default; }
.conv-menu-item.off:hover { background: none; }
.conv-menu-item.off .cm-kb { color: color-mix(in srgb, var(--nb-dim-2) 55%, #fff); }

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
   四栏骨架 · 设计稿图3（浅色蓝）
   栏宽走 --rail-w / --list-w / --drawer-w；
   三栏 chrome 贴视口边、上下无缝、radius 0，只有内容元素带小圆角。
   ============================================================ */
/* 外层用 --nb-bg-shell：比卡片内最深的底色（列表 --nb-bg-3）再压一档，缝隙才读得出是一条线；
   会话区本身就是 --nb-bg-0，同色会让右边的缝隐形，所以这里不用 --nb-bg-0 铺底 */
/* 会话区标题栏和抽屉页签条共用同一个行高：两条 border-bottom 落在同一个 y，
   横过卡片才是一条直线（原来差 1px，接缝处看得见一个台阶） */
.app4 { display: flex; height: calc(100vh - var(--winbar-h)); flex: 1 1 auto; min-height: 0;
  --head-h: 50px;
  background: var(--nb-bg-shell); font-size: 14px; }
/* 列表 / 会话 / 抽屉 / 侧面板合成一张卡片：栏与栏之间不画线，靠底色分层；
   轮廓由外层透出右边和下边各 5px 缝隙 + 8px 圆角显示；左栏仍贴视口边、不带圆角 */
.body-row { flex: 1 1 auto; min-width: 0; display: flex; overflow: hidden;
  border-radius: 8px; margin: 0 5px 5px 0; background: #fff; }

.tag-demo {
  display: inline-block; margin-left: 6px; padding: 0 5px; border-radius: 3px;
  background: rgba(224, 138, 30, 0.12); color: var(--warn);
  font-size: 10px; line-height: 15px; font-weight: 400; vertical-align: 1px;
}
.tag-demo.big { font-size: 11px; line-height: 20px; }

/* ---- ① 图标栏 ---- */
/* 和标题栏、卡片外的缝隙同色：整条左栏就是"最底那一层"露出来的部分，不画边、不另起一块白 */
.rail {
  width: var(--rail-w); flex: 0 0 var(--rail-w); background: var(--nb-bg-shell);
  display: flex; flex-direction: column; align-items: center; padding: 0 0 5px;
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
.rail-btn:hover, .rail-btn.open { background: var(--nb-bg-3); color: var(--nb-text); }
.rail-group.wb-group { margin-top: 6px; padding-top: 8px; border-top: 1px solid var(--nb-line); }
.rail-btn.active { background: var(--brand-soft); color: var(--brand); }
.rail-badge {
  position: absolute; top: 2px; right: 0; min-width: 16px; height: 16px; padding: 0 4px;
  border-radius: 8px; background: var(--danger); color: #fff;
  font-size: 10px; line-height: 16px; text-align: center;
}
.rail-foot { display: flex; flex-direction: column; align-items: center; gap: 8px; }
.rail-conn { width: 8px; height: 8px; border-radius: 50%; background: var(--nb-dim-2); }
.rail-conn.ONLINE { background: var(--ok); }
.rail-conn.BUSY { background: var(--warn); }

/* ---- 图标栏 ☰ 菜单：外壳在上面那条共用规则里，这里只留自己的注释 ---- */
.rail-menu-hd { padding: 12px 16px 8px; font-size: 11.5px; color: var(--nb-dim); }
.rail-menu-list { padding: 4px 0; }
.rail-menu-item {
  display: flex; align-items: center; gap: 10px; width: 100%; padding: 9px 16px;
  /* 16px 是右键菜单那行里图标撑出来的内容高；全局是 border-box，上下 9px 内距必须一起算进
     min-height，只写 16px 会被内距吃掉完全不生效（量出来 31 vs 34 就是这么来的） */
  min-height: calc(16px + 9px + 9px);
  font: inherit; font-size: 13px; line-height: 1; letter-spacing: 0.3px; text-align: left; color: var(--nb-text);
  background: none; border: 0; cursor: pointer;
  transition: background-color .12s ease, color .12s ease;
}
.rail-menu-item:hover { background: rgba(43, 107, 232, 0.07); }
.rail-menu-item.on { color: var(--brand); }
.rail-menu-item.quit:hover { color: var(--danger); }
.pm-tick { margin-left: auto; font-size: 12px; color: var(--brand); }
.pm-dot { flex: 0 0 8px; width: 8px; height: 8px; border-radius: 50%; background: var(--nb-dim-2); }
.pm-dot.ONLINE { background: var(--ok); }
.pm-dot.BUSY { background: var(--warn); }
.rail-menu-sep { height: 1px; margin: 4px 12px; background: rgba(43, 107, 232, 0.1); }

/* ---- ② 列表栏 ---- */
/* 卡片内部不画分隔线：列表比会话区深一档，靠这一步色差分出轮廓。
   --row-hover 在列表底之上再压一档，不然 hover 和底色同为 --nb-bg-3 就看不见了 */
.side {
  width: var(--list-w); flex: 0 0 var(--list-w); background: var(--nb-bg-3);
  --row-hover: color-mix(in srgb, var(--nb-dim-2) 16%, var(--nb-bg-3));
  display: flex; flex-direction: column;
}
/* min-height 50 = 通讯录/项目群组两页量出来的现值（上内距 14 + 那颗 ＋ 26 + 下内距 10）。
   消息页没有那颗 ＋，标题行只有 23px 高，整栏会比另外两页矮 3px —— 换页签时下面的搜索框会跳 */
.side-head { display: flex; align-items: flex-start; padding: 14px 14px 10px; min-height: 50px; }
/* 标题和计数同一行、按基线对齐（16px 的标题和 12px 的计数顶对齐会显得计数往下掉） */
.side-title { flex: 1; min-width: 0; display: flex; align-items: baseline; gap: 8px; }
.side-title > span:first-child { font-size: 16px; font-weight: 600; color: var(--nb-text); }
.side-meta { margin-top: 0; font-size: 12px; color: var(--nb-dim); }
.side-acts { display: flex; gap: 6px; }
.side-act {
  width: 26px; height: 26px; border: 1px solid var(--nb-line); border-radius: 6px;
  background: #fff; color: var(--brand); font-size: 15px; line-height: 1; cursor: pointer;
}
.side-act:hover { background: var(--brand-soft); }
.side-search {
  display: flex; align-items: center; gap: 6px; height: 32px; margin: 0 12px; padding: 0 10px;
  background: var(--nb-bg-1); border: 1px solid transparent; border-radius: 7px;
}
.side-search:focus-within { border-color: var(--brand-line); }
.ss-ico { color: var(--nb-dim); font-size: 14px; }
.ss-input { flex: 1; min-width: 0; border: 0; outline: none; background: transparent; color: var(--nb-text); font: inherit; font-size: 13px; }
.ss-clear { color: var(--nb-dim); cursor: pointer; font-size: 12px; }
.side-list { flex: 1; min-height: 0; position: relative; display: flex; flex-direction: column; }
.side-body { flex: 1; min-height: 0; overflow-y: auto; padding: 0 8px 10px; position: relative; }
/* 消息 tab 上给右侧索引条留 17px（和 AlphaList 里 .al-body 那档一样），行里的时间/未读跟着往左挪 */
.side-body.has-rail { padding-right: 25px; }
/* 这条列表不画滚动条，但照常能滚：overflow 不动，只是不占那 6px、也不显示滑块，位置感交给右侧字母索引。
   别顺手写 scrollbar-width —— 一旦设成非 auto，整组 ::-webkit-scrollbar 规则会被静默废掉 */
.side-body::-webkit-scrollbar { width: 0; height: 0; }
.sec {
  display: flex; align-items: center; gap: 6px; padding: 12px 8px 6px;
  font-size: 11px; color: var(--nb-dim-2); letter-spacing: .4px;
}
.row {
  position: relative; display: flex; align-items: center; gap: 10px;
  padding: 8px; margin-bottom: 4px; border-radius: 8px; cursor: pointer;
  /* 滚动后 hover 底色会残留一小截（他那张图量出来：行宽 246、高 8px 的 #E0E4EC 窄带落在行缝上）。
     给底色加一段过渡，hover 变更时整个矩形会被重新invalidate，不留旧瓦片 */
  transition: background-color .12s ease;
}
.row:hover { background: var(--row-hover); }
/* 选中原来直接吃 --brand-soft（品牌色约 10% 落白），在列表灰底上几乎看不出来。
   这里按 20% 重混：比 hover 那档深一档，也不会和 --row-hover 撞色 */
.row.active { background: color-mix(in srgb, var(--brand) 20%, var(--nb-bg-1)); }
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
/* 行上的 ⋯ 常驻可见（只在 hover 才出现的话，看不见的那会儿也就点不着了）；
   反馈只换颜色和底色，不动尺寸 */
.row-more {
  width: 24px; height: 24px; flex: 0 0 24px; padding: 0; border: 1px solid transparent;
  background: none; border-radius: 6px; color: var(--nb-dim); font-size: 15px; line-height: 1; cursor: pointer;
  transition: color .12s ease, background-color .12s ease, border-color .12s ease;
}
.row-more:hover, .row-more:focus-visible { color: var(--brand); background: var(--brand-soft); border-color: var(--brand-line); }
.btn-del { border-color: rgba(217, 72, 96, .4); color: var(--danger); }
.btn-del:hover:not(:disabled) { background: rgba(217, 72, 96, .1); color: var(--danger); box-shadow: none; }
/* 群头像跟列表里那颗一样是圆角方块，不是圆 */
.avatar.group-ava { border-radius: 18px; background: #4f86f5; }
.list-empty { padding: 22px 8px; text-align: center; color: var(--nb-dim-2); font-size: 13px; }

/* ---- ③ 会话主区 ---- */
.main { position: relative; flex: 1; min-width: 0; display: flex; flex-direction: column; background: #fff; }
.m-head { display: flex; align-items: center; gap: 12px; padding: 12px 18px 10px; min-height: var(--head-h); border-bottom: 1px solid var(--nb-line-soft); }
.mh-title { display: flex; align-items: center; gap: 4px; min-width: 0; }
.mh-hash { color: var(--nb-dim-2); font-size: 17px; }
.mh-name { font-size: 17px; font-weight: 600; color: var(--nb-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mh-sub { font-size: 12px; color: var(--nb-dim); flex: 1; min-width: 0; }
.mh-stack { display: flex; align-items: center; }
.stack-ava {
  width: 24px; height: 24px; border-radius: 50%; background: var(--brand); color: #fff; display: grid; place-items: center;
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
.m-body { flex: 1; overflow-y: auto; padding: 16px 18px 40px; background: var(--nb-bg-1); }
/* 没有可显示的消息时的占位：只有一枚浅灰图形，不铺演示对话，也不写文案。
   底色跟着 .m-body 走同一个令牌，气泡上的眼睛才镂得空 */
.m-empty { height: 100%; display: grid; place-items: center; }
.m-empty svg { fill: color-mix(in srgb, var(--nb-dim-2) 26%, var(--nb-bg-1)); }
/* 气泡上的眼睛要"镂空"：presentation attribute 不认 var()，只能写在 style 里让它进 CSS 级联 */
.m-empty svg .eye { fill: var(--nb-bg-1); }
/* 抽屉收起时消息列直接顶到窗口右缘，滚动条会和系统的拉伸热区叠在一处。
   透明右边框把滚动条让进来：桌面壳上 Windows 自己会画 1px 窗框、正好盖住页面最右一列，
   所以留 2px 才看得见 1px 缝。边框画在滚动条外侧，底色由 background-clip 补上，看不出接缝 */
.main.no-drawer .m-body { border-right: 2px solid transparent; }
/* 横排定位尺：贴在消息区底部、整条水平居中（left 50% + translateX(-50%)），
   尺子绝对定位在 .main 上，所以内容滚动时它不动 */
.ovr { position: absolute; left: 50%; height: 8px; transform: translateX(-50%); pointer-events: none; }
/* 全局是 border-box，这里反过来用 content-box：可见条只有 height 那 3px，四周 2px padding 是
   透明的命中边（底色同样 clip 到 content-box，所以那圈不画出来），和输入框那颗凸块一个做法。
   top: -1px 让 3px 那条的底边正好压在改前那一行上，刻度离消息区底缘的 16px 不会因为变厚而挪走 */
.ovr-t { position: absolute; top: -1px; width: var(--tw); height: 3px; padding: 2px; border: 0;
  box-sizing: content-box; border-radius: 1px; background: color-mix(in srgb, var(--brand) 20%, #fff) content-box;
  pointer-events: auto; cursor: pointer;
  transform: translateX(-50%);
  transition: background-color .16s ease; }
/* hover / 聚焦：只换底色，不长宽、不变厚、不加光圈 —— 和输入框那颗凸块同一条规矩。
   上色只能写 background-color：写简写 background 会把上面那条 content-box 裁切重置回 border-box，
   透明命中边会跟着一起涂蓝（量到过：画满 8×3 那一格，而不是只换色） */
.ovr-t:hover:not(:disabled), .ovr-t:focus-visible { background-color: var(--brand); }
.ovr-t:disabled { cursor: default; }
/* 自绘气泡：原生 title 要等约 1s、样式不可控、还会被无边框窗口裁掉。
   单行、超出用省略号；锚在整排刻度正上方，所以不需要左右夹取 */
.ovr-tip { position: absolute; left: 50%; bottom: 13px; max-width: 320px; display: flex; align-items: baseline;
  gap: 6px; padding: 5px 10px; border: 1px solid var(--nb-line); border-radius: 6px; background: #fff;
  box-shadow: 0 4px 14px rgba(24, 44, 84, .12); color: var(--nb-text); font-size: 12px; line-height: 1.4;
  white-space: nowrap; opacity: 0; transform: translate(-50%, 4px); pointer-events: none;
  transition: opacity .14s ease, transform .18s cubic-bezier(.34, 1.42, .64, 1); }
.ovr-tip.on { opacity: 1; transform: translate(-50%, 0); }
.tip-t { flex: 0 0 auto; color: var(--nb-dim); font-variant-numeric: tabular-nums; }
.tip-x { min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.day-split { text-align: center; margin: 6px 0 16px; }
/* 日期块和气泡同一种处理（白底上填一块灰）；字跟着从 --nb-dim-2 提到 --nb-dim，
   不然灰块上的 11px 小字对比只剩 2.66，比原来白块上还低 */
.day-split span {
  display: inline-block; padding: 2px 10px; border-radius: 10px; background: var(--nb-bg-3);
  color: var(--nb-dim); font-size: 11px;
}
.msg { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 16px; }
/* 自己的消息：DOM 里已经是「气泡在前、头像在后」，所以只靠右对齐，
   不能再 row-reverse —— 两次反转会把头像甩到左边。 */
.msg.self { justify-content: flex-end; }
.msg-ava {
  width: 34px; height: 34px; flex: 0 0 34px; border-radius: 10px; background: var(--brand); color: #fff;
  display: grid; place-items: center; font-size: 13px; overflow: hidden; cursor: pointer;
}
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
  padding: 6px 13px; border-radius: 10px; background: var(--nb-bg-3);
  color: var(--nb-text); line-height: 22px; word-break: break-word; white-space: pre-wrap;
  box-shadow: var(--shadow-1);
}
.msg.self .bubble { background: var(--brand); color: #fff; }
/* 图片消息不要那圈底色和内边距：图自己就是这块表面。圆角 10px 和头像/文字气泡同值，
   白底上的白图靠图自己那点投影分出来，不靠色块 */
.bubble.b-media, .msg.self .bubble.b-media { padding: 0; background: none; box-shadow: none; }
/* 文件也一样把底色撤掉：那张白卡片自己就是这块表面，外面再套一圈蓝底（自己发的）
   或灰底（别人的）只是把它框了一层，看着像"气泡里塞了个控件" */
.bubble.b-doc, .msg.self .bubble.b-doc { padding: 0; background: none; box-shadow: none; }
/* 引用行：气泡下方一小块灰字，左边一条竖线。自己发的要靠右，所以用 fit-content + margin-left:auto */
.msg-quote {
  margin-top: 4px; width: fit-content; max-width: 100%;
  padding: 1px 0 1px 8px; border-left: 2px solid var(--nb-line);
  font-size: 12px; line-height: 1.5; color: var(--nb-dim);
  white-space: pre-wrap; word-break: break-word;
}
.msg.self .msg-quote { margin-left: auto; }
/* 引用的是图片/文件：名字和缩略图/文件片排一行，放不下就折，别把图挤扁 */
.msg-quote.q-media { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; white-space: normal }
.mq-name { flex: none }
.mq-th { max-width: 132px; max-height: 88px; border-radius: 8px; object-fit: cover; cursor: zoom-in; box-shadow: var(--shadow-1) }
.mq-wait { flex: none; color: var(--nb-dim-2) }
/* 引用里的文件片只改尺寸，形状/描边/点开行为跟正文里那张是同一个 .b-file */
.mq-file { max-width: 220px; padding: 4px 8px }
.mention { color: var(--brand); font-weight: 500; }
.msg.self .mention { color: #dbe7ff; }
.b-del { color: var(--nb-dim-2); font-style: italic; }
.b-img { display: block; max-width: 280px; border-radius: 10px; cursor: zoom-in; box-shadow: var(--shadow-1); }
/* ---- 图片查看器 ---- */
.img-view {
  position: fixed; inset: 0; z-index: 10000; background: rgba(12, 18, 30, .86);
  display: grid; place-items: center; overflow: hidden;
}
.iv-img {
  max-width: 86vw; max-height: 82vh; border-radius: 10px; user-select: none; touch-action: none;
  cursor: grab; will-change: transform;
}
.iv-img:active { cursor: grabbing; }
.iv-bar {
  position: absolute; left: 50%; bottom: 22px; transform: translateX(-50%);
  display: flex; align-items: center; gap: 2px; padding: 5px 6px; border-radius: 10px;
  background: rgba(255, 255, 255, .94); box-shadow: 0 2px 14px rgba(20, 32, 56, .13);
}
.iv-btn {
  width: 28px; height: 28px; border: 0; border-radius: 7px; background: transparent;
  color: var(--nb-text); font-size: 16px; display: grid; place-items: center; cursor: pointer;
}
.iv-btn.wide { width: auto; padding: 0 10px; font-size: 12px; }
/* 悬停只换底色，不长尺寸 */
.iv-btn:hover:not(:disabled) { background: var(--nb-bg-3); }
.iv-btn:disabled { color: var(--nb-dim-2); cursor: default; }
.iv-pct { min-width: 46px; text-align: center; font-size: 12px; color: var(--nb-dim); }
/* 编辑态：底图和一层透明画布叠成一块。画布后备像素 = 图片原始尺寸，
   所以标注坐标就是图片像素，导出是两张叠一块，不经过缩放映射 */
.iv-stage { position: relative; }
.iv-base { display: block; width: 100%; height: 100%; border-radius: 10px; user-select: none; }
.iv-cv { position: absolute; inset: 0; width: 100%; height: 100%; border-radius: 10px; touch-action: none; cursor: default; }
.iv-cv.draw { cursor: crosshair; }
.iv-txt { position: absolute; min-width: 120px; padding: 2px 6px; border: 1px solid var(--brand);
  border-radius: 5px; background: rgba(255, 255, 255, .96); color: #111; font-size: 16px; outline: none; }
.iv-sp { width: 1px; height: 20px; margin: 0 4px; background: var(--nb-line); }
.iv-dot { width: 18px; height: 18px; padding: 0; border: 2px solid transparent; border-radius: 50%; cursor: pointer; }
.iv-dot.on { border-color: var(--nb-text); }
.iv-btn.on { background: var(--brand); color: #fff; }
.iv-btn.on:hover:not(:disabled) { background: var(--brand-strong); }
.iv-x {
  position: absolute; top: 14px; right: 16px; width: 30px; height: 30px; border: 0; border-radius: 8px;
  background: rgba(255, 255, 255, .14); color: #fff; font-size: 15px; display: grid; place-items: center; cursor: pointer;
}
.iv-x:hover { background: rgba(255, 255, 255, .26); }
.b-wait { display: inline-block; min-width: 96px; font-size: 12px; color: var(--nb-dim); }
/* 气泡已经是灰底了，里面这颗文件片得反过来用白底才分得开（原来是灰片在白气泡上）。
   文字颜色必须写死：片子底永远是白的，跟着气泡 inherit 的话自己发的那条就是白字落白底
   （实测名字对底 Δ0，只剩右边那个灰色的"5 B"看得见） */
.b-file { display: inline-flex; align-items: center; gap: 8px; max-width: 240px; padding: 6px 10px; border: 1px solid var(--nb-line); border-radius: 8px; background: var(--nb-bg-1); color: var(--nb-text); text-decoration: none; cursor: pointer; }
.b-file:hover { border-color: var(--brand); }
.b-file-nm { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 13px; }
.b-file-sz { flex: none; font-size: 11px; color: var(--nb-dim); }
.msg-fail {
  width: 16px; height: 16px; flex: 0 0 16px; margin-top: 6px; border-radius: 50%;
  background: var(--danger); color: #fff; font-size: 11px; line-height: 16px; text-align: center; cursor: help;
}
/* 消息列不设 max-width、也不居中：左右间隙就是 .m-body 的 18px padding，恒定不变，
   窗口多宽就铺多宽。别再给消息列加回 900px 上限——那会在宽屏右边留一大片死白。 */

/* ---- 输入区 ---- */
.m-input { position: relative; border-top: 1px solid var(--nb-line); background: #fff; padding: 10px 16px 12px; }
/* 待发送附件条：横向卡片流，和选人弹框那两块一个做法；只在里面滚，外层不滚。
   文件名折行不截断——截了等于这张卡片没做完，而且这是要发出去的东西，看不全就不该点发送 */
.pend {
  display: flex; flex-wrap: wrap; gap: 6px; max-height: 132px; overflow-y: auto;
  margin-bottom: 8px; padding-bottom: 8px; border-bottom: 1px solid var(--nb-line);
}
.pc {
  display: grid; grid-template-columns: auto auto minmax(0, 1fr) auto; align-items: center; gap: 8px;
  max-width: 260px; padding: 5px 6px; border: 1px solid var(--nb-line); border-radius: 10px; background: var(--nb-bg-1);
}
.pc-th { width: 38px; height: 38px; border-radius: 8px; object-fit: cover; }
.pc-ic { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 8px;
  background: var(--nb-bg-3); color: var(--brand); }
.pc-tx { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.pc-nm { font-size: 12.5px; font-weight: 600; line-height: 15px; color: var(--nb-text); word-break: break-all }
.pc-sz { font-size: 11px; line-height: 14px; color: var(--nb-dim) }
.pc.busy { border-color: var(--brand-line) }
.pc.err { border-color: var(--danger) }
/* ✕ 一直看得见：hover 才出现的角标，看不见的时候也就点不着；这里只换颜色不动尺寸 */
.pc-x { width: 20px; height: 20px; flex: none; border: 0; border-radius: 6px; background: none;
  color: var(--nb-dim-2); font-size: 11px; line-height: 20px; text-align: center; cursor: pointer; transition: background .12s, color .12s }
.pc-x:hover { background: var(--nb-bg-3); color: var(--danger) }
.pend-n { font-size: 12px; color: var(--nb-dim) }
.snd-n { font-size: 12px; font-weight: 400 }
.drop-mask {
  position: absolute; inset: 0; z-index: 2; display: grid; place-content: center; justify-items: center; gap: 6px;
  background: rgba(43, 107, 232, .08); border: 1px dashed var(--brand-line); color: var(--brand);
  /* 纯提示层，不能接事件：它一盖住指针，指针下面就从输入区变成这张遮罩，
     容器收到一次 dragleave → 遮罩撤掉 → 指针又落回输入区 → dragover → 遮罩盖上……
     按住不放时这个环每帧转一次，就是他说的一直闪 */
  pointer-events: none;
}
.area {
  width: 100%; min-height: 56px; max-height: 320px; border: 0; outline: none; resize: none;
  font: inherit; color: var(--nb-text); background: transparent;
}
/* 只有那颗凸块能拖：整条关掉 pointer 事件，凸块自己开回来。
   内边距撑出 11px 的按压高度，底色用 content-box 只画中间 6px（端头圆角上限=厚度一半，4px 厚时半径只有 2px，看着偏方），所以看得见的是凸块、点得着的也是凸块 */
.rz { display: flex; justify-content: center; height: 11px; margin: -6px -16px 3px; pointer-events: none; }
.rz-grip { display: block; width: 46px; height: 11px; padding: 2.5px 0; box-sizing: border-box;
  border-radius: 999px; background: var(--nb-line) content-box; outline: none;
  cursor: row-resize; touch-action: none; pointer-events: auto;
  transition: background .14s; }
/* 只换色、不跟着变宽度：凸块从 62px 缩回 46px 时让出来的那 8px 端头，在桌面壳那侧的合成器里
   不保证会被重画（真机量到残留 10 个像素、最深处 rgb(78,132,235)，要靠改窗口尺寸才冲掉），
   所以悬停反馈只留给颜色这一档 */
.rz-grip:hover, .rz-grip:focus-visible, .m-input.rzging .rz-grip { background: var(--brand) content-box; }
.m-input.rzging { cursor: row-resize; }
.bar { display: flex; align-items: center; justify-content: space-between; border-top: 1px solid var(--nb-line); padding-top: 8px; }
.bar-tools { display: flex; align-items: center; gap: 2px; }
.tool-wrap { position: relative; display: flex; }
.tool {
  width: 30px; height: 30px; border: 0; border-radius: 7px; background: transparent;
  color: var(--nb-dim); display: grid; place-items: center; cursor: pointer; font-size: 15px;
}
.tool:hover, .tool.open { background: var(--brand-soft); color: var(--brand); }
/* 置灰不消失：私聊里没有"群里的人"可 @，但这颗的位置要留着，抽掉整条工具栏会跳 */
.tool:disabled { color: var(--nb-dim-2); cursor: default; }
.tool:disabled:hover { background: transparent; color: var(--nb-dim-2); }
.tool.at { font-size: 17px; }
.tool.ai { color: var(--brand); }
/* @ 成员名单：挂在输入区上、往上开（输入区贴着窗口底，往下开就出屏了）。
   外壳和表情框同一套：白底、10px 圆角、软阴影、一条线描边 */
.mention-pop {
  position: absolute; bottom: calc(100% - 2px); left: 16px; z-index: 24; width: 232px; padding: 6px;
  background: #fff; border: 1px solid var(--nb-line); border-radius: 10px; box-shadow: var(--shadow-2);
}
.mp-cap { padding: 2px 6px 6px; font-size: 11px; letter-spacing: 1px; color: var(--nb-dim-2); }
/* 名单只在里面滚，外层不滚；行高 30px，八条出头就开始滚。
   行间给 3px 缝：原来几条贴着排，选中那条的高亮块和上下块糊成一片，看不出选的是谁 */
.mp-list { display: flex; flex-direction: column; gap: 3px; max-height: 250px; overflow-y: auto }
.mp-row { display: flex; align-items: center; gap: 8px; width: 100%; padding: 4px 6px; border: 0;
  border-radius: 7px; background: transparent; color: var(--nb-text); font: inherit; font-size: 13px; text-align: left; cursor: pointer }
.mp-row:hover, .mp-row.on { background: var(--brand-soft) }
.mp-av { width: 22px; height: 22px; flex: none; border-radius: 6px; background: var(--brand-soft);
  color: var(--brand-strong); font-size: 11px; font-weight: 600; display: grid; place-items: center }
.mp-nm { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.mp-empty { padding: 10px 6px; font-size: 12px; color: var(--nb-dim); text-align: center }
.emoji-pop {
  position: absolute; bottom: 34px; left: 0; z-index: 20; width: 272px; padding: 8px;
  background: #fff; border: 1px solid var(--nb-line); border-radius: 10px; box-shadow: var(--shadow-2);
}
/* 弹框底边离工具栏那行只有 4px（bottom:34px 减掉 30px 高的按钮），鼠标跨这 4px 时既不在按钮上
   也不在弹框上 → mouseleave 先把它关了，看着就是"还没移上去就没了"。
   补一条透明的桥：它是弹框的后代，指针落在桥上仍算在弹框内，所以不用退回去加宽限计时器 */
.emoji-pop::after { content: ""; position: absolute; left: 0; right: 0; top: 100%; height: 16px; }
/* 轨道原来缩在弹框里 8px（.emoji-pop 的 padding），右边露出一条缝、看着像滚动条跑偏。
   把格子的盒子往外推 8px 让轨道贴住边框，再用 padding 把格子本身留回 8px 不贴条 */
.emoji-grid { display: grid; grid-template-columns: repeat(8, 1fr); gap: 2px; margin-right: -8px; padding-right: 8px; }
.emoji-cell { padding: 3px; font-size: 18px; text-align: center; cursor: pointer; border-radius: 4px; }
.emoji-cell:hover { background: var(--brand-soft); }
.emoji-hint { margin-top: 4px; padding-top: 6px; border-top: 1px solid var(--nb-line); font-size: 11px; color: var(--nb-dim); }
.bar-right { display: flex; align-items: center; gap: 10px; }
/* 字数槽常驻、只切 visibility，宽度按最长那一档（4 位数 + "· 将以 txt 发送"）量出来定死，
   所以打字过程中「发送」那颗不会左右跳 */
.bar-cnt { visibility: hidden; flex: none; min-width: 158px; text-align: right; font-size: 12px; color: var(--nb-dim); }
.bar-cnt.show { visibility: visible }
.bar-cnt.over { color: var(--warn) }
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
  /* 会话区和抽屉都是白底，这条竖线是两者唯一的分界；和上面那条横线同一个淡度 */
  border-left: 1px solid var(--nb-line-soft);
  display: flex; flex-direction: column;
}
/* 页签条的下沿和会话区标题栏的下沿是"同一条线"的两段：颜色、行高都必须同源
   （--nb-line-soft + --head-h），差 1px 或差一档色，接缝处就看得见台阶 */
.dw-tabs { display: flex; align-items: center; gap: 18px; padding: 12px 16px 7px; min-height: var(--head-h); border-bottom: 1px solid var(--nb-line-soft); }
.dwt { position: relative; border: 0; background: transparent; padding: 0 0 10px; font-size: 13px; color: var(--nb-dim); cursor: pointer; }
.dwt.on { color: var(--nb-text); font-weight: 500; }
.dwt.on::after { content: ""; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px; background: var(--brand); }
.dw-close { margin-left: auto; border: 0; background: transparent; color: var(--nb-dim-2); cursor: pointer; font-size: 13px; padding: 0 0 10px; }
/* 上下内边距从 14/20 收到 12/8：内容 714px、窗口 820 时可用只有 700，
   原来那 6px（页签加高后变 13px）的溢出会撑出一条几乎满高的 thumb，看着像根不能动的僵尸条。
   窗口再矮就真的装不下了，那时出滚动条是对的。 */
.dw-body { flex: 1; overflow-y: auto; padding: 12px 16px 8px; }

/* ---- 抽屉「成员」页签：照微信「聊天信息」那一页的版式 ---- */
/* .dw-body 左右各 16px，分隔线要通到抽屉边缘就用 -16px 把容器顶出去，行内再用 16px 收回来了 */
.gs-search {
  display: flex; align-items: center; gap: 6px; height: 32px; padding: 0 10px;
  background: var(--nb-bg-3); border: 1px solid transparent; border-radius: 7px;
}
.gs-search:focus-within { border-color: var(--brand-line); }
.gs-ico { color: var(--nb-dim); font-size: 14px; }
.gs-search input { flex: 1; min-width: 0; border: 0; outline: none; background: transparent;
  color: var(--nb-text); font: inherit; font-size: 13px; }
.gs-clear { color: var(--nb-dim); cursor: pointer; font-size: 12px; }

/* 一行四个：236px 内容宽 - 3×8 间隙 = 53px 一格，头像 44px */
.gs-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px 8px; margin-top: 14px; }
.gs-cell { display: flex; flex-direction: column; align-items: center; gap: 4px; cursor: pointer; }
.gs-ava { position: relative; width: 44px; height: 44px; }
.gs-init {
  width: 44px; height: 44px; border-radius: 8px; background: var(--brand); color: #fff;
  display: grid; place-items: center; font-size: 15px;
}
.gs-name { max-width: 100%; font-size: 11px; color: var(--nb-dim); text-align: center;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.gs-tag {
  position: absolute; right: -3px; bottom: -3px; padding: 0 3px; border-radius: 3px;
  color: #fff; font-size: 9px; line-height: 13px;
}
.gs-tag.owner { background: var(--warn); }
.gs-tag.admin { background: var(--brand); }
/* 移出模式：只有真的能被移走的那个人显示红减号；命中区=看得见的凸块本身 */
.gs-minus {
  position: absolute; right: -4px; bottom: -4px; width: 16px; height: 16px; border-radius: 50%;
  background: var(--danger); color: #fff; display: grid; place-items: center; line-height: 0;
}
.gs-cell.picking .gs-init { outline: 2px solid var(--danger); outline-offset: 1px; }
.gs-tile { display: flex; flex-direction: column; align-items: center; gap: 4px;
  border: 0; background: none; padding: 0; cursor: pointer; }
.gs-box { width: 44px; height: 44px; border-radius: 8px; border: 1px dashed var(--nb-dim-2);
  color: var(--nb-dim-2); display: grid; place-items: center; font-size: 17px; line-height: 1; }
.gs-tile em { font-style: normal; font-size: 11px; color: var(--nb-dim-2); }
.gs-tile:hover:not(:disabled) .gs-box { border-color: var(--brand); color: var(--brand); }
.gs-tile.on .gs-box { border-style: solid; border-color: var(--danger); color: var(--danger); }
.gs-tile.on em { color: var(--danger); }
/* 后端没有"能移出任何人"的权限时置灰不消失 */
.gs-tile:disabled { opacity: .45; cursor: default; }

/* 群名/群公告：常驻表单，不是一行行的可点条目。名称必填带红星，两个框各带字数计数器 */
.gs-form { margin-top: 18px; }
.gs-field + .gs-field { margin-top: 14px; }
.gs-lab { font-size: 13px; color: var(--nb-text); margin-bottom: 6px; }
.gs-req { color: var(--danger); margin-left: 2px; }
.gs-opt { color: var(--nb-dim-2); font-size: 12px; font-weight: 400; }
.gs-field-box { position: relative; display: flex; align-items: center; min-height: 34px;
  border: 1px solid var(--nb-line); border-radius: 7px; background: var(--nb-bg-1); }
.gs-field-box.ta { align-items: flex-start; }
.gs-field-box:focus-within { border-color: var(--brand-line); }
.gs-in { flex: 1; min-width: 0; width: 100%; border: 0; outline: none; background: transparent;
  color: var(--nb-text); font: inherit; font-size: 13px; line-height: 1.6; padding: 7px 54px 7px 10px;
  resize: none; }
.gs-count { position: absolute; right: 10px; top: 50%; transform: translateY(-50%);
  font-size: 11px; color: var(--nb-dim-2); pointer-events: none; }
.gs-field-box.ta .gs-count { top: auto; bottom: 7px; transform: none; }
/* 禁用态要自己给底色：input 的底色在 .gs-field-box 上，浏览器置灰只会灰掉输入区 */
.gs-in:disabled { background: transparent; color: var(--nb-dim); cursor: default; }
.gs-field-box:has(.gs-in:disabled) { background: var(--nb-bg-3); }
.gs-save { width: 100%; margin-top: 14px; }
/* 三个按钮锁 38px 高，并额外压 1px 上内边距。
   flex 居中的是"行盒"，行盒居中不等于"字的墨迹"居中：标签是纯中文，行高度量却来自拉丁字体
   Rajdhani，逐像素量下来墨迹离上边比离下边少 1.5px（三个按钮 -1.50 / -1.25 / -1.75）。
   DPR=1 下基线只会吸附到整像素，所以可达的位置只有两档：padTop 0 → 差 -1.5，padTop 1 → 差 +0.5；
   取 1px 这一档（残留 0.25~0.75px = 一个像素行）。改成中文字体优先也是同一档，不额外换字体。
   getBoundingClientRect / Range 量出来永远是 10/10（那是行盒），量不到这个偏差 */
.gs-save, .gs-btns .btn { height: 38px; line-height: 38px; padding: 2px 18px 0; }
/* 中性动作用自己这套描边按钮，不用 .btn-ghost：那个类在这套浅色主题下是坏的
   （底色只有 10% 粉、hover 把字改成白色），白抽屉上点一下字就看不见了 */
.btn-neutral { border: 1px solid var(--nb-line); background: var(--nb-bg-1); color: var(--nb-text); }
.btn-neutral:hover:not(:disabled) { background: var(--nb-bg-3); border-color: var(--brand-line); color: var(--nb-text); }
.gs-note { margin-top: 8px; font-size: 11px; color: var(--nb-dim-2); }

/* 底部两个动作：一行等分。一格 (235-8)/2 = 113.5px，最长的「清空聊天记录」实测文字 90px，
   左右各留 10px 放得下（沿用 .btn 的 18px 会挤掉 6.5px）。
   这里不用 flex:1 1 0 去分：描边那颗会多出自身边框那 2px（实测 114.5 / 112.5），
   直接按 (100% - 间隙)/2 定宽才真的等宽 */
.gs-btns { display: flex; gap: 8px; margin: 18px -16px 0;
  padding: 14px 16px 0; border-top: 1px solid var(--nb-line-soft); }
.gs-btns .btn { flex: 0 0 calc((100% - 8px) / 2); min-width: 0; padding: 2px 10px 0; }
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
</style>
