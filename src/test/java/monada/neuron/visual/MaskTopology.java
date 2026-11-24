package monada.neuron.visual;

import java.util.ArrayDeque;

/**
 * Test only flood fill over a mask (contract Section 4.5): foreground components are 8 connected, and a hole is a 4
 * connected background component with no pixel on the raster border.
 */
record MaskTopology(int components, int holes) {

    static MaskTopology of(BinaryMask mask) {
        int w = mask.width();
        int h = mask.height();
        boolean[] seen = new boolean[w * h];
        int components = 0;
        int holes = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (seen[y * w + x]) {
                    continue;
                }
                boolean foreground = mask.get(x, y);
                boolean touchesBorder = fill(mask, seen, x, y, foreground);
                if (foreground) {
                    components++;
                } else if (!touchesBorder) {
                    holes++;
                }
            }
        }
        return new MaskTopology(components, holes);
    }

    private static boolean fill(BinaryMask mask, boolean[] seen, int startX, int startY, boolean foreground) {
        int w = mask.width();
        int h = mask.height();
        boolean touchesBorder = false;
        var stack = new ArrayDeque<int[]>();
        stack.push(new int[] {startX, startY});
        seen[startY * w + startX] = true;
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            touchesBorder |= p[0] == 0 || p[1] == 0 || p[0] == w - 1 || p[1] == h - 1;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    boolean diagonal = dx != 0 && dy != 0;
                    int x = p[0] + dx;
                    int y = p[1] + dy;
                    if ((diagonal && !foreground) || x < 0 || y < 0 || x >= w || y >= h || seen[y * w + x]
                            || mask.get(x, y) != foreground) {
                        continue;
                    }
                    seen[y * w + x] = true;
                    stack.push(new int[] {x, y});
                }
            }
        }
        return touchesBorder;
    }
}
