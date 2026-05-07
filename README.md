# MuYun-FRP

MuYun-FRP 是一个自用轻量内网穿透工具，用来在网络单向可通的场景下，把内网 TCP 服务暴露到公网。当前版本聚焦可靠 TCP 透传：Server 通常部署在公网机器上，Agent 部署在内网机器上，用户访问 Server 的开放端口后，流量会经由 Agent 转发到内网真实服务。

HTTP 服务也可以通过 TCP 透传使用，但当前不做 HTTP Host/SNI 路由。

## 适用场景

- 家庭宽带或内网机器上的 SSH、HTTP、数据库等 TCP 服务，需要临时或长期暴露到公网。
- 一台公网机器可以作为入口，一台内网机器可以主动连到公网机器。
- 自用、可信环境部署，优先追求简单、可理解、可排障。

## 暂不适合

- UDP 转发。
- HTTP Host/SNI 多域名路由。
- 多 agent 负载均衡。
- 多租户、用户体系、计费。
- Web UI、Prometheus 指标。

## 工作原理

```text
User
  -> Server openPort
  -> Server/Agent WebSocket tunnel
  -> Agent
  -> 内网真实服务
```

核心概念：

- `FRP Server`：公网入口，负责监听用户访问端口和 Agent 连接端口。
- `FRP Agent`：内网代理客户端，连接 Server，并转发到真实上游服务。
- `Tunnel`：一组端口和鉴权配置，包含 `openPort`、`agentPort` 和 token hash。
- `openPort`：用户访问的公网端口。
- `agentPort`：Agent 连接 Server 的端口。
- `agentToken`：Agent 接入 tunnel 的鉴权 token，只在创建或重置时返回一次。

