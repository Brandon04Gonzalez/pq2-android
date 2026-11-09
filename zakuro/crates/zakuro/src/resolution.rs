//! the resolution the GPU draws the 3D at when ZAKURO_RESOLUTION=auto: up
//! to a scale picked for the GPU by its name, one lower while the game runs
//! whenever the GPU keeps it from full speed, and one higher tried again
//! after a while at full speed, as a game's scenes differ. the scale is
//! kept for that GPU in resolution.txt in the data folder, and the next
//! game starts at it.

use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

/// how long a span of play is measured for.
const SPAN: Duration = Duration::from_secs(5);
/// and a span of a scale being tried, which has to keep up in a few of
/// them in a row.
const TRIAL_SPAN: Duration = Duration::from_secs(2);
const TRIAL_SPANS: u32 = 3;
/// the slow spans in a row that lower the scale, a slow moment being no
/// reason to.
const SLOW_SPANS: u32 = 2;
/// slower than this a span is slow, and this fast it is at full speed.
const SLOW: f64 = 0.95;
const FULL_SPEED: f64 = 0.98;
/// how long the game runs at full speed before the scale above is tried,
/// at first, and at most after tries that were too much.
const PATIENCE: Duration = Duration::from_secs(60);
const MOST_PATIENCE: Duration = Duration::from_secs(16 * 60);
/// measuring waits this long after the game starts, for loading and the
/// pipelines compiled on the first draws, and this long after the scale
/// changes.
const SETTLE: Duration = Duration::from_secs(15);
const CHANGE_SETTLE: Duration = Duration::from_secs(2);
/// a step longer than this was a pause, the app in the background say,
/// which the span leaves out.
const PAUSE: Duration = Duration::from_millis(250);

/// what ZAKURO_RESOLUTION asks for: a scale, or auto, or nothing, the
/// settings' scale.
pub enum Asked {
    Settings,
    Fixed(u32),
    Auto,
}

pub fn asked() -> Asked {
    match std::env::var("ZAKURO_RESOLUTION").as_deref().map(str::trim) {
        Ok("auto") => Asked::Auto,
        Ok(value) => value.parse().ok().filter(|&scale| scale >= 1).map_or(Asked::Settings, |scale: u32| Asked::Fixed(scale.min(8))),
        Err(_) => Asked::Settings,
    }
}

/// the scale a GPU draws at full speed, by its name, from the phones tried
/// and how their GPUs compare, none for a desktop's, which draw any.
pub fn guess(gpu: &str) -> Option<u32> {
    let name = gpu.to_ascii_lowercase();
    // the number after a word, "Adreno (TM) 740" or "Mali-G57 MC2"
    let number = |word: &str| -> Option<u32> {
        let after = &name[name.find(word)? + word.len()..];
        let digits: String =
            after.trim_start_matches(|c: char| !c.is_ascii_digit()).chars().take_while(char::is_ascii_digit).collect();
        digits.parse().ok()
    };
    if name.contains("adreno") {
        return Some(match number("adreno")? {
            740..=799 | 825.. => 4,
            630..=699 | 720..=739 | 810..=824 => 3,
            614..=629 | 700..=719 => 2,
            _ => 1,
        });
    }
    if name.contains("immortalis") {
        return Some(4);
    }
    if name.contains("mali") {
        // the Bifrost and Valhall GPUs, G and a number, which says the
        // generation and the tier but not the number of cores
        return Some(match number("mali-g").or_else(|| number("mali g"))? {
            77 | 78 | 610 | 710 | 715 | 720 | 725 | 925 => 3,
            57 | 68 | 76 | 615 | 620 => 2,
            _ => 1,
        });
    }
    if name.contains("powervr") || name.contains("img ") {
        return Some(if name.contains("dxt") || name.contains("d-series") {
            3
        } else if name.contains("bxm-8") {
            // the moto g54's, 37 to 54 frames a second at 3x and about 60 at 2x
            2
        } else {
            1
        });
    }
    if name.contains("xclipse") {
        return Some(if number("xclipse")? >= 940 { 4 } else { 3 });
    }
    if name.contains("maleoon") {
        return Some(3);
    }
    // a phone's GPU unheard of
    if cfg!(target_os = "android") {
        return Some(2);
    }
    None
}

/// one span of play measured.
struct Span {
    since: Instant,
    last_step: Instant,
    frames: u32,
    waiting: Duration,
    cpu: Option<Duration>,
}

