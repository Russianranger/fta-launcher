#pragma once
// Host-only adapter for exact SELECT results captured from a verified private
// MariaDB backup. No database implementation or server code is replaced in APKs.
#include <cstdint>
#include <memory>
#include <string>
#include <type_traits>
#include <vector>

struct Field {
    std::string text;
    template<class T> T Get() const {
        if constexpr (std::is_same_v<T, std::string>) return text;
        else if constexpr (std::is_same_v<T, float>) return std::stof(text);
        else return static_cast<T>(std::stoul(text));
    }
};
struct CapturedQueryResult {
    std::vector<std::vector<Field>> rows;
    std::size_t current = 0;
    unsigned GetFieldCount() const { return rows.at(0).size(); }
    unsigned GetRowCount() const { return rows.size(); }
    Field* Fetch() { return rows.at(current).data(); }
    Field& operator[](unsigned i) { return rows.at(current).at(i); }
    bool NextRow() { return ++current < rows.size(); }
};
using QueryResult = std::shared_ptr<CapturedQueryResult>;
