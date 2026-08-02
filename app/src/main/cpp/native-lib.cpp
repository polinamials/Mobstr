#include <android/native_window_jni.h>
#include <jni.h>
#include <memory>

#include "src/stream_controller.hpp"

namespace {
std::unique_ptr<StreamController> controller;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_mobstr_MainActivity_initCameraStream(JNIEnv* env, jobject, jint port, jint mtu,
                                                       jint width, jint height, jint bitrate) {
    controller.reset();
    controller = std::make_unique<StreamController>(
        static_cast<uint16_t>(port), static_cast<size_t>(mtu), bitrate);
    ANativeWindow* window = controller->initializeEncoder(width, height);
    if (!window) {
        controller.reset();
        return nullptr;
    }
    jobject surface = ANativeWindow_toSurface(env, window);
    ANativeWindow_release(window);
    return surface;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mobstr_MainActivity_startCameraStream(JNIEnv*, jobject) {
    if (controller)
        controller->startStreaming();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mobstr_MainActivity_stopCameraStream(JNIEnv*, jobject) {
    controller.reset();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mobstr_MainActivity_getNativeDiagnostics(JNIEnv* env, jobject) {
    const std::string value = controller ? controller->diagnostics() : "Stream stopped";
    return env->NewStringUTF(value.c_str());
}
