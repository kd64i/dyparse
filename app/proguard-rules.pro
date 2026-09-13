# ============================================================
# dyparse R8 规则（release 构建）
# 说明：
#  - 不再使用 -dontoptimize：R8 将完整执行 shrink + optimize + obfuscate
#  - AGP 默认规则(getDefaultProguardFile("proguard-android-optimize.txt"))
#    已包含: @JavascriptInterface 方法保留、Signature/注解属性、native 方法、
#    枚举、Parcelable、View setter 等，这里只补充项目特有的保留项
#  - okhttp/okio、coil、media3(ExoPlayer) 均自带 consumer rules 或官方声明
#    兼容 R8，不再整包保留，让 R8 裁剪未使用的代码
# ============================================================

# ---- Gson 持久化数据模型 ----
# 历史记录以 Gson JSON 形式存储在 Room / 文件里（字段名即 JSON 键），
# 混淆字段名会导致升级后旧数据读不出来，因此 data 模型类+成员必须保留。
-keep class com.jn.dyparse.data.** { *; }

# Gson 文件缓存的内部模型字段也保留，避免跨版本缓存失效
-keepclassmembers class com.jn.dyparse.ParserViewModel$CachedPlaybackUrl { *; }
-keepclassmembers class com.jn.dyparse.AuthorBatchManager$CachedAuthorPostRequest { *; }

# Gson 自身的反射需求
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.annotations.** { *; }
-keep @com.google.gson.annotations.SerializedName class * {
    <fields>;
}

# ---- JNI（libnet_utils.so / native-lib.cpp）----
# RegisterNatives 按方法名字符串绑定，类名和方法名都不能混淆
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.jn.dyparse.NativeLib { *; }

# ---- Room ----
# Room 运行时按类名字符串反射实例化生成的 *_Impl
-keep class com.jn.dyparse.data.local.HistoryDatabase_Impl { *; }

# ---- WebView Javascript 桥（AGP 默认规则已覆盖，显式声明双保险）----
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- Media3 / ExoPlayer ----
# media3 各模块自带 consumer rules 覆盖 DefaultRenderersFactory 等反射构造，
# 整包保留会白白增大体积，改为仅按官方文档保留两个监听器接口的名字。
-keepnames class androidx.media3.exoplayer.video.VideoRendererEventListener
-keepnames class androidx.media3.exoplayer.analytics.AnalyticsListener

# ---- OkHttp / Okio ----
# okhttp 官方自带 consumer rules；这里仅保留警告抑制。
-dontwarn okhttp3.**
-dontwarn okio.**

# ---- 其他 ----
# 保留泛型签名（Gson 需要）、注解属性
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*