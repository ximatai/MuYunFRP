plugins {
    alias(libs.plugins.quarkus)
}

quarkus {
    val version = project.version.toString()
    quarkusBuildProperties.set(
        mapOf(
            "quarkus.package.output-name" to "muyun-frp-server-$version"
        )
    )
}

dependencies {
    implementation(project(":frp-common"))

    implementation(enforcedPlatform(libs.quarkus.platform.bom))
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-websockets")
    implementation(libs.jackson.databind)

}
