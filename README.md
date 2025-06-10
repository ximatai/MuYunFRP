# MuYun-FRP

一套基于 Java 打造的 FRP 服务，轻便易用。

![FRP](https://github.com/user-attachments/assets/f4817a58-d26d-425f-af48-abf2ec077de9)

## Creating a native executable

You can create a native executable using:

```shell script
./gradlew :frp-server:build -Dquarkus.package.jar.type=uber-jar
./gradlew :frp-agent:build -Dquarkus.package.jar.type=uber-jar
```


Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./gradlew :frp-server:build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
./gradlew :frp-agent:build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
```
