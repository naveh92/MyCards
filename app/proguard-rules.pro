# Catalog/provider models are populated reflectively by Gson — keep their fields.
-keep class com.mycards.data.catalog.model.** { *; }
-keep class com.mycards.data.source.model.** { *; }

# Gson
-dontwarn sun.misc.**
-keepattributes Signature, *Annotation*, EnclosingMethod

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
