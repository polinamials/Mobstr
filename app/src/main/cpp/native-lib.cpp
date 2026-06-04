#include <jni.h>
#include <android/native_window_jni.h>
#include "src/stream_controller.hpp"

// global stream controller
StreamController* g_streamController = nullptr;

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_mobstr_MainActivity_initCameraStream(JNIEnv* env, jobject thiz, jstring ip, jint port) {

    const char* ipStr = env->GetStringUTFChars(ip, nullptr);

    // Initialize stream controller
    g_streamController = new StreamController(ipStr, static_cast<uint16_t>(port));
    env->ReleaseStringUTFChars(ip, ipStr);

    // TODO: unhardcode resolution
    ANativeWindow* surfaceWindow = g_streamController->initializeEncoder(640, 480);

    // Return java surface
    jobject javaSurface = ANativeWindow_toSurface(env, surfaceWindow);
    return javaSurface;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mobstr_MainActivity_startCameraStream(JNIEnv* env, jobject thiz) {
    if (g_streamController) g_streamController->startStreaming();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mobstr_MainActivity_stopCameraStream(JNIEnv* env, jobject thiz) {
    if (g_streamController) g_streamController->stopStreaming();
}