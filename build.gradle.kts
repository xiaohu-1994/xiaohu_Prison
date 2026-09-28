plugins {
    `java-library`
    // Fat jar: bundles the plugin's runtime dependencies into one deployable jar.
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.xiaohu.prison"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // The Paper server provides this API at runtime, so it must not be bundled.
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks {
    jar {
        archiveBaseName.set("xiaohu_Prison")
    }
    shadowJar {
        archiveBaseName.set("xiaohu_Prison")
        archiveClassifier.set("")
    }
    assemble {
        dependsOn(shadowJar)
    }
    processResources {
        // Keep plugin.yml in sync with the build version.
        filesMatching("plugin.yml") {
            expand("version" to version)
        }
    }
}
