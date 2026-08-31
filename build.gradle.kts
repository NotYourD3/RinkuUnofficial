import java.util.jar.JarFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// =============================================================================
// JCEF (Java Chromium Embedded Framework) integration
// =============================================================================
// Mirrors the upstream Rinku approach:
//   1. Resolve jcef-rinku.jar / jcef-rinku-sources.jar via the Ivy layout in
//      repositories.gradle from the NotYourD3/jcef-rinku GitHub Release
//      pinned by gradle.properties -> jcef_commit.
//   2. Make the JCEF Java API visible at compile time (compileOnly) so the
//      mod's own source compiles against org.cef.*.
//   3. Flat-merge org/cef/** class files from jcef-rinku.jar directly into
//      the produced mod JAR so downstream consumers require NO transitive
//      JCEF dependency.  Same flattening is applied to -sources so IDEs can
//      still step into JCEF sources when publishing sources.
//   4. Generate de.keksuccino.rinku.JcefRuntimeIdentity with the exact
//      40-char commit so the in-game downloader fetches the native binary
//      build exactly matching the Java API compiled in.
// =============================================================================

val jcefReleaseGroup = "java-cef"
val rawJcefCommit = providers.gradleProperty("jcef_commit").orNull
    ?: throw GradleException("gradle.properties must define jcef_commit")
if (!Regex("[0-9a-f]{40}").matches(rawJcefCommit)) {
    throw GradleException("gradle.properties jcef_commit must be exactly one lowercase 40-char hex commit, got: '$rawJcefCommit'")
}
val jcefCommit: String = rawJcefCommit
extra["jcefCommit"] = jcefCommit

// --------------------------------------------------------------------------
// Custom detached configurations for the three JCEF artifacts
// --------------------------------------------------------------------------
val jcefApi: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = false
}
val jcefBinary: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    extendsFrom(jcefApi)
}
val jcefSources: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

// compileOnly picks up the JCEF Java API so our code can `import org.cef.*`
// WITHOUT leaking jcef-rinku as a published transitive dependency.
configurations["compileOnly"].extendsFrom(jcefApi)
configurations["testCompileOnly"].extendsFrom(jcefApi)

dependencies {
    // Use single-string coordinates to avoid Gradle 9+ Configuration.invoke(...) deprecation.
    jcefApi("$jcefReleaseGroup:jcef-rinku:$jcefCommit@jar")
    jcefSources("$jcefReleaseGroup:jcef-rinku:$jcefCommit:sources@jar")
}

// JCEF binary classes must also be present when running dev clients/servers
sourceSets["main"].runtimeClasspath += jcefBinary
sourceSets["test"].runtimeClasspath += jcefBinary

// --------------------------------------------------------------------------
// Generate JcefRuntimeIdentity.java (build-time -> run-time commit bridge)
// --------------------------------------------------------------------------
val generatedJcefIdentityDir = layout.buildDirectory.dir("generated/sources/jcefIdentity/java/main")
val generatedJcefIdentityFile = generatedJcefIdentityDir.map { it.file("de/keksuccino/rinku/JcefRuntimeIdentity.java") }

val generateJcefRuntimeIdentity by tasks.registering {
    notCompatibleWithConfigurationCache("Uses project.logger and project state in doLast")
    inputs.property("jcefCommit", jcefCommit)
    outputs.dir(generatedJcefIdentityDir)
    doLast {
        val sourceFile = generatedJcefIdentityFile.get().asFile
        sourceFile.parentFile.mkdirs()
        val existed = sourceFile.isFile
        val commit = jcefCommit
        val javaSource = buildString {
            appendLine("package de.keksuccino.rinku;")
            appendLine()
            appendLine("/**")
            appendLine(" * Build-generated identity binding the compiled-in JCEF Java API and")
            appendLine(" * the in-game-downloaded native runtime to one exact NotYourD3/jcef-rinku")
            append(" * GitHub Release (tag = java-cef-").append(commit).appendLine(").")
            appendLine(" *")
            appendLine(" * THIS FILE IS AUTO-GENERATED.  DO NOT EDIT BY HAND.")
            appendLine(" * Regenerate by running any Gradle task (e.g. ./gradlew jar).")
            appendLine(" */")
            appendLine("public final class JcefRuntimeIdentity {")
            appendLine()
            append("    public static final String JAVA_CEF_COMMIT = \"").append(commit).appendLine("\";")
            appendLine()
            appendLine("    private JcefRuntimeIdentity() {}")
            appendLine()
            appendLine("}")
        }
        sourceFile.writeText(javaSource.trimEnd(), Charsets.UTF_8)
        val rel = rootProject.projectDir.toPath().relativize(sourceFile.toPath())
        logger.lifecycle("${if (existed) "Regenerated" else "Generated"} JCEF runtime identity at $rel for commit $commit.")
    }
}

