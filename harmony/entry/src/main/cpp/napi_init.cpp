#include <cstdint>
#include <cstring>
#include <string>

#include "napi/native_api.h"
#include "libcomfypilot_core_api.h"

namespace {

constexpr size_t kJsonBufferSize = 16 * 1024;

napi_value MakeString(napi_env env, const std::string &value) {
    napi_value result = nullptr;
    napi_create_string_utf8(env, value.c_str(), value.size(), &result);
    return result;
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
        return MakeString(env, R"({"ok":false,"message":"共享层自检没有返回内容"})");
    }
    if (static_cast<size_t>(written) >= sizeof(buffer)) {
        return MakeString(env, R"({"ok":false,"message":"自检 JSON 超出缓冲区"})");
    }
    return MakeString(env, std::string(buffer, static_cast<size_t>(written)));
}

}  // namespace

EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
        {"nativeVersion", nullptr, NativeVersion, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"nativeSelfTest", nullptr, NativeSelfTest, nullptr, nullptr, nullptr, napi_default, nullptr},
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
