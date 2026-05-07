pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        mavenLocal()
    }
}

rootProject.name = "muyun-frp"

include("frp-common")
include("frp-server")
include("frp-agent")
include("frp-test")
