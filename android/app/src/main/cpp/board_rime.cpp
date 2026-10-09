// Board —— librime JNI 桥接层
//
// 只做一件事：把 librime 的 C API（rime_api.h 里的 RimeApi 结构体）暴露给 Kotlin。
// 这里不实现任何输入法逻辑，中文分词 / 拼音 / 候选全部由 librime 完成。
//
// 线程安全：librime 的会话不是线程安全的，因此除了部署流程之外，
// 所有调用都用同一把互斥锁串行化。

#include <jni.h>

#include <cstring>
#include <mutex>
#include <string>

#include "rime_api.h"
#include "rime_levers_api.h"

namespace {

RimeApi* g_api = nullptr;
std::mutex g_mutex;

std::string ToUtf8(JNIEnv* env, jstring value) {
  if (value == nullptr) {
    return std::string();
  }
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string result = chars != nullptr ? chars : "";
  if (chars != nullptr) {
    env->ReleaseStringUTFChars(value, chars);
  }
  return result;
}

jstring ToJString(JNIEnv* env, const char* value) {
  if (value == nullptr) {
    return nullptr;
  }
  return env->NewStringUTF(value);
}

bool EnsureApi() {
  if (g_api == nullptr) {
    g_api = rime_get_api();
  }
  return g_api != nullptr;
}

// levers 模块提供用户词典管理（导出 / 导入学习记录）。
// 只有它存在时才能访问，因此单独做一次惰性查找。
RimeLeversApi* LeversApi() {
  static RimeLeversApi* s_levers = nullptr;
  static bool s_probed = false;
  if (s_probed) {
    return s_levers;
  }
  s_probed = true;
  if (!EnsureApi() || g_api->find_module == nullptr) {
    return nullptr;
  }
  RimeModule* module = g_api->find_module("levers");
  if (module == nullptr || module->get_api == nullptr) {
    return nullptr;
  }
  RimeCustomApi* custom = module->get_api();
  s_levers = reinterpret_cast<RimeLeversApi*>(custom);
  return s_levers;
}

jobjectArray NewStringArray(JNIEnv* env, int size) {
  jclass string_class = env->FindClass("java/lang/String");
  if (string_class == nullptr) {
    return nullptr;
  }
  return env->NewObjectArray(size, string_class, nullptr);
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeSetup(
    JNIEnv* env, jobject /*thiz*/, jstring shared_data_dir, jstring user_data_dir,
    jstring app_name, jstring version, jstring log_dir) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->setup == nullptr || g_api->initialize == nullptr) {
    return JNI_FALSE;
  }

  const std::string shared = ToUtf8(env, shared_data_dir);
  const std::string user = ToUtf8(env, user_data_dir);
  const std::string app = ToUtf8(env, app_name);
  const std::string ver = ToUtf8(env, version);
  const std::string logs = ToUtf8(env, log_dir);

  RimeTraits traits;
  std::memset(&traits, 0, sizeof(traits));
  RIME_STRUCT_INIT(RimeTraits, traits);
  traits.shared_data_dir = shared.c_str();
  traits.user_data_dir = user.c_str();
  traits.log_dir = logs.c_str();
  traits.distribution_name = "Board";
  traits.distribution_code_name = "board-ime";
  traits.distribution_version = ver.c_str();
  traits.app_name = app.c_str();
  // INFO：排查部署问题需要看 deployer 自己的日志（正式版可改回 2=WARNING）
  traits.min_log_level = 0;

  g_api->setup(&traits);
  g_api->initialize(&traits);
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeFinalize(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (EnsureApi() && g_api->finalize != nullptr) {
    g_api->finalize();
  }
}

JNIEXPORT jstring JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeVersion(JNIEnv* env, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->get_version == nullptr) {
    return nullptr;
  }
  return ToJString(env, g_api->get_version());
}

JNIEXPORT jboolean JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeStartMaintenance(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean full_check) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->start_maintenance == nullptr) {
    return JNI_FALSE;
  }
  return g_api->start_maintenance(full_check == JNI_TRUE) == True ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeIsMaintenanceMode(JNIEnv* /*env*/,
                                                                 jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->is_maintenance_mode == nullptr) {
    return JNI_FALSE;
  }
  return g_api->is_maintenance_mode() == True ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeJoinMaintenance(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (EnsureApi() && g_api->join_maintenance_thread != nullptr) {
    g_api->join_maintenance_thread();
  }
}

