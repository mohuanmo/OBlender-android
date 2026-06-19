/*
 * native-lib.cpp - OBlender Native Activity native layer
 *
 * 🔧 修复记录:
 * 1. mainBlenderInitial 返回 nullptr 时 isInitial 保持 false → 防止 SIGSEGV
 * 2. 主循环 timeout 策略：未初始化时 -1（阻塞等待），初始化后 16ms（60fps）
 * 3. 增加 app->paused 检查，后台不渲染
 * 4. 补全 APP_CMD_PAUSE / APP_CMD_RESUME / APP_CMD_DESTROY 处理
 * 5. 路径空值保护 + argv 参数修复
 */

#include <initializer_list>
#include <memory>
#include <cstdlib>
#include <cstring>
#include <jni.h>
#include <cerrno>
#include <cassert>
#include <string>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <android/sensor.h>
#include <android/log.h>
#include "android_native_app_glue.h"

#include <BLI_blenlib.h>
#include "creator/creator.h"

#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, "OBlenderNative", __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, "OBlenderNative", __VA_ARGS__))

struct appUserData {
    void *pContext;
};

bool isInitial = false;
bool isSurfaceValid = false;

char strHomePath[256] = {0};
char strConfigPath[256] = {0};

/* ---------- JNI 函数 ---------- */

extern "C" JNIEXPORT void JNICALL
Java_com_epai_oblender_OBLNativeActivity_initial(JNIEnv *env, jobject thiz,
                                                  jstring homePath, jstring configPath) {
    const char *home = env->GetStringUTFChars(homePath, nullptr);
    const char *config = env->GetStringUTFChars(configPath, nullptr);
    if (home != nullptr) {
        strncpy(strHomePath, home, sizeof(strHomePath) - 1);
        strHomePath[sizeof(strHomePath) - 1] = '\0';
        env->ReleaseStringUTFChars(homePath, home);
    }
    if (config != nullptr) {
        strncpy(strConfigPath, config, sizeof(strConfigPath) - 1);
        strConfigPath[sizeof(strConfigPath) - 1] = '\0';
        env->ReleaseStringUTFChars(configPath, config);
    }
    LOGI("JNI initial: home=%s config=%s", strHomePath, strConfigPath);
}

extern "C" JNIEXPORT void JNICALL
Java_com_epai_oblender_OBLNativeActivity_onPauseNative(JNIEnv *env, jobject thiz) {
    LOGI("onPauseNative");
}

extern "C" JNIEXPORT void JNICALL
Java_com_epai_oblender_OBLNativeActivity_onResumeNative(JNIEnv *env, jobject thiz) {
    LOGI("onResumeNative");
}

