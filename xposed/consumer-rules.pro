# Keep the Xposed entry point and everything it reflects over.
# The framework instantiates the entry class by name from META-INF/xposed/java_init.list.
-keep class io.guise.xposed.** { *; }
-keepnames class io.guise.xposed.**

# libxposed API is compileOnly for :hook but a real dependency of :app.
-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**
