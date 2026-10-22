#include "process_owner.h"
#include <cassert>
#include <cerrno>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <signal.h>
#include <string>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>

int main() {
    char path[] = "/tmp/fta-owner-XXXXXX";
    const auto* created = mkdtemp(path); assert(created);
    const std::string root(created);
    fta::StartOptions options{"/bin/sh", {"-c", "printf 'ok'; printf 'err' >&2; exit 7"},
        {"PATH=/usr/bin:/bin"}, root, "", root+"/out", root+"/err"};
    const auto done = fta::start(options);
    assert(fta::wait_for(done,5000) == 7);
    assert(fta::wait_for(done,0) == 7);
    assert(fta::stop(done,100) == 7);
    std::string value;
    { std::ifstream file(root+"/out"); file >> value; assert(value=="ok"); }
    { std::ifstream file(root+"/err"); file >> value; assert(value=="err"); }
    struct stat info{}; assert(stat((root+"/out").c_str(),&info)==0); assert((info.st_mode & 0777)==0600);
    options.executable="/bin/sleep"; options.arguments={"30"};
    const auto live=fta::start(options);
    assert(fta::wait_for(live,10)==fta::running);
    assert(fta::stop(live,2000)==143);
    assert(fta::wait_for(live,0)==143);
    sigset_t blocked, previous;
    sigemptyset(&blocked); sigaddset(&blocked,SIGTERM);
    assert(sigprocmask(SIG_BLOCK,&blocked,&previous)==0);
    const auto masked=fta::start(options);
    assert(sigprocmask(SIG_SETMASK,&previous,nullptr)==0);
    assert(fta::stop(masked,2000)==143);
    options.executable="/bin/sh"; options.arguments={"-c","head -c 10000000 /dev/zero"};
    auto loud=fta::start(options); assert(fta::wait_for(loud,10000)==0);
    assert(std::filesystem::file_size(root+"/out")==8*1024*1024);
    // A finished leader cannot leave children in its owned process group.
    assert(prctl(PR_SET_CHILD_SUBREAPER,1)==0);
    options.arguments={"-c","(trap '' TERM; exec /bin/sleep 30) & echo $!; exit 0"};
    auto descendants=fta::start(options); assert(fta::wait_for(descendants,5000)==0);
    pid_t descendant=0; { std::ifstream file(root+"/out"); file >> descendant; } assert(descendant>0);
    int descendant_status=0;
    assert(waitpid(descendant,&descendant_status,0)==descendant);
    assert(WIFSIGNALED(descendant_status) && WTERMSIG(descendant_status)==SIGKILL);
    // Abrupt parent death also terminates its direct native child.
    int report[2]; assert(pipe(report)==0);
    const pid_t owner=fork(); assert(owner>=0);
    if (!owner) {
        close(report[0]);
        options.output=root+"/orphan-out"; options.error=root+"/orphan-err";
        options.arguments={"-c","trap '' TERM; echo $$; exec /bin/sleep 30"};
        fta::start(options);
        pid_t native_child=0;
        for (int i=0;i<100 && native_child<=0;++i) {
            std::ifstream file(options.output); file >> native_child;
            if (native_child<=0) std::this_thread::sleep_for(std::chrono::milliseconds(20));
        }
        const auto ignored=write(report[1],&native_child,sizeof(native_child)); (void)ignored;
        _exit(native_child>0 ? 0 : 1);
    }
    close(report[1]); pid_t orphan=0;
    assert(read(report[0],&orphan,sizeof(orphan))==sizeof(orphan)); close(report[0]);
    int owner_status=0; assert(waitpid(owner,&owner_status,0)==owner && WEXITSTATUS(owner_status)==0);
    assert(orphan>0);
    int orphan_status=0; pid_t observed=0;
    for (int i=0;i<100 && observed==0;++i) {
        observed=waitpid(orphan,&orphan_status,WNOHANG);
        if (!observed) std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    assert(observed==orphan && WIFSIGNALED(orphan_status) && WTERMSIG(orphan_status)==SIGKILL);
    options.executable="/does/not/exist";
    bool refused=false; try { fta::start(options); } catch (...) { refused=true; } assert(refused);
    refused=false; try { fta::stop(99999999,0); } catch (...) { refused=true; } assert(refused);
    options.executable="/bin/true";
    unlink((root+"/out").c_str());
    assert(symlink((root+"/err").c_str(),(root+"/out").c_str())==0);
    refused=false; try { fta::start(options); } catch (...) { refused=true; } assert(refused);
    std::filesystem::remove_all(root);
    std::cout << "Native owner tests: PASS (exit, idempotence, TERM, log bound, IO modes, rejected exec/handles/symlinks)\n";
}
