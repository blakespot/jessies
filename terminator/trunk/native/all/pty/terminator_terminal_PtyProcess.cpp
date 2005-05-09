typedef long long __int64;

#include "terminator_terminal_PtyProcess.h"

#ifdef _WIN32

extern "C" jint Java_terminator_terminal_PtyProcess_startProcess(JNIEnv *, jobject, jobjectArray, jobject, jobject) {
    return (jint)-1;
}
extern "C" void Java_terminator_terminal_PtyProcess_sendResizeNotification(JNIEnv *, jobject, jobject, jobject) {
}
extern "C"  void Java_terminator_terminal_PtyProcess_destroy(JNIEnv *, jobject) {
}
extern "C"  void Java_terminator_terminal_PtyProcess_waitFor(JNIEnv *, jobject) {
}

#else

#include <errno.h>
#include <grp.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <termios.h>
#include <fcntl.h>

#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>

#include <iostream>
#include <sstream>
#include <string>

#ifdef __APPLE__

// Apple has helpfully commented these out in stdlib.h, so although the code
// in this file should compile on all POSIX systems, we need these stubs on
// Mac OS 10.3 where I notice there's also a posix_openpt(3) which sounds
// like it would pretty interesting if it weren't also commented out:
// http://www.opengroup.org/onlinepubs/009695399/functions/posix_openpt.html

int grantpt(int) {
    return -1;
}

char* ptsname(int) {
    return 0;
}

int unlockpt(int) {
    return -1;
}

#endif


class IOException {
    std::string message;
    
public:
    IOException(const std::string& msg) : message(msg) { }
    IOException(const char* msg) : message(std::string(msg)) { }
    
    const char* getMessage() const {
        return message.c_str();
    }
};

class PtyGenerator {
    std::string ptyName;
    int masterFd;

    int search_for_pty(std::string& pts_name) {
        for (char* ptr1 = "pqrstuvwxyzPQRST"; *ptr1 != 0; ++ptr1) {
            for (char* ptr2 = "0123456789abcdef"; *ptr2 != 0; ++ptr2) {
                pts_name = "/dev/pty";
                pts_name.append(1, *ptr1);
                pts_name.append(1, *ptr2);
                
                /* try to open master */
                int fdm = open(pts_name.c_str(), O_RDWR);
                if (fdm < 0) {
                    if (errno == ENOENT) {
                        /* Different from EIO. */
                        throw IOException("Out of pseudo-terminal devices");
                    } else {
                        /* Try next pty device. */
                        continue;
                    }
                }
                
                /* Return name of slave. */
                pts_name = "/dev/tty";
                pts_name.append(1, *ptr1);
                pts_name.append(1, *ptr2);
                
                /* Return fd of master. */
                return fdm;
            }
        }
        throw IOException("Out of pseudo-terminal devices");
    }
    
    int ptym_open(std::string& pts_name) {
        int ptmx_fd = open("/dev/ptmx", O_RDWR);
        if (ptmx_fd < 0) {
            return search_for_pty(pts_name);
        }
        
        const char* name = ptsname(ptmx_fd);
        if (name == 0) {
            std::ostringstream oss;
            oss << "Failed to get ptysname for file descriptor " << ptmx_fd;
            throw IOException(oss.str().c_str());
        }
        pts_name = name;
        
        if (grantpt(ptmx_fd) != 0) {
            std::ostringstream oss;
            oss << "Failed to get grantpt for " << name;
            throw IOException(oss.str());
        }
        if (unlockpt(ptmx_fd) != 0) {
            std::ostringstream oss;
            oss << "Failed to get unlockpt for " << name;
            throw IOException(oss.str());
        }
        return ptmx_fd;
    }
    
public:
    PtyGenerator() : ptyName(std::string("")), masterFd(-1) { }
    
    // NO VIRTUAL DESTRUCTOR!  DO NOT SUBCLASS!
    
    int getMasterFd() {
        return masterFd;
    }
    
