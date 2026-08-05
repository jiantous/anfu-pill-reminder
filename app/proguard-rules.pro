# ---- kotlinx.serialization ----
# 序列化器是编译期生成的伴生对象，混淆会导致读写数据文件与备份失败。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.jian.pillreminder.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.jian.pillreminder.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# sealed interface Schedule 的多态序列化依赖具体类型名，必须整体保留
-keep class com.jian.pillreminder.data.Schedule { *; }
-keep class com.jian.pillreminder.data.Schedule$* { *; }
-keep,includedescriptorclasses class com.jian.pillreminder.data.**$$serializer { *; }

# ---- ML Kit 文字识别 ----
# 模型加载走反射，压缩掉这些类会导致 OCR 直接失败
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# ---- CameraX ----
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ---- 保留崩溃日志里的行号，方便排查线上问题 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Compose / AndroidX 通用 ----
-dontwarn org.jetbrains.annotations.**
