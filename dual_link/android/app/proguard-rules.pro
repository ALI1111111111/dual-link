# Started directly with app_process (not referenced from app code), so the shrinker must keep them,
# plus the protocol classes the voice helper uses.
-keep class com.duallink.app.probe.** { *; }
-keep class com.duallink.app.voice.** { *; }
-keep class com.duallink.app.SecureLink { *; }
-keep class com.duallink.app.SecureLink$Companion { *; }
-keep class com.duallink.app.Crypto { *; }
-keep class com.duallink.app.ProtocolException { *; }
-keep class com.duallink.app.ProtocolKt { *; }
# ADB-to-self (libadb-android + SPAKE2 native + Conscrypt TLS 1.3 + BouncyCastle ASN.1)
-keep class io.github.muntashirakon.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn io.github.muntashirakon.**
