#include <jni.h>
#include <pty.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

/*
 * CrabCode Mobile — JNI bridge for PTY allocation.
 * Mirrors what node-pty does for the desktop CrabCode IDE.
 *
 * forkPty() forks with a pseudo-terminal and the CHILD immediately execve()s
 * argv[0] with envp — all JNI string conversion happens before fork so the
 * child stays async-signal-safe.
 */

static char **to_c_array(JNIEnv *env, jobjectArray jarr, jsize *out_len) {
    jsize n = (*env)->GetArrayLength(env, jarr);
    char **result = calloc((size_t)n + 1, sizeof(char *));
    if (!result) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, jarr, i);
        if (!js) { result[i] = strdup(""); continue; }
        const char *cs = (*env)->GetStringUTFChars(env, js, NULL);
        result[i] = cs ? strdup(cs) : strdup("");
        if (cs) (*env)->ReleaseStringUTFChars(env, js, cs);
        (*env)->DeleteLocalRef(env, js);
    }
    result[n] = NULL;
    *out_len = n;
    return result;
}

static void free_c_array(char **arr, jsize len) {
    if (!arr) return;
    for (jsize i = 0; i < len; i++) free(arr[i]);
    free(arr);
}

JNIEXPORT jintArray JNICALL
Java_com_crabcode_ide_linux_NativePty_forkPty(JNIEnv *env, jclass clazz, jint rows, jint cols,
                                              jobjectArray jargv, jobjectArray jenvp) {
    (void)clazz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);

    jsize argv_len = 0, envp_len = 0;
    char **argv = to_c_array(env, jargv, &argv_len);
    char **envp = to_c_array(env, jenvp, &envp_len);
    if (!argv || !envp) {
        if (argv) free_c_array(argv, argv_len);
        if (envp) free_c_array(envp, envp_len);
        return NULL;
    }

    int masterFd = -1;
    pid_t pid = forkpty(&masterFd, NULL, NULL, &ws);

    if (pid == 0) {
        /* child — no JNI from here on */
        execve(argv[0], argv, envp);
        _exit(127);
    }

    free_c_array(argv, argv_len);
    free_c_array(envp, envp_len);

    jintArray arr = (*env)->NewIntArray(env, 2);
    if (!arr) return NULL;
    if (pid < 0) {
        jint tmp[2] = {-1, (jint)errno};
        (*env)->SetIntArrayRegion(env, arr, 0, 2, tmp);
    } else {
        jint tmp[2] = {(jint)pid, masterFd};
        (*env)->SetIntArrayRegion(env, arr, 0, 2, tmp);
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_com_crabcode_ide_linux_NativePty_setWindowSize(JNIEnv *env, jclass clazz, jint masterFd, jint rows, jint cols) {
    (void)env; (void)clazz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    ioctl(masterFd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_com_crabcode_ide_linux_NativePty_sendSignal(JNIEnv *env, jclass clazz, jint pid, jint sig) {
    (void)env; (void)clazz;
    kill((pid_t)pid, sig);
}

JNIEXPORT jint JNICALL
Java_com_crabcode_ide_linux_NativePty_waitPidNoHang(JNIEnv *env, jclass clazz, jint pid) {
    (void)env; (void)clazz;
    int status = 0;
    pid_t r = waitpid((pid_t)pid, &status, WNOHANG);
    if (r == (pid_t)pid) {
        if (WIFEXITED(status)) return WEXITSTATUS(status);
        if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
        return 0;
    }
    return -1; /* still running or unknown */
}
