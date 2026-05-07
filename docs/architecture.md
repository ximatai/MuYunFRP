# MuYunFRP V1 架构概要

## 核心对象

- `FRP Server`：运行在公网，按配置启动 tunnel。
- `Tunnel`：一组端口配置，包含用户访问的 `open-port`、Agent 连接的 `agent-port` 和 token。
- `FRP Agent`：运行在内网，连接 server 的 `agent-port`，并转发到真实上游服务。
- `AgentSession`：server 端已连接 agent 的会话状态。V1 每个 tunnel 最多一个已鉴权 session。
- `RequestContext`：server 端用户连接上下文，使用 requestId 绑定用户 socket 和 agent session。
- `TunnelRuntimeRegistry`：server 端轻量运行态注册表，为管理 API 提供状态快照。

## 数据流

```text
User -> Server open-port -> RequestContext
     -> Server/Agent WebSocket
     -> Agent -> Proxy service
     -> Agent/Server WebSocket
     -> User
```

## 连接规则

- Agent WebSocket 建连后必须先发 `AUTH`。
- 鉴权成功后，server 将该连接设置为 tunnel 的 active session。
- 新 agent 使用正确 token 连接时，后连踢前连。
- 替换时先标记旧 session inactive，再关闭旧用户连接，最后关闭旧 WebSocket。
- 无 active session 时，用户连接立即关闭。

## 管理状态

`/api/tunnel` 合并静态配置和运行态，返回 agent 在线状态、agentName、sessionId、activeConnections、connectedAt、lastSeenAt。接口不返回 token。
