#include "Logger.h"
#include "Appender.h"
#include "LogMessage.h"
#include <cstdlib>
#include <string>

struct Capture : Appender {
    unsigned count = 0;
    size_t bytes = 0;
    Capture(unsigned id, LogLevel level) : Appender(id, "fixture", level, AppenderFlags(6)) {}
    AppenderType getType() const override { return APPENDER_FILE; }
    void _write(LogMessage const* message) override { ++count; bytes += message->prefix.size() + message->text.size() + 1; }
};
static void require(bool ok) { if (!ok) std::abort(); }
int main() {
    require(LOG_LEVEL_INFO == 4 && LOG_LEVEL_WARN == 3 && LOG_LEVEL_ERROR == 2);
    Capture gate(1, LOG_LEVEL_INFO), console(2, LOG_LEVEL_ERROR);
    Logger root("root", LOG_LEVEL_WARN), lifecycle("server.worldserver", LOG_LEVEL_INFO);
    root.addAppender(1, &gate); root.addAppender(2, &console); lifecycle.addAppender(1, &gate);
    for (unsigned i = 0; i < 50000; ++i) {
        LogMessage message(LOG_LEVEL_INFO, "fixture.routine", std::string(200, 'x'));
        root.write(&message);
    }
    require(gate.count == 0 && console.count == 0);
    for (auto level : {LOG_LEVEL_FATAL, LOG_LEVEL_ERROR, LOG_LEVEL_WARN}) {
        LogMessage message(level, "fixture.any", "retained warning or failure"); root.write(&message);
    }
    require(gate.count == 3 && console.count == 2);
    for (auto text : {">> Initialized 113 Data Stores in 1 ms", "(worldserver-daemon) ready...",
                      "Starting up anti-freeze thread (60 seconds max stuck time)...", "Halting process..."}) {
        LogMessage message(LOG_LEVEL_INFO, "server.worldserver", text); lifecycle.write(&message);
    }
    require(gate.count == 7 && console.count == 2 && gate.bytes < 1024);
}
