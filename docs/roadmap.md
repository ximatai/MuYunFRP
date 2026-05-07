# MuYunFRP Roadmap

## V1: 可靠 TCP Tunnel

- 每 tunnel token 鉴权。
- Agent `AUTH / AUTH_OK / AUTH_FAIL` 握手。
- 每 tunnel 单 active agent session，后连踢前连。
- 管理 API HTTP Basic Auth 鉴权。
- `/api/tunnels` 返回配置和运行态。
- TCP/HTTP 透传成功路径和关键鉴权场景测试。

## V1.1: Runtime Tunnel Management

- tunnel 来源迁移到本地 JSON store。
- 管理 API 支持 list/get/create/delete/restart/reset-token。
- store 只保存 PBKDF2 token hash，明文 agent token 只在 create/reset 响应返回一次。
- tunnel runtime 增加 STARTING/LISTENING/FAILED/STOPPING/STOPPED 生命周期。

## V2: HTTP/HTTPS 入口能力

- HTTP Host 路由到 tunnel。
- HTTPS SNI 路由或证书策略。
- 多域名配置校验和冲突检测。
- WebSocket 透传专项测试。

## V3: 运维体验

- Docker/systemd 部署示例。
- 更完整的日志和运行状态。
- 可选 Prometheus 指标。
- 可选轻量 dashboard。
