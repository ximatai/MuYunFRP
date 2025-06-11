plugins {
    alias(libs.plugins.quarkus)
}

quarkus {
    val version = project.version.toString()
    quarkusBuildProperties.set(mapOf(
        "quarkus.package.output-name" to "muyun-frp-agent-$version"
    ))
}

dependencies {
    implementation(project(":frp-shared"))
    implementation(enforcedPlatform(libs.quarkus.platform.bom))
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-vertx")
}
