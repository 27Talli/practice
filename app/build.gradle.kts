plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val retainedStore = System.getenv("POS_SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
val retainedSigningReady = retainedStore != null && listOf("POS_STORE_PASSWORD", "POS_KEY_ALIAS", "POS_KEY_PASSWORD").all { !System.getenv(it).isNullOrBlank() }

android {
    namespace = "com.offerpoint.pos"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.offerpoint.pos"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.2.0-rc1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    flavorDimensions += "data"
    productFlavors {
        create("live") {
            dimension = "data"
            buildConfigField("boolean", "DEMO_MODE", "false")
        }
        create("demo") {
            dimension = "data"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("boolean", "DEMO_MODE", "true")
        }
    }

    val retainedSigning = retainedStore?.takeIf { retainedSigningReady }?.let { path ->
        signingConfigs.create("retainedRelease") {
            storeFile = file(path)
            storePassword = requireNotNull(System.getenv("POS_STORE_PASSWORD"))
            keyAlias = requireNotNull(System.getenv("POS_KEY_ALIAS"))
            keyPassword = requireNotNull(System.getenv("POS_KEY_PASSWORD"))
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = retainedSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

gradle.taskGraph.whenReady {
    if (allTasks.any { it.name in listOf("assembleLiveRelease", "assembleDemoRelease", "bundleLiveRelease", "bundleDemoRelease") }) {
        require(retainedSigningReady) { "Release builds require a retained signing key and its credentials. See docs/SAFE_UPGRADES.md; no ephemeral production key is generated." }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
