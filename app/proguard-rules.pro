# ProGuard / R8 rules customised for l2dchat release builds.

# --- Live2D Cubism --------------------------------------------------------
# The Live2D runtime relies heavily on JNI symbols with fixed class and
# method names. If these are obfuscated, `System.loadLibrary` succeeds but the
# subsequent native method lookups crash the app at startup (seen only in the
# release build with minify enabled). Keep the entire package unobfuscated and
# preserve native method signatures.
-keep class com.live2d.sdk.cubism.** { *; }
-keep class jp.live2d.** { *; }
-keepclasseswithmembers class * {
	native <methods>;
}
# The framework uses reflection to access optional logging hooks; suppress
# warnings about missing optional classes shipped in proprietary archives.
-dontwarn com.live2d.sdk.cubism.**

# The embedded sample runtime (`com.live2d.demo.full`) stores model directories
# and other state in fields we access via reflection. Obfuscation would rename
# those members and break model switching, so keep the package intact.
-keep class com.live2d.demo.** { *; }
-keep class com.live2d.demo.full.** { *; }

# --- General project notes -----------------------------------------------
# (Add any additional keep/donotwarn rules below as new libraries are linked.)