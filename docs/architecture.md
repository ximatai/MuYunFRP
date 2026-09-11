# MuYunFRP V1 架构概要

## 核心对象

- `FRP Server`：运行在公网，启动时从本地 tunnel store 加载 tunnel，并通过管理 API 动态维护 tunnel。
- `Tunnel`：一组端口配置，包含用户访问的 `open-port`、Agent 连接的 `agent-port` 和 token hash。
- `TunnelStore`：本地 JSON tunnel 持久化文件，store 中只保存 token hash。
- `TunnelManager`：运行态 tunnel 管理器，负责加载 store、deploy/undeploy listener、创建、删除、重启和 reset token。
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

## Agent 请求转发

- Agent 收到 `CONNECT` 时立即创建本地请求状态；目标 TCP 连接仍在建立时到达的 `DATA` 进入该请求专属的 FIFO 队列。
- 队列按写入完成顺序串行写入目标 socket，因此同一 requestId 的 TCP 字节顺序与 Server 发出的 `DATA` 帧顺序一致。
- 每个请求最多暂存 1 MiB。超过上限、目标连接失败或异步写入失败时，Agent 关闭该请求并向创建它的 Agent session 发送 `CLOSE`。
- 请求绑定到创建它的控制 WebSocket。控制 session 丢失、被替换或收到 `CLOSE` 后，请求会被移除；晚到的 TCP connect/write 回调只能关闭自身资源，不能重新注册请求或向新 session 写数据。

## 管理状态

`/api/tunnels` 合并持久化配置和运行态，返回 lifecycle、agent 在线状态、agentName、sessionId、activeConnections、connectedAt、lastSeenAt。接口不返回 tokenHash。
