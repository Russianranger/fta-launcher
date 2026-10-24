#include "../../native/process-supervisor/game_data_cache.cpp"
#include <sys/mman.h>
#include <array>
#include <cstdio>
#include <cstdlib>
#include <vector>

static void require(bool value) { if (!value) std::abort(); }
int main() {
    char name[] = "/tmp/fta-cache-residency-XXXXXX";
    int fd = mkstemp(name); require(fd >= 0); unlink(name);
    constexpr size_t size = 64 * 1024 * 1024;
    std::array<char, 256 * 1024> block{};
    for (size_t at = 0; at < size; at += block.size()) require(write(fd, block.data(), block.size()) == static_cast<ssize_t>(block.size()));
    require(fsync(fd) == 0);
    const long page = sysconf(_SC_PAGESIZE); require(page > 0);
    void* mapping = mmap(nullptr, size, PROT_NONE, MAP_SHARED, fd, 0); require(mapping != MAP_FAILED);
    std::vector<unsigned char> pages(size / page);
    auto resident = [&]() { require(mincore(mapping, size, pages.data()) == 0); size_t n = 0; for (auto p : pages) n += p & 1; return n * page; };
    const auto before = resident(); require(before > 0);
    require(advise(fd, 0, size) == 0);
    const auto after = resident(); require(after < before);
    require(lseek(fd, 0, SEEK_CUR) == static_cast<off_t>(size));
    int pipes[2]; require(pipe(pipes) == 0); require(advise(pipes[0], 0, 4096) == ESPIPE);
    close(pipes[0]); close(pipes[1]); munmap(mapping, size); close(fd);
    std::printf("{\"residentBytesBefore\":%zu,\"residentBytesAfter\":%zu,\"pipeRejected\":true}\n", before, after);
}
