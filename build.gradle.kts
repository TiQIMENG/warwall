plugins {
    `java-library`
    // Fat jar：把插件自身的运行期依赖打进一个可部署 jar。
    id("com.gradleup.shadow") version "8.3.6"
}

val paperVersion: String = providers.gradleProperty("paperVersion").get()
val pluginVersion: String = providers.gradleProperty("version").get()

group = providers.gradleProperty("group").get()
version = pluginVersion

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 服务端运行期自带这部分 API，不能打进 jar。
    compileOnly("io.papermc.paper:paper-api:$paperVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

java {
    toolchain {
        // 本机同时装了 jdk-21 与 jdk-25，用 toolchain 固定到 21；foojay 在缺失时自动下载。
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    maxHeapSize = "256m"
}

tasks {
    shadowJar {
        archiveBaseName.set("warwall")
        archiveClassifier.set("")
        // 第三方许可证声明随包分发（当前无第三方运行期依赖，保留机制）。
        from("LICENSE") {
            into("META-INF")
            rename { "LICENSE-WARWALL.txt" }
        }
    }
    assemble {
        dependsOn(shadowJar)
    }
    processResources {
        filteringCharset = "UTF-8"
        // plugin.yml 里的 ${version} 由构建注入，避免手写版本号与 jar 名不一致。
        filesMatching("plugin.yml") {
            expand("version" to version)
        }
    }
}
