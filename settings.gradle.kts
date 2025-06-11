pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}

rootProject.name="muyun-frp"

include("frp-shared")
include("frp-server")
include("frp-agent")
include("frp-test")
