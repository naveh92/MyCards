# Catalog models are populated reflectively by Gson — keep their fields.
-keep class com.mycards.data.catalog.model.** { *; }

# The backup payload is also reflective, and its field names ARE the file format: a
# backup is Gson-serialised BackupPayload, encrypted. Without this rule R8 renames pan,
# cvv and uuid to a, b, c — so a backup written by one release restores as nulls in the
# next, whose R8 run picked different names. Import would report "not a backup", or
# worse, succeed with empty cards. This is the one feature whose entire job is surviving
# a lost phone, so it must never depend on optimiser output being stable.
-keep class com.mycards.data.backup.BackupPayload { *; }
-keep class com.mycards.data.backup.BackupPayload$* { *; }

# Store lists are read with a streaming JsonReader against literal field names, so they
# need no keep rule; only the two reflective models above do.

# WorkManager resolves workers by class name at runtime, so nothing references them
# statically and R8 cannot see they are live.
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }

# Gson
-dontwarn sun.misc.**
-keepattributes Signature, *Annotation*, EnclosingMethod

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
