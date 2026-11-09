//! half the frames of a game that draws at 60 a second, drawn: the 30
//! frames a second mode of the Android ports, for GPUs that can't keep up.
//!
//! a round of a buffer is the game clearing it with a fill, drawing into it
//! and copying it out with a display transfer, a picture made whole. a game
//! that makes such rounds every frame, of the screens' pictures and the
//! offscreen ones they take, is at 60 frames a second, and every other frame
//! of it is left out: its rounds, fills, draws and transfers, and the frame
//! is not shown. the screens keep the frame before, the buffers the games
//! copy out hold nothing the next round needs, it clears them first. a
//! buffer ever drawn into outside a round, whose picture may build up over
//! frames, a map say, is never left out, nor are the frames of a game at 30
//! frames a second, which makes its rounds every other frame. Persona Q
//! and Q2 are such games most of the time: there the mode shows each of
//! their frames once rather than twice.

use std::collections::HashMap;

/// the buffers tracked at most, a few for the screens and the offscreen
/// pictures a game draws into each frame. more are forgotten.
const MOST_BUFFERS: usize = 64;

/// a buffer's rounds of fill, draws and transfer.
#[derive(Debug, Clone, Copy, Default)]
struct Buffer {
    /// a round began and its transfer has not come yet.
    open: bool,
    /// the round going on is left out.
    skipping: bool,
    /// the round going on had a draw.
    drew: bool,
    /// it went through a whole round, fill, draws and transfer.
    rounds: bool,
    /// it was drawn into outside a round, and so may keep what is drawn.
    kept: bool,
    /// the depth buffer its draws used, cleared with it.
    depth: Option<u32>,
}

#[derive(Debug, Default)]
pub(crate) struct HalfRate {
    /// leaving frames out, the mode being on.
    on: bool,
    /// the frame going on is left out.
    skipping: bool,
    /// the rounds with draws ended in the frame going on, and in the two
    /// before it.
    ended: u32,
    before: [u32; 2],
    buffers: HashMap<u32, Buffer>,
    /// a transfer copied a new picture out since this was last asked.
    fresh: bool,
    /// events logged, ZAKURO_HALF_RATE_LOG=1, a few frames' worth.
    logged: u32,
    tracing: bool,
}

impl HalfRate {
    pub(crate) fn set(&mut self, on: bool) {
        self.on = on;
        self.tracing = std::env::var_os("ZAKURO_HALF_RATE_LOG").is_some();
    }

    pub(crate) fn on(&self) -> bool {
        self.on
    }

    /// the console's next frame begins: left out when the one before was
    /// drawn and the game made rounds in each of the last two, so at 60
    /// frames a second.
    fn trace(&mut self, what: std::fmt::Arguments<'_>) {
        if self.tracing && self.logged < 400 {
            self.logged += 1;
            log::info!("half rate: {what}");
        }
    }

    pub(crate) fn begin_frame(&mut self) {
        let ended = self.ended;
        self.trace(format_args!("frame, {ended} rounds ended"));
        self.before = [self.ended, self.before[0]];
        self.ended = 0;
        self.skipping = self.on && !self.skipping && self.before.iter().all(|&rounds| rounds > 0);
    }

    /// whether a transfer copied a new picture out since the last time this
    /// was asked, which a frame left out did not.
    pub(crate) fn take_fresh(&mut self) -> bool {
        std::mem::take(&mut self.fresh)
    }

    /// a fill from address: a round of the buffer there begins, and whether
    /// to leave the fill out, its round being left out, or a depth buffer of
    /// one that is.
    pub(crate) fn fill(&mut self, address: u32) -> bool {
        self.trace(format_args!("fill {address:08X}"));
        if self.buffers.values().any(|buffer| buffer.open && buffer.skipping && buffer.depth == Some(address)) {
            return true;
        }
        if !self.buffers.contains_key(&address) && self.buffers.len() >= MOST_BUFFERS {
            self.buffers.retain(|_, buffer| buffer.kept);
        }
        let skipping = self.skipping;
        let buffer = self.buffers.entry(address).or_default();
        buffer.open = true;
        buffer.drew = false;
        buffer.skipping = skipping && buffer.rounds && !buffer.kept;
        buffer.skipping
    }