JNIEXPORT jlong JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeCreateSession(JNIEnv* /*env*/, jobject /*thiz*/) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->create_session == nullptr) {
    return 0;
  }
  return static_cast<jlong>(g_api->create_session());
}

JNIEXPORT void JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeDestroySession(JNIEnv* /*env*/, jobject /*thiz*/,
                                                              jlong session) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (EnsureApi() && g_api->destroy_session != nullptr && session != 0) {
    g_api->destroy_session(static_cast<RimeSessionId>(session));
  }
}

JNIEXPORT jboolean JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeProcessKey(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jlong session, jint keycode,
                                                          jint mask) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->process_key == nullptr || session == 0) {
    return JNI_FALSE;
  }
  return g_api->process_key(static_cast<RimeSessionId>(session), keycode, mask) == True ? JNI_TRUE
                                                                                        : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeSelectCandidate(JNIEnv* /*env*/, jobject /*thiz*/,
                                                              jlong session, jint index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->select_candidate_on_current_page == nullptr || session == 0) {
    return JNI_FALSE;
  }
  return g_api->select_candidate_on_current_page(static_cast<RimeSessionId>(session),
                                                static_cast<size_t>(index)) == True
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeChangePage(JNIEnv* /*env*/, jobject /*thiz*/,
                                                          jlong session, jboolean backward) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->change_page == nullptr || session == 0) {
    return JNI_FALSE;
  }
  return g_api->change_page(static_cast<RimeSessionId>(session), backward == JNI_TRUE) == True
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeCommitComposition(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                jlong session) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->commit_composition == nullptr || session == 0) {
    return JNI_FALSE;
  }
  return g_api->commit_composition(static_cast<RimeSessionId>(session)) == True ? JNI_TRUE
                                                                               : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeClearComposition(JNIEnv* /*env*/, jobject /*thiz*/,
                                                               jlong session) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (EnsureApi() && g_api->clear_composition != nullptr && session != 0) {
    g_api->clear_composition(static_cast<RimeSessionId>(session));
  }
}

// 取出并清空待上屏内容；没有则返回 null
JNIEXPORT jstring JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeGetCommit(JNIEnv* env, jobject /*thiz*/,
                                                        jlong session) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->get_commit == nullptr || session == 0) {
    return nullptr;
  }
  RIME_STRUCT(RimeCommit, commit);
  jstring result = nullptr;
  if (g_api->get_commit(static_cast<RimeSessionId>(session), &commit) == True) {
    result = ToJString(env, commit.text);
    if (g_api->free_commit != nullptr) {
      g_api->free_commit(&commit);
    }
  }
  return result;
}

// 当前原始输入（拼音串）；没有则返回 null
JNIEXPORT jstring JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeGetInput(JNIEnv* env, jobject /*thiz*/,
                                                       jlong session) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->get_input == nullptr || session == 0) {
    return nullptr;
  }
  return ToJString(env, g_api->get_input(static_cast<RimeSessionId>(session)));
}

// 当前组词的音节切分（preedit，音节间以空格分隔）；没有则返回 null
JNIEXPORT jstring JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeGetPreedit(JNIEnv* env, jobject /*thiz*/,
                                                         jlong session) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->get_context == nullptr || session == 0) {
    return nullptr;
  }
  RIME_STRUCT(RimeContext, context);
  if (g_api->get_context(static_cast<RimeSessionId>(session), &context) != True) {
    return nullptr;
  }
  jstring result = ToJString(env, context.composition.preedit);
  if (g_api->free_context != nullptr) {
    g_api->free_context(&context);
  }
  return result;
}

