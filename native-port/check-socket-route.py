#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Exercise exact reviewed connection/updater bodies with inert C API stubs."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from fta_source import verify_source_tree


def functions(source):
    connection = (source / 'src/server/database/Database/MySQLConnection.cpp').read_text()
    start = connection.index('uint32 MySQLConnection::Open()')
    opened = connection[start:connection.index('\nbool MySQLConnection::PrepareStatements()', start)]
    updater = (source / 'src/server/database/Updater/DBUpdater.cpp').read_text()
    start = updater.index('template<class T>\nvoid DBUpdater<T>::ApplyFile(DatabaseWorkerPool<T>& pool, std::string const& host,')
    applied = updater[start:updater.index('\ntemplate class AC_DATABASE_API DBUpdater', start)]
    return opened, applied


STUBS = r'''
#include <algorithm>
#include <cassert>
#include <charconv>
#include <chrono>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <limits>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <type_traits>
#include <vector>
using uint32 = std::uint32_t;
using Milliseconds = std::chrono::milliseconds;
using Path = std::filesystem::path;
#define MARIADB_BASE_VERSION "fixture"
#define LOG_ERROR(...) ((void)0)
#define LOG_INFO(...) ((void)0)
#define LOG_FATAL(...) ((void)0)
enum { MYSQL_SET_CHARSET_NAME, MYSQL_OPT_PROTOCOL, MYSQL_PROTOCOL_SOCKET, MYSQL_PROTOCOL_PIPE, CR_UNKNOWN_ERROR=2000 };
struct MYSQL {};
using MySQLHandle = MYSQL;
MYSQL handle;
struct ConnectCall { std::string host, socket; unsigned port, protocol; };
std::vector<ConnectCall> connects;
unsigned protocol = 0;
unsigned closed = 0;
MYSQL* mysql_init(void*) { protocol=0; return &handle; }
int mysql_options(MYSQL*, int option, void const* value) {
    if (option==MYSQL_OPT_PROTOCOL) protocol=*static_cast<unsigned const*>(value);
    return 0;
}
MYSQL* mysql_real_connect(MYSQL* ptr, char const* host, char const*, char const*, char const*, unsigned port, char const* socket, unsigned long) {
    connects.push_back({host ? host : "", socket ? socket : "", port, protocol}); return ptr;
}
void mysql_close(MYSQL*) { ++closed; }
char const* mysql_get_client_info() { return "fixture"; }
char const* mysql_get_server_info(MYSQL*) { return "fixture"; }
char const* mysql_error(MYSQL*) { return "fixture"; }
unsigned mysql_errno(MYSQL*) { return CR_UNKNOWN_ERROR; }
int mysql_autocommit(MYSQL*, int) { return 0; }
int mysql_set_character_set(MYSQL*, char const*) { return 0; }
struct MySQLConnectionInfo { std::string host, port_or_socket, user="fixture", password="fixture", database="fixture", ssl=""; };
class MySQLConnection {
public:
    MySQLConnectionInfo& m_connectionInfo;
    bool m_reconnecting=false;
    MySQLHandle* m_Mysql=nullptr;
    explicit MySQLConnection(MySQLConnectionInfo& value):m_connectionInfo(value) {}
    uint32 Open();
};
struct Config {
    std::string temporary;
    template<class T> T GetOption(char const*, T fallback) {
        if constexpr (std::is_same_v<T,std::string>) return temporary;
        else return fallback;
    }
    bool isDryRun() { return true; }
} config;
Config* sConfigMgr=&config;
struct UpdateException:std::runtime_error { using std::runtime_error::runtime_error; };
namespace DBUpdaterUtil {
    unsigned failures=0;
    std::string GetCorrectedMySQLExecutable() { return "/fixture/client"; }
    void MarkUpdateFailed() { ++failures; }
}
std::vector<std::string> client_args;
std::string client_input;
namespace Acore {
    template<class T> std::optional<T> StringTo(std::string_view value) {
        T parsed{};
        auto result=std::from_chars(value.data(),value.data()+value.size(),parsed);
        if (result.ec!=std::errc() || result.ptr!=value.data()+value.size()) return std::nullopt;
        return parsed;
    }
    namespace String {
        std::string AddSuffixIfNotExists(std::string value,char suffix) {
            if (value.empty() || value.back()!=suffix) value+=suffix;
            return value;
        }
    }
    int StartProcess(std::string const& exe,std::vector<std::string> const& args,std::string const&,std::string input,bool secure) {
        assert(exe=="/fixture/client" && secure); client_args=args; client_input=input; return 0;
    }
}
template<class T> struct DatabaseWorkerPool {
    MySQLConnectionInfo* info;
    MySQLConnectionInfo* GetConnectionInfo() { return info; }
};
template<class T> struct DBUpdater {
    static void ApplyFile(DatabaseWorkerPool<T>&,std::string const&,std::string const&,std::string const&,std::string const&,std::string const&,std::string const&,Path const&);
};
'''

