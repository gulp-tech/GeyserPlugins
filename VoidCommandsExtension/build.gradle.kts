plugins {
    java
}

val id = project.property("id") as String
val extensionName = project.property("name") as String
val author = project.property("author") as String
val extensionVersion = project.version as String
val geyserApiVersion = "2.11.0"
val protocolVersion = "3.0.0.Beta15-20261026.210753-2"
val paperApiVersion = "26.2-R0.1-SNAPSHOT"

repositories {
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    // Всё compileOnly: классы предоставляются Geyser-Spigot и Purpur.
    compileOnly("org.geysermc.geyser:api:$geyserApiVersion-SNAPSHOT")
    compileOnly("org.cloudburstmc.protocol:bedrock-codec:$protocolVersion")
    // Bukkit API достаточно; на Purpur 26.2 Paper API работает без изменений.
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
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
