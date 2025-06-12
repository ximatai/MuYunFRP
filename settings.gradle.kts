pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}

rootProject.name="muyun-frp"
include("frp-common")
include("frp-server")
include("frp-agent")
include("frp-test")
