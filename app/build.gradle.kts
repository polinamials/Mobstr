plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.mobstr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.mobstr"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ADD THIS BLOCK TO FORCE CMAKE TO EXPOSE THE API 29 HEADERS
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_PLATFORM=android-29")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("profiling") {
            isDebuggable = false
            isProfileable = true
            ndk {
                debugSymbolLevel = "FULL"
            }
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.camera:camera-viewfinder:1.4.0-alpha01")
    implementation("com.google.guava:guava:31.1-android")
}