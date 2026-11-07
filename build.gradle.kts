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

version = "3.0.5"
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

// 当本地存在 JCEF-RINKU 仓库时，直接以子项目方式编译，方便同时修改 JCEF 与 Rinku 代码；
// 否则回退到从 GitHub Release 下载 jcef-rinku.jar。
val useLocalJcef: Boolean = file("../JCEF-RINKU/build.gradle").exists()

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
    if (useLocalJcef) {
        // 本地子项目：jcefApi 用 project 依赖（编译期）；jcefBinary / JCEF 源码包
        // 都用 files(...) 直接引用构建产物并显式 builtBy，避免：
        //   1) runtimeClasspath 上出现 project(":jcef-rinku") 触发 GTNH 约定插件
        //      切换到 classes 目录模式（丢失 MANIFEST.MF 中的 MixinConfigs）；
        //   2) 产物作为 project 依赖被 RFG 的 DependencyDeobfuscationTransform
        //      在「计算任务图」阶段解析——那时 :jcef-rinku 的 jar 任务还没执行，
        //      transformer 把不存在的输入路径注册为输出，报
        //      "Transform output ... must exist"。
        add("jcefApi", project(":jcef-rinku", configuration = "jcefJar"))
    } else {
        // 远程依赖：从 GitHub Release 下载 jcef-rinku.jar
        add("jcefApi", "$jcefReleaseGroup:jcef-rinku:$jcefCommit@jar")
        add("jcefSources", "$jcefReleaseGroup:jcef-rinku:$jcefCommit:sources@jar")
        add("jcefBinary", "$jcefReleaseGroup:jcef-rinku:$jcefCommit@jar")
    }
}

// --------------------------------------------------------------------------
// JCEF 源码包输入：刻意不通过可解析 Configuration 解析。
// RFG 会给所有可解析配置打上 rfgDeobfuscatorTransformed=true，导致 Gradle 对
// 其中的每个 jar 运行 DependencyDeobfuscationTransform。对「由本构建中另一个
// 任务稍后才产出」的 project artifact，transform 在任务图计算阶段就看到不存在的
// 文件，于是报 "Transform output ... must exist"。
// 普通 FileCollection + builtBy 保留了任务依赖，但不参与变体/属性匹配，因此
// 不会触发任何 artifact transform；远程模式下仍是下载好的实体 jar，正常解析。
// --------------------------------------------------------------------------
val jcefSourceJarFiles = files()
if (useLocalJcef) {
    jcefSourceJarFiles.from(file("../JCEF-RINKU/build/libs/jcef-rinku-sources.jar"))
    jcefSourceJarFiles.builtBy(":jcef-rinku:sourcesJar")
} else {
    jcefSourceJarFiles.from(jcefSources)
}
val jcefSourceJarFile = providers.provider { jcefSourceJarFiles.singleFile }

// 二进制包同理：不经过可解析 Configuration，避免 RFG artifact transform 的
// 先有鸡先有蛋问题（本地产物由 :jcef-rinku:jar 稍后产出）。
val jcefBinaryJarFiles = files()
if (useLocalJcef) {
    jcefBinaryJarFiles.from(file("../JCEF-RINKU/build/libs/jcef-rinku.jar"))
    jcefBinaryJarFiles.builtBy(":jcef-rinku:jar")
} else {
    jcefBinaryJarFiles.from(jcefBinary)
}
val jcefBinaryJarFile = providers.provider { jcefBinaryJarFiles.singleFile }

// JCEF binary classes must also be present when running dev clients/servers
sourceSets["main"].runtimeClasspath += jcefBinaryJarFiles
sourceSets["test"].runtimeClasspath += jcefBinaryJarFiles

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
val jcefBinaryContents = providers.provider { zipTree(jcefBinaryJarFile.get()) }
val jcefSourceContents = providers.provider { zipTree(jcefSourceJarFile.get()) }

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
    dependsOn(jcefBinaryJarFiles)
    // 本地同时开发 JCEF 时，把重编的 jcef.dll 内嵌进 mod jar，这样「单文件复制进
    // mods/」就能直接验证 GPU 零拷贝路径；CefUtil 会在 CefApp.startup() 之前把它
    // 发布到 JCEF 安装目录。CI / 远程构建没有本地 JCEF 源码树时跳过：运行时下载器
    // 会按 jcef_commit 从对应 Release 取到同一版本的 DLL。
    val bundledJcefDll = file("../JCEF-Rinku/jcef_build/native/Release/jcef.dll")
    if (useLocalJcef && bundledJcefDll.isFile) {
        from(bundledJcefDll) {
            into("rinku_native")
            rename { "jcef.dll" }
        }
    }
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
    dependsOn(jcefSourceJarFiles, generateJcefRuntimeIdentity)
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
    // doLast 里直接读取任务对象与脚本级 FileCollection，无法被配置缓存序列化
    notCompatibleWithConfigurationCache("Reads task objects and script file collections in doLast")
    inputs.property("jcefCommit", jcefCommit)
    inputs.file(jcefLicense)
    val binaryJarTask = tasks.named<Jar>("jar")
    val sourcesJarTask = tasks.named<Jar>("sourcesJar")
    inputs.files(binaryJarTask.flatMap { it.archiveFile }, sourcesJarTask.flatMap { it.archiveFile })
    inputs.files(jcefBinaryJarFiles, jcefSourceJarFiles)

    doLast {
        fun entries(jf: JarFile): Set<String> = jf.entries().asSequence()
            .filter { !it.isDirectory }
            .map { it.name }
            .toSet()

        // Collect what the upstream JCEF binary/sources JARs ship
        val upstreamBinaryEntries: Set<String>
        val upstreamSourceEntries: Set<String>
        JarFile(jcefBinaryJarFile.get()).use { jar ->
            upstreamBinaryEntries = entries(jar).filter { it.startsWith("org/cef/") }.toSet()
        }
        JarFile(jcefSourceJarFile.get()).use { jar ->
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
