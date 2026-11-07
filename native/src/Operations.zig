//! Host-independent terminal operations shared by JNI and WebAssembly adapters.
const std = @import("std");
const vt = @import("ghostty-vt");
const Session = @import("Session.zig");

pub fn rgbFromArgb(value: i64) ?vt.color.RGB {
    if (value < 0) return null;
    return .{
        .r = @truncate(@as(u64, @intCast(value)) >> 16),
        .g = @truncate(@as(u64, @intCast(value)) >> 8),
        .b = @truncate(@as(u64, @intCast(value))),
    };
}

pub fn viewportPin(t: *vt.Terminal, col: i32, row: i32) ?vt.Pin {
    if (col < 0 or row < 0) return null;
    return t.screens.active.pages.pin(.{ .viewport = .{
        .x = std.math.cast(u16, col) orelse return null,
        .y = std.math.cast(u32, row) orelse return null,
    } });
}

pub fn installSelection(s: *Session, selection: ?vt.Selection) bool {
    const sel = selection orelse return false;
    s.terminal.screens.active.select(sel) catch return false;
    s.markDirty();
    return true;
}

pub fn mods(bits: i32) vt.input.KeyMods {
    return @bitCast(@as(u16, @truncate(@as(u32, @bitCast(bits)))));
}
