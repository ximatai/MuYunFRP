plugins {
    alias(libs.plugins.quarkus)
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.platform.bom))

    testImplementation(project(":frp-server"))
    testImplementation(project(":frp-agent"))

    testImplementation("io.vertx:vertx-junit5:4.5.14")

    testImplementation("io.quarkus:quarkus-config-yaml")
    testImplementation("io.quarkus:quarkus-arc")
    testImplementation("io.quarkus:quarkus-rest")
    testImplementation("io.quarkus:quarkus-vertx")
    testImplementation("io.quarkus:quarkus-reactive-routes")
    testImplementation(libs.jackson.databind)

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
}
