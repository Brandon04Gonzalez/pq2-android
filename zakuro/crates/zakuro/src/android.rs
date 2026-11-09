//! Android: what the Java side hands over before the game starts, the
//! controls it draws over the screens, and where the screens are so that it
//! can place the controls around them and touch the bottom one.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicU64, Ordering};

use zakuro_core::services::hid::{InputState, PadState};

/// the buttons the on-screen controls hold, PadState's bits.
static BUTTONS: AtomicU32 = AtomicU32::new(0);
/// the buttons a controller holds, from its key events.
static PAD_BUTTONS: AtomicU32 = AtomicU32::new(0);
/// the circle pad, -1000 to 1000 on each axis.
static CIRCLE_X: AtomicI32 = AtomicI32::new(0);
static CIRCLE_Y: AtomicI32 = AtomicI32::new(0);
/// the bottom screen touched, the top bit set while it is, then x and y.
static TOUCH: AtomicU32 = AtomicU32::new(0);
/// the menu over the game was asked for, and whether it is open.
static MENU_REQUEST: AtomicBool = AtomicBool::new(false);
static MENU_OPEN: AtomicBool = AtomicBool::new(false);
static RUNNING: AtomicBool = AtomicBool::new(false);
/// the screens' places in the window, in pixels, 16 bits each of x, y,
/// width and height, none while they are not shown.
static TOP_RECT: AtomicU64 = AtomicU64::new(0);
static BOTTOM_RECT: AtomicU64 = AtomicU64::new(0);
/// frames shown a second, as f32 bits, for the overlay's counter.
static FPS: AtomicU32 = AtomicU32::new(0);
/// how many times the console's resolution the 3D is drawn at, for the
/// overlay to show with the frames, 0 in software.
static SCALE: AtomicU32 = AtomicU32::new(0);

/// the window's size the presenter last heard of.
static SIZE: AtomicU64 = AtomicU64::new(0);

/// whether the window is no longer the size the presenter was told, which
/// it then is. a turned phone resizes the window without always saying so.
pub fn resized(width: u32, height: u32) -> bool {
    let size = (width as u64) << 32 | height as u64;
    SIZE.swap(size, Ordering::Relaxed) != size
}

/// the game and the data folder the Java side picked, from the environment
/// it set before the library loaded, or else from the launch.txt it wrote in
/// the app's own folder, the game's path on its first line and the data
/// folder's on the second. a bridge running ARM code on another processor
/// keeps an environment of its own, which the Java side's never reaches.
pub fn launch(internal: Option<&std::path::Path>) -> (Option<String>, Option<PathBuf>) {
    let rom = std::env::var("ZAKURO_ROM").ok().filter(|rom| !rom.is_empty());
    let data = std::env::var_os("ZAKURO_DATA_DIR").filter(|dir| !dir.is_empty()).map(PathBuf::from);
    if rom.is_some() && data.is_some() {
        environment_file(data.as_deref());
        return (rom, data);
    }
    let written = internal.and_then(|dir| std::fs::read_to_string(dir.join("launch.txt")).ok()).unwrap_or_default();
    let mut lines = written.lines().map(str::trim).filter(|line| !line.is_empty());
    let (file_rom, file_data) = (lines.next().map(str::to_owned), lines.next().map(PathBuf::from));
    let data = data.or(file_data);
    environment_file(data.as_deref());
    (rom.or(file_rom), data)
}

/// env.txt in the data folder, one NAME=value a line (# starts a comment),
/// set before the game starts, for tests on a phone (ZAKURO_CPU_SHADERS=1 to
/// compare, ZAKURO_LONGEST_TRANSLATED, RUST_LOG, ...).
fn environment_file(data: Option<&std::path::Path>) {
    let Some(text) = data.and_then(|dir| std::fs::read_to_string(dir.join("env.txt")).ok()) else { return };
    for line in text.lines().map(str::trim).filter(|line| !line.is_empty() && !line.starts_with('#')) {
        if let Some((name, value)) = line.split_once('=') {
            // SAFETY: before the game's threads start, nothing else reads the
            // environment meanwhile
            #[allow(unused_unsafe)]
            unsafe {
                std::env::set_var(name.trim(), value.trim());
            }
            log::info!("env.txt: {}={}", name.trim(), value.trim());
        }
    }
}

/// the input with the on-screen controls' and the controller's added.
pub fn apply(mut state: InputState) -> InputState {
    let held = BUTTONS.load(Ordering::Relaxed) | PAD_BUTTONS.load(Ordering::Relaxed);
    state.buttons |= PadState::from_bits_truncate(held);
    let (x, y) = (CIRCLE_X.load(Ordering::Relaxed), CIRCLE_Y.load(Ordering::Relaxed));
    if x != 0 || y != 0 {
        state.circle_x = x as f32 / 1000.0;
        state.circle_y = y as f32 / 1000.0;
    }
    let touch = TOUCH.load(Ordering::Relaxed);
    if touch & (1 << 31) != 0 {
        state.touch = Some((((touch >> 16) & 0x7FFF) as u16, (touch & 0xFFFF) as u16));
    }
    state
}

