package net.shiroha233.roadweaver.decoration;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared geometry and palette for a glazed viewing corridor.
 *
 * <p>Two builders need the same vault: {@code RoadTunnelBuilder} glazes a bored tunnel, and
 * {@code BridgeBuilder} glazes a submerged crossing that has no bore at all. Keeping the
 * profile in one place means an underwater deck and an underwater tunnel meet with the same
 * cross-section, which is what makes them read as one continuous tube.</p>
 */
public final class ScenicGlassShell {
    /**
     * Half-width of the vault, in blocks: the arch spans {@code 2*RADIUS+1} columns and
     * reaches {@code RADIUS} blocks above its springing line, so the profile is a true
     * half-round rather than a wedge with a flat crown.
     */
    public static final int RADIUS = 3;

    private ScenicGlassShell() {
    }

    /**
     * Glass tint at {@code progress} along a span, as a there-and-back gradient.
     *
     * <p>Colour is a function of position rather than a hash of the first block, so
     * neighbouring stations differ by at most one palette step and a long corridor fades
     * instead of speckling. Folding at the midpoint returns the span to its starting tint,
     * so consecutive spans meet without a visible seam.</p>
     *
     * @param progress 0 at the span start, 1 at its end; clamped to that range
     */
    public static BlockState glass(double progress) {
        BlockState[] palette = {
                Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(),
                Blocks.CYAN_STAINED_GLASS.defaultBlockState(),
                Blocks.BLUE_STAINED_GLASS.defaultBlockState(),
                Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(),
                Blocks.CYAN_STAINED_GLASS.defaultBlockState(),
        };
        double clamped = Math.max(0.0D, Math.min(1.0D, progress));
        double folded = clamped < 0.5D ? clamped * 2.0D : (1.0D - clamped) * 2.0D;
        int step = (int) Math.round(folded * (palette.length - 1));
        return palette[Math.floorMod(step, palette.length)];
    }

    /**
     * How far the vault rises above its springing line at this lateral offset.
     *
     * <p>{@code sqrt(r^2 - lateral^2)} for the semicircle, rounded to whole blocks. The
     * centre column resolves to {@link #RADIUS} and the outermost column to zero, which is
     * the springing line itself — so the arch meets a vertical side wall exactly, with no
     * step and no gap.</p>
     *
     * @param lateral blocks from the corridor centre; negative values are its mirror
     * @return the rise in blocks, or -1 when the offset is outside the arch
     */
    public static int archRise(int lateral) {
        int offset = Math.abs(lateral);
        if (offset > RADIUS) return -1;
        return (int) Math.round(Math.sqrt(
                (double) RADIUS * RADIUS - (double) offset * offset));
    }
}
