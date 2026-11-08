plugins {
    java
}

val id = project.property("id") as String
val extensionName = project.property("name") as String
val author = project.property("author") as String
val extensionVersion = project.version as String
val geyserApiVersion = "2.11.0"
val protocolVersion = "3.0.0.Beta15-20261026.210753-2"

repositories {
    maven("https://repo.opencollab.dev/main/")
    mavenCentral()
}

val bukkitStubs = sourceSets.create("bukkitStubs") {
    // Только сигнатуры для компиляции. Jar собирается из main, поэтому
    // org/bukkit/** остаётся на стороне Purpur и не попадает в артефакт.
}

val selfTest = sourceSets.create("selfTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    // Всё compileOnly: классы предоставляются Geyser-Spigot и Purpur.
    compileOnly("org.geysermc.geyser:api:$geyserApiVersion-SNAPSHOT")
    compileOnly("org.cloudburstmc.protocol:bedrock-codec:$protocolVersion")
    compileOnly(files(bukkitStubs.output))
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.register<JavaExec>("selfTest") {
    group = "verification"
    description = "Runs dependency-free suggestion regression tests with assertions enabled."
    dependsOn(selfTest.classesTaskName)
    classpath = selfTest.runtimeClasspath
    mainClass.set("ru.voidpe.geyser.commands.SuggestionTextTest")
    enableAssertions = true
}

tasks {
    check {
        dependsOn("selfTest")
    }
    processResources {
        filesMatching("extension.yml") {
            expand(
                "id" to id,
                "name" to extensionName,
                "api" to geyserApiVersion,
                "version" to extensionVersion,
                "author" to author
            )
        }
    }
}
