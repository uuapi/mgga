# libxposed 新 API 入口保护
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keepattributes RuntimeVisibleAnnotations
-keep,allowobfuscation,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
    public void onPackageLoaded(...);
    public void onPackageReady(...);
    public void onModuleLoaded(...);
    public void onSystemServerLoaded(...);
}

# DexKit 通过反射调用，避免裁剪
-keep class org.luckypray.dexkit.** { *; }