    bool openMaster() {
        masterFd = ptym_open(ptyName);
        return (masterFd >= 0);
    }
  
    int openSlaveAndCloseMaster() {
        struct group* grptr = getgrnam("tty");
        gid_t gid = (grptr != NULL) ? grptr->gr_gid : gid_t(-1);
        
        const char *cName = ptyName.c_str();
        chown(cName, getuid(), gid);
        chmod(cName, S_IRUSR | S_IWUSR | S_IWGRP);
        
        int fds = open(cName, O_RDWR);
        if (fds < 0) {
            std::ostringstream oss;
            oss << "Failed to open " << cName;
            throw IOException(oss.str());
        }
        close(masterFd);
        return fds;
    }
};

static void setFileDescriptor(JNIEnv *env, jobject fileDescriptor, int fd) {
    jclass fileDescriptorClass = env->GetObjectClass(fileDescriptor);
    jfieldID fid = env->GetFieldID(fileDescriptorClass, "fd", "I");
    env->SetIntField(fileDescriptor, fid, fd);
}

static int getIntField(JNIEnv *env, jobject obj, const char *fieldName) {
    jclass objClass = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(objClass, fieldName, "I");
    return (unsigned short) env->GetIntField(obj, fid);
}

static int getFileDescriptor(JNIEnv *env, jobject ptyProcess) {
    return getIntField(env, ptyProcess, "fd");
}

static pid_t getPid(JNIEnv *env, jobject ptyProcess) {
    return (pid_t) getIntField(env, ptyProcess, "processId");
}

static void clientPanic(int fd, const char *message) {
    FILE *tunnel = fdopen(fd, "w");
    fprintf(tunnel, "Error from child: %s\n", message);
    exit(1);
}

static int runChild(char * const *cmd, PtyGenerator& ptyGenerator) {
    if (setsid() < 0) {
        fprintf(stderr, "Failed to setsid.\n");
        exit(1);
    }
    int childFd = ptyGenerator.openSlaveAndCloseMaster();
    if (childFd < 0) {
        fprintf(stderr, "Failed to open client's side of the pty.\n");
        exit(2);
    }
#if    defined(TIOCSCTTY) && !defined(CIBAUD)
    /* 44BSD way to acquire controlling terminal */
    /* !CIBAUD to avoid doing this under SunOS */
    if (ioctl(childFd, TIOCSCTTY, (char *) 0) < 0) {
        clientPanic(childFd, "TIOCSCTTY error");
    }
#endif
    /* slave becomes stdin/stdout/stderr of child */
    if (dup2(childFd, STDIN_FILENO) != STDIN_FILENO) {
        clientPanic(childFd, "dup2 error to stdin");
    }
    if (dup2(childFd, STDOUT_FILENO) != STDOUT_FILENO) {
        clientPanic(childFd, "dup2 error to stdout");
    }
    if (dup2(childFd, STDERR_FILENO) != STDERR_FILENO) {
        clientPanic(childFd, "dup2 error to stderr");
    }
    if (childFd > STDERR_FILENO) {
        close(childFd);
    }
    putenv("TERM=terminator");
    
    /*
     * rxvt resets these signal handlers, and we'll do the same, because it magically
     * fixes the bug where ^c doesn't work if we're launched from KDE or Gnome's
     * launcher program.  I don't quite understand why - maybe bash reads the existing
     * SIGINT setting, and if it's set to something other than DFL it lets the parent process
     * take care of job control.
     */
    signal(SIGINT, SIG_DFL);
    signal(SIGQUIT, SIG_DFL);
    signal(SIGCHLD, SIG_DFL);
    
    if (execvp(cmd[0], cmd) < 0) {
        fprintf(stderr, "Error from child: Can't execute %s\n", cmd[0]);
        exit(1);
    }
    return(0);        /* child returns 0 just like fork() */
}

static pid_t doExecution(char * const *cmd, PtyGenerator& ptyGenerator) {
    pid_t pid = fork();
    if (pid < 0) {
        return -1;
    } else if (pid == 0) {
        return runChild(cmd, ptyGenerator);  // Should never return.
    } else {
        return pid;
    }
}

