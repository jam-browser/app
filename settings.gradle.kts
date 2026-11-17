pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "jam-browser-app"

// core : 안드로이드와 무관한 Jam 프로토콜 (암호화 · 초대 코드 · 서버 연결 · 동기화 규칙) — PC(JVM)에서 테스트한다
// app  : 안드로이드 앱 (WebView 브라우저 + 화면)
include(":core", ":app")
