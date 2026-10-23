plugins {
    java
}

val id = project.property("id") as String
val extensionName = project.property("name") as String
val author = project.property("author") as String
val extensionVersion = project.version as String
val geyserApiVersion = "2.11.0"

repositories {
    maven("https://repo.opencollab.dev/main/")
    mavenCentral()
}

dependencies {
    compileOnly("org.geysermc.geyser:api:$geyserApiVersion-SNAPSHOT")
    compileOnly("org.geysermc.cumulus:cumulus:1.1.2")
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