// Make sure the generated source is compiled and indexed by IDEs
sourceSets["main"].java.srcDir(generatedJcefIdentityDir)
idea {
    module {
        generatedSourceDirs.add(generatedJcefIdentityDir.get().asFile)
    }
}

tasks.named("compileJava").configure { dependsOn(generateJcefRuntimeIdentity) }
try { tasks.named("processSources", AbstractCopyTask::class.java).configure { dependsOn(generateJcefRuntimeIdentity) } } catch (_: Exception) {}

// --------------------------------------------------------------------------
// Flat-merge JCEF binaries / sources into the produced Rinku artifacts
// --------------------------------------------------------------------------
val jcefBinaryContents = providers.provider { zipTree(jcefBinary.singleFile) }
val jcefSourceContents = providers.provider { zipTree(jcefSources.singleFile) }

val projectLicense = rootProject.file("LICENSE-template").takeIf { it.exists() }
    ?: rootProject.file("LICENSE")
val jcefLicense = rootProject.file("licenses/JCEF-LICENSE.txt")

// In DEVELOPMENT RUNS (runClient / runServer / plain compileJava) the jcef-rinku Java API jar
// is already on `runtimeClasspath` (line 73 above) so we do NOT need to flat-merge its org/cef/**
// classes into the dev jar.  Worse: Forge 1.7.10 scans candidate mod jars with ASM 5.0.3, which
// cannot read Java 9+ class file versions and throws an IllegalArgumentException on the JCEF
// classes, causing Forge to mark the whole Rinku dev jar as "probably a corrupt zip" and IGNORE
// the mod entirely.  For release builds (build / check / assemble / publish / verifyJcefPackaging /
// reobfJar) we still flat-merge, because in that case the downstream jar is the final artifact.
val requestedTasks = gradle.startParameter.taskNames
val isDevelopmentRun = requestedTasks.any { t ->
    t == "runClient" || t == "runServer" || t == "classes" || t == "compileJava" || t == "compileTestJava" ||
        t == "idea" || t == "eclipse" || t == "processResources" || t.endsWith(":runClient") || t.endsWith(":runServer")
}

tasks.named<Jar>("jar").configure {
    dependsOn(jcefBinary)
    if (!isDevelopmentRun) {
        from(jcefBinaryContents) {
            include("org/cef/**")
            // ASM 5 cannot parse Java 9+ module descriptors and will throw
            //   IllegalArgumentException: Unsupported class file major version XX
            // if we ship module-info.class inside a scanned Forge 1.7.10 mod jar.
            exclude("**/module-info.class", "**/META-INF/versions/**/module-info.class")
        }
    }
    if (projectLicense.exists()) {
        from(projectLicense) { rename { "${it}_Rinku" } }
    }
    from(jcefLicense) {
        into("META-INF/licenses/jcef")
        rename { "LICENSE.txt" }
    }
    manifest {
        attributes(
            "java-cef-commit" to jcefCommit
        )
    }
}

tasks.named<Jar>("sourcesJar").configure {
    dependsOn(jcefSources, generateJcefRuntimeIdentity)
    from(jcefSourceContents) {
        include("org/cef/**/*.java")
    }
    if (projectLicense.exists()) {
        from(projectLicense) { rename { "${it}_Rinku" } }
    }
    from(jcefLicense) {
        into("META-INF/licenses/jcef")
        rename { "LICENSE.txt" }
    }
    manifest {
        attributes("java-cef-commit" to jcefCommit)
    }
}

