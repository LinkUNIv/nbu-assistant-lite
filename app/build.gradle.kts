plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 签名密钥。
//
// preview 与 release 原本直接复用 `debug` 签名，而 debug keystore 是「首次构建时按机器生成」的：
// CI 每次都在全新 runner 上跑，于是每个版本的证书都不一样。实测 0.33.0 与 0.33.1 的
// 证书 SHA-256 完全不同，装好前者再装后者会被系统拒绝（INSTALL_FAILED_UPDATE_INCOMPATIBLE，
// 界面提示「与已安装应用签名不同」）。应用内更新器因此从来没成功过 —— 它下载的每个包
// 都盖不上已安装的那个。
//
// 现在改用固定密钥：本地从 local.properties 读，CI 从环境变量读。
// 两处都缺时才退回 debug，同时打出警告 —— 静默退回会让这个问题再长回来。
//
// 这里不用 java.util.Properties：在 kts 里 `java` 会被 JavaPluginExtension 的访问器遮蔽，
// 写成 java.util.Properties() 会报 Unresolved reference: util。
fun localProperty(key: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return null
    return file.readLines(Charsets.UTF_8)
        .firstOrNull { it.trimStart().startsWith("$key=") }
        ?.substringAfter("=")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

fun signingValue(key: String, env: String): String? =
    localProperty(key) ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val fixedStoreFile = signingValue("nbult.storeFile", "NBULT_STORE_FILE")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }
val hasFixedKey = fixedStoreFile != null

if (!hasFixedKey) {
    logger.warn(
        "警告：未找到固定签名密钥（local.properties 的 nbult.storeFile / 环境变量 NBULT_STORE_FILE），" +
            "preview 与 release 将退回 debug 签名，产出的包无法覆盖安装已发布的版本。"
    )
}

android {
    namespace = "app.nbult.lite"
    compileSdk = 36
    // 本机只装了 34.0.0 与 36.0.0；AGP 8.9 默认要 35.0.0，显式钉死避免它去自动下载
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.nbult.lite"
        minSdk = 24
        targetSdk = 36
        // 版本号规则：主.次.修订；小改动只递增修订号
        // versionCode = 主*10000 + 次*100 + 修订，每版必须递增，保证可覆盖安装
        versionCode = 3501
        versionName = "0.35.1"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasFixedKey) {
            create("nbult") {
                storeFile = fixedStoreFile
                storePassword = signingValue("nbult.storePassword", "NBULT_STORE_PASSWORD")
                keyAlias = signingValue("nbult.keyAlias", "NBULT_KEY_ALIAS") ?: "nbult"
                keyPassword = signingValue("nbult.keyPassword", "NBULT_KEY_PASSWORD")
                    ?: signingValue("nbult.storePassword", "NBULT_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        // 三个包名互不覆盖：正式版 app.nbult.lite / 预览版 .preview / 调试版 .debug
        // versionNameSuffix 区分测试版，正式版不带后缀（1.0.0 才出正式包）
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-Debug"
            isMinifyEnabled = false
            resValue("string", "app_name", "宁大助手 Debug")
            buildConfigField("String", "BUILD_KIND", "\"debug\"")
        }
        release {
            // R8 + 资源压缩。正式版 1.0.0 出包前必须验证过一整套功能。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasFixedKey) signingConfigs.getByName("nbult") else null
            resValue("string", "app_name", "宁大助手 Lite")
            buildConfigField("String", "BUILD_KIND", "\"release\"")
        }
        // 预览版：取代原来的 bench —— debuggable=false 走 AOT 编译，
        // 性能与正式版一致，可用来做"卡顿来自代码还是 debug 构建"的对照。
        create("preview") {
            isDebuggable = false
            // 与 release 一致的 R8 配置：这样 R8 打坏东西能在出正式包之前就暴露。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 固定密钥优先。退回 debug 时每个构建环境的证书都不同，包盖不上任何既有安装
            signingConfig = if (hasFixedKey) signingConfigs.getByName("nbult") else signingConfigs.getByName("debug")
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-Preview"
            resValue("string", "app_name", "宁大助手 Preview")
            buildConfigField("String", "BUILD_KIND", "\"preview\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    // 变更记录随包分发。仓库根目录的 CHANGELOG.md 是唯一来源，构建时复制进 assets，
    // 更新日志页直接读它 —— 不在 app 里维护第二份，避免两处不同步。
    // 生成物落在 build/ 下，不进版本库，也不会每次构建都把工作区弄脏。
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/changelogAssets"))
}

val changelogAssetsDir = layout.buildDirectory.dir("generated/changelogAssets")

val copyChangelog = tasks.register<Copy>("copyChangelog") {
    from(rootProject.file("CHANGELOG.md"))
    into(changelogAssetsDir.map { it.dir("changelog") })
}

// assets 目录是「源目录」而非任务产物，AGP 不会自动建立依赖关系。
// 读取它的任务有两类：合并资源（merge*Assets）与 lint 分析
// （generate*LintVitalReportModel、lintVitalAnalyze*、lintAnalyze*）。
// 少挂一个，Gradle 会直接以「uses this output without declaring an explicit
// or implicit dependency」判失败，而不是容忍执行顺序不确定。
// 注意任务名大小写不一致：mergePreviewAssets 是小写，lintVitalAnalyzePreview
// 也是小写开头，generatePreviewLintVitalReportModel 中间才是大写 Lint。
tasks.matching {
    it.name.contains("lint", ignoreCase = true) ||
        (it.name.startsWith("merge", ignoreCase = true) && it.name.endsWith("Assets"))
}.configureEach { dependsOn(copyChangelog) }

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // M2 的 pullRefresh：material3 1.2 没有 PullToRefreshBox，而升 BOM 风险更大
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 桌面小组件：Glance 用 Compose 语法写 RemoteViews。
    // 选它而不是手写 XML，是因为三个尺寸要共用同一套行式布局，
    // XML 方案得为每种尺寸各写一份，改一处要改三处
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("io.coil-kt:coil-compose:2.6.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
