plugins {
    java
}

group = "dev.cashroy"
// CI passes the tag version (v1.2.3 -> 1.2.3) via RELEASE_VERSION.
version = System.getenv("RELEASE_VERSION")?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "1.0.0"

// Paper 26.x publishes versions like 26.3.0.build.12-stable.
// "26.3.+" picks the newest 26.3 build; override with -PpaperVersion=... if you want a fixed one.
val paperVersion: String = (findProperty("paperVersion") as String?) ?: "26.3.+"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveFileName.set("PaperWaystones-${project.version}.jar")
}
