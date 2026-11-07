
pluginManagement {
    repositories {
        maven {
            // RetroFuturaGradle
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.20")
}

// 把本地 JCEF-RINKU 仓库作为子项目引入，方便同时修改 JCEF 和 Rinku 代码。
// 本地没有该目录时（CI/远程构建）回退到从 GitHub Release 下载 jcef-rinku.jar。
val jcefRinkuDir = file("../JCEF-RINKU")
if (jcefRinkuDir.resolve("build.gradle").exists()) {
    include(":jcef-rinku")
    project(":jcef-rinku").projectDir = jcefRinkuDir
}
