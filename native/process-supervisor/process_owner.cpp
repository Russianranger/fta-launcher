#include "process_owner.h"
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <map>
#include <memory>
#include <mutex>
#include <poll.h>
#include <signal.h>
#include <stdexcept>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>

namespace fta {
namespace {
constexpr std::size_t log_limit = 8 * 1024 * 1024;
struct Owned {
    pid_t pid;
    std::atomic<int> status{running};
    std::mutex mutex;
    std::atomic<bool> finish_logs{false};
    std::thread output_thread, error_thread;
    explicit Owned(pid_t p) : pid(p) {}
    ~Owned() {
        finish_logs = true;
        if (output_thread.joinable()) output_thread.join();
        if (error_thread.joinable()) error_thread.join();
    }
};
std::mutex registry_mutex;
// Keep finished statuses so stop/wait are idempotent. Bound caller resource use.
std::map<std::int64_t, std::shared_ptr<Owned>> registry;
std::int64_t next_handle = 1;
[[noreturn]] void fail(const char* message) { throw std::runtime_error(message); }
void close_fd(int& fd) { if (fd >= 0) { close(fd); fd = -1; } }
int regular_file(const std::string& path, bool write) {
    int fd = open(path.c_str(), (write ? O_WRONLY | O_CREAT | O_TRUNC : O_RDONLY) | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK, 0600);
    if (fd < 0) fail("Cannot open private process IO");
    struct stat st{};
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || (write && fchmod(fd, 0600))) {
        close(fd); fail("Process IO must be an owned regular file");
    }
    return fd;
}
void drain(int fd, int target, Owned* owned) {
    std::size_t written = 0;
    std::size_t finished_bytes = 0;
    char buffer[16384];
    while (true) {
        pollfd watched{fd, POLLIN, 0};
        const int ready = poll(&watched, 1, 100);
        if (ready < 0 && errno == EINTR) continue;
        if (ready > 0) {
            const ssize_t got = read(fd, buffer, sizeof(buffer));
            if (got <= 0) break;
            std::size_t count = std::min(static_cast<std::size_t>(got), log_limit - written);
            std::size_t offset = 0;
            while (offset < count) {
                ssize_t done = write(target, buffer + offset, count - offset);
                if (done < 0 && errno == EINTR) continue;
                if (done <= 0) { count = offset; break; }
                offset += static_cast<std::size_t>(done);
            }
            written += count;
            if (owned->finish_logs) {
                finished_bytes += static_cast<std::size_t>(got);
                if (finished_bytes >= 128 * 1024) break;
            }
        } else if (ready < 0 || owned->finish_logs) break;
    }
    close(fd); close(target);
}
std::shared_ptr<Owned> lookup(std::int64_t handle) {
    std::lock_guard<std::mutex> guard(registry_mutex);
    auto found = registry.find(handle);
    if (found == registry.end()) fail("Unknown owned process handle");
    return found->second;
}
int poll_owned(Owned& owned) {
    if (owned.status != running) return owned.status;
    // Observe termination without releasing the leader PID. Its reserved PID
    // prevents a recycled process group being targeted while we end descendants.
    siginfo_t info{};
    if (waitid(P_PID, static_cast<id_t>(owned.pid), &info, WEXITED | WNOHANG | WNOWAIT)) {
        if (errno == EINTR) return running;
        fail("Owned process could not be observed");
    }
    if (!info.si_pid) return running;
    if (kill(-owned.pid, SIGKILL) && errno != ESRCH) fail("Owned process group could not be completed");
    int value = 0;
    pid_t result;
    do { result = waitpid(owned.pid, &value, 0); } while (result < 0 && errno == EINTR);
    if (result < 0) fail("Owned process could not be reaped");
    owned.status = WIFEXITED(value) ? WEXITSTATUS(value) : 128 + WTERMSIG(value);
    owned.finish_logs = true;
    return owned.status;
}
void bounded_strings(const std::vector<std::string>& strings, std::size_t maximum) {
    if (strings.size() > maximum) fail("Too many process arguments");
    for (const auto& value : strings) if (value.size() > 16384 || value.find('\0') != std::string::npos) fail("Invalid process argument");
}
}

