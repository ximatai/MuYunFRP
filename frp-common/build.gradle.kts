plugins {
    java
    `java-library`
}

dependencies{
    implementation(enforcedPlatform(libs.quarkus.platform.bom))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest")
}

