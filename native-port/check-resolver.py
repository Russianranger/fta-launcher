#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Check exact reviewed Resolver.h using real Boost 1.83 and a Bionic flag gate.

Host-only regression: the libc interposer models Android's supported AI_MASK,
then delegates strict numeric addresses to host libc with DNS disabled. It is
not execution of Android libc or device qualification.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from fta_source import verify_source_tree

HARNESS = r'''
#include <arpa/inet.h>
#include <cassert>
#include <cerrno>
#include <cstring>
#include <dlfcn.h>
#include <netdb.h>
#include <sys/socket.h>
#include <boost/version.hpp>
#include <boost/asio/ip/tcp.hpp>
#include <boost/asio/post.hpp>
static_assert(BOOST_VERSION == 108300);
#include IO_CONTEXT_HEADER
#if TEST_ANDROID_BRANCH
#define __ANDROID__ 1
#endif
#include RESOLVER_HEADER

namespace {
unsigned queries=0, delegated=0, bad_flags=0, invalid_hosts=0, network_sockets=0;
int last_flags=0, last_family=0, last_type=0, last_protocol=0, last_error=0;
bool null_service=false;
constexpr int android_ai_mask=0x1|0x2|0x4|0x8|0x400;
template<class T> T libc_function(char const* name) {
    auto function=reinterpret_cast<T>(dlsym(RTLD_NEXT,name));
    assert(function); return function;
}
}

extern "C" int socket(int domain,int type,int protocol) noexcept {
    if (domain==AF_INET || domain==AF_INET6 || domain==AF_NETLINK) {
        ++network_sockets; errno=EPERM; return -1;
    }
    using Function=int(*)(int,int,int);
    return libc_function<Function>("socket")(domain,type,protocol);
}

extern "C" int getaddrinfo(char const* host,char const* service,addrinfo const* hints,addrinfo** result) {
    ++queries; assert(hints && result); *result=nullptr;
    last_flags=hints->ai_flags; last_family=hints->ai_family;
    last_type=hints->ai_socktype; last_protocol=hints->ai_protocol;
    null_service=service==nullptr;
    if (hints->ai_flags & ~android_ai_mask) {
        ++bad_flags; return last_error=EAI_BADFLAGS;
    }
    in_addr parsed{};
    if (!host || hints->ai_family!=AF_INET || inet_pton(AF_INET,host,&parsed)!=1) {
        ++invalid_hosts; return last_error=EAI_NONAME;
    }
    if (service) {
        for (auto cursor=service;*cursor;++cursor) assert(*cursor>='0' && *cursor<='9');
    }
    // Host constants remain native except AI_ALL. Force numeric-only libc work.
    addrinfo native_hints=*hints;
    native_hints.ai_flags=AI_NUMERICHOST|AI_NUMERICSERV;
    using Function=int(*)(char const*,char const*,addrinfo const*,addrinfo**);
    ++delegated;
    return last_error=libc_function<Function>("getaddrinfo")(host,service,&native_hints,result);
}

int main() {
    Acore::Asio::IoContext context;
    Acore::Asio::Resolver resolver(context);
    for (auto host:{"127.0.0.1","127.0.0.1","255.255.255.0"}) {
        auto endpoint=resolver.Resolve(boost::asio::ip::tcp::v4(),host,"");
        assert(last_family==AF_INET && last_type==SOCK_STREAM && last_protocol==IPPROTO_TCP && null_service);
#if TEST_ANDROID_BRANCH
        assert(endpoint && endpoint->address().to_string()==host && endpoint->port()==0);
        assert(last_flags==0 && last_error==0);
#else
        assert(!endpoint && last_flags==0x100 && last_error==EAI_BADFLAGS);
#endif
    }
    auto serviced=resolver.Resolve(boost::asio::ip::tcp::v4(),"127.0.0.1","1234");
    assert(!null_service);
#if TEST_ANDROID_BRANCH
    assert(serviced && serviced->port()==1234 && delegated==4 && bad_flags==0);
#else
    assert(!serviced && delegated==0 && bad_flags==4);
#endif
    for (auto host:{"unresolvable.invalid","256.1.1.1","127.0.0.1 ","::1"}) {
        auto before=delegated;
        assert(!resolver.Resolve(boost::asio::ip::tcp::v4(),host,""));
        assert(delegated==before);
#if TEST_ANDROID_BRANCH
        assert(last_flags==0 && last_error==EAI_NONAME);
#else
        assert(last_flags==0x100 && last_error==EAI_BADFLAGS);
#endif
    }
    assert(queries==8 && network_sockets==0);
#if TEST_ANDROID_BRANCH
    assert(invalid_hosts==4);
#else
    assert(invalid_hosts==0 && bad_flags==8);
#endif
    return 0;
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--profile', default='android-arm64-resolver-v3')
    parser.add_argument('--boost-include', type=Path, required=True)
    parser.add_argument('--cxx', default='g++')
    args = parser.parse_args()
    inventory = verify_source_tree(args.source, port_profile=args.profile)
    boost = args.boost_include.resolve(strict=True)
    version = (boost / 'boost/version.hpp').read_text()
    if not re.search(r'^#define BOOST_VERSION 108300$', version, re.M):
        raise ValueError('The explicit Boost include path must provide Boost 1.83.0')
    source = args.source.resolve(strict=True)
    resolver = source / 'src/common/Asio/Resolver.h'
    io_context = source / 'src/common/Asio/IoContext.h'
    with tempfile.TemporaryDirectory(prefix='fta-resolver-') as directory:
        root = Path(directory)
        # The target defines AI_ALL although Android's supported AI_MASK omits it.
        (root / 'netdb.h').write_text('#include_next <netdb.h>\n#undef AI_ALL\n#define AI_ALL 0x100\n')
        harness = root / 'resolver.cpp'
        harness.write_text('#define IO_CONTEXT_HEADER ' + json.dumps(str(io_context)) + '\n' +
                           '#define RESOLVER_HEADER ' + json.dumps(str(resolver)) + '\n' + HARNESS)
        for android in (0, 1):
            binary = root / ('resolver-' + str(android))
            subprocess.run([args.cxx, '-std=c++20', '-O0', '-pthread', '-I' + str(root),
                            '-I' + str(boost), '-I' + str(source / 'src/common/Utilities'),
                            '-DTEST_ANDROID_BRANCH=' + str(android), str(harness), '-ldl', '-o', str(binary)],
                           check=True, timeout=60, cwd=root)
            subprocess.run([str(binary)], check=True, timeout=10, cwd=root)
    print(json.dumps({'status': 'passed', 'cases_per_branch': 8, 'branches': ['non-Android', 'Android'],
                      'network_socket_calls': 0, 'actual_source_tree_sha256': inventory['tree_sha256'],
                      'resolver_header_sha256': hashlib.sha256(resolver.read_bytes()).hexdigest(),
                      'boost_version': '1.83.0', 'boost_version_header_sha256': hashlib.sha256(version.encode()).hexdigest(),
                      'scope': 'exact Resolver.h and real Boost; interposed Android AI_MASK; numeric-only host libc; no DNS/listener/device execution'}))


if __name__ == '__main__':
    main()
