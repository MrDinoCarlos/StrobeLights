plugins {
    id("java")
}

group = "es.mrdino"
version = "0.9.6"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.test {
    useJUnitPlatform()
}

val shaderPackZip = tasks.register<Zip>("shaderPackZip") {
    from("resource-pack")
    archiveFileName = "StrobeLights-ResourcePack-1.21.4.zip"
    destinationDirectory = layout.buildDirectory.dir("generated-resource-pack")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.processResources {
    dependsOn(shaderPackZip)
    val properties = mapOf("version" to project.version)
    inputs.properties(properties)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(properties)
    }
    from(shaderPackZip.flatMap { it.archiveFile }) {
        into("embedded")
    }
}

val exportResourcePack = tasks.register<Copy>("exportResourcePack") {
    dependsOn(shaderPackZip)
    from(shaderPackZip.flatMap { it.archiveFile })
    into(layout.buildDirectory.dir("distributions"))
}

tasks.jar {
    archiveFileName = "StrobeLights-v.${project.version}+mc.1.21.4.jar"
    manifest {
        attributes(
            "Implementation-Title" to "StrobeLights",
            "Implementation-Version" to project.version,
            "Built-For-Minecraft" to "1.21.4"
        )
    }
}

tasks.build {
    dependsOn(exportResourcePack)
}
