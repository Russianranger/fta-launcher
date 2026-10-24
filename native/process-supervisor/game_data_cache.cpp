#include <jni.h>
#include <cerrno>
#include <cstdint>
#include <fcntl.h>
#include <limits>
#include <sys/stat.h>
#include <unistd.h>

// Advice affects clean cache only. Caller must sync writes first. No read, write,
// truncate, seek, global drop_caches, process control or imported code execution.
static int advise(int fd, jlong offset, jlong length) {
    static_assert(sizeof(off_t) >= 8, "64-bit file offsets required");
    if (offset < 0 || length <= 0 || offset > std::numeric_limits<int64_t>::max() - length) return EINVAL;
    struct stat st{};
    if (fstat(fd, &st) != 0) return errno;
    if (!S_ISREG(st.st_mode)) return ESPIPE;
    return posix_fadvise(fd, static_cast<off_t>(offset), static_cast<off_t>(length), POSIX_FADV_DONTNEED);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_russianranger_fta_runtime_GameDataCacheNative_adviseDescriptor(JNIEnv*, jobject, jint fd, jlong offset, jlong length) {
    return advise(fd, offset, length); // Borrowed FD: never close it or change its position.
}

extern "C" JNIEXPORT jint JNICALL
Java_com_russianranger_fta_runtime_GameDataCacheNative_advisePath(JNIEnv* env, jobject, jstring path, jlong offset, jlong length) {
    if (path == nullptr) return EINVAL;
    const char* name = env->GetStringUTFChars(path, nullptr);
    if (name == nullptr) return ENOMEM;
    const int fd = open(name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    const int error = errno;
    env->ReleaseStringUTFChars(path, name);
    if (fd < 0) return error;
    const int result = advise(fd, offset, length);
    close(fd);
    return result;
}
