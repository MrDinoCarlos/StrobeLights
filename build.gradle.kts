import java.util.zip.ZipFile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins { id("java") }

group = "es.mrdino"
version = "0.10.43"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

apply(from = "gradle/legacy-carriers.gradle.kts")

data class Target(val minecraft: String, val api: String, val java: Int, val family: String) {
    val key = "mc" + minecraft.replace(".", "_")
    val legacy = family == "1.20.X"
    val pluginApi = if (legacy) "1.20" else if (minecraft == "26.1.2") "26.1" else minecraft
}

val targets = listOf(
    Target("1.20.1", "1.20.1-R0.1-SNAPSHOT", 17, "1.20.X"),
    Target("1.21.4", "1.21.4-R0.1-SNAPSHOT", 21, "1.21.X"),
    Target("1.21.10", "1.21.10-R0.1-SNAPSHOT", 21, "1.21.X"),
    Target("1.21.11", "1.21.11-R0.1-SNAPSHOT", 21, "1.21.X"),
    Target("26.1.2", "26.1.2.build.74-stable", 25, "26.1.X"),
    Target("26.2", "26.2.build.121-stable", 25, "26.2.X"),
    Target("26.3", "26.3.build.252-stable", 25, "26.3.X")
)

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

val legacyJava = tasks.register<Sync>("prepareLegacyJava") {
    val overrides = fileTree("compatibility/legacy/java").files.map {
        it.relativeTo(file("compatibility/legacy/java")).invariantSeparatorsPath
    }
    from("src/main/java") { exclude(overrides) }
    from("compatibility/legacy/java")
    into(layout.buildDirectory.dir("generated/legacy-java"))
}

val packs = targets.associateWith { target ->
    val overlay = file("compatibility/${target.minecraft}/resource-pack")
    val excludesFile = file("compatibility/${target.minecraft}/pack-excludes.txt")
    val excluded = if (excludesFile.exists()) excludesFile.readLines().filter { it.isNotBlank() } else emptyList()
    val shared = file("compatibility/modern/resource-pack")
    val useShared = target.minecraft in listOf("1.21.10", "1.21.11", "26.1.2", "26.2")
    val overridden = fileTree(overlay).files.map { it.relativeTo(overlay).invariantSeparatorsPath }
    val sharedOverrides = if (useShared) fileTree(shared).files.map { it.relativeTo(shared).invariantSeparatorsPath } else emptyList()
    val prepare = tasks.register<Sync>("preparePack${target.key}") {
        from("resource-pack") { exclude(excluded + overridden + sharedOverrides) }
        if (useShared) from(shared) { exclude(overridden) }
        from(overlay)
        if (target.legacy) {
            dependsOn("generateLegacyCarrierPack")
            from(layout.buildDirectory.dir("generated/legacy-carrier-pack-opaque"))
        }
        if (excludesFile.exists()) inputs.file(excludesFile)
        into(layout.buildDirectory.dir("packs/${target.minecraft}"))
    }
    tasks.register<Zip>("shaderPackZip${target.key}") {
        dependsOn(prepare)
        from(prepare)
        archiveFileName = "StrobeLights-ResourcePack-${target.minecraft}.zip"
        destinationDirectory = layout.buildDirectory.dir("generated-resource-pack")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        duplicatesStrategy = DuplicatesStrategy.FAIL
    }
}

