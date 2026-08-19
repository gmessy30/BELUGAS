import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

// Reads Supabase credentials from the gitignored local.properties (or env vars, for CI)
// and generates a Kotlin source file so they never live in tracked source.
val generatedSecretsDir = layout.buildDirectory.dir("generated/secrets/commonMain/kotlin")

val generateSupabaseSecrets by tasks.registering {
    val localProperties = Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { load(it) }
        }
    }
    val supabaseUrl = (localProperties.getProperty("supabase.url")
        ?: System.getenv("SUPABASE_URL")
        ?: "").also {
        if (it.isEmpty()) logger.warn("supabase.url is not set in local.properties or SUPABASE_URL env var")
    }
    val supabaseAnonKey = (localProperties.getProperty("supabase.anonKey")
        ?: System.getenv("SUPABASE_ANON_KEY")
        ?: "").also {
        if (it.isEmpty()) logger.warn("supabase.anonKey is not set in local.properties or SUPABASE_ANON_KEY env var")
    }

    val outputDir = generatedSecretsDir.get().asFile
    outputs.dir(outputDir)

    doLast {
        val outputFile = outputDir.resolve("com/cookinlet/belugas/SupabaseSecrets.kt")
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            |package com.cookinlet.belugas
            |
            |internal const val SUPABASE_URL: String = "$supabaseUrl"
            |internal const val SUPABASE_ANON_KEY: String = "$supabaseAnonKey"
            |""".trimMargin()
        )
    }
}

sqldelight {
    databases {
        create("BelugaDatabase") {
            packageName.set("com.cookinlet.belugas.db")
        }
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    android {
       namespace = "com.cookinlet.belugas.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        commonMain {
            kotlin.srcDir(generateSupabaseSecrets)
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)
                implementation(libs.maplibre.compose)
                implementation(libs.maplibre.spatialk.geojson)
                implementation(libs.androidx.sqlite)
                implementation(libs.androidx.sqlite.bundled)

                // Shared Supabase & Ktor Core
                implementation(project.dependencies.platform(libs.supabase.bom))
                implementation(libs.supabase.postgrest)
                implementation(libs.supabase.storage)
                implementation(libs.supabase.realtime)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.compose.uiTooling)
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.android)
                implementation(libs.google.play.services.location)
                implementation(libs.kotlinx.coroutines.play.services)
                implementation(libs.sqldelight.driver.android)
                implementation(libs.androidx.camera.core)
                implementation(libs.androidx.camera.camera2)
                implementation(libs.androidx.camera.lifecycle)
                implementation(libs.androidx.camera.view)
            }
        }
        iosMain {
            dependencies {
                // iOS Native HTTP Engine
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqldelight.driver.native)
            }
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}