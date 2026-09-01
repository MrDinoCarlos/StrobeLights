import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    id("java")
}

group = "es.mrdino"
version = "0.9.6"

val minecraftVersion = "1.20.1"
val paperApiVersion = "1.20.1-R0.1-SNAPSHOT"
val javaVersion = 17

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaVersion
}

tasks.test {
    useJUnitPlatform()
}

val generatedLegacyCarrierPack = layout.buildDirectory.dir(
    "generated/legacy-carrier-pack"
)

val generateLegacyCarrierPack = tasks.register("generateLegacyCarrierPack") {
    outputs.dir(generatedLegacyCarrierPack)

    doLast {
        val packRoot = generatedLegacyCarrierPack.get().asFile
        val itemModelDirectory = packRoot.resolve(
            "assets/minecraft/models/item/lp_payload"
        )
        val textureDirectory = packRoot.resolve(
            "assets/minecraft/textures/misc/lp_payload"
        )
        itemModelDirectory.mkdirs()
        textureDirectory.mkdirs()

        val overrides = mutableListOf<String>()
        for (highByte in 0..255) {
            val suffix = highByte.toString(16).padStart(2, '0')
            val texture = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            val sourcePixel = (24 shl 24) or (highByte shl 16) or
                (32 shl 8) or 224
            val flashPixel = (24 shl 24) or (highByte shl 16) or
                (224 shl 8) or 32
            for (y in 0 until 16) {
                for (x in 0 until 16) {
                    val pixel = if (x < 8) sourcePixel else flashPixel
                    texture.setRGB(x, y, pixel)
                }
            }
            check(ImageIO.write(
                texture,
                "png",
                textureDirectory.resolve("$suffix.png")
            ))

            itemModelDirectory.resolve("source_$suffix.json").writeText(
                """
                {
                  "parent": "minecraft:item/lp_payload_source_base",
                  "textures": { "0": "minecraft:misc/lp_payload/$suffix" }
                }
                """.trimIndent() + "\n"
            )
            itemModelDirectory.resolve("flash_$suffix.json").writeText(
                """
                {
                  "parent": "minecraft:item/lp_payload_flash_base",
                  "textures": { "0": "minecraft:misc/lp_payload/$suffix" }
                }
                """.trimIndent() + "\n"
            )
            overrides +=
                """    { "predicate": { "custom_model_data": ${6_700 + highByte} }, "model": "minecraft:item/lp_payload/source_$suffix" }"""
        }
        for (highByte in 0..255) {
            val suffix = highByte.toString(16).padStart(2, '0')
            overrides +=
                """    { "predicate": { "custom_model_data": ${7_200 + highByte} }, "model": "minecraft:item/lp_payload/flash_$suffix" }"""
        }

        val rootModelDirectory = packRoot.resolve(
            "assets/minecraft/models/item"
        )
        rootModelDirectory.mkdirs()
        rootModelDirectory.resolve("lime_stained_glass.json").writeText(
            buildString {
                appendLine("{")
                appendLine("""  "parent": "minecraft:block/lime_stained_glass",""")
                appendLine("""  "overrides": [""")
                appendLine(overrides.joinToString(",\n"))
                appendLine("  ]")
                appendLine("}")
            }
        )
    }
}

val shaderPackZip = tasks.register<Zip>("shaderPackZip") {
    dependsOn(generateLegacyCarrierPack)
    from("resource-pack")
    from(generatedLegacyCarrierPack)
    archiveFileName = "StrobeLights-ResourcePack-$minecraftVersion.zip"
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
    archiveFileName = "StrobeLights-v.${project.version}+mc.$minecraftVersion.jar"
    manifest {
        attributes(
            "Implementation-Title" to "StrobeLights",
            "Implementation-Version" to project.version,
            "Built-For-Minecraft" to minecraftVersion
        )
    }
}

tasks.build {
    dependsOn(exportResourcePack)
}
