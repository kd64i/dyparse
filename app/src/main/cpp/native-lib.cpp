#include <jni.h>
#include <string>
#include <regex>
#include <vector>
#include <fstream>
#include <algorithm>
#include <cctype>
#include <cstring>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <unistd.h>

// ---- XOR 混淆字符串工具：关键检测关键词运行时解密，避免静态字符串直接暴露 ----
static void xor_decrypt(unsigned char* data, int len, unsigned char key) {
    for (int i = 0; i < len; i++) {
        data[i] ^= (key + i);
    }
}

static std::string obf_token(const unsigned char* enc, int len, unsigned char key) {
    std::vector<unsigned char> buf(enc, enc + len);
    xor_decrypt(buf.data(), len, key);
    return std::string(reinterpret_cast<char*>(buf.data()), len);
}

// 混淆后的可疑关键词表（XOR 0x5A + 位置偏移）
struct EncToken {
    const unsigned char* data;
    int len;
};
static const unsigned char enc_frida[] = {0x3c, 0x29, 0x35, 0x39, 0x3f};
static const unsigned char enc_xposed[] = {0x22, 0x2b, 0x33, 0x2e, 0x3b, 0x3b};
static const unsigned char enc_lsposed[] = {0x36, 0x28, 0x2c, 0x32, 0x2d, 0x3a, 0x04};
static const unsigned char enc_zygisk[] = {0x20, 0x22, 0x3b, 0x34, 0x2d, 0x34};
static const unsigned char enc_riru[] = {0x28, 0x32, 0x2e, 0x28};
static const unsigned char enc_edxp[] = {0x3f, 0x3f, 0x24, 0x2d};
static const unsigned char enc_sandhook[] = {0x29, 0x3a, 0x32, 0x39, 0x36, 0x30, 0x0f, 0x0a};
static const unsigned char enc_lsplant[] = {0x36, 0x28, 0x2c, 0x31, 0x3f, 0x31, 0x14};
static const unsigned char enc_epic[] = {0x3f, 0x2b, 0x35, 0x3e};
static const unsigned char enc_whale[] = {0x2d, 0x33, 0x3d, 0x31, 0x3b};
static const unsigned char enc_taichi[] = {0x2e, 0x3a, 0x35, 0x3e, 0x36, 0x36};
static const unsigned char enc_xpatch[] = {0x22, 0x2b, 0x3d, 0x29, 0x3d, 0x37};
static const unsigned char enc_pine[] = {0x2a, 0x32, 0x32, 0x38};
static const unsigned char enc_adclose[] = {0x3b, 0x3f, 0x3f, 0x31, 0x31, 0x2c, 0x05};
static const unsigned char enc_closehook[] = {0x39, 0x37, 0x33, 0x2e, 0x3b, 0x71, 0x08, 0x0e, 0x0d, 0x08, 0x4a, 0x04, 0x02, 0x14};

static const EncToken kEncTokens[] = {
    {enc_frida, sizeof(enc_frida)},
    {enc_xposed, sizeof(enc_xposed)},
    {enc_lsposed, sizeof(enc_lsposed)},
    {enc_zygisk, sizeof(enc_zygisk)},
    {enc_riru, sizeof(enc_riru)},
    {enc_edxp, sizeof(enc_edxp)},
    {enc_sandhook, sizeof(enc_sandhook)},
    {enc_lsplant, sizeof(enc_lsplant)},
    {enc_epic, sizeof(enc_epic)},
    {enc_whale, sizeof(enc_whale)},
    {enc_taichi, sizeof(enc_taichi)},
    {enc_xpatch, sizeof(enc_xpatch)},
    {enc_pine, sizeof(enc_pine)},
    {enc_adclose, sizeof(enc_adclose)},
    {enc_closehook, sizeof(enc_closehook)},
};

static bool contains_suspicious_token(const std::string& text) {
    std::string lowered = text;
    std::transform(lowered.begin(), lowered.end(), lowered.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });

    for (const auto& token : kEncTokens) {
        std::string needle = obf_token(token.data, token.len, 0x5A);
        if (lowered.find(needle) != std::string::npos) {
            return true;
        }
    }
    return false;
}

static bool suspicious_in_file(const char* path) {
    std::ifstream input(path);
    if (!input.is_open()) {
        return false;
    }

    std::string line;
    while (std::getline(input, line)) {
        if (contains_suspicious_token(line)) {
            return true;
        }
    }
    return false;
}