CASES = r'''
bool has(std::string const& argument) { return std::find(client_args.begin(),client_args.end(),argument)!=client_args.end(); }
int main(int argc,char** argv) {
    assert(argc==2); config.temporary=argv[1];
    std::string socket="/fixture/private auth/database.sock";
    MySQLConnectionInfo shared{".",socket};
    MySQLConnection first(shared),second(shared);
    for (int index=0;index<4;++index) {
        first.m_reconnecting=index>0;
        second.m_reconnecting=index>0;
        for (auto connection:{&first,&second}) {
            auto before=connects.size();
            assert(connection->Open()==0 && connects.size()==before+1);
            auto const& call=connects.back();
            assert(call.host=="localhost" && call.port==0 && call.socket==socket && call.protocol==MYSQL_PROTOCOL_SOCKET);
            assert(shared.host=="." && shared.port_or_socket==socket);
        }
    }
    for (auto port:{"1","3306","65535"}) {
        MySQLConnectionInfo info{"127.0.0.1",port}; MySQLConnection connection(info);
        auto before=connects.size(); assert(connection.Open()==0 && connects.size()==before+1);
        auto const& call=connects.back();
        assert(call.host=="127.0.0.1" && call.port==std::stoul(port) && call.socket.empty() && call.protocol!=MYSQL_PROTOCOL_SOCKET);
        assert(info.host=="127.0.0.1" && info.port_or_socket==port);
    }
    for (auto port:{"","abc","3306tail","-1","+3306"," 3306","3306 ","65536","4294967295","4294967296","99999999999999999999999999","/fixture/database.sock"}) {
        MySQLConnectionInfo info{"127.0.0.1",port}; MySQLConnection connection(info);
        auto before=connects.size(); auto closes=closed;
        assert(connection.Open()!=0 && connects.size()==before && closed==closes+1);
        assert(info.host=="127.0.0.1" && info.port_or_socket==port);
    }
    MySQLConnectionInfo empty_socket{".",""}; MySQLConnection empty(empty_socket);
    auto before=connects.size(); auto closes=closed;
    assert(empty.Open()!=0 && connects.size()==before && closed==closes+1 && empty_socket.host==".");
    DatabaseWorkerPool<int> pool{&shared};
    Path input=Path(config.temporary)/"fixture-input.sql";
    DBUpdater<int>::ApplyFile(pool,shared.host,shared.user,shared.password,shared.port_or_socket,shared.database,shared.ssl,input);
    assert(has("-hlocalhost") && has("-P0") && has("--protocol=SOCKET") && has("-S"+socket));
    assert(!has("-h.") && !has("--protocol=TCP") && client_input==input.generic_string());
    assert(client_args.front().find("--defaults-extra-file=")==0 && shared.host==".");
    assert(has("-u"+shared.user) && has(shared.database) && has("--default-character-set=utf8") && has("--max-allowed-packet=1GB"));
    MySQLConnectionInfo relative{".","3306"}; MySQLConnection numeric_socket(relative);
    assert(numeric_socket.Open()==0 && connects.back().host=="localhost" && connects.back().port==0 && connects.back().socket=="3306");
    pool.info=&relative;
    DBUpdater<int>::ApplyFile(pool,relative.host,relative.user,relative.password,relative.port_or_socket,relative.database,relative.ssl,input);
    assert(has("-hlocalhost") && has("-P0") && has("--protocol=SOCKET") && has("-S3306") && !has("-P3306"));
    MySQLConnectionInfo tcp{"127.0.0.1","3306"}; pool.info=&tcp;
    DBUpdater<int>::ApplyFile(pool,tcp.host,tcp.user,tcp.password,tcp.port_or_socket,tcp.database,tcp.ssl,input);
    assert(has("-h127.0.0.1") && has("-P3306") && !has("--protocol=SOCKET") && !has("-S3306"));
    assert(DBUpdaterUtil::failures==0);
    return 0;
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--profile', default='android-arm64-socket-v2')
    parser.add_argument('--cxx', default='g++')
    args = parser.parse_args()
    inventory = verify_source_tree(args.source, port_profile=args.profile)
    opened, applied = functions(args.source)
    with tempfile.TemporaryDirectory(prefix='fta-socket-route-') as directory:
        root = Path(directory)
        source, binary = root / 'socket-route.cpp', root / 'socket-route-test'
        source.write_text(STUBS + '\n' + opened + '\n' + applied + '\n' + CASES)
        subprocess.run([args.cxx, '-std=c++20', '-O0', '-Wall', '-Wextra', '-Wno-unused-parameter', str(source), '-o', str(binary)], check=True, timeout=60, cwd=root)
        subprocess.run([str(binary), directory], check=True, timeout=10, cwd=root)
    print(json.dumps({'status': 'passed', 'socket_opens': 9, 'tcp_ports': 3, 'rejected_ports': 12,
                      'rejected_empty_sockets': 1, 'updater_routes': 3, 'actual_source_tree_sha256': inventory['tree_sha256'],
                      'body_sha256': [hashlib.sha256(body.encode()).hexdigest() for body in (opened, applied)],
                      'scope': 'compiled exact connection/updater functions; stub C API/process; no DB or SQL execution'}))


if __name__ == '__main__':
    main()
