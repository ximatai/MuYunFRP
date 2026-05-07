# MuYunFRP V1 产品需求概要

## 定位

MuYunFRP V1 面向个人自用和可信环境部署，目标是提供一个简单、可理解、可排障的轻量内网穿透工具。V1 聚焦 TCP 透传的可靠性，不追求平台化和完整 frp/ngrok 能力。

## V1 目标

- Server 静态声明 tunnel，Agent 主动连接指定 tunnel。
- 每个 tunnel 使用独立 token 鉴权，未鉴权 agent 不能参与流量转发。
- 管理接口使用独立 management token 保护。
- 无已鉴权 agent 时，用户访问开放端口应快速失败。
- 提供 tunnel 运行状态查询，便于自用排障。

## 非目标

- 不做 HTTP Host/SNI 路由。
- 不做 UDP。
- 不做多 agent 负载均衡。
- 不做多租户、计费、用户体系。
- 不做 Web UI 和 Prometheus 指标。

## 关键场景

- 家庭或内网机器运行 Agent，将 SSH、HTTP 服务通过公网 Server 暴露。
- Agent 重启后使用同一 token 重新接管 tunnel。
- 管理端通过 `/api/tunnel` 查看 agent 是否在线和当前连接数。

## 验收标准

- 正确 token 下 TCP/HTTP 透传保持可用。
- 错误 token 或未认证 agent 不能接管 tunnel。
- 管理 API 无 Bearer token 或 token 错误时返回 401。
- API 和日志不输出 tunnel token 或 management token。
