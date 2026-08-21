import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun localProperty(name: String): String? =
    (localProperties.getProperty(name) ?: System.getenv(name))?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.hypercopy"
compileSdk = 37

    defaultConfig {
        applicationId = "io.github.hypercopy"
        minSdk = 33
        targetSdk = 36
        versionCode = 346
        versionName = "1.145.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val releaseStoreFile = localProperty("RELEASE_STORE_FILE")
            val releaseStorePassword = localProperty("RELEASE_STORE_PASSWORD")
            val releaseKeyAlias = localProperty("RELEASE_KEY_ALIAS")
            val releaseKeyPassword = localProperty("RELEASE_KEY_PASSWORD") ?: releaseStorePassword

            if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // v7: SKIP_OBFUSCATE=1 跳过 R8（快速交付验证，产物未混淆；正式发布保持默认）
            isMinifyEnabled = System.getenv("SKIP_OBFUSCATE") != "1"
            isShrinkResources = System.getenv("SKIP_OBFUSCATE") != "1"
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("releaseFast") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
    // v3.2 构建加速：禁用 release 构建的 lintVital 强制检查（改文件迭代时每次全量 lint 是最大隐藏成本）
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")

    implementation("io.github.libxposed:service:102.0.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigationevent:navigationevent-android:1.1.2")
    implementation("androidx.navigationevent:navigationevent-compose-android:1.1.2")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
