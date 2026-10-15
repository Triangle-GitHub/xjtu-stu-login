# androidx.security:security-crypto → Tink：反射 + shaded protobuf，R8 必须显式保留
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
# 协议引擎：无反射，保留包名只为崩溃栈可读
-keep class org.xjtulogin.core.** { *; }

# --- Shizuku ---
# UserService 的类名由 Shizuku 服务端按 ComponentName 反射加载 ⇒ 类名与构造器都不能被改/删
-keep class org.xjtulogin.app.ShellUserService { <init>(...); *; }
-keep interface org.xjtulogin.app.IShellUserService { *; }
-keep class org.xjtulogin.app.IShellUserService$Stub { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