static void throwJavaIOException(JNIEnv *env, const char *message) {
    jclass exceptionClass = env->FindClass("java/io/IOException");
    if (exceptionClass) {
        env->ThrowNew(exceptionClass, message);
    }
}

extern "C" jint Java_terminator_terminal_PtyProcess_startProcess(JNIEnv *env, jobject ptyProcess, jobjectArray command, jobject inDescriptor, jobject outDescriptor) {
    (void) ptyProcess;
    int cmdLength = env->GetArrayLength(command);
    char **cmd = (char **) calloc(cmdLength + 1, sizeof(char *));
    jstring *strings = (jstring *) calloc(cmdLength, sizeof(jstring));
    for (int i = 0; i < cmdLength; i++) {
        jobject obj = env->GetObjectArrayElement(command, i);
        strings[i] = (jstring) obj;
        cmd[i] = (char *) env->GetStringUTFChars(strings[i], 0);
    }
    PtyGenerator ptyGenerator;
    if (ptyGenerator.openMaster() == false) {
        return (jint) -1;
    }
    pid_t pid;
    try {
        pid = doExecution(cmd, ptyGenerator);
        for (int i = 0; i < cmdLength; i++) {
            env->ReleaseStringUTFChars(strings[i], cmd[i]);
        }
        free(strings);
        free(cmd);
        setFileDescriptor(env, inDescriptor, ptyGenerator.getMasterFd());
//        if (fcntl(outDescriptor, F_NOCACHE, 1) < 0) {
//            throw IOException("Failed to disable pipe cache.");
//        }
        setFileDescriptor(env, outDescriptor, ptyGenerator.getMasterFd());
        setFileDescriptor(env, ptyProcess, ptyGenerator.getMasterFd());
    } catch (const IOException& exception) {
        throwJavaIOException(env, exception.getMessage());
    }
    return (jint) pid;
}

static unsigned short getDimensionField(JNIEnv *env, jobject dimension, const char *fieldName) {
    return (unsigned short) getIntField(env, dimension, fieldName);
}

extern "C" void Java_terminator_terminal_PtyProcess_sendResizeNotification(JNIEnv *env, jobject ptyProcess, jobject sizeInChars, jobject sizeInPixels) {
    struct winsize size;
    size.ws_col = getDimensionField(env, sizeInChars, "width");
    size.ws_row = getDimensionField(env, sizeInChars, "height");
    size.ws_xpixel = getDimensionField(env, sizeInPixels, "width");
    size.ws_ypixel = getDimensionField(env, sizeInPixels, "height");
    if (ioctl(getFileDescriptor(env, ptyProcess), TIOCSWINSZ, (char *) &size) < 0) {
        throwJavaIOException(env, "TIOCSWINSZ error");
    }
}

static void waitFor(JNIEnv *env, jobject ptyProcess, pid_t pid) {
    int status;
    // FIXME: note that we really want to signal the process group, not the process.
    waitpid(pid, &status, 0);
    
    int exitValue = WEXITSTATUS(status);
    jclass ptyClass = env->GetObjectClass(ptyProcess);
    jfieldID fid = env->GetFieldID(ptyClass, "hasTerminated", "Z");
    env->SetBooleanField(ptyProcess, fid, JNI_TRUE);
    fid = env->GetFieldID(ptyClass, "exitValue", "I");
    env->SetIntField(ptyProcess, fid, exitValue);
}

extern "C"  void Java_terminator_terminal_PtyProcess_destroy(JNIEnv *env, jobject ptyProcess) {
    pid_t pid = getPid(env, ptyProcess);
    kill(pid, SIGTERM);
    waitFor(env, ptyProcess, pid);
}

extern "C"  void Java_terminator_terminal_PtyProcess_waitFor(JNIEnv *env, jobject ptyProcess) {
    waitFor(env, ptyProcess, getPid(env, ptyProcess));
}

#endif