/// what a span of play says of the scale.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Measured {
    /// the game's speed, 1 for full.
    pub speed: f64,
    /// the share of the time the host waited for the GPU, to record or to
    /// show a frame.
    pub gpu: f64,
    /// the share the game's thread kept a CPU busy, where the system says.
    pub cpu: Option<f64>,
}

impl Measured {
    /// whether the GPU kept the game from full speed: running behind with
    /// the game's thread idle much of the time, waiting for the GPU. a
    /// thread kept busy is held back by the CPU, which a lower scale would
    /// not help.
    pub fn gpu_bound(&self) -> bool {
        self.speed < SLOW && self.gpu >= 0.15 && self.cpu.is_none_or(|cpu| cpu < 0.85)
    }
}

pub struct Auto {
    gpu: String,
    kept: Option<PathBuf>,
    /// the highest scale tried, the guess for the GPU.
    ceiling: u32,
    /// when measuring starts.
    from: Instant,
    span: Option<Span>,
    /// slow spans in a row.
    slow: u32,
    /// since when every span ran at full speed.
    smooth_since: Option<Instant>,
    /// how long that has to last before the next scale up is tried, longer
    /// each time one was too much.
    patience: Duration,
    /// the spans a scale being tried kept up in so far.
    trial: Option<u32>,
}

impl Auto {
    /// for the GPU named, and the scale to draw at: the one kept for it, or
    /// else the guess for it, or else the settings' scale.
    pub fn new(gpu: &str, data_dir: Option<&Path>, settings_scale: u32) -> (Auto, u32) {
        let kept = data_dir.map(|dir| dir.join("resolution.txt"));
        let ceiling = guess(gpu).unwrap_or(settings_scale).max(1);
        let learned = kept.as_deref().and_then(|file| learned(file, gpu)).map(|scale| scale.min(ceiling));
        let scale = learned.unwrap_or(ceiling);
        match learned {
            Some(scale) => log::info!("resolution: {scale}x, kept for {gpu}, up to {ceiling}x"),
            None => log::info!("resolution: {scale}x to start with on {gpu}"),
        }
        let auto = Auto {
            gpu: gpu.to_owned(),
            kept,
            ceiling,
            from: Instant::now() + SETTLE,
            span: None,
            slow: 0,
            smooth_since: None,
            patience: PATIENCE,
            trial: None,
        };
        (auto, scale)
    }

    /// counts a step of the frontend, a frame emulated or not, the time
    /// showing it took and how long the host waited for the GPU meanwhile,
    /// while the game plays, and says the scale to draw at when it is to
    /// change.
    pub fn step(&mut self, playing: bool, emulated: bool, showing: Duration, waited: Duration, scale: u32) -> Option<u32> {
        let now = Instant::now();
        let paused = self.span.as_ref().is_some_and(|span| now - span.last_step > PAUSE);
        if !playing || now < self.from || paused {
            self.span = None;
            return None;
        }
        let Some(span) = self.span.as_mut() else {
            self.span = Some(Span { since: now, last_step: now, frames: 0, waiting: Duration::ZERO, cpu: thread_cpu_time() });
            return None;
        };
        span.last_step = now;
        span.frames += emulated as u32;
        // waits for the GPU while recording count with those for showing
        span.waiting += showing + waited;
        let elapsed = now - span.since;
        // a scale being tried is judged sooner, slow spans of it would show
        if elapsed < if self.trial.is_some() { TRIAL_SPAN } else { SPAN } {
            return None;
        }
        let seconds = elapsed.as_secs_f64();
        let measured = Measured {
            speed: span.frames as f64 * super::FRAME_TIME.as_secs_f64() / seconds,
            gpu: span.waiting.as_secs_f64() / seconds,
            cpu: span.cpu.zip(thread_cpu_time()).map(|(then, now)| now.saturating_sub(then).as_secs_f64() / seconds),
        };
        let since = span.since;
        self.span = None;
        log::debug!(
            "resolution: {:.0}% speed at {scale}x, waiting for the GPU {:.0}% of the time, the CPU busy {}",
            measured.speed * 100.0,
            measured.gpu * 100.0,
            measured.cpu.map_or("unknown".to_owned(), |cpu| format!("{:.0}%", cpu * 100.0)),
        );
        self.judge(since, now, measured, scale)
    }

