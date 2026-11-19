#include <jni.h>
#include <sys/types.h>
#include <stdbool.h>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <environ/environ.h>

#include "stdio_is.h"

//
// Created by maks on 17.02.21.
//

static volatile jobject exitTrap_ctx;
static volatile jclass exitTrap_exitClass;
static volatile jmethodID exitTrap_staticMethod;
static JavaVM *exitTrap_jvm;
static volatile bool headlessExitMode = false;
static int stdin_pfd[2] = {-1, -1};

static int pfd[2];
static pthread_t logger;
static jmethodID logger_onEventLogged;
static volatile jobject logListener = NULL;
static int latestlog_fd = -1;


static bool recordBuffer(char* buf, ssize_t len) {
    if(strstr(buf, "Session ID is")) return false;
    if(latestlog_fd != -1) {
        write(latestlog_fd, buf, len);
        fdatasync(latestlog_fd);
    }
    return true;
}

static void *logger_thread() {
    JNIEnv *env;
    jstring writeString;
    JavaVM* dvm = pojav_environ->dalvikJavaVMPtr;
    (*dvm)->AttachCurrentThread(dvm, &env, NULL);
    ssize_t  rsize;
    char buf[2050];
    while((rsize = read(pfd[0], buf, sizeof(buf)-1)) > 0) {
        bool shouldRecordString = recordBuffer(buf, rsize); //record with newline int latestlog
        if(buf[rsize-1]=='\n') {
            rsize=rsize-1; //truncate
        }
        buf[rsize]=0x00;
        if(shouldRecordString && logListener != NULL) {
            writeString = (*env)->NewStringUTF(env, buf); //send to app without newline
            (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, writeString);
            (*env)->DeleteLocalRef(env, writeString);
        }
    }
    (*dvm)->DetachCurrentThread(dvm);
    return NULL;
}
JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_begin(JNIEnv *env, __attribute((unused)) jclass clazz, jstring logPath) {
    if(latestlog_fd != -1) {
        int localfd = latestlog_fd;
        latestlog_fd = -1;
        close(localfd);
    }
    if(logger_onEventLogged == NULL) {
        jclass eventLogListener = (*env)->FindClass(env, "net/kdt/pojavlaunch/Logger$eventLogListener");
        logger_onEventLogged = (*env)->GetMethodID(env, eventLogListener, "onEventLogged", "(Ljava/lang/String;)V");
    }
    jclass ioeClass = (*env)->FindClass(env, "java/io/IOException");


    setvbuf(stdout, 0, _IOLBF, 0); // make stdout line-buffered
    setvbuf(stderr, 0, _IONBF, 0); // make stderr unbuffered

    /* create the pipe and redirect stdout and stderr */
    pipe(pfd);
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);

    /* open latestlog.txt for writing */
    const char* logFilePath = (*env)->GetStringUTFChars(env, logPath, NULL);
    latestlog_fd = open(logFilePath, O_WRONLY | O_TRUNC);
    if(latestlog_fd == -1) {
        latestlog_fd = 0;
        (*env)->ThrowNew(env, ioeClass, strerror(errno));
        return;
    }
    (*env)->ReleaseStringUTFChars(env, logPath, logFilePath);

    /* spawn the logging thread */
    int result = pthread_create(&logger, 0, logger_thread, 0);
    if(result != 0) {
        close(latestlog_fd);
        (*env)->ThrowNew(env, ioeClass, strerror(result));
    }
    pthread_detach(logger);
}

