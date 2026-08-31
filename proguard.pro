# YknBoss release obfuscation rules.
# Shrinking and optimization are intentionally disabled: Bukkit listeners,
# BetterModel integrations and plugin lifecycle methods depend on runtime APIs.
-dontshrink
-dontoptimize

-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod,Record,Exceptions,MethodParameters,LineNumberTable
-renamesourcefileattribute YknBoss

# Paper loads this exact class name from plugin.yml.
-keep public class cn.yknboss.YknBossPlugin extends org.bukkit.plugin.java.JavaPlugin {
    public <init>();
    public *;
    protected *;
}

# Phase and skill names are displayed and also used as configuration keys.
-keepclassmembers enum cn.yknboss.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static final ** *;
}

# Keep resource files such as plugin.yml and config.yml unchanged.
-keepdirectories
