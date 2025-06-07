plugins {
    java
    alias(libs.plugins.quarkus) apply false
}

allprojects {
    apply {
        plugin("java")
    }

    repositories {
        mavenCentral()
        mavenLocal()
    }

    group = "net.ximatai.frp"
    version = "1.0.0-SNAPSHOT"

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }


    tasks.test {
        useJUnitPlatform()
    }

    tasks.withType<Test> {
        systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    }
    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

}

