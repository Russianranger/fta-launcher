#pragma once
// Host integration adapter only. Production ResultSet/Field and DBC loaders
// come unchanged from the hash-pinned private source; no captured SQL rows.
#include "QueryResult.h"
#include "MySQLHacks.h"
#include <set>
#include <stdexcept>

struct LiveWorldDatabase {
    MYSQL* connection = nullptr;
    std::set<std::string> exactQueries;
    unsigned calls = 0;
    QueryResult Query(std::string const& query) {
        if (!connection || !exactQueries.contains(query))
            throw std::runtime_error("unexpected original SQL query");
        ++calls;
        if (mysql_query(connection, query.c_str()))
            throw std::runtime_error("native SQL query failed");
        auto raw = mysql_store_result(connection);
        if (!raw) throw std::runtime_error("native SQL result missing");
        auto result = std::make_shared<ResultSet>(reinterpret_cast<MySQLResult*>(raw),
            reinterpret_cast<MySQLField*>(mysql_fetch_fields(raw)),
            mysql_num_rows(raw), mysql_num_fields(raw));
        // Same first-row ownership contract as DatabaseWorkerPool::Query.
        if (!result->GetRowCount() || !result->NextRow()) return nullptr;
        return result;
    }
};
extern LiveWorldDatabase WorldDatabase;