    /// what a span of play from since to now says of the scale: one down
    /// after slow spans, the one being tried taken back or kept, one up
    /// tried after a while at full speed.
    fn judge(&mut self, since: Instant, now: Instant, measured: Measured, scale: u32) -> Option<u32> {
        if let Some(passed) = self.trial {
            if measured.gpu_bound() && scale > 1 {
                self.trial = None;
                self.patience = (self.patience * 2).min(MOST_PATIENCE);
                self.changed(now);
                log::info!(
                    "resolution: back to {}x, {scale}x kept the game at {:.0}% speed, the next try in {} s",
                    scale - 1,
                    measured.speed * 100.0,
                    self.patience.as_secs()
                );
                return Some(scale - 1);
            }
            if passed + 1 < TRIAL_SPANS {
                self.trial = Some(passed + 1);
            } else {
                self.trial = None;
                self.patience = PATIENCE;
                log::info!("resolution: {scale}x keeps up");
                self.keep(scale);
            }
            return None;
        }
        self.slow = if measured.gpu_bound() { self.slow + 1 } else { 0 };
        if self.slow >= SLOW_SPANS && scale > 1 {
            self.changed(now);
            log::info!("resolution: {}x from now on, the GPU kept the game at {:.0}% speed at {scale}x", scale - 1, measured.speed * 100.0);
            self.keep(scale - 1);
            return Some(scale - 1);
        }
        if measured.speed >= FULL_SPEED {
            self.smooth_since.get_or_insert(since);
        } else {
            self.smooth_since = None;
        }
        if scale < self.ceiling && self.smooth_since.is_some_and(|smooth| now - smooth >= self.patience) {
            self.changed(now);
            self.trial = Some(0);
            log::info!("resolution: trying {}x, the game ran at full speed at {scale}x", scale + 1);
            return Some(scale + 1);
        }
        None
    }

    /// starts measuring over after the scale changes.
    fn changed(&mut self, now: Instant) {
        self.from = now + CHANGE_SETTLE;
        self.span = None;
        self.slow = 0;
        self.smooth_since = None;
    }

    /// keeps the scale for the GPU, the next game starts at it.
    fn keep(&self, scale: u32) {
        if let Some(file) = &self.kept {
            if let Err(error) = std::fs::write(file, format!("gpu={}\nscale={scale}\n", self.gpu)) {
                log::warn!("could not keep the resolution in {}, {error}", file.display());
            }
        }
    }
}

/// the scale kept in the file for the GPU named, if it is that GPU's.
fn learned(file: &Path, gpu: &str) -> Option<u32> {
    let text = std::fs::read_to_string(file).ok()?;
    let value = |key: &str| text.lines().find_map(|line| line.trim().strip_prefix(key)?.strip_prefix('=').map(str::trim));
    (value("gpu")? == gpu).then_some(())?;
    value("scale")?.parse().ok().filter(|&scale| (1..=8).contains(&scale))
}

