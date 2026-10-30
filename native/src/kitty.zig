//! Kitty graphics placements and image pixels for the renderer.
const std = @import("std");
const Session = @import("Session.zig");

/// One visible placement, flattened into `GhosttyVt.KITTY_PLACEMENT_LONGS`
/// longs in field order.
pub const Placement = extern struct {
    image_id: i64,
    image_generation: i64,
    viewport_col: i64,
    viewport_row: i64,
    offset_x: i64,
    offset_y: i64,
    dest_width: i64,
    dest_height: i64,
    source_x: i64,
    source_y: i64,
    source_width: i64,
    source_height: i64,
    z: i64,
    layer: i64,

    pub const longs = @sizeOf(Placement) / @sizeOf(i64);
};

/// Images larger than this are not handed to the renderer.
const max_image_pixels = 100 * 1000 * 1000;

/// Collects all placements from the last render state update, ordered by
/// layer, z-index, and image ID. Call this after a snapshot.
pub fn placements(session: *Session, alloc: std.mem.Allocator) ![]Placement {
    var list: std.ArrayList(Placement) = .empty;
    errdefer list.deinit(alloc);

    var it = session.render.imagePlacementIterator();
    while (it.next()) |p| {
        try list.append(alloc, .{
            .image_id = p.image_id,
            .image_generation = @intCast(p.image_generation),
            .viewport_col = p.x,
            .viewport_row = p.y,
            .offset_x = p.offset_x,
            .offset_y = p.offset_y,
            .dest_width = p.width,
            .dest_height = p.height,
            .source_x = p.source_x,
            .source_y = p.source_y,
            .source_width = p.source_width,
            .source_height = p.source_height,
            .z = p.z,
            .layer = @backingInt(p.layer),
        });
    }

    std.mem.sortUnstable(Placement, list.items, {}, struct {
        fn lessThan(_: void, a: Placement, b: Placement) bool {
            if (a.layer != b.layer) return a.layer < b.layer;
            if (a.z != b.z) return a.z < b.z;
            return a.image_id < b.image_id;
        }
    }.lessThan);
    return list.toOwnedSlice(alloc);
}

/// The current frame of an image as `[width, height, argb...]`.
pub fn imageArgb(session: *Session, alloc: std.mem.Allocator, image_id: u32) !?[]i32 {
    const storage = &session.terminal.screens.active.kitty_images;
    const image = storage.images.getPtr(image_id) orelse return null;
    const data = image.renderData().bytes() orelse return null;
    const bpp: usize = switch (image.format) {
        .gray => 1,
        .gray_alpha => 2,
        .rgb => 3,
        .rgba => 4,
        else => return null,
    };
    const pixel_count = @as(usize, image.width) * image.height;
    if (pixel_count == 0 or pixel_count > max_image_pixels or data.len < pixel_count * bpp) return null;

    const out = try alloc.alloc(i32, 2 + pixel_count);
    out[0] = @intCast(image.width);
    out[1] = @intCast(image.height);
    for (out[2..], 0..) |*dest, i| {
        const px = data[i * bpp ..][0..bpp];
        const r: u32, const g: u32, const b: u32, const a: u32 = switch (bpp) {
            1 => .{ px[0], px[0], px[0], 0xFF },
            2 => .{ px[0], px[0], px[0], px[1] },
            3 => .{ px[0], px[1], px[2], 0xFF },
            else => .{ px[0], px[1], px[2], px[3] },
        };
        dest.* = @bitCast(a << 24 | r << 16 | g << 8 | b);
    }
    return out;
}
