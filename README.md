# muyun-frp

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

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