/// the CPU time the calling thread used so far, where the system says.
fn thread_cpu_time() -> Option<Duration> {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        #[repr(C)]
        struct Timespec {
            seconds: std::ffi::c_long,
            nanoseconds: std::ffi::c_long,
        }
        extern "C" {
            fn clock_gettime(clock: i32, time: *mut Timespec) -> i32;
        }
        const CLOCK_THREAD_CPUTIME_ID: i32 = 3;
        let mut time = Timespec { seconds: 0, nanoseconds: 0 };
        // SAFETY: time is a timespec, written before the call returns
        if unsafe { clock_gettime(CLOCK_THREAD_CPUTIME_ID, &mut time) } != 0 {
            return None;
        }
        Some(Duration::new(time.seconds as u64, time.nanoseconds as u32))
    }
    #[cfg(windows)]
    {
        #[repr(C)]
        #[derive(Default)]
        struct FileTime {
            low: u32,
            high: u32,
        }
        extern "system" {
            fn GetCurrentThread() -> *mut std::ffi::c_void;
            fn GetThreadTimes(
                thread: *mut std::ffi::c_void,
                creation: *mut FileTime,
                exit: *mut FileTime,
                kernel: *mut FileTime,
                user: *mut FileTime,
            ) -> i32;
        }
        let [mut creation, mut exit, mut kernel, mut user] = [(); 4].map(|()| FileTime::default());
        // SAFETY: the pseudo handle of the calling thread, and four times
        // written before the call returns
        if unsafe { GetThreadTimes(GetCurrentThread(), &mut creation, &mut exit, &mut kernel, &mut user) } == 0 {
            return None;
        }
        let ticks = |time: &FileTime| (time.high as u64) << 32 | time.low as u64;
        // in hundreds of nanoseconds
        Some(Duration::from_nanos((ticks(&kernel) + ticks(&user)) * 100))
    }
    #[cfg(not(any(target_os = "linux", target_os = "android", windows)))]
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn phones_gpus_get_a_scale_by_their_tier() {
        assert_eq!(guess("Adreno (TM) 750"), Some(4));
        assert_eq!(guess("Adreno (TM) 830"), Some(4));
        assert_eq!(guess("Adreno (TM) 730"), Some(3));
        assert_eq!(guess("Adreno (TM) 650"), Some(3));
        assert_eq!(guess("Adreno (TM) 810"), Some(3));
        assert_eq!(guess("Adreno (TM) 710"), Some(2));
        assert_eq!(guess("Adreno (TM) 619"), Some(2));
        assert_eq!(guess("Adreno (TM) 610"), Some(1));
        assert_eq!(guess("Mali-G710"), Some(3));
        assert_eq!(guess("Mali-G57 MC2"), Some(2));
        assert_eq!(guess("Mali-G52"), Some(1));
        assert_eq!(guess("Immortalis-G720 MC12"), Some(4));
        assert_eq!(guess("PowerVR B-Series BXM-8-256"), Some(2));
        assert_eq!(guess("PowerVR Rogue GE8320"), Some(1));
        assert_eq!(guess("PowerVR D-Series DXT-48-1536"), Some(3));
        assert_eq!(guess("Samsung Xclipse 920"), Some(3));
        assert_eq!(guess("Samsung Xclipse 940"), Some(4));
        assert_eq!(guess("Maleoon 910"), Some(3));
    }

    #[test]
    fn desktop_gpus_keep_the_settings_scale() {
        if !cfg!(target_os = "android") {
            assert_eq!(guess("NVIDIA GeForce RTX 4060 Laptop GPU"), None);
            assert_eq!(guess("AMD Radeon RX 7600"), None);
        }
    }

    #[test]
    fn only_a_slow_game_waiting_on_the_gpu_is_gpu_bound() {
        // the moto g54 at 3x: behind, its thread idle half the time
        assert!(Measured { speed: 0.75, gpu: 0.45, cpu: Some(0.41) }.gpu_bound());
        // full speed
        assert!(!Measured { speed: 0.99, gpu: 0.5, cpu: Some(0.4) }.gpu_bound());
        // behind with the CPU busy all the time
        assert!(!Measured { speed: 0.7, gpu: 0.05, cpu: Some(0.97) }.gpu_bound());
        // behind waiting for something else, pipelines compiled say
        assert!(!Measured { speed: 0.6, gpu: 0.02, cpu: Some(0.3) }.gpu_bound());
        // no CPU time to tell
        assert!(Measured { speed: 0.8, gpu: 0.3, cpu: None }.gpu_bound());
    }

    #[test]
    fn the_scale_kept_is_the_gpus_own() {
        let dir = std::env::temp_dir().join(format!("zakuro-resolution-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("resolution.txt");
        std::fs::write(&file, "gpu=PowerVR B-Series BXM-8-256\nscale=1\n").unwrap();
        assert_eq!(learned(&file, "PowerVR B-Series BXM-8-256"), Some(1));
        assert_eq!(learned(&file, "Adreno (TM) 740"), None);
        let (_, scale) = Auto::new("PowerVR B-Series BXM-8-256", Some(&dir), 3);
        assert_eq!(scale, 1);
        let (_, scale) = Auto::new("Adreno (TM) 740", Some(&dir), 3);
        assert_eq!(scale, 4);
        // never above the guess for the GPU
        std::fs::write(&file, "gpu=PowerVR BXM-8-256\nscale=3\n").unwrap();
        let (_, scale) = Auto::new("PowerVR BXM-8-256", Some(&dir), 3);
        assert_eq!(scale, 2);
        std::fs::remove_dir_all(&dir).unwrap();
    }

    const SMOOTH: Measured = Measured { speed: 1.0, gpu: 0.1, cpu: Some(0.5) };
    const BEHIND: Measured = Measured { speed: 0.65, gpu: 0.55, cpu: Some(0.45) };

    /// an Auto for a GPU drawing up to 3x, and a clock from now.
    fn auto() -> (Auto, impl FnMut(u64) -> (Instant, Instant)) {
        let auto = Auto {
            gpu: "test".to_owned(),
            kept: None,
            ceiling: 3,
            from: Instant::now(),
            span: None,
            slow: 0,
            smooth_since: None,
            patience: PATIENCE,
            trial: None,
        };
        let start = Instant::now();
        let mut at = Duration::ZERO;
        // a span of the seconds given, from where the last one ended
        let clock = move |seconds: u64| {
            let since = start + at;
            at += Duration::from_secs(seconds);
            (since, start + at)
        };
        (auto, clock)
    }

    #[test]
    fn slow_spans_in_a_row_lower_the_scale() {
        let (mut auto, mut clock) = auto();
        let (since, now) = clock(5);
        assert_eq!(auto.judge(since, now, BEHIND, 3), None, "one slow span is no reason");
        let (since, now) = clock(5);
        assert_eq!(auto.judge(since, now, SMOOTH, 3), None);
        let (since, now) = clock(5);
        assert_eq!(auto.judge(since, now, BEHIND, 3), None);
        let (since, now) = clock(5);
        assert_eq!(auto.judge(since, now, BEHIND, 3), Some(2));
        // not lower than 1x
        let (since, now) = clock(5);
        assert_eq!(auto.judge(since, now, BEHIND, 1), None);
        let (since, now) = clock(5);
        assert_eq!(auto.judge(since, now, BEHIND, 1), None);
    }

    #[test]
    fn a_scale_up_is_tried_after_a_minute_at_full_speed_and_kept_when_it_keeps_up() {
        let (mut auto, mut clock) = auto();
        for _ in 0..11 {
            let (since, now) = clock(5);
            assert_eq!(auto.judge(since, now, SMOOTH, 2), None);
        }
        let (since, now) = clock(5);
        assert_eq!(auto.judge(since, now, SMOOTH, 2), Some(3), "a minute at full speed");
        for _ in 0..TRIAL_SPANS {
            let (since, now) = clock(2);
            assert_eq!(auto.judge(since, now, SMOOTH, 3), None);
        }
        assert_eq!(auto.trial, None, "kept");
        // nothing above the ceiling
        for _ in 0..30 {
            let (since, now) = clock(5);
            assert_eq!(auto.judge(since, now, SMOOTH, 3), None);
        }
    }

    #[test]
    fn a_scale_up_too_much_goes_back_at_once_and_waits_longer_each_time() {
        let (mut auto, mut clock) = auto();
        let mut waits = Vec::new();
        for _ in 0..6 {
            let mut spans = 0;
            loop {
                let (since, now) = clock(5);
                spans += 1;
                if auto.judge(since, now, SMOOTH, 1) == Some(2) {
                    break;
                }
            }
            waits.push(spans * 5);
            let (since, now) = clock(2);
            assert_eq!(auto.judge(since, now, BEHIND, 2), Some(1), "back at the first slow span");
        }
        assert_eq!(waits, [60, 120, 240, 480, 960, 960]);
    }

    #[test]
    fn a_slow_span_breaks_the_run_at_full_speed() {
        let (mut auto, mut clock) = auto();
        for _ in 0..8 {
            let (since, now) = clock(5);
            auto.judge(since, now, SMOOTH, 2);
        }
        let (since, now) = clock(5);
        auto.judge(since, now, Measured { speed: 0.9, gpu: 0.05, cpu: Some(0.9) }, 2);
        for _ in 0..11 {
            let (since, now) = clock(5);
            assert_eq!(auto.judge(since, now, SMOOTH, 2), None);
        }
        let (since, now) = clock(5);
        assert_eq!(auto.judge(since, now, SMOOTH, 2), Some(3));
    }

    #[test]
    fn the_threads_cpu_time_moves_on() {
        let Some(before) = thread_cpu_time() else { return };
        // busy for longer than Windows' clock ticks
        let start = Instant::now();
        while start.elapsed() < Duration::from_millis(60) {
            std::hint::black_box(start.elapsed());
        }
        let used = thread_cpu_time().unwrap() - before;
        assert!(used >= Duration::from_millis(30), "{used:?}");
    }
}
