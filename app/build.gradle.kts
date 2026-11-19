import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 버전은 이름만 바꾸면 된다: 0.2.0 → versionCode 200 (자동 업데이트는 이름으로, 안드로이드는 번호로 비교)
val appVersion = (findProperty("jamVersion") as String?) ?: "0.2.0" // -PjamVersion=… 는 업데이트 시험용
val appVersionCode = appVersion.split('.').map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

// 릴리스 서명: 키 저장소 · 비밀번호는 저장소 밖에 두고 환경 변수로만 알려 준다 (README "릴리스 만들기")
//   JAM_KEYSTORE, JAM_KEYSTORE_PASSWORD, JAM_KEY_ALIAS (기본 jam-app), JAM_KEY_PASSWORD (없으면 저장소 비밀번호)
val releaseKeystore: File? = System.getenv("JAM_KEYSTORE")?.takeIf { it.isNotBlank() }?.let(::file)?.takeIf { it.exists() }

android {
    namespace = "io.github.jambrowser.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jambrowser.app"
        minSdk = 29 // 안드로이드 10 이상 (다운로드 보관 · 저장소 권한 없이 동작)
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("JAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("JAM_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "jam-app"
                keyPassword = System.getenv("JAM_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: System.getenv("JAM_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 키가 없으면 서명 안 된 APK(app-release-unsigned.apk)가 나온다 — 그대로는 설치 · 업데이트할 수 없음
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.google.android.material:material:1.12.0")
}

// 릴리스에 올릴 파일 만들기: build/release/jam-browser-<버전>.apk + .apk.sha256 (앱의 자동 업데이트가 이 해시로 확인)
tasks.register("releaseApk") {
    group = "distribution"
    description = "서명된 릴리스 APK 와 SHA-256 파일을 build/release 에 만든다 (JAM_KEYSTORE 등 필요)"
    dependsOn("assembleRelease")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        if (!apk.exists()) throw GradleException("서명된 app-release.apk 가 없어요. JAM_KEYSTORE / JAM_KEYSTORE_PASSWORD 환경 변수를 확인하세요")
        val outDir = layout.buildDirectory.dir("release").get().asFile.apply { mkdirs() }
        val name = "jam-browser-$appVersion.apk"
        val out = File(outDir, name)
        apk.copyTo(out, overwrite = true)
        val sha = MessageDigest.getInstance("SHA-256").digest(out.readBytes()).joinToString("") { "%02x".format(it) }
        File(outDir, "$name.sha256").writeText("$sha  $name\n")
        println("$out\nSHA-256 $sha")
    }
}