std::int64_t start(const StartOptions& options) {
    bounded_strings(options.arguments, 128); bounded_strings(options.environment, 64);
    if (options.executable.empty() || options.executable[0] != '/' || options.cwd.empty() || options.cwd[0] != '/') fail("Process paths must be absolute");
    if (options.output == options.error) fail("Process log paths must differ");
    std::vector<std::string> values{options.executable};
    values.insert(values.end(), options.arguments.begin(), options.arguments.end());
    std::vector<char*> argv, env;
    for (auto& value : values) argv.push_back(value.data());
    argv.push_back(nullptr);
    for (const auto& value : options.environment) env.push_back(const_cast<char*>(value.c_str()));
    env.push_back(nullptr);
    int input = -1, output = -1, error = -1;
    int out_pipe[2]{-1,-1}, err_pipe[2]{-1,-1}, exec_pipe[2]{-1,-1};
    pid_t child = -1;
    try {
        input = options.input.empty() ? open("/dev/null", O_RDONLY | O_CLOEXEC) : regular_file(options.input, false);
        if (input < 0) fail("Cannot open process input");
        output = regular_file(options.output, true); error = regular_file(options.error, true);
        if (pipe2(out_pipe, O_CLOEXEC) || pipe2(err_pipe, O_CLOEXEC) || pipe2(exec_pipe, O_CLOEXEC)) fail("Cannot create process pipes");
        std::lock_guard<std::mutex> guard(registry_mutex);
        // Reap/drop completed handles before imposing the outstanding-process cap.
        for (auto i = registry.begin(); i != registry.end();) {
            if (registry.size() >= 64 && i->second->status != running) i = registry.erase(i); else ++i;
        }
        if (registry.size() >= 64) fail("Owned process limit reached");
        const pid_t parent = getpid();
        const long descriptor_limit = std::min<long>(sysconf(_SC_OPEN_MAX), 65536);
        sigset_t empty_signals;
        sigemptyset(&empty_signals);
        struct sigaction default_signal{};
        default_signal.sa_handler = SIG_DFL;
        sigemptyset(&default_signal.sa_mask);
        child = fork();
        if (child < 0) fail("Cannot create native child");
        if (child == 0) {
            // Only async-signal-safe/syscall operations after forking the JVM.
            int problem = 0;
            if (sigprocmask(SIG_SETMASK, &empty_signals, nullptr) ||
                sigaction(SIGTERM, &default_signal, nullptr) || sigaction(SIGINT, &default_signal, nullptr) ||
                prctl(PR_SET_PDEATHSIG, SIGKILL) || getppid() != parent || setpgid(0,0) || chdir(options.cwd.c_str())) problem = errno ? errno : ECHILD;
            umask(0077);
            if (!problem && (dup2(input, 0) < 0 || dup2(out_pipe[1], 1) < 0 || dup2(err_pipe[1], 2) < 0)) problem = errno;
            // Prevent inherited JVM/application fds leaking into the server.
            for (int fd = 3; fd < descriptor_limit; ++fd) if (fd != exec_pipe[1]) close(fd);
            if (!problem) execve(options.executable.c_str(), argv.data(), env.data());
            if (!problem) problem = errno;
            const auto ignored = write(exec_pipe[1], &problem, sizeof(problem));
            (void)ignored;
            _exit(127);
        }
        close_fd(input); close_fd(out_pipe[1]); close_fd(err_pipe[1]); close_fd(exec_pipe[1]);
        int problem = 0;
        ssize_t got;
        do { got = read(exec_pipe[0], &problem, sizeof(problem)); } while (got < 0 && errno == EINTR);
        close_fd(exec_pipe[0]);
        if (got != 0) { waitpid(child, nullptr, 0); child = -1; fail("Native executable did not start"); }
        auto owned = std::make_shared<Owned>(child);
        owned->output_thread = std::thread(drain, out_pipe[0], output, owned.get()); out_pipe[0] = output = -1;
        owned->error_thread = std::thread(drain, err_pipe[0], error, owned.get()); err_pipe[0] = error = -1;
        const auto handle = next_handle++;
        registry.emplace(handle, owned);
        child = -1;
        return handle;
    } catch (...) {
        if (child > 0) { kill(-child, SIGKILL); kill(child, SIGKILL); waitpid(child, nullptr, 0); }
        close_fd(input); close_fd(output); close_fd(error);
        for (int* pair : {out_pipe,err_pipe,exec_pipe}) { close_fd(pair[0]); close_fd(pair[1]); }
        throw;
    }
}

int process_id(std::int64_t handle) {
    auto owned = lookup(handle);
    std::lock_guard<std::mutex> guard(owned->mutex);
    if (owned->status != running) fail("Owned process has already exited");
    // Observation only: never reap or clean up descendants while taking identity.
    siginfo_t info{};
    int result;
    do { result = waitid(P_PID, static_cast<id_t>(owned->pid), &info, WEXITED | WNOHANG | WNOWAIT); }
    while (result && errno == EINTR);
    if (result) fail("Owned process could not be observed");
    if (info.si_pid) fail("Owned process has already exited");
    return owned->pid;
}

int wait_for(std::int64_t handle, std::int64_t timeout_ms) {
    auto owned = lookup(handle);
    const auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(std::clamp<std::int64_t>(timeout_ms,0,600000));
    do {
        { std::lock_guard<std::mutex> guard(owned->mutex); const int value = poll_owned(*owned); if (value != running) {
            if (owned->output_thread.joinable()) owned->output_thread.join();
            if (owned->error_thread.joinable()) owned->error_thread.join();
            return value;
        } }
        if (std::chrono::steady_clock::now() >= end) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    } while (true);
    return running;
}

int stop(std::int64_t handle, std::int64_t grace_ms) {
    auto owned = lookup(handle);
    int completed;
    {
        std::lock_guard<std::mutex> guard(owned->mutex);
        completed = poll_owned(*owned);
        if (completed == running && kill(-owned->pid, SIGTERM) && errno != ESRCH) fail("Owned process shutdown failed");
    }
    if (completed != running) return wait_for(handle,0);
    const int value = wait_for(handle, std::clamp<std::int64_t>(grace_ms,0,30000));
    if (value != running) return value;
    {
        std::lock_guard<std::mutex> guard(owned->mutex);
        if (poll_owned(*owned) == running && kill(-owned->pid, SIGKILL) && errno != ESRCH) fail("Owned process forced shutdown failed");
    }
    return wait_for(handle,5000);
}
}
