# MuYun-FRP

### FRP 是一种代理服务，主要解决网络单向可通的情况下，把内网的服务暴露到公网的问题。

典型的应用场景如下：

1. 在家庭宽带环境内搭建的网络服务想暴露到公网，供他人访问。
2. 一个服务器没有公网，但是有一个跳板机同时可以访问公网和该服务器，则可借助跳板机，将内网服务暴露到公网。

### 名词解释：

* `FRP-Server`，服务端，负责接收客户端的请求，并对数据进行转发，通常运行在公网上。
* `Tunnel`，服务端所拉起的一个通道，该通道包含两个端口，`open-port`是服务代理后可供用户直接访问的端口，`agetn-port`是供
  `agent`端链接的端口。 一个`FRP-Server`可以配置多个`Tunnel`。
* `FRP-Agent`，代理客户端，通常运行在私网内，用来扮演请求真实服务即`Upstream Server`和数据转发的角色。所以要在其中配置
  `frp-tunnel`信息以及真实上游服务的`proxy`信息。

### 网络图

![FRP](https://github.com/user-attachments/assets/f4817a58-d26d-425f-af48-abf2ec077de9)

### 使用

#### 1. 环境要求：`JRE 21+`

#### 2. 服务端配置及启动

典型配置文件（*application.yml*）：

```yml
frp-server:
  management:
    port: 8089
  tunnels:
    - name: 测试
      open-port: 8082
      agent-port: 8083

quarkus:
  http:
    port: ${frp-server.management.port}
```

路径存放：

```
./your-server-folder/
   ├── muyun-frp-server-x.x.x-runner.jar     # MuYun FRP Server JAR
   └── config/                               # 配置文件夹      
      └── application.yml                    # 配置文件
```

启动命令：

```shell
java -jar muyun-frp-server-x.x.x-runner.jar
```

#### 3. Agent端配置及启动

典型配置文件（*application.yml*）：

```yml
frp-agent:
  type: tcp
  frp-tunnel:
    host: 127.0.0.1
    port: 8083
  proxy:
    host: 192.168.6.203
    port: 22
```

路径存放：

```
./your-agent-folder/
   ├── muyun-frp-agent-x.x.x-runner.jar     # MuYun FRP Agent JAR
   └── config/                               # 配置文件夹      
      └── application.yml                    # 配置文件
```

启动命令：

```shell
java -jar muyun-frp-agent-x.x.x-runner.jar
```

其他补充：

1. 如果需要把`jar`当做服务持续启动的话，可以参考下面的命令：

    ```shell
    nohup java -jar muyun-frp-agent-x.x.x-runner.jar > /dev/null 2>&1 & 
    ```
2. 如果想定制更负责的日志输出，可以参考`./frp-server/src/main/resources/application-demo.yml`文件内容。
3. 如果遇到启动失败，请检查端口占用情况。典型的报错信息为：`java.net.BindException: Address already in use`

### For Develop

运行单元测试

```shell script
./gradlew :frp-test:test
```

项目打包

```shell script
./gradlew :frp-server:build -Dquarkus.package.jar.type=uber-jar
./gradlew :frp-agent:build -Dquarkus.package.jar.type=uber-jar
```

因为是`Quarkus`项目，所以支持打包本地二进制文件，感兴趣的可以尝试下面的命令

```shell script
./gradlew :frp-server:build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
./gradlew :frp-agent:build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
```
