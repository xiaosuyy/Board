# Board —— R8 keep 规则（v1.1.0）
#
# 原则：只保留**真正必需**的，不用 `-keep class ** { *; }` 这种偷懒写法。
# 依赖自带的 consumer 规则已经覆盖的东西，这里不重复添加。

# ---------------------------------------------------------------------------
# 1) JNI / librime bridge —— 必须保留
#
#    librime 是本地库，方法不是通过反射调用，而是**按符号名解析**：
#    C++ 侧函数名 = Java_com_board_ime_engine_rime_RimeNative_<方法名>
#    （全工程没有 RegisterNatives，也没有 native → Java 回调）。
#    所以「类名 + native 方法名」一旦被 R8 改名，运行时就 UnsatisfiedLinkError。
#    只 keep 这一个 bridge 类，不用通配。
# ---------------------------------------------------------------------------
-keep class com.board.ime.engine.rime.RimeNative {
    native <methods>;
}

# ---------------------------------------------------------------------------
# 2) 崩溃栈可读性：App 会把崩溃写入本地供用户导出（CrashRecorder），
#    保留行号信息，配合 mapping.txt 才能还原堆栈。
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable

# ---------------------------------------------------------------------------
# 3) Room / WorkManager / OkHttp / security-crypto：**不添加** keep 规则。
#
#    已核实各自制品自带的 consumer 规则足够：
#      · room-runtime-2.8.4.aar  → `-keep class * extends androidx.room.RoomDatabase { void <init>(); }`
#      · work-runtime-2.10.0.aar → `-keepnames class * extends androidx.work.ListenableWorker`
#                                  `-keepclassmembers ... { public <init>(...); }`
#      · security-crypto-1.1.0.aar → 声明 "safe to shrink"
#      · okhttp 4.12.0 jar → 自带 proguard 规则
#    （v1.0.0 里那条 `-keep class * extends androidx.room.RoomDatabase` 与 AAR 重复，已删除。）
#
#    另外 App 自身没有任何反射调用（无 Class.forName / getDeclaredMethod / newInstance），
#    没有 Parcelable / Serializable 传递，所以不需要额外的 keep。
# ---------------------------------------------------------------------------