/// takes the menu's request, once.
pub fn take_menu_request() -> bool {
    MENU_REQUEST.swap(false, Ordering::Relaxed)
}

pub fn set_state(running: bool, menu_open: bool, fps: f32, scale: u32) {
    RUNNING.store(running, Ordering::Relaxed);
    MENU_OPEN.store(menu_open, Ordering::Relaxed);
    FPS.store(fps.to_bits(), Ordering::Relaxed);
    SCALE.store(scale, Ordering::Relaxed);
}

/// where the screens are now, for the controls around them.
pub fn set_screens(top: Option<zakuro_gpu::Viewport>, bottom: Option<zakuro_gpu::Viewport>) {
    let pack = |viewport: Option<zakuro_gpu::Viewport>| {
        viewport.map_or(0, |v| {
            let field = |value: f32| (value.max(0.0).round() as u64).min(0xFFFF);
            field(v.x) << 48 | field(v.y) << 32 | field(v.width) << 16 | field(v.height)
        })
    };
    TOP_RECT.store(pack(top), Ordering::Relaxed);
    BOTTOM_RECT.store(pack(bottom), Ordering::Relaxed);
}

/// a controller's button, by Android's key code, as the console's.
pub fn pad_key(code: u32, pressed: bool) -> bool {
    let button = match code {
        96 => PadState::A,       // KEYCODE_BUTTON_A, the bottom face button
        97 => PadState::B,       // KEYCODE_BUTTON_B, the right one
        99 => PadState::X,       // KEYCODE_BUTTON_X
        100 => PadState::Y,      // KEYCODE_BUTTON_Y
        102 => PadState::L,      // KEYCODE_BUTTON_L1
        103 => PadState::R,      // KEYCODE_BUTTON_R1
        104 => PadState::ZL,     // KEYCODE_BUTTON_L2
        105 => PadState::ZR,     // KEYCODE_BUTTON_R2
        108 => PadState::START,  // KEYCODE_BUTTON_START
        109 => PadState::SELECT, // KEYCODE_BUTTON_SELECT
        19 => PadState::UP,      // KEYCODE_DPAD_UP
        20 => PadState::DOWN,
        21 => PadState::LEFT,
        22 => PadState::RIGHT,
        _ => return false,
    };
    // Android's A is the bottom face button and Nintendo's the right one
    let button = match button {
        PadState::A => PadState::B,
        PadState::B => PadState::A,
        PadState::X => PadState::Y,
        PadState::Y => PadState::X,
        other => other,
    };
    if pressed {
        PAD_BUTTONS.fetch_or(button.bits(), Ordering::Relaxed);
    } else {
        PAD_BUTTONS.fetch_and(!button.bits(), Ordering::Relaxed);
    }
    true
}

pub fn release_all() {
    BUTTONS.store(0, Ordering::Relaxed);
    PAD_BUTTONS.store(0, Ordering::Relaxed);
    CIRCLE_X.store(0, Ordering::Relaxed);
    CIRCLE_Y.store(0, Ordering::Relaxed);
    TOUCH.store(0, Ordering::Relaxed);
}

// what the Java side calls, the static native methods of
// com.pq2port.app.GameActivity

type Env = *mut std::ffi::c_void;
type Class = *mut std::ffi::c_void;

