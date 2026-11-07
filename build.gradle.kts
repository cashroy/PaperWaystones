plugins {
    java
}

group = "dev.cashroy"
// CI passes the tag version (v1.2.3 -> 1.2.3) via RELEASE_VERSION.
version = System.getenv("RELEASE_VERSION")?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "1.0.0"

// Built against the oldest supported API (1.21.4) for Java 21, so one jar runs on 1.21.4 through 26.x.
// CI also compiles against the newest Paper (-PpaperVersion=26.3.+ -PjavaRelease=25) to catch API breaks.
val paperVersion: String = (findProperty("paperVersion") as String?) ?: "1.21.4-R0.1-SNAPSHOT"
val javaRelease: Int = ((findProperty("javaRelease") as String?) ?: "21").toInt()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaRelease))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(javaRelease)
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
