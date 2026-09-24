# Chat System

基于 Spring Boot 4 + Go + Vue3 的聊天系统。

## 技术栈

| 模块 | 技术 |
|------|------|
| Web 前端 | Vue 3 + Element Plus |
| WebSocket | Go + Gorilla/WebSocket |
| 业务服务 | Spring Boot 4 |
| 缓存 | Redis |
| 消息队列 | RabbitMQ |
| 用户数据库 | PostgreSQL |
| 消息数据库 | Cassandra |
| 文件存储 | MinIO |
| 服务发现 | Consul |

## 项目结构

```
chat-system/
├── backend/
│   ├── chat-user/          # 用户服务 (Spring Boot 4) - 端口 8081
│   ├── chat-group/         # 群组服务 (Spring Boot 4) - 端口 8084
│   └── chat-message/       # 消息服务 (Spring Boot 4) - 端口 8083
├── gateway/
│   └── chat-gateway/       # WebSocket 网关 (Go) - 端口 8082
├── frontend/
│   └── chat-web/           # Web 前端 (Vue3) - 端口 3000
├── deploy/
│   ├── docker-compose.yml
│   ├── postgres/init/      # PostgreSQL 初始化脚本
│   └── cassandra/init.cql  # Cassandra 初始化脚本
└── 架构.txt                # 架构设计文档
```

## 功能清单

### ✅ 已完成

- 用户注册/登录/JWT
- 用户资料更新/修改密码
- 好友添加/删除/列表
- 一对一聊天
- 群聊（创建/邀请/踢人/退出/禁言）
- 消息已读状态
- 消息持久化 (Cassandra)
- WebSocket 连接管理
- 心跳/断线/重连
- Redis 在线状态
- 跨日期消息分页
- 文件/图片消息（对象存 MinIO，WS 帧里只传引用；上传/下载走消息服务 REST，不过网关）
- 消息撤回（发出 2 分钟内，仅本人；私聊与群聊都实时通知，带文件的消息撤回后对象一并删除）

### 🚧 待开发

- RabbitMQ 异步消息处理
- WebRTC 音视频
- 消息搜索

## 快速开始

### 1. 启动中间件

```bash
cd deploy
docker-compose up -d
```

### 2. 初始化数据库

```bash
# PostgreSQL (自动执行 deploy/postgres/init/ 下的脚本)

# Cassandra
docker exec -it chat-cassandra cqlsh -f /docker-entrypoint-initdb.d/init.cql
# 或手动执行:
docker exec -it chat-cassandra cqlsh
> SOURCE '/docker-entrypoint-initdb.d/init.cql';
```

### 3. 启动用户服务

```bash
cd backend/chat-user
mvn spring-boot:run
```

### 4. 启动群组服务

```bash
cd backend/chat-group
mvn spring-boot:run
```

### 5. 启动消息服务

```bash
cd backend/chat-message
mvn spring-boot:run
```

### 6. 启动 WebSocket 网关

```bash
cd gateway/chat-gateway
go mod tidy
go run main.go
```

## 必需环境变量

`INTERNAL_JWT_SECRET` 必须设置且四个服务保持一致（网关 / chat-user / chat-group / chat-message）。
它是服务间短期令牌（30 秒）的签名密钥，替代了以前写死的 `X-Internal-Key: gateway-internal`。
网关在该变量为空时会直接拒绝启动；Java 服务在为空时拒绝一切内部令牌（浏览器 JWT 仍可用）。

浏览器侧身份走 `Authorization: Bearer <accessToken>`，`X-User-Id` 头已不再被任何服务端读取。

文件/图片走 MinIO，`chat-message` 侧可覆盖：`MINIO_ENDPOINT`（默认 `http://172.20.70.38:19000`）、
`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`MINIO_BUCKET`（默认 `chat-files`，不存在时启动自动建桶）。
compose 里 MinIO 是 9000，但 9000 常被别的服务占，本机实测映射到 19000；MinIO 跑在 WSL 时
endpoint 要用 WSL 的 IP，NAT 模式下 Windows 侧 `localhost` 转不进去。单文件上限 20MB（超了回 413）。

### 7. 启动前端

```bash
cd frontend/chat-web
pnpm install
pnpm dev
```

## 端口分配

| 服务 | 端口 |
|------|------|
| PostgreSQL | 5432 |
| Redis | 6379 |
| RabbitMQ | 5672 / 15672 |
| Cassandra | 9042 |
| MinIO | 9000 / 9001 |
| Consul | 8500 |
| 用户服务 | 8081 |
| 消息服务 | 8083 |
| 群组服务 | 8084 |
| WebSocket 网关 | 8082 |
| 前端 | 3000 |

## WebSocket 协议

### 消息格式

```json
{
  "type": "MESSAGE_SEND",
  "requestId": "req-xxx",
  "data": { ... }
}
```

### 消息类型

| 类型 | 说明 | 方向 |
|------|------|------|
| `PING` / `PONG` | 心跳 | 双向 |
| `MESSAGE_SEND` | 发送消息 | 客户端→服务端 |
| `MESSAGE_ACK` | 消息确认 | 服务端→客户端 |
| `MESSAGE_RECEIVE` | 收到新消息 | 服务端→客户端 |
| `MESSAGE_READ` | 已读回执 | 双向 |
| `LOAD_MESSAGES` | 加载历史消息 | 客户端→服务端 |
| `SYNC_CONVERSATIONS` | 同步会话列表 | 客户端→服务端 |

### 会话ID规则

- 一对一: `min(userId1, userId2) * 100000 + max(userId1, userId2)`
- 群聊: `g{groupId}`

## API 接口

### 用户服务 (8081)

- `POST /api/auth/register` - 注册
- `POST /api/auth/login` - 登录
- `POST /api/auth/refresh` - 刷新 Token
- `GET /api/user/{userId}` - 获取用户信息
- `GET /api/user/search?username=xxx` - 搜索用户
- `GET /api/user/me` - 获取当前用户
- `PUT /api/user/profile` - 更新资料
- `POST /api/user/change-password` - 修改密码
- `POST /api/friend/add` - 添加好友
- `DELETE /api/friend/remove/{friendId}` - 删除好友
- `GET /api/friend/list` - 好友列表
- `GET /api/conversation/list` - 会话列表
- `POST /api/conversation/create` - 创建会话

### 群组服务 (8084)

- `POST /api/group/create` - 创建群
- `GET /api/group/{groupId}` - 群信息
- `PUT /api/group/{groupId}` - 更新群信息
- `DELETE /api/group/{groupId}` - 解散群
- `POST /api/group/{groupId}/invite` - 邀请成员
- `DELETE /api/group/{groupId}/member/{userId}` - 移除成员
- `POST /api/group/{groupId}/leave` - 退群
- `PUT /api/group/{groupId}/member/{userId}/role` - 设置角色
- `POST /api/group/{groupId}/member/{userId}/mute` - 禁言
- `GET /api/group/{groupId}/members` - 成员列表
- `GET /api/group/my` - 我的群列表

### 消息服务 (8083)

- `POST /api/message/send` - 保存消息
- `GET /api/message/list/{conversationId}` - 最新消息
- `GET /api/message/history/{conversationId}` - 历史消息分页
- `POST /api/message/read` - 标记已读
- `GET /api/message/read/{conversationId}` - 已读状态