![FRP](https://github.com/user-attachments/assets/f4817a58-d26d-425f-af48-abf2ec077de9)

## 快速开始

### 1. 环境要求

- JRE 21+
- 从 [GitHub Releases](https://github.com/ximatai/MuYunFRP/releases) 下载：
  - `muyun-frp-server-x.x.x-runner.jar`，例如 `muyun-frp-server-2.26.1-runner.jar`
  - `muyun-frp-agent-x.x.x-runner.jar`，例如 `muyun-frp-agent-2.26.1-runner.jar`

### 2. 准备 Server 配置

目录结构：

```text
./your-server-folder/
  ├── muyun-frp-server-x.x.x-runner.jar
  └── config/
      └── application.yml
```

`config/application.yml`：

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

启动 Server：

```shell
cd ./your-server-folder
export FRP_SERVER_MANAGEMENT_USERNAME=admin
export FRP_SERVER_MANAGEMENT_PASSWORD=change-me
java -jar muyun-frp-server-x.x.x-runner.jar
```

示例配置中的 `./config/application.yml` 和 `./config/tunnels.json` 都按启动命令的当前工作目录解析。建议进入 jar 所在目录后启动，避免配置文件或 store 写到意外位置。

### 3. 创建 tunnel

创建一个 TCP tunnel：Server 监听 `openPort=8082` 作为用户入口，同时监听 `agentPort=8083` 供 Agent 建立 WebSocket tunnel。真实内网目标不是 `agentPort`，而是后面 Agent 配置里的 `proxy.host` 和 `proxy.port`。

```shell
curl -u "$FRP_SERVER_MANAGEMENT_USERNAME:$FRP_SERVER_MANAGEMENT_PASSWORD" \
  -H "Content-Type: application/json" \
  -d '{"name":"ssh_home","type":"tcp","openPort":8082,"agentPort":8083}' \
  http://127.0.0.1:8089/api/tunnels
```

上面的 `127.0.0.1:8089` 适用于在 Server 机器上执行管理命令。如果从其他机器远程管理，需要替换为 Server 地址，并确认管理端口安全暴露。

响应中会包含一次性的 `agentToken`：

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

`agentToken` 只返回一次。Server 只在 `tunnels.json` 中保存 PBKDF2 token hash，不保存明文 token。

### 4. 准备 Agent 配置

目录结构：

```text
./your-agent-folder/
  ├── muyun-frp-agent-x.x.x-runner.jar
  └── config/
      └── application.yml
```

`config/application.yml`：

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

启动 Agent：

```shell
cd ./your-agent-folder
export FRP_AGENT_TUNNEL_TOKEN='<agentToken>'
java -jar muyun-frp-agent-x.x.x-runner.jar
```

这里的 `<agentToken>` 替换为创建 tunnel 响应里的真实 token。建议保留引号，避免 shell 对特殊字符做额外解析。

### 5. 验证转发

先查看运行态，确认 `agentOnline` 为 `true`：

```shell
curl -u "$FRP_SERVER_MANAGEMENT_USERNAME:$FRP_SERVER_MANAGEMENT_PASSWORD" \
  http://127.0.0.1:8089/api/tunnels/ssh_home
```

如果上面的 `proxy.host:proxy.port` 指向内网 SSH 服务，则可以从外部访问：

```shell
ssh -p 8082 user@your-server-host
```

如果用户访问 `openPort` 时没有已鉴权 Agent 在线，连接会被立即关闭。

云服务器或有防火墙的环境需要放通端口：

- `openPort`：放通给最终用户访问，例如 `8082`。
- `agentPort`：放通给 Agent 连接，例如 `8083`。
- `management.port`：仅在需要远程管理时放通，例如 `8089`；建议配合 HTTPS 或反向代理 TLS。

## 管理 API

管理 API 使用 HTTP Basic Auth 保护。浏览器访问时会弹出用户名密码输入框：

```shell
curl -u "$FRP_SERVER_MANAGEMENT_USERNAME:$FRP_SERVER_MANAGEMENT_PASSWORD" \
  http://127.0.0.1:8089/api/tunnels
```

如果不在 Server 机器上执行，请把 `127.0.0.1` 替换为 Server 地址。

常用接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/tunnels` | 查看所有 tunnel 配置和运行态 |
| `GET` | `/api/tunnels/{name}` | 查看单个 tunnel |
| `POST` | `/api/tunnels` | 创建 tunnel，并返回一次性 `agentToken` |
| `DELETE` | `/api/tunnels/{name}` | 删除 tunnel |
| `POST` | `/api/tunnels/{name}/restart` | 重启 tunnel listener |
| `POST` | `/api/tunnels/{name}/token/reset` | 重置 Agent token，并返回一次性新 token |

接口不会返回 `tokenHash`、明文 agent token 或管理端密码。Basic Auth 本身不加密用户名密码，公网使用时应配合 HTTPS 或反向代理 TLS。

## 行为说明

- Agent 连接 tunnel 后必须先完成 `AUTH` 鉴权，鉴权成功前不会参与流量转发。
- 每个 tunnel 最多一个在线 Agent；新 Agent 使用正确 token 连接时会替换旧连接。
- Agent 被替换、断线或 token reset 后，旧 Agent 下的用户连接会被关闭。
- tunnel 配置保存在本地 JSON store，默认路径可通过 `frp-server.tunnel-store.path` 指定。
- HTTP 当前仍作为 TCP 透传使用，暂不支持 Host/SNI 路由。

## 1.x 升级到 2.x

2.x 移除了静态 `frp-server.tunnels` 配置，改为本地 JSON store 和管理 API 动态创建 tunnel。迁移步骤见 [docs/migrate-1x-to-2x.md](docs/migrate-1x-to-2x.md)。

## 更多配置

可以参考 demo 配置：

- [frp-server/src/main/resources/application-demo.yml](frp-server/src/main/resources/application-demo.yml)
- [frp-agent/src/main/resources/application-demo.yml](frp-agent/src/main/resources/application-demo.yml)

如果遇到启动失败，请优先检查端口占用。典型错误是：

```text
java.net.BindException: Address already in use
```

长期后台运行可以参考：

```shell
nohup java -jar muyun-frp-agent-x.x.x-runner.jar > /dev/null 2>&1 &
```

## 开发

运行测试：

```shell
./gradlew test
```

构建 UberJAR：

```shell
./gradlew :frp-server:build -Dquarkus.package.jar.type=uber-jar
./gradlew :frp-agent:build -Dquarkus.package.jar.type=uber-jar
```

构建 native binary：

```shell
./gradlew :frp-server:build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
./gradlew :frp-agent:build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
```
