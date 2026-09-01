plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.hilt)
}

android {
    namespace = "com.marotidev.citole"
    compileSdk = 37
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.marotidev.citole"
        minSdk = 26
        targetSdk = 37
        versionCode = 39
        versionName = "0.3.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    //standard material
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)

    //m3expressive
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.material)
    implementation(libs.androidx.compose.animation.core)

    //playback
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    //room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    //hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.android.compiler)

    //plugins
    implementation(libs.coil.compose)
    implementation(libs.material.kolor)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.reorderable)
    implementation(libs.androidx.datastore.preferences)

    //testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val rustRootDir = rootProject.file("rust")
val rustLocalDir = file("rust")

tasks.register<Exec>("cargoBuild") {
    notCompatibleWithConfigurationCache("Exec with external cargo process")
    onlyIf { rustLocalDir.exists() || rustRootDir.exists() }
    outputs.dir(jniLibsDir)
    workingDir(rustRootDir)
    commandLine(
        "sh", "-c",
        """
        set -e
        if command -v cargo-ndk >/dev/null 2>&1; then
          cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ${'$'}PWD/../app/src/main/jniLibs build --release
        else
          cargo build --release --target aarch64-linux-android --manifest-path ${'$'}PWD/Cargo.toml
          mkdir -p ${'$'}PWD/../app/src/main/jniLibs/arm64-v8a
          cp ${'$'}PWD/target/aarch64-linux-android/release/libcitole_engine.so ${'$'}PWD/../app/src/main/jniLibs/arm64-v8a/ 2>/dev/null || true
        fi
        """.trimIndent()
    )
}

tasks.named("preBuild") {
    dependsOn("cargoBuild")
}