#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <string>
#include "whisper.h"

#define LOG_TAG "WBoardWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_wboard_ime_voice_WhisperEngine_nativeInit(
        JNIEnv *env,
        jobject /* thiz */,
        jstring modelPathStr) {
    if (modelPathStr == nullptr) return 0;
    const char *modelPath = env->GetStringUTFChars(modelPathStr, nullptr);
    if (modelPath == nullptr) return 0;

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    struct whisper_context *ctx = whisper_init_from_file_with_params(modelPath, cparams);
    env->ReleaseStringUTFChars(modelPathStr, modelPath);

    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper context");
        return 0;
    }
    LOGI("Whisper context initialized");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_wboard_ime_voice_WhisperEngine_nativeFree(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jlong ctxPtr) {
    if (ctxPtr == 0) return;
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    whisper_free(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_wboard_ime_voice_WhisperEngine_nativeTranscribe(
        JNIEnv *env,
        jobject /* thiz */,
        jlong ctxPtr,
        jint numThreads,
        jfloatArray audioData,
        jstring languageStr,
        jstring initialPromptStr) {
    if (ctxPtr == 0 || audioData == nullptr) {
        return env->NewStringUTF("");
    }

    auto *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    const jsize nSamples = env->GetArrayLength(audioData);
    if (nSamples <= 0) {
        return env->NewStringUTF("");
    }

    jfloat *samples = env->GetFloatArrayElements(audioData, nullptr);
    if (samples == nullptr) {
        return env->NewStringUTF("");
    }

    const char *langC = languageStr != nullptr ? env->GetStringUTFChars(languageStr, nullptr) : nullptr;
    const char *promptC = initialPromptStr != nullptr ? env->GetStringUTFChars(initialPromptStr, nullptr) : nullptr;

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = false;
    params.suppress_blank = true;
    params.n_threads = std::max(1, std::min(4, static_cast<int>(numThreads)));
    params.language = (langC != nullptr && langC[0] != '\0') ? langC : "id";
    params.initial_prompt = (promptC != nullptr && promptC[0] != '\0') ? promptC : nullptr;

    int rc = whisper_full(ctx, params, samples, static_cast<int>(nSamples));

    if (langC != nullptr) env->ReleaseStringUTFChars(languageStr, langC);
    if (promptC != nullptr) env->ReleaseStringUTFChars(initialPromptStr, promptC);
    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full failed with code %d", rc);
        return env->NewStringUTF("");
    }

    std::string result;
    const int nSegments = whisper_full_n_segments(ctx);
    for (int i = 0; i < nSegments; ++i) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg != nullptr) {
            result += seg;
        }
    }
    return env->NewStringUTF(result.c_str());
}

} // extern "C"