extern "C" JNIEXPORT void JNICALL
Java_com_epai_oblender_OBLNativeActivity_onSurfaceDestroyed(JNIEnv *env, jobject thiz) {
    LOGI("onSurfaceDestroyed");
    isSurfaceValid = false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_epai_oblender_OBLNativeActivity_setNativeWindow(JNIEnv *env, jobject thiz,
                                                          jobject surface) {
    LOGI("setNativeWindow");
}

void initialLib(void *app) {
    LOGI("initialLib");
}

/* ---------- 通知 Java 层初始化失败 ---------- */

static void notifyJavaInitFailed(struct android_app *app) {
    JNIEnv *env = nullptr;
    if (app->activity->vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        jclass clazz = env->GetObjectClass(app->activity->clazz);
        jmethodID method = env->GetMethodID(clazz, "onBlenderInitFailed", "()V");
        if (method != nullptr) {
            env->CallVoidMethod(app->activity->clazz, method);
        }
        app->activity->vm->DetachCurrentThread();
    }
}

/* ---------- 初始化 EGL / Blender ---------- */

static int engine_init_display(struct android_app *app) {
    LOGI("engine_init_display");

    // 路径有效性检查
    if (strlen(strHomePath) == 0 || strlen(strConfigPath) == 0) {
        LOGE("路径未初始化");
        return -1;
    }

    // 重定向 stderr 到日志文件
    char logpath[512] = {0};
    strcat(logpath, strHomePath);
    strcat(logpath, "error.log");
    FILE *logFile = freopen(logpath, "w", stderr);
    if (logFile == nullptr) {
        LOGE("无法打开日志文件: %s", logpath);
    } else {
        LOGI("stderr -> %s", logpath);
    }

    // 初始化 userData
    auto *userData = new (std::nothrow) appUserData;
    if (userData == nullptr) {
        LOGE("内存分配失败");
        return -1;
    }
    userData->pContext = nullptr;
    app->userData = userData;

    initialLib((void *)app);

    // 设置环境变量
    BLI_setenv("XDG_CACHE_HOME", strHomePath);
    BLI_setenv("HOME", strHomePath);
    BLI_setenv("BLENDER_SYSTEM_DATAFILES", strConfigPath);
    BLI_setenv("BLENDER_SYSTEM_SCRIPTS", strConfigPath);
    BLI_setenv("PYTHONPATH", strConfigPath);
    BLI_setenv("PYTHONHOME", strConfigPath);

    // 🔧 修复：argv 参数与原始代码保持一致
    // argv[0] = executable path, argv[1] = "-d" (debug flag)
    const char *argv[2] = {strHomePath, "-d"};

    void *context = mainBlenderInitial(2, argv);
    if (context == nullptr) {
        LOGE("mainBlenderInitial 返回 nullptr");
        delete userData;
        app->userData = nullptr;
        notifyJavaInitFailed(app);
        return -1;
    }

    userData->pContext = context;
    isInitial = true;
    isSurfaceValid = true;
    LOGI("Blender 初始化成功");
    return 0;
}

static int engine_init_display_reinit(struct android_app *app) {
    LOGI("engine_init_display_reinit");
    if (app->userData == nullptr) {
        LOGE("userData 为空");
        return -1;
    }
    auto *userData = (appUserData *)app->userData;
    if (userData->pContext != nullptr) {
        mainBlenderInitial_reinit(userData->pContext);
        isSurfaceValid = true;
        LOGI("reinit 成功");
        return 0;
    }
    LOGE("pContext 为空");
    return -1;
}

static int engine_term_display(struct android_app *app) {
    LOGI("engine_term_display");
    isSurfaceValid = false;
    return 0;
}

static void engine_draw_frame(struct android_app *app) {
    if (app->userData == nullptr) return;
    auto *userData = (appUserData *)app->userData;
    if (userData->pContext != nullptr) {
        mainBlenderLoop(userData->pContext);
    }
}

/* ---------- 生命周期事件处理 ---------- */

static void engine_handle_cmd(struct android_app *app, int32_t cmd) {
    switch (cmd) {
        case APP_CMD_INIT_WINDOW:
            LOGI("CMD_INIT_WINDOW");
            if (app->window != nullptr) {
                if (!isInitial) {
                    if (engine_init_display(app) != 0) {
                        LOGE("init_display 失败");
                    }
                } else {
                    engine_init_display_reinit(app);
                }
            }
            break;
        case APP_CMD_TERM_WINDOW:
            LOGI("CMD_TERM_WINDOW");
            isSurfaceValid = false;
            break;
        case APP_CMD_GAINED_FOCUS:
            LOGI("CMD_GAINED_FOCUS");
            break;
        case APP_CMD_LOST_FOCUS:
            LOGI("CMD_LOST_FOCUS");
            break;
        case APP_CMD_PAUSE:
            LOGI("CMD_PAUSE");
            // app->paused 由 android_native_app_glue 自动管理
            break;
        case APP_CMD_RESUME:
            LOGI("CMD_RESUME");
            break;
        case APP_CMD_DESTROY:
            LOGI("CMD_DESTROY");
            isInitial = false;
            isSurfaceValid = false;
            break;
        case APP_CMD_CONFIG_CHANGED:
            LOGI("CMD_CONFIG_CHANGED");
            break;
        case APP_CMD_LOW_MEMORY:
            LOGI("CMD_LOW_MEMORY");
            break;
    }
}

/* ---------- 主循环 ---------- */

void android_main(struct android_app *app) {
    LOGI("android_main 启动");

    // 注册命令处理器
    app->onAppCmd = engine_handle_cmd;
    app->userData = nullptr;

    int events;
    struct android_poll_source *source;

    // 🔧 主循环：
    // - 未初始化时 timeout=-1（阻塞等待事件，0% CPU）
    // - 初始化后 timeout=16（60fps 帧率限制，防止 100% CPU）
    // - 后台时（paused）不渲染，省电
    while (true) {
        // 计算超时
        int timeout;
        if (!isInitial || !isSurfaceValid) {
            timeout = -1;  // 阻塞等待事件
        } else if (app->paused) {
            timeout = -1;  // 后台时也阻塞等待，省电
        } else {
            timeout = 16;  // ~60fps
        }

        // 处理事件
        while (ALooper_pollAll(timeout, nullptr, &events,
                                (void **)&source) >= 0) {
            if (source != nullptr) {
                source->process(app, source);
            }
            if (app->destroyRequested != 0) {
                LOGI("destroyRequested，退出主循环");
                goto cleanup;
            }
        }

        // 渲染帧（仅当前台且初始化完成）
        if (isInitial && isSurfaceValid && app->window != nullptr &&
            !app->paused && app->destroyRequested == 0) {
            engine_draw_frame(app);
        }
    }

cleanup:
    LOGI("android_main 退出");
    isInitial = false;
    isSurfaceValid = false;

    // 清理 userData
    if (app->userData != nullptr) {
        auto *userData = (appUserData *)app->userData;
        delete userData;
        app->userData = nullptr;
    }
}