val testTasks = mutableListOf<TaskProvider<Test>>()
val familyJars = mutableListOf<TaskProvider<Jar>>()
targets.forEach { target ->
    val defaultTarget = target.minecraft == "1.21.4"
    val mainSources = if (defaultTarget) sourceSets.main.get() else sourceSets.create(target.key)
    if (!defaultTarget) {
        mainSources.java.setSrcDirs(emptyList<String>())
        if (target.legacy) mainSources.java.srcDir(legacyJava) else mainSources.java.srcDir("src/main/java")
        mainSources.resources.setSrcDirs(listOf("src/main/resources"))
    }
    val testSources = if (defaultTarget) sourceSets.test.get() else sourceSets.create("${target.key}Test")
    if (!defaultTarget) {
        val overlay = file("compatibility/${target.minecraft}/tests")
        val overridden = fileTree(overlay).files.map { it.relativeTo(overlay).invariantSeparatorsPath }
        val prepareTests = tasks.register<Sync>("prepareTests${target.key}") {
            from("src/test/java") {
                exclude(overridden)
                exclude("**/OptifineCarrierTest.java")
                if (target.minecraft == "26.3") exclude("**/ShaderPackContractTest.java")
            }
            from(overlay)
            into(layout.buildDirectory.dir("generated/tests/${target.minecraft}"))
        }
        testSources.java.setSrcDirs(listOf(prepareTests))
        testSources.resources.setSrcDirs(emptyList<String>())
        testSources.compileClasspath += mainSources.output
        testSources.runtimeClasspath += mainSources.output
    }
    dependencies {
        add(mainSources.compileOnlyConfigurationName, "io.papermc.paper:paper-api:${target.api}")
        add(testSources.implementationConfigurationName, platform("org.junit:junit-bom:5.11.4"))
        add(testSources.implementationConfigurationName, "org.junit.jupiter:junit-jupiter")
        add(testSources.implementationConfigurationName, "org.mockito:mockito-core:5.23.0")
        add(testSources.implementationConfigurationName, "io.papermc.paper:paper-api:${target.api}")
        add(testSources.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher")
    }
    listOf(mainSources, testSources).forEach { sources ->
        tasks.named<JavaCompile>(sources.compileJavaTaskName) {
            javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(target.java) }
            options.encoding = "UTF-8"
            options.release = target.java
        }
    }
    tasks.named<ProcessResources>(mainSources.processResourcesTaskName) {
        val properties = mapOf("version" to project.version, "apiVersion" to target.pluginApi,
            "minecraftFamily" to target.family)
        inputs.properties(properties)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") { expand(properties) }
        targets.filter { it.family == target.family }.forEach { member ->
            from(packs.getValue(member).flatMap { it.archiveFile }) { into("embedded") }
        }
    }
    val fixture = tasks.register<Sync>("prepareFixture${target.key}") {
        from(mainSources.java) { into("src/main/java") }
        from("src/main/resources") { into("src/main/resources") }
        from(layout.buildDirectory.dir("packs/${target.minecraft}")) { into("resource-pack") }
        from("build.gradle.kts")
        dependsOn("preparePack${target.key}")
        if (target.legacy) {
            from(layout.buildDirectory.dir("generated/legacy-carrier-pack-opaque")) {
                into("build/generated/legacy-carrier-pack-opaque")
            }
        }
        into(layout.buildDirectory.dir("fixtures/${target.minecraft}"))
    }
    val testTask = if (defaultTarget) tasks.named<Test>("test") else tasks.register<Test>("test${target.key}")
    testTask.configure {
        description = "Runs behavioral and resource-pack contracts on Paper ${target.minecraft}."
        group = "verification"
        dependsOn(fixture)
        testClassesDirs = testSources.output.classesDirs
        classpath = testSources.runtimeClasspath
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(target.java) }
        workingDir = layout.buildDirectory.dir("fixtures/${target.minecraft}").get().asFile
        useJUnitPlatform()
        systemProperty("strobelights.test.minecraft", target.minecraft)
        jvmArgs("-XX:+EnableDynamicAgentLoading")
    }
    testTasks.add(testTask)
    if (target == targets.first { it.family == target.family }) {
        val family = targets.filter { it.family == target.family }
        val jarTask = if (defaultTarget) tasks.named<Jar>("jar") else tasks.register<Jar>("jar${target.key}")
        jarTask.configure {
            if (!defaultTarget) from(mainSources.output)
            archiveFileName = "StrobeLights-${project.version}+mc.v.${target.family}.jar"
            destinationDirectory = layout.buildDirectory.dir("intermediates/jars")
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            duplicatesStrategy = DuplicatesStrategy.FAIL
            manifest.attributes(
                "Implementation-Title" to "StrobeLights",
                "Implementation-Version" to project.version,
                "Built-For-Minecraft" to family.joinToString(",") { it.minecraft }
            )
        }
        familyJars.add(jarTask)
    }
}

tasks.register("shaderPackZip") {
    group = "build"
    description = "Builds all maintained packs, also embedded in the family JARs."
    dependsOn(packs.values)
}