// 当前候选（只取第一页），始终返回数组（可能为空）
JNIEXPORT jobjectArray JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeGetCandidates(JNIEnv* env, jobject /*thiz*/,
                                                            jlong session) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->get_context == nullptr || session == 0) {
    return NewStringArray(env, 0);
  }

  RIME_STRUCT(RimeContext, context);
  if (g_api->get_context(static_cast<RimeSessionId>(session), &context) != True) {
    return NewStringArray(env, 0);
  }

  const int count = context.menu.num_candidates > 0 ? context.menu.num_candidates : 0;
  jobjectArray result = NewStringArray(env, count);
  if (result != nullptr) {
    for (int i = 0; i < count; ++i) {
      const char* text = context.menu.candidates != nullptr ? context.menu.candidates[i].text
                                                           : nullptr;
      jstring item = ToJString(env, text);
      if (item == nullptr) {
        item = env->NewStringUTF("");
      }
      env->SetObjectArrayElement(result, i, item);
      env->DeleteLocalRef(item);
    }
  }

  if (g_api->free_context != nullptr) {
    g_api->free_context(&context);
  }
  return result;
}

JNIEXPORT jboolean JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeSetOption(JNIEnv* env, jobject /*thiz*/,
                                                        jlong session, jstring option,
                                                        jboolean value) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->set_option == nullptr || session == 0) {
    return JNI_FALSE;
  }
  const std::string name = ToUtf8(env, option);
  g_api->set_option(static_cast<RimeSessionId>(session), name.c_str(), value == JNI_TRUE);
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeGetOption(JNIEnv* env, jobject /*thiz*/,
                                                        jlong session, jstring option) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->get_option == nullptr || session == 0) {
    return JNI_FALSE;
  }
  const std::string name = ToUtf8(env, option);
  return g_api->get_option(static_cast<RimeSessionId>(session), name.c_str()) == True ? JNI_TRUE
                                                                                     : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeSelectSchema(JNIEnv* env, jobject /*thiz*/,
                                                           jlong session, jstring schema_id) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->select_schema == nullptr || session == 0) {
    return JNI_FALSE;
  }
  const std::string id = ToUtf8(env, schema_id);
  return g_api->select_schema(static_cast<RimeSessionId>(session), id.c_str()) == True ? JNI_TRUE
                                                                                      : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeGetCurrentSchema(JNIEnv* env, jobject /*thiz*/,
                                                               jlong session) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->get_current_schema == nullptr || session == 0) {
    return nullptr;
  }
  char buffer[256] = {0};
  if (g_api->get_current_schema(static_cast<RimeSessionId>(session), buffer, sizeof(buffer)) !=
      True) {
    return nullptr;
  }
  return ToJString(env, buffer);
}

// 是否把 ascii 模式打开（等同切换到英文）；由 schema 的 ascii_mode 选项控制
JNIEXPORT jboolean JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeHasSchema(JNIEnv* env, jobject /*thiz*/,
                                                        jlong session) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!EnsureApi() || g_api->get_current_schema == nullptr || session == 0) {
    return JNI_FALSE;
  }
  char buffer[256] = {0};
  return g_api->get_current_schema(static_cast<RimeSessionId>(session), buffer, sizeof(buffer)) ==
                 True
             ? JNI_TRUE
             : JNI_FALSE;
}

// 导出用户词典（学习记录）到文本文件，返回条目数；失败返回 -1
JNIEXPORT jint JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeExportUserDict(JNIEnv* env, jobject /*thiz*/,
                                                             jstring dict_name,
                                                             jstring text_file) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeLeversApi* levers = LeversApi();
  if (levers == nullptr || levers->export_user_dict == nullptr) {
    return -1;
  }
  const std::string name = ToUtf8(env, dict_name);
  const std::string file = ToUtf8(env, text_file);
  return static_cast<jint>(levers->export_user_dict(name.c_str(), file.c_str()));
}

// 从文本文件导入用户词典，返回条目数；失败返回 -1
JNIEXPORT jint JNICALL
Java_com_board_ime_engine_rime_RimeNative_nativeImportUserDict(JNIEnv* env, jobject /*thiz*/,
                                                             jstring dict_name,
                                                             jstring text_file) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeLeversApi* levers = LeversApi();
  if (levers == nullptr || levers->import_user_dict == nullptr) {
    return -1;
  }
  const std::string name = ToUtf8(env, dict_name);
  const std::string file = ToUtf8(env, text_file);
  return static_cast<jint>(levers->import_user_dict(name.c_str(), file.c_str()));
}

}  // extern "C"
