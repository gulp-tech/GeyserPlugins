plugins { java }
group = "org.pexserver"
version = "0.2.13-voidpe.3"
repositories { maven("https://repo.opencollab.dev/main/"); mavenCentral() }
dependencies {
    compileOnly("org.geysermc.geyser:api:2.11.3-SNAPSHOT")
    compileOnly("org.geysermc.geyser:core:2.11.3-SNAPSHOT")
    testImplementation("org.geysermc.geyser:api:2.11.3-SNAPSHOT")
    testImplementation("org.geysermc.geyser:core:2.11.3-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
tasks.processResources { filesMatching("extension.yml") { expand("version" to project.version) } }
tasks.jar { archiveBaseName.set("GeyserJavaGlowing") }
tasks.test { useJUnitPlatform(); testLogging { events("passed", "skipped", "failed") } }
