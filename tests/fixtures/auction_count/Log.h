#pragma once
#include <stdexcept>
extern unsigned warnings;
#define LOG_WARN(...) (++warnings)
#define LOG_ERROR(...) throw std::runtime_error("unexpected database error")
#define LOG_FATAL(...) throw std::runtime_error("unexpected conversion failure")
#define LOG_INFO(...) ((void)0)
#define LOG_DEBUG(...) ((void)0)
#define LOG_TRACE(...) ((void)0)