val exportJars = tasks.register<Sync>("exportJars") {
    group = "distribution"
    description = "Exports exactly one plugin JAR per Minecraft family."
    from(familyJars.map { it.flatMap { task -> task.archiveFile } })
    into(layout.buildDirectory.dir("exports"))
}

tasks.register<Sync>("exportResourcePack") {
    group = "distribution"
    description = "Optional standalone packs; normal releases need only exportJars."
    from(packs.values.map { it.flatMap { task -> task.archiveFile } })
    into(layout.buildDirectory.dir("distributions/resource-packs"))
}

tasks.check { dependsOn(testTasks) }
tasks.assemble { dependsOn(exportJars) }

val verifyExports = tasks.register("verifyExports") {
    group = "verification"
    description = "Checks family packaging, embedded pack routing and Java bytecode versions."
    dependsOn(exportJars)
    inputs.files(familyJars.map { it.flatMap { task -> task.archiveFile } })
    doLast {
        val exports = layout.buildDirectory.dir("exports").get().asFile.listFiles()!!.filter { it.extension == "jar" }
        check(exports.size == targets.map { it.family }.distinct().size) { "Expected one JAR per Minecraft family" }
        targets.groupBy { it.family }.forEach { (family, members) ->
            val jar = exports.single { it.name.endsWith("+mc.v.$family.jar") }
            ZipFile(jar).use { zip ->
                val embedded = zip.entries().asSequence().filter { it.name.startsWith("embedded/") && it.name.endsWith(".zip") }.map { it.name }.toSet()
                check(embedded == members.map { "embedded/StrobeLights-ResourcePack-${it.minecraft}.zip" }.toSet()) { "Incorrect embedded packs in $family" }
                val metadata = zip.getInputStream(zip.getEntry("plugin.yml")).bufferedReader().use { it.readText() }
                check(metadata.contains("api-version: '${members.first().pluginApi}'")) { "Incorrect minimum API in $family" }
                val bytes = zip.getInputStream(zip.getEntry("es/mrdino/strobelights/StrobeLightsPlugin.class")).use { it.readBytes() }
                val major = ((bytes[6].toInt() and 255) shl 8) or (bytes[7].toInt() and 255)
                check(major == members.first().java + 44) { "Incorrect Java bytecode in $family" }
                members.forEach { member ->
                    val entry = zip.getEntry("embedded/StrobeLights-ResourcePack-${member.minecraft}.zip")
                    val actual = zip.getInputStream(entry).use { it.readBytes() }
                    val expected = packs.getValue(member).get().archiveFile.get().asFile.readBytes()
                    check(actual.contentEquals(expected)) { "Stale embedded pack for ${member.minecraft}" }
                }
            }
        }
    }
}
exportJars.configure { mustRunAfter(testTasks) }
tasks.check { dependsOn(verifyExports) }

// Run after validate_26_3_shaders.py has expanded the shaders. This executes
// capture -> lighting -> composition in a hidden OpenGL context, not just a compiler.
val gpuSources = sourceSets.create("gpuRegression") {
    java.srcDir("scripts/gpu")
}
val gpuNativePlatform = when {
    System.getProperty("os.name").startsWith("Windows") -> "natives-windows"
    System.getProperty("os.name").startsWith("Mac") -> "natives-macos"
    else -> "natives-linux"
} + if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "-arm64" else ""
dependencies {
    listOf("lwjgl", "lwjgl-opengl", "lwjgl-sdl").forEach { module ->
        add(gpuSources.implementationConfigurationName, "org.lwjgl:$module:3.4.3")
        add(gpuSources.runtimeOnlyConfigurationName, "org.lwjgl:$module:3.4.3:$gpuNativePlatform")
    }
    add(gpuSources.implementationConfigurationName, "org.joml:joml:1.10.9")
}
tasks.register<JavaExec>("gpuTest263") {
    group = "verification"
    description = "Executes 26.3 RGB shaders on the GPU with reversed-Z and verifies actual pixel colors."
    classpath = gpuSources.runtimeClasspath
    mainClass = "FrameRendererGpuTest"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args(layout.buildDirectory.dir("shader-validation").get().asFile.absolutePath)
    doFirst {
        check(layout.buildDirectory.file("shader-validation/item-opengl-0.vert").get().asFile.exists()) {
            "Run scripts/validate_26_3_shaders.py first to prepare the shader sources."
        }
    }
}
