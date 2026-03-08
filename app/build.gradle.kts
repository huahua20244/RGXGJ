plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.wuji.jizhang"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.wuji.jizhang"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // 处理网络请求，维持登录 Cookie (Session)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
// 解析网页 HTML 标签（提取课表内容就靠它）
    implementation("org.jsoup:jsoup:1.17.2")
// Kotlin 协程（用于在后台线程抓取网络，防止卡死主界面UI）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}