#[no_mangle]
pub extern "C" fn Java_com_pq2port_app_GameActivity_nativeSetButtons(_: Env, _: Class, mask: i32) {
    BUTTONS.store(mask as u32, Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn Java_com_pq2port_app_GameActivity_nativeSetCirclePad(_: Env, _: Class, x: f32, y: f32) {
    CIRCLE_X.store((x.clamp(-1.0, 1.0) * 1000.0) as i32, Ordering::Relaxed);
    CIRCLE_Y.store((y.clamp(-1.0, 1.0) * 1000.0) as i32, Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn Java_com_pq2port_app_GameActivity_nativeSetTouch(_: Env, _: Class, active: u8, x: i32, y: i32) {
    let packed = if active != 0 { 1 << 31 | (x.clamp(0, 319) as u32) << 16 | y.clamp(0, 239) as u32 } else { 0 };
    TOUCH.store(packed, Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn Java_com_pq2port_app_GameActivity_nativeRequestMenu(_: Env, _: Class) {
    MENU_REQUEST.store(true, Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn Java_com_pq2port_app_GameActivity_nativeMenuOpen(_: Env, _: Class) -> u8 {
    MENU_OPEN.load(Ordering::Relaxed) as u8
}

#[no_mangle]
pub extern "C" fn Java_com_pq2port_app_GameActivity_nativeRunning(_: Env, _: Class) -> u8 {
    RUNNING.load(Ordering::Relaxed) as u8
}

#[no_mangle]
pub extern "C" fn Java_com_pq2port_app_GameActivity_nativeTopRect(_: Env, _: Class) -> i64 {
    TOP_RECT.load(Ordering::Relaxed) as i64
}

#[no_mangle]
pub extern "C" fn Java_com_pq2port_app_GameActivity_nativeBottomRect(_: Env, _: Class) -> i64 {
    BOTTOM_RECT.load(Ordering::Relaxed) as i64
}

#[no_mangle]
pub extern "C" fn Java_com_pq2port_app_GameActivity_nativeFps(_: Env, _: Class) -> f32 {
    f32::from_bits(FPS.load(Ordering::Relaxed))
}

#[no_mangle]
pub extern "C" fn Java_com_pq2port_app_GameActivity_nativeScale(_: Env, _: Class) -> i32 {
    SCALE.load(Ordering::Relaxed) as i32
}

/// the cores the game's thread runs on, all but the slowest ones of a chip
/// whose cores differ, the little ones of Snapdragon, Dimensity and Helio,
/// Exynos, Tensor and Kirin chips alike, so that it never lands on one of
/// those while it waits on the display and has to climb back up. the threads
/// started before, the shader compilers and the sound's, stay where the
/// system puts them. ZAKURO_CORES=all leaves the game's alone too, and a
/// list such as 4-7 or 6,7 picks the cores.
pub fn prefer_fast_cores() {
    static DONE: std::sync::Once = std::sync::Once::new();
    DONE.call_once(|| {
        let asked = std::env::var("ZAKURO_CORES").unwrap_or_default();
        if asked.trim() == "all" {
            return;
        }
        let cores = if asked.trim().is_empty() { fast_cores() } else { parse_cores(&asked) };
        if cores.is_empty() {
            return;
        }
        let mut mask = [0u64; 16];
        for &core in &cores {
            if core < 1024 {
                mask[core / 64] |= 1 << (core % 64);
            }
        }
        extern "C" {
            fn sched_setaffinity(pid: i32, size: usize, mask: *const u64) -> i32;
        }
        // SAFETY: the mask is as long as the size says, pid 0 is this thread
        let result = unsafe { sched_setaffinity(0, std::mem::size_of_val(&mask), mask.as_ptr()) };
        if result == 0 {
            log::info!("Android: the game runs on cores {cores:?}");
        } else {
            log::warn!("Android: could not keep the game on cores {cores:?}, {}", std::io::Error::last_os_error());
        }
    });
}

/// the cores faster than the slowest, none when they are all as fast.
fn fast_cores() -> Vec<usize> {
    let possible = std::fs::read_to_string("/sys/devices/system/cpu/possible").unwrap_or_default();
    let all_cores = parse_cores(&possible);
    let speeds: Vec<(usize, u64)> = all_cores
        .iter()
        .filter_map(|&core| {
            let p1 = format!("/sys/devices/system/cpu/cpu{core}/cpufreq/cpuinfo_max_freq");
            let p2 = format!("/sys/devices/system/cpu/cpu{core}/cpufreq/scaling_max_freq");
            let p3 = format!("/sys/devices/system/cpu/cpu{core}/cpu_capacity");
            let speed = std::fs::read_to_string(p1)
                .ok()
                .and_then(|s| s.trim().parse().ok())
                .or_else(|| std::fs::read_to_string(p2).ok()?.trim().parse().ok())
                .or_else(|| std::fs::read_to_string(p3).ok()?.trim().parse().ok())?;
            Some((core, speed))
        })
        .collect();
    if let Some(&(_, slowest)) = speeds.iter().min_by_key(|&&(_, speed)| speed) {
        let fast: Vec<usize> = speeds.iter().filter(|&&(_, speed)| speed > slowest).map(|&(core, _)| core).collect();
        if !fast.is_empty() {
            log::info!("Android: cores and their top speeds {speeds:?}");
            return fast;
        }
    }
    // Fallback if SELinux blocked sysfs cpufreq
    let n = all_cores.len();
    if n >= 4 {
        let half = n / 2;
        let fast: Vec<usize> = all_cores.into_iter().skip(half).collect();
        log::info!("Android: cpufreq blocked by SELinux; using fallback fast cores {fast:?}");
        return fast;
    }
    Vec::new()
}

/// cores as Linux lists them, 0-3,6 say.
fn parse_cores(list: &str) -> Vec<usize> {
    let mut cores = Vec::new();
    for part in list.trim().split(',').map(str::trim).filter(|part| !part.is_empty()) {
        match part.split_once('-') {
            Some((from, to)) => {
                if let (Ok(from), Ok(to)) = (from.trim().parse::<usize>(), to.trim().parse::<usize>()) {
                    cores.extend(from..=to.min(1023));
                }
            }
            None => cores.extend(part.parse::<usize>().ok()),
        }
    }
    cores
}

/// writes a line to logcat, under the tag zakuro.
pub fn logcat(level: log::Level, line: &str) {
    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(priority: i32, tag: *const std::ffi::c_char, text: *const std::ffi::c_char) -> i32;
    }
    let priority = match level {
        log::Level::Error => 6,
        log::Level::Warn => 5,
        log::Level::Info => 4,
        log::Level::Debug => 3,
        log::Level::Trace => 2,
    };
    let text = std::ffi::CString::new(line.replace('\0', " ")).unwrap_or_default();
    // SAFETY: both are NUL-terminated strings that outlive the call
    unsafe { __android_log_write(priority, c"zakuro".as_ptr(), text.as_ptr()) };
}
