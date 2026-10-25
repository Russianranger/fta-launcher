#include "Field.h"
#include <array>
#include <iostream>
#include <limits>
#include <memory>
#include <stdexcept>
#include <unordered_map>

unsigned warnings = 0;
std::string GetDebugInfo() { return {}; }
namespace Acore {
[[noreturn]] void Assert(std::string_view, uint32, std::string_view, std::string_view,
    std::string_view, std::string_view) { std::abort(); }
}
static void require(bool ok) {
    if (!ok) throw std::runtime_error("auction snapshot fixture failed");
}
// The original Field grants this adapter friendship. Metadata and rows are
// synthetic; the original non-prepared text conversion runs unchanged.
class ResultSet {
    std::array<QueryResultFieldMetadata, 5> metadata;
    std::array<Field, 5> fields;
    std::array<std::string, 5> values;
    std::vector<uint64> counts;
    std::size_t index = 0;
public:
    explicit ResultSet(std::vector<uint64> input) : counts(std::move(input)) {
        for (uint32 i = 0; i < 5; ++i) {
            metadata[i].Index = i;
            metadata[i].Type = i == 4 ? DatabaseFieldTypes::Int64 : DatabaseFieldTypes::Int32;
            metadata[i].TypeName = i == 4 ? "LONGLONG" : "LONG";
            metadata[i].Alias = i == 4 ? "COUNT(*)" : "fixture";
            fields[i].SetMetadata(&metadata[i]);
        }
    }
    Field* Fetch() {
        values = {"2", "3", "17", "1", std::to_string(counts.at(index))};
        for (unsigned i = 0; i < 5; ++i)
            fields[i].SetStructuredValue(values[i].data(), values[i].size());
        return fields.data();
    }
    bool NextRow() { return ++index < counts.size(); }
};
using QueryResult = std::shared_ptr<ResultSet>;
struct Database {
    std::string database = "fixture_characters";
    QueryResult result;
    std::string query;
    Database const* GetConnectionInfo() const { return this; }
    QueryResult Query(std::string const& text) { query = text; return result; }
} CharacterDatabase, WorldDatabase;
enum class AuctionatorItemOrigin { Unknown, Fixture };
struct Auctionator {
    AuctionatorItemOrigin ResolveItemOrigin(uint32 item, uint32 level) const {
        require(item == 17 && level == 1); return AuctionatorItemOrigin::Fixture;
    }
};
static uint32 GetOriginIndex(AuctionatorItemOrigin origin) { return static_cast<uint32>(origin); }
class AuctionatorSeller {
public:
    struct AuctionHouseSnapshot {
        std::unordered_map<uint64, uint32> categoryCounts;
        std::unordered_map<uint32, uint32> itemCounts;
        std::array<uint32, 5> originCounts{};
    };
    Auctionator* nator = nullptr;
    uint64 MakeCategoryKey(uint32 itemClass, uint32 subclass, AuctionatorItemOrigin origin) const {
        require(itemClass == 2 && subclass == 3);
        return 100 + GetOriginIndex(origin);
    }
    AuctionHouseSnapshot BuildAuctionHouseSnapshot(uint32 houseId) const;
};
#include "snapshot.inc"

int main(int argc, char** argv) {
    try {
        require(argc == 2);
        unsigned const warningsPerRow = std::stoul(argv[1]);
        Auctionator nator;
        AuctionatorSeller seller;
        for (bool populated : {false, true}) {
            seller.nator = populated ? &nator : nullptr;
            WorldDatabase.result.reset();
            auto empty = seller.BuildAuctionHouseSnapshot(7);
            require(empty.itemCounts.empty() && empty.categoryCounts.empty());
            std::vector<uint64> counts{0, 1, 17, UINT32_MAX, uint64(UINT32_MAX) + 1, UINT64_MAX};
            uint32 total = 0;
            for (auto count : counts) total += static_cast<uint32>(count);
            WorldDatabase.result = std::make_shared<ResultSet>(counts);
            unsigned const before = warnings;
            auto snapshot = seller.BuildAuctionHouseSnapshot(7);
            auto origin = populated ? AuctionatorItemOrigin::Fixture : AuctionatorItemOrigin::Unknown;
            require(snapshot.itemCounts.size() == 1 && snapshot.itemCounts.at(17) == total);
            require(snapshot.categoryCounts.size() == 1 && snapshot.categoryCounts.at(100 + GetOriginIndex(origin)) == total);
            for (unsigned i = 0; i < 5; ++i)
                require(snapshot.originCounts[i] == (i == GetOriginIndex(origin) ? total : 0));
            require(warnings - before == counts.size() * warningsPerRow);
            require(WorldDatabase.query.find("COUNT(*)") != std::string::npos);
            require(WorldDatabase.query.find("fixture_characters.auctionhouse") != std::string::npos);
            require(WorldDatabase.query.find("ah.houseid = 7") != std::string::npos);
        }
        std::cout << "rows=12 warnings=" << warnings << " snapshot-equivalent=true\n";
    } catch (...) {
        std::cerr << "auction snapshot fixture failed\n";
        return 1;
    }
}
