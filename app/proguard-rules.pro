# ─────────────────────────────────────────────────────────────
# 宁大助手 Lite — R8 混淆规则
#
# 原则：只写"不写就会崩"的规则。合规的 AAR（Compose / Room / OkHttp / Coil /
# DataStore）都自带 consumer rules，手写过宽的规则只会让体积降不下来。
# ─────────────────────────────────────────────────────────────

# 行号必须保留：没有它，CrashHandler 抓到的堆栈全是 Unknown Source，等于自废取证能力。
-keepattributes SourceFile,LineNumberTable

# 注解与泛型签名：Room / Compose / Kotlin 反射都可能用到
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Room：实体与生成的 Impl 都在这两个包里。
# 理论上 Room 的 consumer rules 已经够用，但历史上出现过 @ColumnInfo 字段被优化后
# 与列名对不上的情况；这几十 KB 换来"绝对不会因为 Room 崩"，值得。
-keep class app.nbult.lite.data.db.** { *; }

# OkHttp 的可选 TLS 实现：我们没有引入 conscrypt / bouncycastle / openjsse，
# 但 R8 会因为这些引用缺失而报错，忽略即可（运行时走后平台 TLS）。
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Jsoup 的可选依赖同理
-dontwarn org.jsoup.**

# kotlinx.coroutines 的调试类（release 下用不到）
-dontwarn kotlinx.coroutines.**
