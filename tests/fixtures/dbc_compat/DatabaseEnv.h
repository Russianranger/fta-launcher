#pragma once
#include "QueryResult.h"
#include <fstream>
#include <map>
#include <stdexcept>

struct CapturedWorldDatabase {
    std::map<std::string, std::string> exactQueries;
    bool empty = false;
    unsigned calls = 0;
    QueryResult Query(std::string const& query) {
        ++calls;
        auto found = exactQueries.find(query);
        if (found == exactQueries.end()) throw std::runtime_error("unexpected original SQL query");
        if (empty) return nullptr;
        auto result = std::make_shared<CapturedQueryResult>();
        std::ifstream input(found->second);
        std::string line;
        while (std::getline(input, line)) {
            auto tab = line.find('\t');
            if (tab == std::string::npos || line.find('\t', tab + 1) != std::string::npos)
                throw std::runtime_error("invalid private two-column result");
            result->rows.push_back({Field{line.substr(0, tab)}, Field{line.substr(tab + 1)}});
        }
        if (result->rows.empty()) throw std::runtime_error("missing captured query rows");
        return result;
    }
};
extern CapturedWorldDatabase WorldDatabase;
