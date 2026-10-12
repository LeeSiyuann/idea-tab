plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.ideatab"
version = "0.1.1"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde) else intellijIdea("2025.3.4")
        pluginVerifier()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
}
tasks.test { useJUnitPlatform() }
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask>().configureEach {
    systemProperty("plugin.verifier.home.dir", layout.projectDirectory.dir(".tools/plugin-verifier").asFile.absolutePath)
    offline = true // Only the bundled platform is required; no Marketplace dependencies.
}

intellijPlatform {
    pluginConfiguration {
        name = "Tab Harbor"
        ideaVersion {
            sinceBuild = "253"
            untilBuild = "253.*"
        }
    }
    pluginVerification {
        ides { current() }
    }
}
