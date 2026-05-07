# MuYunFRP V1 协议概要

## WebSocket 层

Server 和 Agent 之间使用 WebSocket 二进制消息传输控制和数据帧。WebSocket 原生 `PING/PONG` 只用于连接保活，不承载业务鉴权语义。

## 控制帧

控制帧用于 Agent 鉴权，不携带 requestId。

```text
1 byte opcode + JSON UTF-8 payload
```

V1 控制操作：

- `AUTH`：Agent -> Server
- `AUTH_OK`：Server -> Agent
- `AUTH_FAIL`：Server -> Agent

`AUTH` payload：

```json
{
  "version": 1,
  "token": "tunnel-token",
  "agentName": "home-agent"
}
```

Server 使用 tunnel store 中的 PBKDF2 token hash 校验 `token`，不持久化明文 token。

`AUTH_OK` payload：

```json
{
  "version": 1,
  "sessionId": "...",
  "message": "ok"
}
```

Agent 建连后 5 秒内必须发送 `AUTH`。鉴权失败时 server 可先返回 `AUTH_FAIL`，随后立即关闭 WebSocket。

## 转发帧

转发帧保留现有格式：

```text
1 byte opcode + 16 bytes requestId + payload
```

V1 转发操作：

- `CONNECT`：server 通知 agent 新用户连接。
- `DATA`：双向传输用户数据。
- `CLOSE`：通知对端关闭 requestId 对应连接。

鉴权成功前收到的转发帧会被忽略，不能影响 tunnel 状态。

## 关闭语义

- 用户连接关闭时，server 向 agent 发送 `CLOSE`。
- 目标服务关闭时，agent 向 server 发送 `CLOSE`。
- Agent 断线或被替换时，server 关闭该 session 下所有用户连接。
