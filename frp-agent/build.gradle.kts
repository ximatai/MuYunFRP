plugins {
    alias(libs.plugins.quarkus)
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("muyun-frp-agent")
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.platform.bom))
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-vertx")
}
