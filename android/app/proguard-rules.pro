# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# JNA (used by lazysodium-android for its native crypto bindings) marshals
# calls via reflection, which R8 can't see -- official rules from
# https://github.com/java-native-access/jna/blob/master/www/FrequentlyAskedQuestions.md
-dontwarn java.awt.*
-keep class com.sun.jna.* { *; }
-keep class * extends com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
