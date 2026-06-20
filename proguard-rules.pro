# ProGuard 规则
# 禁用代码优化和混淆，避免 R8 编译问题
-dontshrink
-dontoptimize
-dontobfuscate

# 保留所有类和成员
-keep class ** { *; }

# 保留 JSON 相关类
-keep class org.json.** { *; }