_Noreturn void nominal_exit(int code, bool is_signal) {
    JNIEnv *env;
    jint errorCode = (*exitTrap_jvm)->GetEnv(exitTrap_jvm, (void**)&env, JNI_VERSION_1_6);
    if(errorCode == JNI_EDETACHED) {
        errorCode = (*exitTrap_jvm)->AttachCurrentThread(exitTrap_jvm, &env, NULL);
    }
    if(errorCode != JNI_OK) {
        // Step on a landmine and die, since we can't invoke the Dalvik exit without attaching to
        // Dalvik.
        // I mean, if Zygote can do that, why can't I?
        killpg(getpgrp(), SIGTERM);
    }
    if(headlessExitMode) {
        if (exitTrap_exitClass != NULL && exitTrap_staticMethod != NULL) {
            (*env)->CallStaticVoidMethod(env, exitTrap_exitClass, exitTrap_staticMethod, exitTrap_ctx, code, is_signal);
        }
    } else if(code != 0) {
        // Exit code 0 is pretty established as "eh it's fine"
        // so only open the GUI if the code is != 0
        (*env)->CallStaticVoidMethod(env, exitTrap_exitClass, exitTrap_staticMethod, exitTrap_ctx, code, is_signal);
    }
    // Delete the reference, not gonna need 'em later anyway
    (*env)->DeleteGlobalRef(env, exitTrap_ctx);
    (*env)->DeleteGlobalRef(env, exitTrap_exitClass);

    // A hat trick, if you will
    // Call the Android System.exit() to perform Android's shutdown hooks and do a
    // fully clean exit.
    // After doing this, either of these will happen:
    // 1. Runtime calls exit() for real and it will be handled by ByteHook's recurse handler
    // and redirected back to the OS
    // 2. Zygote sends SIGTERM (no handling necessary, the process perishes)
    // 3. A different thread calls exit() and the hook will go through the exit_tripped path
    jclass systemClass = (*env)->FindClass(env,"java/lang/System");
    jmethodID exitMethod = (*env)->GetStaticMethodID(env, systemClass, "exit", "(I)V");
    (*env)->CallStaticVoidMethod(env, systemClass, exitMethod, 0);
    // System.exit() should not ever return, but the compiler doesn't know about that
    // so put a while loop here
    while(1) {}
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_Logger_appendToLog(JNIEnv *env, __attribute((unused)) jclass clazz, jstring text) {
    jsize appendStringLength = (*env)->GetStringUTFLength(env, text);
    char newChars[appendStringLength+2];
    (*env)->GetStringUTFRegion(env, text, 0, (*env)->GetStringLength(env, text), newChars);
    newChars[appendStringLength] = '\n';
    newChars[appendStringLength+1] = 0;
    if(recordBuffer(newChars, appendStringLength+1) && logListener != NULL) {
        (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, text);
    }
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_setLogListener(JNIEnv *env, __attribute((unused)) jclass clazz, jobject log_listener) {
    jobject logListenerLocal = logListener;
    if(log_listener == NULL) {
        logListener = NULL;
    }else{
        logListener = (*env)->NewGlobalRef(env, log_listener);
    }
    if(logListenerLocal != NULL) (*env)->DeleteGlobalRef(env, logListenerLocal);
}


JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_setupExitMethod(JNIEnv *env, jclass clazz,
                                                        jobject context) {
    headlessExitMode = false;
    exitTrap_ctx = (*env)->NewGlobalRef(env,context);
    (*env)->GetJavaVM(env,&exitTrap_jvm);
    exitTrap_exitClass = (*env)->NewGlobalRef(env,(*env)->FindClass(env,"net/kdt/pojavlaunch/ExitActivity"));
    exitTrap_staticMethod = (*env)->GetStaticMethodID(env,exitTrap_exitClass,"showExitMessage","(Landroid/content/Context;IZ)V");
}

JNIEXPORT void JNICALL
Java_com_star_launcher_bridge_process_BridgeJvmNative_setupHeadlessExit(JNIEnv *env, jclass clazz,
                                                                        jobject context) {
    headlessExitMode = true;
    exitTrap_ctx = (*env)->NewGlobalRef(env, context);
    (*env)->GetJavaVM(env, &exitTrap_jvm);
    jclass localClass = (*env)->FindClass(env, "com/star/launcher/bridge/process/BridgeJvmExitBridge");
    if (localClass != NULL) {
        exitTrap_exitClass = (*env)->NewGlobalRef(env, localClass);
        exitTrap_staticMethod = (*env)->GetStaticMethodID(env, exitTrap_exitClass, "onJvmExit", "(Landroid/content/Context;IZ)V");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_star_launcher_bridge_process_BridgeJvmNative_openStdinPipe(JNIEnv *env, jclass clazz) {
    if (stdin_pfd[1] != -1) {
        close(stdin_pfd[1]);
        stdin_pfd[1] = -1;
    }
    if (pipe(stdin_pfd) != 0) {
        return JNI_FALSE;
    }
    if (dup2(stdin_pfd[0], STDIN_FILENO) == -1) {
        close(stdin_pfd[0]);
        close(stdin_pfd[1]);
        stdin_pfd[0] = -1;
        stdin_pfd[1] = -1;
        return JNI_FALSE;
    }
    close(stdin_pfd[0]);
    stdin_pfd[0] = -1;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_star_launcher_bridge_process_BridgeJvmNative_writeStdin(JNIEnv *env, jclass clazz, jstring command) {
    if (stdin_pfd[1] == -1 || command == NULL) {
        return JNI_FALSE;
    }
    const char *utf = (*env)->GetStringUTFChars(env, command, NULL);
    if (utf == NULL) {
        return JNI_FALSE;
    }
    size_t len = strlen(utf);
    ssize_t written = write(stdin_pfd[1], utf, len);
    (*env)->ReleaseStringUTFChars(env, command, utf);
    return (written == (ssize_t)len) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_star_launcher_bridge_process_BridgeJvmNative_closeStdinPipe(JNIEnv *env, jclass clazz) {
    if (stdin_pfd[1] != -1) {
        close(stdin_pfd[1]);
        stdin_pfd[1] = -1;
    }
}