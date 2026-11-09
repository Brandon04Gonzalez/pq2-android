//! Persona Q2 for Android. The game's code, recompiled by 3dsrecomp to
//! native code, and Zakuro doing everything else: the kernel, the services,
//! the GPU and the sound. The game's files are read from the copy the player
//! chose. Without the linked feature the APK holds no game code: the phone
//! recompiles the player's own game the first time it starts.

#[cfg(feature = "linked")]
use std::ffi::c_void;
#[cfg(feature = "linked")]
use std::ptr::addr_of;

use winit::platform::android::activity::AndroidApp;

/// Persona Q2: New Cinema Labyrinth (USA).
#[cfg(feature = "linked")]
const PROGRAM_ID: u64 = 0x0004_0000_001D_7100;

#[cfg(feature = "linked")]
extern "C" {
    static recomp_abi: u32;
    static recomp_entry_count: u32;
    static recomp_entries: u8;
    static recomp_module_count: u32;
    static recomp_modules: u8;
}

#[no_mangle]
fn android_main(app: AndroidApp) {
    // SAFETY: the tables 3dsrecomp wrote beside the code build.rs links in
    #[cfg(feature = "linked")]
    let code = Some(unsafe {
        zakuro::Linked::new(
            PROGRAM_ID,
            recomp_abi,
            addr_of!(recomp_entries) as *const c_void,
            recomp_entry_count,
            addr_of!(recomp_modules) as *const c_void,
            recomp_module_count,
        )
    });
    // the APK holds no game code: zakuro recompiles the player's game on the
    // phone the first time, with the C compiler the app ships (ZAKURO_CC)
    #[cfg(not(feature = "linked"))]
    let code = None;
    zakuro::run_android(app, code);
}