    /// a draw into the color buffer at color with the depth buffer at depth,
    /// and whether to leave it out.
    pub(crate) fn draw(&mut self, color: u32, depth: Option<u32>) -> bool {
        let Some(buffer) = self.buffers.get_mut(&color) else { return false };
        if !buffer.open {
            // drawn into without a fill first, it may keep what is drawn
            buffer.kept = true;
            self.trace(format_args!("draw {color:08X} outside a round"));
            return false;
        }
        buffer.drew = true;
        if depth.is_some() {
            buffer.depth = depth;
        }
        buffer.skipping
    }

    /// a display transfer out of address, which ends the round of the buffer
    /// there, and whether to leave it out with the round.
    pub(crate) fn transfer(&mut self, address: u32) -> bool {
        self.trace(format_args!("transfer {address:08X}"));
        let Some(buffer) = self.buffers.get_mut(&address).filter(|buffer| buffer.open) else {
            self.fresh = true;
            return false;
        };
        buffer.open = false;
        if buffer.drew || buffer.skipping {
            self.ended += 1;
        }
        if buffer.skipping {
            buffer.skipping = false;
            return true;
        }
        buffer.rounds |= buffer.drew;
        self.fresh = true;
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const COLOR: u32 = 0x1800_0000;
    const DEPTH: u32 = 0x1810_0000;
    const MAP: u32 = 0x1820_0000;

    /// a round of a buffer, and what was left out of it: the fill, the
    /// draw, the transfer.
    fn round(rate: &mut HalfRate, color: u32) -> bool {
        let fill = rate.fill(color);
        let depth = rate.fill(DEPTH);
        let draw = rate.draw(color, Some(DEPTH));
        let transfer = rate.transfer(color);
        assert!(fill == draw && draw == transfer, "a round is left out whole");
        assert!(!depth || fill);
        fill
    }

    /// a frame of a game at 60 frames a second, both screens drawn through
    /// the same buffer one after the other, and whether it was left out.
    fn frame(rate: &mut HalfRate) -> bool {
        rate.begin_frame();
        let top = round(rate, COLOR);
        let bottom = round(rate, COLOR);
        assert_eq!(top, bottom, "the screens of a frame are left out together");
        top
    }

    #[test]
    fn every_other_frame_of_a_game_at_60_is_left_out() {
        let mut rate = HalfRate::default();
        rate.set(true);
        let frames: Vec<(bool, bool)> = (0..9).map(|_| (frame(&mut rate), rate.take_fresh())).collect();
        // the first frames teach it the buffer and the game's pace, and
        // there is something new to show after each frame drawn
        let skipped = [false, false, true, false, true, false, true, false, true];
        assert_eq!(frames, skipped.map(|skipped| (skipped, !skipped)));
        assert!(!rate.take_fresh());
    }

    #[test]
    fn off_nothing_is_left_out() {
        let mut rate = HalfRate::default();
        assert!((0..8).all(|_| !frame(&mut rate)));
    }

    #[test]
    fn a_game_at_30_keeps_every_frame() {
        let mut rate = HalfRate::default();
        rate.set(true);
        for _ in 0..8 {
            assert!(!frame(&mut rate));
            // the frame it draws nothing in
            rate.begin_frame();
        }
        // nor when a round of its runs over two of the console's frames
        for _ in 0..8 {
            rate.begin_frame();
            assert!(!rate.fill(COLOR));
            assert!(!rate.draw(COLOR, None));
            rate.begin_frame();
            assert!(!rate.draw(COLOR, None));
            assert!(!rate.transfer(COLOR));
        }
    }

    #[test]
    fn a_buffer_ever_drawn_into_outside_a_round_is_never_left_out() {
        let mut rate = HalfRate::default();
        rate.set(true);
        for _ in 0..3 {
            rate.begin_frame();
            round(&mut rate, COLOR);
            // the map, cleared and copied out at times, a stroke drawn over
            // what is there in between
            assert!(!round(&mut rate, MAP));
            assert!(!rate.draw(MAP, None));
        }
        for _ in 0..6 {
            rate.begin_frame();
            round(&mut rate, COLOR);
            assert!(!round(&mut rate, MAP));
        }
    }

    #[test]
    fn turned_off_it_draws_every_frame() {
        let mut rate = HalfRate::default();
        rate.set(true);
        while !frame(&mut rate) {}
        rate.set(false);
        assert!((0..4).all(|_| !frame(&mut rate)));
    }
}
