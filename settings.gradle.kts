pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}

rootProject.name = "muyun-frp"

include("frp-common")
include("frp-server")
include("frp-agent")
include("frp-test")