static bool has_local_listener(int port) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        return false;
    }

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<uint16_t>(port));
    inet_pton(AF_INET, "127.0.0.1", &address.sin_addr);

    timeval timeout{};
    timeout.tv_sec = 0;
    timeout.tv_usec = 150000;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

    bool connected = connect(sock, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == 0;
    close(sock);
    return connected;
}

static bool has_tracer() {
    std::ifstream input("/proc/self/status");
    if (!input.is_open()) {
        return false;
    }

    std::string line;
    while (std::getline(input, line)) {
        if (line.rfind("TracerPid:", 0) == 0) {
            auto value = line.substr(10);
            try {
                return std::stoi(value) > 0;
            } catch (...) {
                return true;
            }
        }
    }
    return false;
}

static bool has_suspicious_runtime() {
    if (has_tracer()) {
        return true;
    }

    if (suspicious_in_file("/proc/self/maps")) {
        return true;
    }

    static const int suspicious_ports[] = {27042, 27043, 23946, 11500};
    for (int port : suspicious_ports) {
        if (has_local_listener(port)) {
            return true;
        }
    }

    return false;
}

// 注：不再使用 PTRACE_TRACEME 判定调试器——部分 ROM/系统限制下该调用会失败，
// 导致误报。TracerPid 检测（has_tracer）已足够且可靠。
static bool has_ptrace_attached() {
    return false;
}

// 注：不再使用 rwxp 段检测注入——Android 正常 App（JIT/ART/Compose 等）的
// maps 中普遍存在可执行可写段，误报率极高。保留函数签名以兼容信号集合。
static bool has_injected_so() {
    return false;
}

// 前向声明（isVerified / has_native_hook 定义在文件后部，但需在 collectRuntimeSignals 引用）
jboolean isVerified(JNIEnv*, jclass);
static bool has_native_hook();

// 信号集合：[0]=tracer, [1]=debugger(ptrace), [2]=suspicious_maps,
//           [3]=suspicious_port, [4]=injected_so,
//           [5]=native_hook（关键函数被 inline hook）, [6]=xposed_active
jbooleanArray collectRuntimeSignals(JNIEnv* env, jclass) {
    bool signals[7];
    signals[0] = has_tracer();
    signals[1] = has_ptrace_attached();
    signals[2] = suspicious_in_file("/proc/self/maps");
    signals[3] = false;
    static const int suspicious_ports[] = {27042, 27043, 23946, 11500};
    for (int port : suspicious_ports) {
        if (has_local_listener(port)) {
            signals[3] = true;
            break;
        }
    }
    signals[4] = has_injected_so();
    // 高强度：native 关键函数被 inline hook（frida/LSPatch 特征）
    signals[5] = has_native_hook();
    // Xposed 框架活动：maps 中出现 xposed 相关库（token 表已含 xposed/lsposed/lspd/zygisk）
    signals[6] = suspicious_in_file("/proc/self/maps");

    jbooleanArray result = env->NewBooleanArray(7);
    if (result != nullptr) {
        jboolean buf[7];
        for (int i = 0; i < 7; i++) {
            buf[i] = signals[i] ? JNI_TRUE : JNI_FALSE;
        }
        env->SetBooleanArrayRegion(result, 0, 7, buf);
    }
    return result;
}

// ========== native 状态（反 hook 保护保留）==========
// g_verified 默认通过（不再做完整性/签名校验）；仅当关键函数被 inline hook
// （frida/LSPatch）时由 isVerified 置为 false，使 UA/AES 密钥返回无效值。
static volatile bool g_verified = true;

