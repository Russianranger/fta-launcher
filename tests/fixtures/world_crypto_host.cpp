// Host-only driver. The original ARC4, provider setup and assertion code are compiled unchanged.
#include "ARC4.h"
#include "OpenSSLCrypto.h"
#include <array>
#include <cstdio>
#include <sys/resource.h>

int main()
{
    const rlimit limit{0, 0};
    if (setrlimit(RLIMIT_CORE, &limit) != 0) return 2;
    OpenSSLCrypto::threadsSetup();
    {
        Acore::Crypto::ARC4 cipher;
        const std::array<uint8, 3> key{'K', 'e', 'y'};
        std::array<uint8, 9> data{'P', 'l', 'a', 'i', 'n', 't', 'e', 'x', 't'};
        cipher.Init(key);
        cipher.UpdateData(data);
        for (auto byte : data) std::printf("%02x", byte);
        std::puts("");
    }
    OpenSSLCrypto::threadsCleanup();
}
