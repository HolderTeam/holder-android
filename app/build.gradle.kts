import com.android.build.api.dsl.ManagedVirtualDevice
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val holderCoreDir = rootProject.layout.projectDirectory.dir("submodules/holder-core").asFile
val holderNdkVersion = "28.2.13676358"
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}
val holderAndroidAbis = (findProperty("holder.android.abis") as String?
    ?: localProperties.getProperty("holder.android.abis"))
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a", "x86_64")
val vcpkgRoot = System.getenv("VCPKG_ROOT")
    ?: localProperties.getProperty("vcpkg.dir")
val vcpkgInstalledDir = rootProject.layout.buildDirectory.dir("vcpkg_installed").get().asFile
val vcpkgAndroidWrapper = project.layout.projectDirectory.file("src/main/cpp/vcpkg-android-wrapper.cmake").asFile
val androidSdkDir = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: localProperties.getProperty("sdk.dir")
val holderNdkDir = androidSdkDir?.let { File(it, "ndk/$holderNdkVersion") }

android {
    namespace = "team.holder.android"
    compileSdk {
        version = release(37)
    }
    ndkVersion = holderNdkVersion

    defaultConfig {
        applicationId = "team.holder.android"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += holderAndroidAbis
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                if (!vcpkgRoot.isNullOrBlank()) {
                    val ndkDir = holderNdkDir
                        ?: throw GradleException("ANDROID_HOME, ANDROID_SDK_ROOT, or local.properties sdk.dir is required when VCPKG_ROOT is set")
                    arguments += listOf(
                        "-DCMAKE_TOOLCHAIN_FILE=${vcpkgAndroidWrapper.absolutePath}",
                        "-DVCPKG_ROOT=$vcpkgRoot",
                        "-DHOLDER_ANDROID_NDK_HOME=${ndkDir.absolutePath}",
                        "-DANDROID_NDK=${ndkDir.absolutePath}",
                        "-DVCPKG_MANIFEST_DIR=${holderCoreDir.absolutePath}",
                        "-DVCPKG_INSTALLED_DIR=${vcpkgInstalledDir.absolutePath}",
                        "-DCMAKE_TRY_COMPILE_PLATFORM_VARIABLES=VCPKG_ROOT;HOLDER_ANDROID_NDK_HOME;VCPKG_MANIFEST_DIR;VCPKG_INSTALLED_DIR",
                    )
                }
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
        debug {
            // Distinct applicationId so a locally-built debug APK installs alongside a real
            // release install rather than overwriting it -- Android sandboxes app data per
            // applicationId, so this gets the debug build a fully separate data directory,
            // database, and SharedPreferences automatically. FileProvider authorities in this
            // app are already built from ${applicationId}/context.packageName rather than a
            // hardcoded literal, so this doesn't need any FileProvider changes.
            //
            // The Google Drive "Android" OAuth client is matched by Google against package
            // name + signing certificate SHA-1, not an embedded client ID, so a debug build
            // under this new applicationId needs its own OAuth client registered in Google
            // Cloud Console (package name "team.holder.android.debug", SHA-1 from `./gradlew
            // signingReport`'s debug variant) before Drive sign-in will work in debug builds.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    sourceSets {
        getByName("main") {
            assets.directories.add(holderCoreDir.resolve("schema").absolutePath)
            assets.directories.add(holderCoreDir.resolve("resources").absolutePath)
        }
    }
    testOptions {
        managedDevices {
            val pixel2Api28 = localDevices.create("pixel2Api28") {
                device = "Pixel 2"
                apiLevel = 28
                systemImageSource = "aosp"
                require64Bit = true
            }
            val pixel2Api36 = localDevices.create("pixel2Api36") {
                device = "Pixel 2"
                apiLevel = 36
                systemImageSource = "aosp"
                require64Bit = true
                pageAlignment = ManagedVirtualDevice.PageAlignment.FORCE_4KB_PAGES
            }
            groups.create("ciPhones") {
                targetDevices.add(pixel2Api28)
                targetDevices.add(pixel2Api36)
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.task.list.items)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.gfm.alerts)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.services.auth)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.play.services)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
