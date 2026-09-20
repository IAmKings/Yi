# ProGuard rules for Yi
# Keep the llama.kt JNI surface (native* declared methods are bound by name in C++)
-keep class com.tensai.llamakt.** { *; }
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
