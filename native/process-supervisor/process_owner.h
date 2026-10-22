#pragma once
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace fta {
constexpr int running = std::numeric_limits<int>::min();
struct StartOptions {
    std::string executable;
    std::vector<std::string> arguments;
    std::vector<std::string> environment;
    std::string cwd, input, output, error;
};
// Handles are owned by this parent process. They are never caller-selected PIDs.
std::int64_t start(const StartOptions& options);
int wait_for(std::int64_t handle, std::int64_t timeout_ms);
int stop(std::int64_t handle, std::int64_t grace_ms);
}
