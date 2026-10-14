# libarchive calls back into Java from native code (exceptions, callbacks, structs),
# so its classes must keep their names and members.
-keep class me.zhanghai.android.libarchive.** { *; }
