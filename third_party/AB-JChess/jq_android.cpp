// Android JNI bridge for an embedded AB-JChess UCI session.
// Mirrors the Pikafish native bridge contract: create / command / release / stop / run.
// Built only on Android (empty translation unit elsewhere); GPL-3.0, part of AB-JChess.
#include <jni.h>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <functional>

namespace jq {
    // uci.cpp
    extern std::function<void(const std::string&)> g_outHook;
    extern std::function<bool(std::string&)> g_lineSource;
    int uci_loop(int argc, char** argv);
}

namespace {

JavaVM* g_vm = nullptr;

struct Session {
    jobject callback = nullptr;              // global ref to IJieqiCallback
    jmethodID onLine = nullptr;
    jmethodID onFinished = nullptr;
    std::mutex mutex;
    std::condition_variable cv;
    std::queue<std::string> commands;
    bool stopping = false;
};

Session g_session;                            // single embedded session
std::mutex g_sessionMutex;
std::condition_variable g_releaseCv;

void emitLine(const std::string& line) {
    JNIEnv* env = nullptr;
    bool attached = false;
    jobject cb;
    jmethodID mid;
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        cb = g_session.callback; mid = g_session.onLine;
        if (!cb) return;
        if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
            attached = true;
        }
    }
    if (!env) return;
    jstring jline = env->NewStringUTF(line.c_str());
    env->CallVoidMethod(cb, mid, jline);
    env->DeleteLocalRef(jline);
    if (attached) g_vm->DetachCurrentThread();
}

void emitFinished(const std::string& error) {
    JNIEnv* env = nullptr;
    bool attached = false;
    jobject cb;
    jmethodID mid;
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        cb = g_session.callback; mid = g_session.onFinished;
        if (!cb) return;
        if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
            attached = true;
        }
    }
    if (!env) return;
    jstring jerr = env->NewStringUTF(error.c_str());
    env->CallVoidMethod(cb, mid, jerr);
    env->DeleteLocalRef(jerr);
    if (attached) g_vm->DetachCurrentThread();
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_zfdang_chess_jieqi_engine_JieqiNativeBridge_create(JNIEnv* env, jclass, jobject callback) {
    // One session at a time. When a previous session is still winding down
    // (activity recreation race), wait for its release instead of failing.
    {
        std::unique_lock<std::mutex> lock(g_sessionMutex);
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(4);
        while (g_session.callback) {
            g_releaseCv.wait_until(lock, deadline);
            if (std::chrono::steady_clock::now() > deadline) break;
        }
        if (g_session.callback) return 0;
    }
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    if (g_session.callback) return 0;
    env->GetJavaVM(&g_vm);
    g_session.callback = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(g_session.callback);
    g_session.onLine = env->GetMethodID(cls, "onLine", "(Ljava/lang/String;)V");
    g_session.onFinished = env->GetMethodID(cls, "onFinished", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);
    // 重置上一会话的停止状态与残留命令队列
    g_session.stopping = false;
    std::queue<std::string> empty;
    std::swap(g_session.commands, empty);
    return 1;
}

JNIEXPORT void JNICALL
Java_com_zfdang_chess_jieqi_engine_JieqiNativeBridge_command(JNIEnv* env, jclass, jlong, jstring jline) {
    if (!jline) return;
    std::string line;
    {
        JNIEnv* env2 = nullptr;
        bool attached = false;
        if (g_vm->GetEnv(reinterpret_cast<void**>(&env2), JNI_VERSION_1_6) != JNI_OK) {
            if (g_vm->AttachCurrentThread(&env2, nullptr) != JNI_OK) return;
            attached = true;
        }
        const char* c = env2->GetStringUTFChars(jline, nullptr);
        line = c ? c : "";
        env2->ReleaseStringUTFChars(jline, c);
        if (attached) g_vm->DetachCurrentThread();
    }
    std::lock_guard<std::mutex> lock(g_session.mutex);
    if (g_session.stopping) return;
    g_session.commands.push(line);
    g_session.cv.notify_all();
}

JNIEXPORT void JNICALL
Java_com_zfdang_chess_jieqi_engine_JieqiNativeBridge_stop(JNIEnv*, jclass, jlong) {
    std::lock_guard<std::mutex> lock(g_session.mutex);
    g_session.stopping = true;
    g_session.commands.push("quit");
    g_session.cv.notify_all();
}

JNIEXPORT void JNICALL
Java_com_zfdang_chess_jieqi_engine_JieqiNativeBridge_release(JNIEnv* env, jclass, jlong) {
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        if (g_session.callback) {
            env->DeleteGlobalRef(g_session.callback);
            g_session.callback = nullptr;
        }
    }
    {
        std::lock_guard<std::mutex> l2(g_session.mutex);
        g_session.stopping = true;
        std::queue<std::string> empty;
        std::swap(g_session.commands, empty);
        g_session.cv.notify_all();
    }
    g_releaseCv.notify_all();
}

// Blocking UCI loop on the caller thread (service worker). Returns after "quit"/stop.
JNIEXPORT void JNICALL
Java_com_zfdang_chess_jieqi_engine_JieqiNativeBridge_run(JNIEnv*, jclass, jlong, jstring) {
    jq::g_outHook = emitLine;
    jq::g_lineSource = [](std::string& outLine) -> bool {
        std::unique_lock<std::mutex> lock(g_session.mutex);
        g_session.cv.wait(lock, [] { return g_session.stopping || !g_session.commands.empty(); });
        if (g_session.stopping && g_session.commands.empty()) return false;
        outLine = g_session.commands.front();
        g_session.commands.pop();
        return true;
    };
    int argc = 1;
    char argv0[] = "abjchess";
    char* argv[1] = { argv0 };
    jq::uci_loop(argc, argv);
    emitFinished(std::string());
    jq::g_outHook = nullptr;
    jq::g_lineSource = nullptr;
}

}  // extern "C"
