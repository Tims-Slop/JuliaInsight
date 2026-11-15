import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.juliainsight"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("platformLocalPath").orNull?.takeIf { it.isNotBlank() }
        if (localPath != null) {
            local(localPath)
        } else {
            pycharm(providers.gradleProperty("platformVersion"))
        }
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        // don't generate bridge overrides for the platform's interface default methods
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.juliainsight"
        name = "Julia Insight"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("sinceBuild")
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    pluginVerification {
        ides {
            val localPath = providers.gradleProperty("platformLocalPath").orNull?.takeIf { it.isNotBlank() }
            if (localPath != null) local(file(localPath)) else recommended()
        }
    }
}

tasks {
    runIde {
        // optional: -PrunIdeProject=<folder> opens a project; JULIA_INSIGHT_TEST_DEPOT sets the Julia depot
        providers.gradleProperty("runIdeProject").orNull?.let { project -> args(listOfNotNull(project, providers.gradleProperty("runIdeFile").orNull)) }
        System.getenv("JULIA_INSIGHT_TEST_DEPOT")?.let { environment("JULIA_DEPOT_PATH", it) }
        jvmArgs("-Didea.trust.all.projects=true", "-Djb.consents.confirmation.enabled=false", "-Djb.privacy.policy.text=<!--999.999-->")
    }
    test {
        useJUnit()
        systemProperty("julia.insight.test.julia", System.getenv("JULIA_INSIGHT_TEST_JULIA") ?: "")
        // a depot that already contains LanguageServer.jl enables the language server test
        System.getenv("JULIA_INSIGHT_TEST_DEPOT")?.let { environment("JULIA_DEPOT_PATH", it) }
        maxHeapSize = "2g"
    }
}
