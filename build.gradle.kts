plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.agenttoolbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.agenttoolbox"
        minSdk = 24
        targetSdk = 32
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared"
                )
            }
        }
    }

    ndkVersion = "27.3.13750724"

    externalNativeBuild {
        cmake {
            path = file("src/main/c/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs = listOf("src")
            jniLibs.srcDirs = listOf("app/src/main/jniLibs")
            assets.srcDirs = listOf("assets")
            res.srcDirs = listOf("res")
            manifest.srcFile("AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
