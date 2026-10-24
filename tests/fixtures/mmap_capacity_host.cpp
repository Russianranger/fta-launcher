// Host-only probe: compile against unchanged Detour from the pinned source ZIP.
// Reproduces MapBuilder's capacity expression; does not generate navigation tiles.
#include "DetourNavMesh.h"
#include <bit>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>

int main(int argc, char** argv)
{
    auto require = [](bool ok) { if (!ok) throw std::runtime_error("original mmap capacity check failed"); };
    static_assert(sizeof(dtPolyRef) == 8 && DT_POLY_BITS == 31);
    static_assert(sizeof(dtNavMeshParams) == 28 && sizeof(int) == 4);
    static_assert(std::endian::native == std::endian::little);
    require(argc == 2);
    // Exact expressions verified against pinned MapBuilder.cpp by the driver.
    int polyBits = DT_POLY_BITS;
    int maxPolysPerTile = 1 << polyBits;
    require(static_cast<uint32_t>(maxPolysPerTile) == 0x80000000U);
    dtNavMeshParams parameters{};
    parameters.tileWidth = 533.33333f;
    parameters.tileHeight = 533.33333f;
    parameters.maxTiles = 4096;
    parameters.maxPolys = maxPolysPerTile;
    auto mesh = dtAllocNavMesh();
    require(mesh && dtStatusSucceed(mesh->init(&parameters)));
    require(mesh->getParams()->maxPolys == maxPolysPerTile);
    dtFreeNavMesh(mesh);
    {
        std::ofstream output(argv[1], std::ios::binary | std::ios::trunc);
        require(static_cast<bool>(output.write(reinterpret_cast<const char*>(&parameters), sizeof(parameters))));
    }
    dtNavMeshParams loaded{};
    std::ifstream input(argv[1], std::ios::binary);
    require(static_cast<bool>(input.read(reinterpret_cast<char*>(&loaded), sizeof(loaded))));
    require(input.peek() == std::ifstream::traits_type::eof());
    require(std::memcmp(&loaded, &parameters, sizeof(loaded)) == 0);
    mesh = dtAllocNavMesh();
    require(mesh && mesh->init(&loaded) == DT_SUCCESS);
    require(mesh->getParams()->maxPolys == maxPolysPerTile);
    dtFreeNavMesh(mesh);
    std::cout << "Original 64-bit Detour accepts and reloads generator maxPolys 0x80000000\n";
}
