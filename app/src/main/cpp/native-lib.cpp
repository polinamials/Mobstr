#include <jni.h>
#include <android/native_window_jni.h>
#include "src/stream_controller.hpp"

// global stream controller
StreamController *g_streamController = nullptr;

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_mobstr_MainActivity_initCameraStream(JNIEnv *env, jobject thiz, jstring ip, jint port, jint mtu, jint width, jint height)
{

    const char *ipStr = env->GetStringUTFChars(ip, nullptr);
    g_streamController = new StreamController(ipStr, static_cast<uint16_t>(port), static_cast<size_t>(mtu));
    env->ReleaseStringUTFChars(ip, ipStr);

    ANativeWindow *surfaceWindow = g_streamController->initializeEncoder(static_cast<int>(width), static_cast<int>(height));

    jobject javaSurface = ANativeWindow_toSurface(env, surfaceWindow);
    return javaSurface;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mobstr_MainActivity_startCameraStream(JNIEnv *env, jobject thiz)
{
    if (g_streamController)
        g_streamController->startStreaming();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mobstr_MainActivity_stopCameraStream(JNIEnv *env, jobject thiz)
{
    if (g_streamController)
        g_streamController->stopStreaming();
}