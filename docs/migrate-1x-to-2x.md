# 从 1.x 升级到 2.x

MuYunFRP 2.x 的主要变化是：Server 不再从 `application.yml` 的 `frp-server.tunnels` 静态数组读取 tunnel，而是从本地 JSON store 加载 tunnel，并通过管理 API 创建、删除、重启和重置 token。

2.x 不做自动迁移。旧配置需要手工重新创建 tunnel。

## 主要变化

| 1.x | 2.x |
| --- | --- |
| `frp-server.tunnels` 静态配置 tunnel | `frp-server.tunnel-store.path` 指向本地 JSON store |
| Server 配置里保存明文 tunnel token | Store 只保存 PBKDF2 token hash |
| 管理 API 使用 Bearer token | 管理 API 使用 HTTP Basic Auth |
| `/api/tunnel` | `/api/tunnels` |
| 修改 tunnel 需要改配置并重启 | 通过管理 API create/delete/restart/reset-token |

## 1. 更新 Server 配置

旧版示例：

```yml
frp-server:
  management:
    port: 8089
    token: ${FRP_SERVER_MANAGEMENT_TOKEN}
  tunnels:
    - name: ssh_home
      type: tcp
      open-port: 8082
      agent-port: 8083
      token: ${FRP_SERVER_TUNNEL_TOKEN}
```

新版示例：

```yml
frp-server:
  management:
    port: 8089
    username: ${FRP_SERVER_MANAGEMENT_USERNAME}
    password: ${FRP_SERVER_MANAGEMENT_PASSWORD}
  tunnel-store:
    path: ./config/tunnels.json

quarkus:
  http:
    port: ${frp-server.management.port}
```

首次启动时，如果 `tunnels.json` 不存在，Server 会创建父目录并初始化为空数组：

```json
[]
```

## 2. 启动新版 Server

下面以 `2.26.1` 为例，实际使用时请替换为你下载的版本号。

```shell
cd ./your-server-folder
export FRP_SERVER_MANAGEMENT_USERNAME=admin
export FRP_SERVER_MANAGEMENT_PASSWORD=change-me
java -jar muyun-frp-server-2.26.1-runner.jar
```

`./config/application.yml` 和 `./config/tunnels.json` 都按启动命令的当前工作目录解析。建议进入 jar 所在目录后启动。

如果 store 文件 JSON 格式错误、端口冲突、token hash 缺失，Server 会启动失败。多个 tunnel 启动时采用 all-or-nothing：任一 tunnel listener 启动失败，已启动 listener 会回滚关闭。

## 3. 重新创建 tunnel

把旧配置里的 tunnel 转成管理 API 请求。`openPort` 是用户访问 Server 的入口端口，`agentPort` 是 Agent 连接 Server 的 tunnel 端口；真实内网目标仍由 Agent 的 `proxy.host` 和 `proxy.port` 决定。

旧配置：

```yml
name: ssh_home
type: tcp
open-port: 8082
agent-port: 8083
```

创建请求：

```shell
curl -u "$FRP_SERVER_MANAGEMENT_USERNAME:$FRP_SERVER_MANAGEMENT_PASSWORD" \
  -H "Content-Type: application/json" \
  -d '{"name":"ssh_home","type":"tcp","openPort":8082,"agentPort":8083}' \
  http://127.0.0.1:8089/api/tunnels
```

上面的 `127.0.0.1:8089` 适用于在 Server 机器上执行管理命令。如果从其他机器远程管理，需要替换为 Server 地址，并确认管理端口安全暴露。

响应会返回一次性 `agentToken`：

```json
{
  "name": "ssh_home",
  "type": "tcp",
  "openPort": 8082,
  "agentPort": 8083,
  "status": "LISTENING",
  "agentToken": "..."
}
```

保存这个 `agentToken` 并配置到对应 Agent。之后 list/get API 不会再返回明文 token。

## 4. 更新 Agent 配置

旧 Agent 的核心配置可以保留，但 token 应改成创建 tunnel 时返回的新 `agentToken`。旧 Server 配置里的 tunnel token 不能直接沿用，因为 2.x 会为重新创建的 tunnel 生成新的 token hash。

```yml
frp-agent:
  type: tcp
  agent-name: home-agent
  frp-tunnel:
    host: your-server-host
    port: 8083
  auth:
    token: ${FRP_AGENT_TUNNEL_TOKEN}
  proxy:
    host: 192.168.6.203
    port: 22
```

启动：

```shell
cd ./your-agent-folder
export FRP_AGENT_TUNNEL_TOKEN='<agentToken>'
java -jar muyun-frp-agent-2.26.1-runner.jar
```

这里的 `<agentToken>` 替换为创建 tunnel 响应里的真实 token。建议保留引号。

## 5. 验证运行态

查看所有 tunnel：

```shell
curl -u "$FRP_SERVER_MANAGEMENT_USERNAME:$FRP_SERVER_MANAGEMENT_PASSWORD" \
  http://127.0.0.1:8089/api/tunnels
```

如果不在 Server 机器上执行，请把 `127.0.0.1` 替换为 Server 地址。

典型字段：

```json
[
  {
    "name": "ssh_home",
    "type": "tcp",
    "openPort": 8082,
    "agentPort": 8083,
    "tokenConfigured": true,
    "status": "LISTENING",
    "agentOnline": true,
    "agentName": "home-agent",
    "activeConnections": 0
  }
]
```

如果 `agentOnline` 为 `false`，用户访问 `openPort` 会快速失败。

云服务器或有防火墙的环境需要放通端口：

- `openPort`：放通给最终用户访问，例如 `8082`。
- `agentPort`：放通给 Agent 连接，例如 `8083`。
- `management.port`：仅在需要远程管理时放通，例如 `8089`；建议配合 HTTPS 或反向代理 TLS。

## 常用管理操作

重启 tunnel listener：

```shell
curl -u "$FRP_SERVER_MANAGEMENT_USERNAME:$FRP_SERVER_MANAGEMENT_PASSWORD" \
  -X POST http://127.0.0.1:8089/api/tunnels/ssh_home/restart
```

重置 Agent token：

```shell
curl -u "$FRP_SERVER_MANAGEMENT_USERNAME:$FRP_SERVER_MANAGEMENT_PASSWORD" \
  -X POST http://127.0.0.1:8089/api/tunnels/ssh_home/token/reset
```

重置成功后旧 token 立即失效，当前 Agent 和用户连接会被关闭。响应中的新 `agentToken` 仍然只返回一次。

删除 tunnel：

```shell
curl -u "$FRP_SERVER_MANAGEMENT_USERNAME:$FRP_SERVER_MANAGEMENT_PASSWORD" \
  -X DELETE http://127.0.0.1:8089/api/tunnels/ssh_home
```

## 注意事项

- 2.x 不会读取旧 `frp-server.tunnels` 配置。
- 旧 `/api/tunnel` 已删除，请使用 `/api/tunnels`。
- Basic Auth 本身不加密用户名密码，公网暴露管理端口时应配合 HTTPS 或反向代理 TLS。
- Store 中只保存 token hash，不要手工写入明文 token。
- 2.x 仍只支持 `type=tcp`。
