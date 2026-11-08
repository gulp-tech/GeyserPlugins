plugins {
    java
}

val id = project.property("id") as String
val extensionName = project.property("name") as String
val author = project.property("author") as String
val extensionVersion = project.version as String
val geyserApiVersion = "2.11.0"
val protocolVersion = "3.0.0.Beta15-20261026.210753-2"
val purpurVersion = providers.environmentVariable("PURPUR_VERSION")
    .orElse("26.2.build.2632-stable")
    .get()

repositories {
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.purpurmc.org/snapshots/")
    mavenCentral()
}

dependencies {
    // Всё compileOnly: классы предоставляются Geyser-Spigot и Purpur.
    compileOnly("org.geysermc.geyser:api:$geyserApiVersion-SNAPSHOT")
    compileOnly("org.cloudburstmc.protocol:bedrock-codec:$protocolVersion")
    compileOnly("org.purpurmc.purpur:purpur-api:$purpurVersion")
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