// --------------------------------------------------------------------------
// Verification task — assert the packaging actually contains JCEF flat-merged
// --------------------------------------------------------------------------
val verifyJcefPackaging by tasks.registering {
    group = "verification"
    description = "Verifies jcef-rinku classes are flat-merged into Rinku binary and sources JARs"
    dependsOn("jar", "sourcesJar")
    inputs.property("jcefCommit", jcefCommit)
    inputs.file(jcefLicense)
    val binaryJarTask = tasks.named<Jar>("jar")
    val sourcesJarTask = tasks.named<Jar>("sourcesJar")
    inputs.files(binaryJarTask.flatMap { it.archiveFile }, sourcesJarTask.flatMap { it.archiveFile })
    inputs.files(jcefBinary, jcefSources)

    doLast {
        fun entries(jf: JarFile): Set<String> = jf.entries().asSequence()
            .filter { !it.isDirectory }
            .map { it.name }
            .toSet()

        // Collect what the upstream JCEF binary/sources JARs ship
        val upstreamBinaryEntries: Set<String>
        val upstreamSourceEntries: Set<String>
        JarFile(jcefBinary.singleFile).use { jar ->
            upstreamBinaryEntries = entries(jar).filter { it.startsWith("org/cef/") }.toSet()
        }
        JarFile(jcefSources.singleFile).use { jar ->
            upstreamSourceEntries = entries(jar)
                .filter { it.startsWith("org/cef/") && it.endsWith(".java") }
                .toSet()
        }

        if (upstreamBinaryEntries.isEmpty()) {
            throw GradleException("Upstream jcef-rinku.jar contained no org/cef/** entries — invalid pin ($jcefCommit)?")
        }

        // Local sources jar may legitimately be empty only when the sibling
        // checkout's java/ directory has no org/cef/*.java — which should
        // never happen, so guard the same way.  For remote builds the check
        // catches mis-published -sources artifacts.
        if (upstreamSourceEntries.isEmpty()) {
            throw GradleException("Upstream jcef-rinku-sources.jar contained no org/cef/**/*.java entries — invalid pin ($jcefCommit)?")
        }

        val expectedLicenseBytes = jcefLicense.readBytes()

        // Binary JAR
        JarFile(binaryJarTask.get().archiveFile.get().asFile).use { archive ->
            val names = entries(archive)
            val missing = upstreamBinaryEntries - names
            if (missing.isNotEmpty()) {
                throw GradleException("Rinku binary JAR is missing ${missing.size} flat-merged JCEF class entries (first: ${missing.first()})")
            }
            if (names.any { it == "jcef-rinku.jar" || it.endsWith("/jcef-rinku.jar") }) {
                throw GradleException("Rinku binary JAR contains NESTED jcef-rinku.jar; classes must be flat-merged.")
            }
            val actualCommit = archive.manifest?.mainAttributes?.getValue("java-cef-commit")
            if (actualCommit != jcefCommit) {
                throw GradleException("Rinku binary JAR manifest java-cef-commit = '$actualCommit'; expected '$jcefCommit'")
            }
            val licEntry = archive.getJarEntry("META-INF/licenses/jcef/LICENSE.txt")
                ?: throw GradleException("Rinku binary JAR is missing META-INF/licenses/jcef/LICENSE.txt")
            if (!archive.getInputStream(licEntry).readBytes().contentEquals(expectedLicenseBytes)) {
                throw GradleException("Rinku binary JAR JCEF license bytes do not match licenses/JCEF-LICENSE.txt")
            }
        }

        // Sources JAR
        JarFile(sourcesJarTask.get().archiveFile.get().asFile).use { archive ->
            val names = entries(archive)
            val missing = upstreamSourceEntries - names
            if (missing.isNotEmpty()) {
                throw GradleException("Rinku sources JAR is missing ${missing.size} flat-merged JCEF source entries (first: ${missing.first()})")
            }
            val actualCommit = archive.manifest?.mainAttributes?.getValue("java-cef-commit")
            if (actualCommit != jcefCommit) {
                throw GradleException("Rinku sources JAR manifest java-cef-commit = '$actualCommit'; expected '$jcefCommit'")
            }
            val licEntry = archive.getJarEntry("META-INF/licenses/jcef/LICENSE.txt")
                ?: throw GradleException("Rinku sources JAR is missing META-INF/licenses/jcef/LICENSE.txt")
            if (!archive.getInputStream(licEntry).readBytes().contentEquals(expectedLicenseBytes)) {
                throw GradleException("Rinku sources JAR JCEF license bytes do not match licenses/JCEF-LICENSE.txt")
            }
        }

        logger.lifecycle("Verified JCEF packaging (commit=$jcefCommit; binaryClasses=${upstreamBinaryEntries.size}; sourceFiles=${upstreamSourceEntries.size}).")
    }
}

tasks.named("check").configure { dependsOn(verifyJcefPackaging) }

// --------------------------------------------------------------------------
// Expose the commit hash as a system property during tests so installer tests
// don't need to spin up a full class-generate cycle
// --------------------------------------------------------------------------
tasks.withType(Test::class.java).configureEach {
    systemProperty("rinku.test.jcefCommit", jcefCommit)
    systemProperty("rinku.test.projectDir", rootProject.projectDir.absolutePath)
}
