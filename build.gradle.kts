plugins {
    java
    alias(libs.plugins.quarkus) apply false
//    id("org.kordamp.gradle.jandex") version "2.1.0"
}

allprojects {
    apply {
        plugin("java")
//        plugin("org.kordamp.gradle.jandex")
    }

    repositories {
//        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
        mavenLocal()
    }

    group = "net.ximatai.frp"
    version = "1.26.1"

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }


    tasks.test {
        useJUnitPlatform()
    }

//    tasks.named<Javadoc>("javadoc") {
//        mustRunAfter(tasks.named("jandex"))
//    }

    tasks.withType<Test> {
        systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    }
    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

}

