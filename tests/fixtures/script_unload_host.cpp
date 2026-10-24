// SPDX-License-Identifier: MIT
// Original registry/dispatch/unload code is supplied privately by the runner.
#include <cassert>
#include <cstdint>
#include <map>
#include <string>
#include <utility>
#include <vector>
#include "TypeList.h"
using uint16 = uint16_t;
using uint32 = uint32_t;
#define ASSERT(x, ...) assert(x)
#define LOG_ERROR(...) std::abort()
struct Object {};
struct ObjectMgr { uint32 GetScriptId(char const*) { return 1; } } objectMgr;
#define sObjectMgr (&objectMgr)
struct ScriptMgr {
    void IncreaseScriptCount() { }
    void OnDestructObject(Object*);
    void Unload();
} manager;
#define sScriptMgr (&manager)
#include "registry.inc"
#include "misc-enum.inc"
static int calls, deleted;
static bool reentrant;
struct FixtureScript {
    virtual ~FixtureScript() { ++deleted; }
    virtual void OnDestructObject(Object*) { ++calls; }
    uint16 GetTotalAvailableHooks() { return MISCHOOK_END; }
    bool isAfterLoadScript() { return false; }
    bool IsDatabaseBound() const { return false; }
    void checkValidity() { }
    std::string const& GetName() const { static std::string name("fixture"); return name; }
};
struct FirstScript : FixtureScript {};
struct MiscScript : FixtureScript {};
struct LastScript : FixtureScript {
    ~LastScript() override { if (reentrant) manager.OnDestructObject(&object); }
    Object object;
};
template<typename T> struct Info { using type = T; };
using ScriptRegistryTypes = Acore::type_list<Info<FirstScript>, Info<MiscScript>, Info<LastScript>>;
int* SpellSummary = nullptr;
#include "dispatch.inc"
#include "unload.inc"
int main(int argc, char** argv) {
    assert(argc == 2);
    std::string mode(argv[1]); reentrant = mode == "reentrant";
    ScriptRegistry<FirstScript>::AddScript(new FirstScript(), {0});
    ScriptRegistry<MiscScript>::AddScript(new MiscScript(), {MISCHOOK_ON_DESTRUCT_OBJECT});
    ScriptRegistry<LastScript>::AddScript(new LastScript(), {0});
    Object object;
    for (int i = 0; i < 32; ++i) manager.OnDestructObject(&object);
    assert(calls == 32);
    manager.Unload();
    assert(deleted == 3);
    if (mode == "late") manager.OnDestructObject(&object);
    if (mode != "active") {
        assert(calls == 32);
        Acore::for_each<ScriptRegistryTypes>([]<typename I>() {
            assert(ScriptRegistry<typename I::type>::ScriptPointerList.empty());
            assert(ScriptRegistry<typename I::type>::EnabledHooks.size() == MISCHOOK_END);
            for (auto const& hooks : ScriptRegistry<typename I::type>::EnabledHooks) assert(hooks.empty());
        });
    }
}
