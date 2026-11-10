# Started directly with app_process (not referenced from app code), so the shrinker must keep it.
-keep class com.duallink.app.probe.** { *; }
