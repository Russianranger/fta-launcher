#include "DBCStore.h"
#include "DatabaseEnv.h"
#include <bit>
#include <cmath>
#include <fstream>
#include <iostream>
#include <sstream>

std::string GetDebugInfo() { return {}; }
namespace Acore {
[[noreturn]] void Assert(std::string_view, uint32, std::string_view, std::string_view,
                        std::string_view, std::string_view) { std::abort(); }
}
LiveWorldDatabase WorldDatabase;
struct GtRecord { float value; };
static void require(bool ok) {
    if (!ok) throw std::runtime_error("live original DBC qualification failed");
}

int main(int argc, char** argv) {
    try {
        // Credentials remain in a mode-0600 file, never argv/stdout/stderr.
        require(argc == 7);
        std::string mode = argv[6]; require(mode == "live" || mode == "empty");
        auto connection = mysql_init(nullptr); require(connection);
        WorldDatabase.connection = connection;
        unsigned timeout = 10, protocol = MYSQL_PROTOCOL_TCP;
        require(mysql_options(connection, MYSQL_READ_DEFAULT_FILE, argv[1]) == 0);
        require(mysql_options(connection, MYSQL_OPT_PROTOCOL, &protocol) == 0);
        require(mysql_options(connection, MYSQL_OPT_CONNECT_TIMEOUT, &timeout) == 0);
        require(mysql_options(connection, MYSQL_OPT_READ_TIMEOUT, &timeout) == 0);
        require(mysql_options(connection, MYSQL_OPT_WRITE_TIMEOUT, &timeout) == 0);
        require(mysql_options(connection, MYSQL_SET_CHARSET_NAME, "utf8") == 0);
        require(mysql_real_connect(connection, "127.0.0.1", nullptr, nullptr,
            mode == "live" ? "acore_world" : "fta_dbc_empty", std::stoul(argv[2]), nullptr, 0));
        require(std::string(mysql_get_server_info(connection)) == "12.3.2-MariaDB");
        require(std::string(mysql_get_client_info()) == "3.4.9");
        std::ifstream manifest(argv[3]); require(manifest.good());
        std::string filename, table; unsigned rows, tables = 0, checked = 0;
        while (manifest >> filename >> table >> rows) {
            auto query = "SELECT * FROM `" + table + "` ORDER BY `ID` DESC";
            WorldDatabase.exactQueries.insert(query);
            DBCStorage<GtRecord> store("df");
            require(!store.Load((std::string(argv[4]) + "/" + filename).c_str()));
            require(store.GetFieldCount() == 1 && store.GetNumRows() == 0);
            store.LoadFromDB(table.c_str(), store.GetFormat());
            if (mode == "empty") {
                require(store.GetNumRows() == 0 && store.LookupEntry(0) == nullptr);
            } else {
                require(store.GetNumRows() == rows && store.LookupEntry(rows) == nullptr);
                // Independent CLI snapshot; compare every decoded IEEE-754 value.
                std::ifstream expected(std::string(argv[5]) + "/" + table + ".tsv");
                require(expected.good());
                std::string line; unsigned next = rows;
                while (std::getline(expected, line)) {
                    require(next > 0); auto tab = line.find('\t');
                    require(tab != std::string::npos && line.find('\t', tab + 1) == std::string::npos);
                    auto id = std::stoul(line.substr(0, tab)); require(id == --next);
                    float wanted = std::stof(line.substr(tab + 1));
                    auto actual = store.LookupEntry(id);
                    require(actual && std::isfinite(wanted) && std::isfinite(actual->value));
                    require(std::bit_cast<uint32>(actual->value) == std::bit_cast<uint32>(wanted));
                    ++checked;
                }
                require(next == 0);
            }
            ++tables;
        }
        require(manifest.eof() && tables == 10 && WorldDatabase.calls == 10);
        require(checked == (mode == "live" ? 8922u : 0u));
        mysql_close(connection); WorldDatabase.connection = nullptr;
        std::cout << (mode == "live" ? "liveTables=10 rows=8922" : "emptyTables=10 rows=0") << '\n';
        return 0;
    } catch (...) {
        // Deliberately omit driver/row strings from public failure output.
        std::cerr << "live original DBC qualification failed\n"; return 1;
    }
}
