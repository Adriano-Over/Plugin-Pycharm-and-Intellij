import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.testing.Test
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

val runIdeIntellijTypeCode = providers.gradleProperty("runIdeIntellijType").orElse("IC")
val runIdeIntellijVersion = providers.gradleProperty("runIdeIntellijVersion").orElse(providers.gradleProperty("platformVersion"))
val runIdeIntellijType = providers.provider {
    IntelliJPlatformType.fromCode(runIdeIntellijTypeCode.get(), runIdeIntellijVersion.get())
}

val runIdePyCharmTypeCode = providers.gradleProperty("runIdePyCharmType").orElse("PC")
val runIdePyCharmVersion = providers.gradleProperty("runIdePyCharmVersion").orElse(providers.gradleProperty("platformVersion"))
val runIdePyCharmType = providers.provider {
    IntelliJPlatformType.fromCode(runIdePyCharmTypeCode.get(), runIdePyCharmVersion.get())
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testRuntimeOnly("junit:junit:4.13.2")

    intellijPlatform {
        create(
            providers.gradleProperty("platformType").get(),
            providers.gradleProperty("platformVersion").get()
        ) {
            // PyCharm Community 2025.2 is resolved from the Maven platform
            // artifact because its standalone installer is no longer listed.
            useInstaller = false
        }
    }
}

java {
    toolchain {
        languageVersion.set(
            JavaLanguageVersion.of(
                providers.gradleProperty("javaVersion").get().toInt()
            )
        )
    }
}

intellijPlatform {
    // This plugin has no Settings/Preferences configurables.
    buildSearchableOptions = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName").get()

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild").get()
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.1")
            create(IntelliJPlatformType.PyCharmProfessional, "2025.2.1")
        }
    }
}

kotlin {
    compilerOptions {
        // Do not generate compatibility bridges that make inherited IntelliJ
        // interface defaults appear as internal API calls in Plugin Verifier.
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

val runIdeIntellij by intellijPlatformTesting.runIde.registering {
    type.set(runIdeIntellijType)
    version.set(runIdeIntellijVersion)
    sandboxDirectory.set(layout.buildDirectory.dir("sandboxes/intellij"))

    task {
        group = "intellij platform"
        description = "Runs Drawing in an IntelliJ IDEA sandbox."
    }
}

val runIdePyCharm by intellijPlatformTesting.runIde.registering {
    type.set(runIdePyCharmType)
    version.set(runIdePyCharmVersion)
    sandboxDirectory.set(layout.buildDirectory.dir("sandboxes/pycharm"))

    task {
        group = "intellij platform"
        description = "Runs Drawing in a PyCharm sandbox."
    }
}

tasks {
    register("prepareBothIdeSandboxes") {
        group = "intellij platform"
        description = "Prepares separate IntelliJ IDEA and PyCharm sandboxes for side-by-side testing."
        dependsOn("prepareSandbox_runIdeIntellij", "prepareSandbox_runIdePyCharm")
    }

    register<Exec>("runBothIdes") {
        group = "intellij platform"
        description = "Starts IntelliJ IDEA and PyCharm sandboxes in separate PowerShell windows."
        dependsOn("prepareBothIdeSandboxes")
        commandLine(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            layout.projectDirectory.file("scripts/run-both-ides.ps1").asFile.absolutePath,
            "-SkipPrepare"
        )
    }

    withType<JavaCompile>().configureEach {
        sourceCompatibility = providers.gradleProperty("javaVersion").get()
        targetCompatibility = providers.gradleProperty("javaVersion").get()
    }

    withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(
                JvmTarget.fromTarget(providers.gradleProperty("javaVersion").get())
            )
        }
    }

    withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
