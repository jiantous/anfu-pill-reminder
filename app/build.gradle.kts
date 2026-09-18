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
 *   4. 用户主目录下 Projects/AndroidKeys/keystore.properties
 */
val keystorePropsFile: File = listOfNotNull(
    System.getenv("PILL_KEYSTORE_PROPS")?.let { File(it) },
    rootProject.file("../AndroidKeys/keystore.properties"),
    File(System.getProperty("user.home"), "AndroidKeys/keystore.properties"),
    File(System.getProperty("user.home"), "Projects/AndroidKeys/keystore.properties")
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
        // 6 = 1.1.4  备份简化成一个按钮：点开直接选位置、立即写入，
        //            不再需要先配置一个"备份文件夹"
        // 7 = 1.1.5  药箱排序、App 启动闹钟自检、暂停恢复当天提醒；
        //            界面统一（卡片 padding、分组标签圆点）；
        //            删除今日页备份提示横幅
        // 8 = 1.1.6  Material 3 Expressive 风格；设置页面重构；
        //            顶栏三点菜单合并为齿轮图标直达设置页；
        //            备份与关于入口合入设置页；
        //            UI 统一（卡片 padding、间距、文字样式对齐）
        // 9 = 1.1.7  设置页新增「界面」分组：整体界面缩放 80%/90%/100%/110%
        // 10 = 1.1.8 底部 Tab 切换时清空返回栈：根 tab（今天/药箱/统计）返回手势即退出，
        //             深层页（设置/关于/编辑）返回逐级回退，不再跨 tab 往复盘 N 页
        // 11 = 1.1.9 库存测算：按每日剂量算出「够吃到几月几日」（每天多次/隔天/
        //             每周固定几天/吃停周期均支持，暂停期不消耗）；
        //             界面缩放改为 80%~130% 滑杆，5% 一档；
        //             厂商自启动提示改为通用文案（不再写死各品牌菜单路径）
        // 12 = 1.1.10 UI 规范化改造（仍是 Material 风格，无功能变更）：动效系统
        //             （tab 转场/列表弹簧动画/顶栏滚动收起/FAB 收起/完成庆祝）、
        //             药品 8 色跟随壁纸取色、错过卡片红边条、按钮回归 M3 默认 40dp、
        //             间距收敛 4dp 网格、日历图例深浅区分、空状态文案统一；
        //             文案：底栏/顶栏「药箱」「统计」、引导页提示语
        // 13 = 1.1.11 修复 1.1.10 的窗口 insets 双重避让：二三级页标题上方与
        //             页面底部、主页面底栏下方各多一条空白带；
        //             全部页面切换动画统一为水平滑移（tab 按左右顺序定向，
        //             子页面进右返左），修复子页面返回无动画
        // 14 = 1.1.12 M3E 动效深化：全勤庆祝弹性脉冲、柱状图逐根延迟生长、
        //             统计页无数据演示柱；形状对齐官方五档（4/8/12/16/28）、
        //             主按钮官方胶囊形；界面缩放滑杆修卡顿（拖动跟手、
        //             松手才落盘）；删全勤弧光高光；
        //             全项目代码清理（47 个未使用 import、死代码 PermissionBanner）
        // 15 = 1.1.13 修复临时改时间：原时刻照响且新时刻提醒被误删（改时间后
        //             撤原时刻常规闹钟槽、scheduleFor 按"下一次发生日"跳过被挪
        //             走的时刻，任意日期生效）；撤销改时间后原时刻闹钟恢复；
        //             修复稍后提醒被编辑/归档/暂停静默撤掉（scheduleFor 撤完
        //             延后槽立即原位重建）；暂停期不再复活延后闹钟；
        //             修复 Android 7.x(API 24/25) 落盘静默失败丢数据
        //             （Files.move 需 API 26，改同目录 renameTo 原子替换）；
        //             写盘加序号防异步乱序盖盘；OCR 识别器用后释放；
        //             统计页删演示柱；清理死代码与多余 import
        versionCode = 15
        versionName = "1.1.13"
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
                // v2/v3 双开：v2 覆盖 Android 7+，v3 覆盖 Android 9+（含密钥轮换），
                // 老设备用 v2、新设备用 v3，全版本可装。
                // v1 只对 Android 6- 有意义，minSdk 24 用不到，AGP 已默认不产。
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
     *
     * 发布原则：GitHub Release 只上传 arm64-v8a 包（现代安卓手机的主流架构），
     * 其余架构包仅供本地调试/模拟器使用，不上传。universalApk 作为兜底同样只留本地。
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

    debugImplementation(libs.androidx.ui.tooling)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
