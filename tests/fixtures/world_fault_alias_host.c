// SPDX-License-Identifier: MIT
#include "../../native/world-fault/world_fault.c"
int main(void) {
    if (image_id("") != 1 || image_id("/apk/libfta_worldserver.so") != 1 ||
        image_id("/apk/libfta_world_session.so") != 1 ||
        image_id("/apk/libfta_world_fault.so") != 3 || image_id("/apk/libfta_world_fault_v2.so") != 3 ||
        image_id("/apk/unrelated.so") != 0 || image_id("/apk/libfta_world_session.so.extra") != 0) return 1;
    return 0;
}
