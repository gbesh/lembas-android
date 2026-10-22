# Custom views are referenced from XML layouts; AGP keeps them automatically.

# The adb client finds Conscrypt and BouncyCastle by name.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn io.github.muntashirakon.**
