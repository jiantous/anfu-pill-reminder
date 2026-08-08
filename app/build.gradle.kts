import java.io.File
import java.util.Properties

plugins {
    // AGP 9 起内置 Kotlin 支持，无需单独应用 kotlin-android 插件
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * 从项目外的 keystore.properties 读签名配置。
 * 刻意放在项目目录之外：源码可以公开，密钥和密码不会跟着走。
 * 找不到时（比如别人从 GitHub clone 下来）只跳过 release 签名，debug 构建照常。
 *
 * 路径按顺序查找，不写死在源码里——写死会暴露开发机的用户名，而且别人也用不了：
 *   1. 环境变量 PILL_KEYSTORE_PROPS，指向 keystore.properties 的完整路径
 *   2. 仓库同级的 AndroidKeys/keystore.properties
 *   3. 用户主目录下的 AndroidKeys/keystore.properties
 */
val keystorePropsFile: File = listOfNotNull(
    System.getenv("PILL_KEYSTORE_PROPS")?.let { File(it) },
    rootProject.file("../AndroidKeys/keystore.properties"),
    File(System.getProperty("user.home"), "AndroidKeys/keystore.properties")
).firstOrNull { it.isFile } ?: File("keystore.properties.absent")

val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}
// 密码还是占位符（<请填写>）时视为未配置，避免用错误的密码去签名
val releaseStorePassword: String? = keystoreProps.getProperty("storePassword")
val hasReleaseSigning: Boolean =
    releaseStorePassword != null &&
        releaseStorePassword.isNotBlank() &&
        !releaseStorePassword.startsWith("<")

android {
    namespace = "com.jian.pillreminder"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jian.pillreminder"
        minSdk = 24
        targetSdk = 37
        // versionCode 每次对外发布都必须 +1，否则用户装不上——Android 会拒绝
        // 同版本号或降级覆盖（INSTALL_FAILED_VERSION_DOWNGRADE）。
        // 它和 versionName 无关，只是个递增整数，发过就不能重复用。
        //
        // 1 = 1.0    首个发布版
        // 2 = 1.1    暂停用药、临时改时间、CSV 导出、设置页、关于页；
        //            修复稍后提醒被静默清掉、示例药会排真闹钟；日期时间改手填
        // 3 = 1.1.1  修复稍后提醒排的延后闹钟在 App 内打卡后没被撤销的问题
        // 4 = 1.1.2  关掉系统自动备份（会用陈旧的云端备份覆盖本地数据）；
        //            读盘失败不再静默清空、写盘改为原子替换
        // 5 = 1.1.3  文字精简：设置页、备份页、关于页、今日页、药箱页、统计页；
        //            删所有灰色分割线；排序提醒体检/关于页等几处文案综合调整
        versionCode = 5
        versionName = "1.1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // storeFile 支持绝对路径，也支持相对 keystore.properties 所在目录，
                // 这样整个 AndroidKeys 目录可以整体搬走/备份到别的机器
                val storePath = keystoreProps.getProperty("storeFile")
                storeFile = File(storePath).takeIf { it.isAbsolute }
                    ?: File(keystorePropsFile.parentFile, storePath)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 同时启用 v1/v2/v3：v1 兼容老系统，v2/v3 是现代校验方式
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 代码压缩+混淆：包更小、也顺带增加逆向难度。
            // 需要保留 kotlinx.serialization 的序列化器，规则已写在 proguard-rules.pro。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        compose = true
    }

    /**
     * 按 CPU 架构拆分 APK。
     * OCR 引擎（libmlkit_google_ocr_pipeline.so）每个架构约 10MB，四份塞在一起
     * 就占了 39MB，而任何一台手机只会用其中一份。拆开后 arm64 版约 20MB。
     * universalApk 仍生成一个全架构包，作为"不知道对方手机是什么"时的兜底。
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    // 拍照识别药品说明书：CameraX 取景拍照 + ML Kit 中文文字识别（完全离线，照片不上传）
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.text.recognition.chinese)

    // 备份导出：持久化用户授权的文件夹（云盘同步目录）
    implementation(libs.androidx.documentfile)
    debugImplementation(libs.androidx.ui.tooling)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
