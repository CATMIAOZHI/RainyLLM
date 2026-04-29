# ── RainyLLM ProGuard/R8 规则 ──

# LiteRT-LM JNI 类保护
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.litertlm.jni.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# NanoHTTPd
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Kotlin 协程
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# DataStore
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# JSON 解析 (org.json)
-keep class org.json.** { *; }

# 保持数据类不被混淆（反射用）
-keep class com.rainyllm.app.model.** { *; }
-keep class com.rainyllm.app.server.RequestParser { *; }