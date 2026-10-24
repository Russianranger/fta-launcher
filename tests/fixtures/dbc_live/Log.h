#pragma once
#include <cstdlib>
// Never mask Field conversion/type warnings or original loader errors.
// No raw native messages (which can include private rows) leave the fixture.
#define LOG_WARN(...) std::abort()
#define LOG_ERROR(...) std::abort()
#define LOG_FATAL(...) std::abort()
#define LOG_INFO(...) ((void)0)
#define LOG_DEBUG(...) ((void)0)
#define LOG_TRACE(...) ((void)0)
