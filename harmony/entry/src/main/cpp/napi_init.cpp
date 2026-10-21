#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "napi/native_api.h"
#include "libcomfypilot_core_api.h"

namespace {

constexpr size_t kJsonBufferSize = 16 * 1024;

napi_value MakeString(napi_env env, const std::string &value) {
    napi_value result = nullptr;
    napi_create_string_utf8(env, value.c_str(), value.size(), &result);
    return result;
}

std::string ReadString(napi_env env, napi_value value) {
    size_t length = 0;
    if (napi_get_value_string_utf8(env, value, nullptr, 0, &length) != napi_ok) {
        return {};
    }
    std::vector<char> buffer(length + 1, 0);
    if (napi_get_value_string_utf8(env, value, buffer.data(), buffer.size(), &length) != napi_ok) {
        return {};
    }
    return std::string(buffer.data(), length);
}

/** 甯ф瘮鍒濆缂撳啿鍖哄ぇ鏃舵墿瀹归噸鍙栵紱Kotlin 渚у鍚屼竴涓?nowMs 鏈夌紦瀛橈紝涓嶄細閲嶅鎺ㄨ繘鐘舵€佹満銆?*/
napi_value PumpFrame(napi_env env, int64_t nowMs) {
    std::vector<char> buffer(kJsonBufferSize, 0);
    int32_t needed = cp_session_pump(nowMs, buffer.data(), static_cast<int32_t>(buffer.size()));
    if (needed <= 0) {
        return MakeString(env, R"({"revision":-1,"commands":[],"state":null,"nextDelayMs":250})");
    }
    if (static_cast<size_t>(needed) >= buffer.size()) {
        buffer.assign(static_cast<size_t>(needed) + 1, 0);
        needed = cp_session_pump(nowMs, buffer.data(), static_cast<int32_t>(buffer.size()));
        if (needed <= 0 || static_cast<size_t>(needed) >= buffer.size()) {
            return MakeString(env, R"({"revision":-1,"commands":[],"state":null,"nextDelayMs":250})");
        }
    }
    return MakeString(env, std::string(buffer.data(), static_cast<size_t>(needed)));
}

napi_value NativeVersion(napi_env env, napi_callback_info /*info*/) {
    napi_value result = nullptr;
    napi_create_int32(env, cp_version(), &result);
    return result;
}

napi_value NativeSelfTest(napi_env env, napi_callback_info /*info*/) {
    char buffer[kJsonBufferSize] = {0};
    const int32_t written = cp_self_test_json(buffer, static_cast<int32_t>(sizeof(buffer)));
    if (written <= 0) {
        return MakeString(env, R"({"ok":false,"message":"鍏变韩灞傝嚜妫€娌℃湁杩斿洖鍐呭"})");
    }
    if (static_cast<size_t>(written) >= sizeof(buffer)) {
        return MakeString(env, R"({"ok":false,"message":"鑷 JSON 瓒呭嚭缂撳啿鍖?})");
    }
    return MakeString(env, std::string(buffer, static_cast<size_t>(written)));
}

napi_value NativeSessionCreate(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value argv[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    const std::string config = (argc > 0 && argv[0] != nullptr) ? ReadString(env, argv[0]) : std::string("{}");
    napi_value result = nullptr;
    napi_create_int32(env, cp_session_create(const_cast<char *>(config.c_str())), &result);
    return result;
}

napi_value NativeSessionPump(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value argv[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    int64_t nowMs = 0;
    if (argc > 0 && argv[0] != nullptr) {
        napi_get_value_int64(env, argv[0], &nowMs);
    }
    return PumpFrame(env, nowMs);
}

napi_value NativeSessionSend(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value argv[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    const std::string message = (argc > 0 && argv[0] != nullptr) ? ReadString(env, argv[0]) : std::string("{}");
    napi_value result = nullptr;
    napi_create_int32(env, cp_session_send(const_cast<char *>(message.c_str())), &result);
    return result;
}

napi_value NativeSessionDestroy(napi_env env, napi_callback_info /*info*/) {
    cp_session_destroy();
    napi_value result = nullptr;
    napi_get_undefined(env, &result);
    return result;
}

}  // namespace

EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
        {"nativeVersion", nullptr, NativeVersion, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"nativeSelfTest", nullptr, NativeSelfTest, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"nativeSessionCreate", nullptr, NativeSessionCreate, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"nativeSessionPump", nullptr, NativeSessionPump, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"nativeSessionSend", nullptr, NativeSessionSend, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"nativeSessionDestroy", nullptr, NativeSessionDestroy, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}
EXTERN_C_END

static napi_module comfypilotModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "entry",
    .nm_priv = nullptr,
    .reserved = {0},
};

extern "C" __attribute__((constructor)) void RegisterEntryModule(void) {
    napi_module_register(&comfypilotModule);
}
