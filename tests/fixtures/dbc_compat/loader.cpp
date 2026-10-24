#include "DBCFileLoader.h"
#include "DBCStore.h"
#include "DBCfmt.h"
#include "DatabaseEnv.h"
#include <cmath>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>

std::string GetDebugInfo() { return {}; }
namespace Acore {
[[noreturn]] void Assert(std::string_view, uint32, std::string_view, std::string_view,
                        std::string_view, std::string_view) { std::abort(); }
}
CapturedWorldDatabase WorldDatabase;
struct GtRecord { float value; };
struct PowerRecord { uint32 id; uint32 type; };
static void require(bool value) { if (!value) throw std::runtime_error("original DBC compatibility probe failed"); }

int main(int argc, char** argv)
{
    require(argc == 4);
    std::string assets = argv[1], snapshots = argv[2];
    std::ifstream manifest(argv[3]);
    std::string filename, table; unsigned rows, checked = 0, tables = 0;
    while (manifest >> filename >> table >> rows) {
        auto query = "SELECT * FROM `" + table + "` ORDER BY `ID` DESC";
        WorldDatabase.exactQueries[query] = snapshots + "/" + table + ".tsv";
        DBCStorage<GtRecord> store("df");
        require(!store.Load((assets + "/dbc/" + filename).c_str()));
        require(store.GetFieldCount() == 1 && store.GetNumRows() == 0);
        // Exact LoadDBC decision sequence is checked against pinned source by driver.
        store.LoadFromDB(table.c_str(), store.GetFormat());
        require(store.GetNumRows() == rows);
        auto expected = WorldDatabase.Query(query);
        do {
            auto fields = expected->Fetch();
            auto id = fields[0].Get<uint32>(); auto wanted = fields[1].Get<float>();
            auto actual = store.LookupEntry(id);
            require(actual && std::isfinite(wanted) && actual->value == wanted);
            ++checked;
        } while (expected->NextRow());
        require(store.LookupEntry(rows) == nullptr);
        // The native fallback does not turn an empty table into a passed load.
        WorldDatabase.empty = true;
        DBCStorage<GtRecord> absent("df");
        require(!absent.Load((assets + "/dbc/" + filename).c_str()));
        absent.LoadFromDB(table.c_str(), absent.GetFormat());
        require(absent.GetNumRows() == 0 && absent.LookupEntry(0) == nullptr);
        WorldDatabase.empty = false; ++tables;
    }
    require(tables == 10 && checked == 8922);
    DBCStorage<PowerRecord> power(PowerDisplayfmt);
    require(power.Load((assets + "/dbc/PowerDisplay.dbc").c_str()));
    for (unsigned i = 0; i < 7; ++i) {
        auto record = power.LookupEntry(i + 1);
        require(record && record->id == i + 1 && record->type == i + 100);
    }
    DBCFileLoader unused;
    require(unused.Load((assets + "/dbc/CharHairTextures.dbc").c_str(), "xxxxxxxx"));
    require(unused.GetCols() == 8 && unused.GetNumRows() == 181);
    std::cout << "Original loaders: 10 SQL-backed GT tables, 8922 exact rows, empty fallback refusal, packed PowerDisplay and unused zero-string header passed\n";
}