jstring getParserUserAgent(JNIEnv* env, jclass) {
    // 关键函数被 hook 时返回无效 UA：即使 Kotlin 守卫被 patch，抖音也会拒绝无效 UA
    if (!g_verified) {
        return env->NewStringUTF("Invalid/1.0");
    }
    return env->NewStringUTF(
        "Mozilla/5.0 (Linux; Android 13; SM-G998B) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    );
}

jstring getApiUrlTemplate(JNIEnv* env, jclass, jint type) {
    if (type == 1) {
        return env->NewStringUTF("https://www.iesdouyin.com/share/video/%s/");
    }
    if (type == 2) {
        return env->NewStringUTF("http://www.iesdouyin.com/aweme/v1/play/?video_id=%s&ratio=1080p&line=0");
    }
    if (type == 3) {
        return env->NewStringUTF("https://www.douyin.com/");
    }
    return env->NewStringUTF("");
}

jstring getRegex(JNIEnv* env, jclass, jint type) {
    switch (type) {
        case 0:
            return env->NewStringUTF("window\\._ROUTER_DATA\\s*=\\s*(.*?)(?:</script>)");
        case 1:
            return env->NewStringUTF("<script id=\"RENDER_DATA\"[^>]*>(.*?)</script>");
        case 2:
            return env->NewStringUTF("video/(\\d+)");
        case 3:
            return env->NewStringUTF("(\\d+)");
        case 4:
            return env->NewStringUTF("https?://[\\S]+");
        case 5:
            return env->NewStringUTF("^\\d+$");
        default:
            return env->NewStringUTF("");
    }
}

jstring extractVideoId(JNIEnv* env, jclass, jstring url) {
    if (url == nullptr) {
        return nullptr;
    }

    const char* str = env->GetStringUTFChars(url, nullptr);
    std::string text(str);
    std::string videoId;

    try {
        std::regex primary("video/(\\d+)");
        std::smatch match;
        if (std::regex_search(text, match, primary) && match.size() > 1) {
            videoId = match.str(1);
        } else {
            std::regex fallback("(\\d+)");
            if (std::regex_search(text, match, fallback) && match.size() > 1) {
                videoId = match.str(1);
            }
        }
    } catch (...) {
    }

    env->ReleaseStringUTFChars(url, str);
    return videoId.empty() ? nullptr : env->NewStringUTF(videoId.c_str());
}

jstring doExtractJson(JNIEnv* env, jclass, jstring html) {
    if (html == nullptr) {
        return nullptr;
    }

    const char* str = env->GetStringUTFChars(html, nullptr);
    std::string text(str);
    std::string result;

    try {
        std::regex router_data("window\\._ROUTER_DATA\\s*=\\s*(.*?)(?:</script>)");
        std::smatch match;
        if (std::regex_search(text, match, router_data) && match.size() > 1) {
            result = match.str(1);
            size_t last = result.find_last_not_of(" \n\r\t");
            if (last != std::string::npos) {
                result = result.substr(0, last + 1);
            }
            if (!result.empty() && result.back() == ';') {
                result.pop_back();
            }
        } else {
            std::regex render_data("<script id=\"RENDER_DATA\"[^>]*>(.*?)</script>");
            if (std::regex_search(text, match, render_data) && match.size() > 1) {
                result = match.str(1);
            }
        }
    } catch (...) {
    }

    env->ReleaseStringUTFChars(html, str);
    return result.empty() ? nullptr : env->NewStringUTF(result.c_str());
}

jboolean hasSuspiciousRuntime(JNIEnv* env, jclass) {
    return has_suspicious_runtime() ? JNI_TRUE : JNI_FALSE;
}

jstring getHistoryFileName(JNIEnv* env, jclass) {
    return env->NewStringUTF(".system_cache_conf");
}

// Remote config AES key part 2 (bytes 12..21 of the 32-byte key), XOR-obfuscated
// with 0x5A to hide it from static scanning. Combined with parts 1 & 3 in Kotlin.
static unsigned char enc_aes_key_part2[] = {
    0xdb, 0xee, 0x74, 0x1a, 0xd5, 0x69, 0xf2, 0x26, 0xc0, 0x7d
};

jbyteArray getRemoteAesKeyPart2(JNIEnv* env, jclass) {
    const int kLen = sizeof(enc_aes_key_part2) / sizeof(enc_aes_key_part2[0]);
    unsigned char k[kLen];
    // 关键函数被 hook 时返回错误密钥：远端配置必然解密失败（即使 Kotlin 守卫被 patch）
    if (!g_verified) {
        for (int i = 0; i < kLen; i++) {
            k[i] = static_cast<unsigned char>(i + 1); // 明确的错误密钥
        }
        jbyteArray result = env->NewByteArray(kLen);
        env->SetByteArrayRegion(result, 0, kLen, reinterpret_cast<jbyte*>(k));
        return result;
    }
    for (int i = 0; i < kLen; i++) {
        k[i] = enc_aes_key_part2[i] ^ 0x5A;
    }
    jbyteArray result = env->NewByteArray(kLen);
    env->SetByteArrayRegion(result, 0, kLen, reinterpret_cast<jbyte*>(k));
    for (int i = 0; i < kLen; i++) {
        k[i] = 0;
    }
    return result;
}

// ========== 高强度反 hook / 反篡改 ==========
// 检测关键函数入口是否被 inline hook（frida/LSPatch 会把函数开头改写为跳转指令）。
// ARM64 典型函数 prologue 开头为 stp/sub/mov 等指令；frida hook 后开头变为
// ldr x16, #imm; br x16（跳转）或 b #imm（无条件跳转）。通过检查前 12 字节
// 是否包含跳转指令特征来判定。

#include <cstdint>

// 读取函数入口字节（ARM64）
static void read_code_bytes(uintptr_t addr, unsigned char* out, int len) {
    // 直接内存读取（自身进程代码段可读）
    for (int i = 0; i < len; i++) {
        out[i] = *reinterpret_cast<const unsigned char*>(addr + i);
    }
}

// ARM64 指令判断：是否为跳转类指令（b/bl/br/b.cond）
static bool is_branch_insn(uint32_t insn) {
    // B / BL：opcode 0x14xxxxxx / 0x94xxxxxx
    if ((insn & 0xFC000000u) == 0x14000000u) return true;
    // BR / BLR：0xD61F0000 区域
    if ((insn & 0xFFFFFC1Fu) == 0xD61F0000u) return true;
    // B.cond：0x54xxxxxx
    if ((insn & 0xFF000000u) == 0x54000000u) return true;
    // CBZ/CBNZ：0x34/0x35
    if ((insn & 0x7E000000u) == 0x34000000u) return true;
    // TBZ/TBNZ：0x36/0x37
    if ((insn & 0x7E000000u) == 0x36000000u) return true;
    return false;
}

// 检查关键函数是否被 inline hook：若函数第一条指令是跳转，说明被篡改
static bool is_function_hooked(void* func) {
    if (func == nullptr) return true;
    uint32_t first_insn = 0;
    read_code_bytes(reinterpret_cast<uintptr_t>(func),
                    reinterpret_cast<unsigned char*>(&first_insn), 4);
    // 正常函数入口不应是跳转指令（除非被 hook 改写）
    return is_branch_insn(first_insn);
}

// 检测 native 关键函数是否被 hook（isVerified / 密钥函数 / UA 函数）
static bool has_native_hook() {
    // isVerified 是校验状态读取的关键路径，被 hook 则判定篡改
    return is_function_hooked(reinterpret_cast<void*>(&isVerified));
}

// ========== native 状态读取（反 hook 保护）==========

// 供 Kotlin 读取 native 状态（无法被 smali patch 伪造）
// 若检测到关键函数被 inline hook（frida/LSPatch），立即视为异常
jboolean isVerified(JNIEnv*, jclass) {
    if (g_verified && has_native_hook()) {
        // 关键函数被 hook：状态不可信
        g_verified = false;
        return JNI_FALSE;
    }
    return g_verified ? JNI_TRUE : JNI_FALSE;
}

static const JNINativeMethod methods[] = {
    {"getParserUserAgent", "()Ljava/lang/String;", reinterpret_cast<void*>(getParserUserAgent)},
    {"getApiUrlTemplate", "(I)Ljava/lang/String;", reinterpret_cast<void*>(getApiUrlTemplate)},
    {"getRegex", "(I)Ljava/lang/String;", reinterpret_cast<void*>(getRegex)},
    {"extractVideoId", "(Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(extractVideoId)},
    {"doExtractJson", "(Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(doExtractJson)},
    {"hasSuspiciousRuntime", "()Z", reinterpret_cast<void*>(hasSuspiciousRuntime)},
    {"collectRuntimeSignals", "()[Z", reinterpret_cast<void*>(collectRuntimeSignals)},
    {"isVerified", "()Z", reinterpret_cast<void*>(isVerified)},
    {"getHistoryFileName", "()Ljava/lang/String;", reinterpret_cast<void*>(getHistoryFileName)},
    {"getRemoteAesKeyPart2", "()[B", reinterpret_cast<void*>(getRemoteAesKeyPart2)},
};

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("com/jn/dyparse/NativeLib");
    if (clazz == nullptr) {
        return JNI_ERR;
    }

    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) < 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
