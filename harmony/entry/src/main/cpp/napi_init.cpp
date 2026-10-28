#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "napi/native_api.h"
// 所有 cp_* 导出都由这个生成的头声明，别再手写一份：手写会和 Kotlin 侧签名漂移
// （例如入参从 void* 变成 const char* 时直接编译失败）。
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

/** 按需工作流视图：缓冲区不够时按返回的完整长度扩容重取。 */
napi_value NativeSessionWorkflow(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value argv[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
    const std::string request = (argc > 0 && argv[0] != nullptr) ? ReadString(env, argv[0]) : std::string("{}");

    std::vector<char> buffer(kJsonBufferSize, 0);
    int32_t needed = cp_session_workflow(
        const_cast<char *>(request.c_str()),
        buffer.data(),
        static_cast<int32_t>(buffer.size()));
    if (needed <= 0) {
        return MakeString(env, R"({"ok":false,"state":"error","message":"工作流视图没有返回内容"})");
    }
    if (static_cast<size_t>(needed) >= buffer.size()) {
        buffer.assign(static_cast<size_t>(needed) + 1, 0);
        needed = cp_session_workflow(
            const_cast<char *>(request.c_str()),
            buffer.data(),
            static_cast<int32_t>(buffer.size()));
        if (needed <= 0 || static_cast<size_t>(needed) >= buffer.size()) {
            return MakeString(env, R"({"ok":false,"state":"error","message":"工作流视图超出缓冲区"})");
        }
    }
    return MakeString(env, std::string(buffer.data(), static_cast<size_t>(needed)));
}

/** 按需取采样预览；图片字节以 base64 放在独立响应里，不进入状态帧。 */
napi_value NativeSessionPreview(napi_env env, napi_callback_info /*info*/) {
    std::vector<char> buffer(kJsonBufferSize, 0);
    int32_t needed = cp_session_preview(buffer.data(), static_cast<int32_t>(buffer.size()));
    if (needed <= 0) {
        return MakeString(env, R"({"ok":false,"message":"预览没有返回内容"})");
    }
    if (static_cast<size_t>(needed) >= buffer.size()) {
        buffer.assign(static_cast<size_t>(needed) + 1, 0);
        needed = cp_session_preview(buffer.data(), static_cast<int32_t>(buffer.size()));
        if (needed <= 0 || static_cast<size_t>(needed) >= buffer.size()) {
            return MakeString(env, R"({"ok":false,"message":"预览超出缓冲区"})");
        }
    }
    return MakeString(env, std::string(buffer.data(), static_cast<size_t>(needed)));
}

napi_value NativeSessionPreviewAnimation(napi_env env, napi_callback_info /*info*/) {
    std::vector<char> buffer(kJsonBufferSize, 0);
    int32_t needed = cp_session_preview_animation(buffer.data(), static_cast<int32_t>(buffer.size()));
    if (needed <= 0) {
        return MakeString(env, R"({"ok":false,"message":"预览没有返回内容"})");
    }
    if (static_cast<size_t>(needed) >= buffer.size()) {
        buffer.assign(static_cast<size_t>(needed) + 1, 0);
        needed = cp_session_preview_animation(buffer.data(), static_cast<int32_t>(buffer.size()));
        if (needed <= 0 || static_cast<size_t>(needed) >= buffer.size()) {
            return MakeString(env, R"({"ok":false,"message":"预览超出缓冲区"})");
        }
    }
    return MakeString(env, std::string(buffer.data(), static_cast<size_t>(needed)));
}

napi_value NativeAiBridge(napi_env env, napi_callback_info info, bool apply) {
    size_t argc = 1;
    napi_value args[1] = {nullptr};
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    std::string request = argc > 0 ? ReadString(env, args[0]) : "{}";
    // ABI 头把入参声明为 void*（Kotlin 侧 CPointer<ByteVar>），string::data() 在
    // C++17 之前是 const char*，这里给一份可写、NUL 结尾的副本。
    std::vector<char> requestBytes(request.begin(), request.end());
    requestBytes.push_back('\0');
    std::vector<char> buffer(kJsonBufferSize, 0);
    int32_t needed = apply
        ? cp_ai_apply(requestBytes.data(), buffer.data(), static_cast<int32_t>(buffer.size()))
        : cp_ai_digest(requestBytes.data(), buffer.data(), static_cast<int32_t>(buffer.size()));
    if (needed <= 0) {
        return MakeString(env, R"({"ok":false,"message":"AI 桥没有返回内容"})");
    }
    if (static_cast<size_t>(needed) >= buffer.size()) {
        buffer.assign(static_cast<size_t>(needed) + 1, 0);
        needed = apply
            ? cp_ai_apply(requestBytes.data(), buffer.data(), static_cast<int32_t>(buffer.size()))
            : cp_ai_digest(requestBytes.data(), buffer.data(), static_cast<int32_t>(buffer.size()));
        if (needed <= 0 || static_cast<size_t>(needed) >= buffer.size()) {
            return MakeString(env, R"({"ok":false,"message":"AI 响应超出缓冲区"})");
        }
    }
    return MakeString(env, std::string(buffer.data(), static_cast<size_t>(needed)));
}

napi_value NativeAiDigest(napi_env env, napi_callback_info info) {
    return NativeAiBridge(env, info, false);
}

napi_value NativeAiApply(napi_env env, napi_callback_info info) {
    return NativeAiBridge(env, info, true);
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
        {"nativeSessionWorkflow", nullptr, NativeSessionWorkflow, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"nativeAiDigest", nullptr, NativeAiDigest, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"nativeAiApply", nullptr, NativeAiApply, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"nativeSessionPreview", nullptr, NativeSessionPreview, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"nativeSessionPreviewAnimation", nullptr, NativeSessionPreviewAnimation, nullptr, nullptr, nullptr, napi_default, nullptr},
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
