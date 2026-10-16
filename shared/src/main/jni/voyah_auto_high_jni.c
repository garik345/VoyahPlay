#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>

/* VoyahTune CanCommandExamples and native-lib.cpp: cmd=1, 10-byte frame.
 * Only explicit IHBC on/off requests are exposed; no arbitrary CAN payloads.
 */
static pthread_mutex_t can_lock = PTHREAD_MUTEX_INITIALIZER;

typedef unsigned int (*can_open_fn)(void);
typedef unsigned int (*can_control_fn)(unsigned int, unsigned char *);
typedef unsigned int (*can_close_fn)(void);

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_voyah_VoyahControls_sendAutoHighBeam(
        JNIEnv *env, jclass type, jint target) {
    (void) env;
    (void) type;
    if (target != 1 && target != 2) return -1;

    unsigned char frame[10] = {0x7a, 0x08, 0, 0, 0, 0, (unsigned char) target, 0, 0, 0};
    pthread_mutex_lock(&can_lock);
    const char *path = sizeof(void *) == 8
            ? "/system/lib64/libqg_hal.so" : "/system/lib/libqg_hal.so";
    void *handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    jint result = -1;
    if (handle != NULL) {
        can_open_fn open_can = (can_open_fn) dlsym(handle, "qg_canbus_open");
        can_control_fn control_can = (can_control_fn) dlsym(handle, "qg_canbus_control");
        can_close_fn close_can = (can_close_fn) dlsym(handle, "qg_canbus_close");
        if (open_can != NULL && control_can != NULL && close_can != NULL && open_can() == 0) {
            result = (jint) control_can(1, frame);
            close_can();
        }
        dlclose(handle);
    }
    pthread_mutex_unlock(&can_lock);
    return result;
}
