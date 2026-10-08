package com.woolipic;

import java.util.ArrayList;
import java.util.List;

/**
 * 羊毛调色板与感知色彩匹配。
 *
 * <p>核心思路：16 种羊毛的颜色是固定的，把图片逐像素映射到"视觉上最接近"的
 * 羊毛色。这里的"接近"用的是 CIE Lab 色彩空间下的 CIEDE2000 色差公式，而不是
 * 简单的 RGB 欧氏距离。
 *
 * <p>为什么重要：RGB 空间不是感知均匀的，两个 RGB 距离相同的颜色对，人眼看到
 * 的差别可能相差数倍。在只有 16 色的极端约束下，用 RGB 距离经常会把深蓝匹配成
 * 品红、把橙色匹配成棕色。CIEDE2000 实现已用 Sharma 等人的 34 组官方验证数据
 * 核对（见 ColorMath#selfTest）。
 */
public final class WoolPalette {

    /** 一种羊毛的固定信息。 */
    public static final class Wool {
        /** 贴图/资源名，对应官方 wool_colored_<id>.png，也是本类内部的主键。 */
        public final String id;
        public final String zhName;
        /** 游戏里实际使用的方块 ID，例如 minecraft:light_gray_wool。 */
        public final String blockId;
        public final int r, g, b;
        /** 预计算的 Lab 值，避免每次匹配重复转换。 */
        final double labL, labA, labB;

        Wool(String id, String javaName, String zhName, int r, int g, int b) {
            this.id = id;
            // 注意：Java 版和基岩版的方块 ID 有差异，不能直接拿贴图名拼。
            // 最典型的是"淡灰色羊毛"：基岩版叫 silver_wool，Java 版叫
            // light_gray_wool。写错的话 /fill 会直接报"未知的方块类型"。
            this.blockId = "minecraft:" + javaName + "_wool";
            this.zhName = zhName;
            this.r = r;
            this.g = g;
            this.b = b;
            double[] lab = ColorMath.rgbToLab(r, g, b);
            this.labL = lab[0];
            this.labA = lab[1];
            this.labB = lab[2];
        }

        @Override
        public String toString() {
            return zhName;
        }
    }

    /**
     * 16 色羊毛。
     *
     * <p>每项是 {贴图名, Java 版方块名, 中文名, R, G, B}。
     * RGB 取自 Mojang 官方资源包贴图 wool_colored_*.png 的平均色——这些值不能凭
     * 肉眼估，官方贴图带细微的噪点和明暗变化，直接用"标准染料色"会有可见偏差。
     *
     * <p>"Java 版方块名"单独列出来，是因为它和贴图名并不总是一致（见 Wool 的注释）。
     */
    private static final Object[][] RAW = {
        {"white",      "white",      "白色羊毛",   234, 236, 237},
        {"orange",     "orange",     "橙色羊毛",   241, 118,  20},
        {"magenta",    "magenta",    "品红色羊毛", 190,  69, 180},
        {"light_blue", "light_blue", "淡蓝色羊毛",  58, 175, 217},
        {"yellow",     "yellow",     "黄色羊毛",   249, 198,  40},
        {"lime",       "lime",       "黄绿色羊毛", 112, 185,  26},
        {"pink",       "pink",       "粉红色羊毛", 238, 141, 172},
        {"gray",       "gray",       "灰色羊毛",    63,  68,  72},
        {"silver",     "light_gray", "淡灰色羊毛", 142, 142, 135},
        {"cyan",       "cyan",       "青色羊毛",    21, 138, 145},
        {"purple",     "purple",     "紫色羊毛",   122,  42, 173},
        {"blue",       "blue",       "蓝色羊毛",    53,  57, 157},
        {"brown",      "brown",      "棕色羊毛",   114,  72,  41},
        {"green",      "green",      "绿色羊毛",    85, 110,  28},
        {"red",        "red",        "红色羊毛",   161,  39,  35},
        {"black",      "black",      "黑色羊毛",    21,  21,  26},
    };

    private static final List<Wool> ALL = new ArrayList<>();
    /** 每种羊毛在 ALL 里的下标到自身索引的快速查表，按 5 位 RGB 缓存最近色。 */
    private static final int CACHE_BITS = 5;
    private static final int CACHE_SIZE = 1 << (CACHE_BITS * 3);
    private static final byte[] CACHE_2000 = new byte[CACHE_SIZE];
    private static final byte[] CACHE_76 = new byte[CACHE_SIZE];
    private static final byte[] CACHE_RGB = new byte[CACHE_SIZE];
    private static final boolean[] CACHE_VALID = new boolean[CACHE_SIZE];
    private static final boolean[] CACHE_VALID_76 = new boolean[CACHE_SIZE];
    private static final boolean[] CACHE_VALID_RGB = new boolean[CACHE_SIZE];

    public static final int MODE_CIEDE2000 = 0;
    public static final int MODE_CIE76 = 1;
    public static final int MODE_RGB = 2;

    static {
        for (Object[] row : RAW) {
            // 列顺序：{贴图名, Java 方块名, 中文名, R, G, B}
            ALL.add(new Wool(
                (String) row[0], (String) row[1], (String) row[2],
                (Integer) row[3], (Integer) row[4], (Integer) row[5]
            ));
        }
    }

    private WoolPalette() {
    }

    public static List<Wool> all() {
        return ALL;
    }

    public static int size() {
        return ALL.size();
    }

    public static Wool get(int index) {
        return ALL.get(index);
    }

    /**
     * 找与给定颜色最接近的羊毛下标。
     *
     * @param enabled 长度等于 {@link #size()} 的开关数组，null 表示全部可用
     * @param mode    {@link #MODE_CIEDE2000} / {@link #MODE_CIE76} / {@link #MODE_RGB}
     */
    public static int nearest(int r, int g, int b, boolean[] enabled, int mode) {
        boolean allEnabled = true;
        if (enabled != null) {
            for (boolean e : enabled) {
                if (!e) {
                    allEnabled = false;
                    break;
                }
            }
        }

        // 只有"全部颜色可用"时才走 5 位缓存：否则缓存键还要带上启用掩码，不划算
        if (allEnabled) {
            int key = cacheKey(r, g, b);
            boolean[] valid = validArray(mode);
            byte[] cache = cacheArray(mode);
            if (valid[key]) {
                return cache[key] & 0xFF;
            }
            int best = computeNearest(r, g, b, null, mode);
            cache[key] = (byte) best;
            valid[key] = true;
            return best;
        }
        return computeNearest(r, g, b, enabled, mode);
    }

    private static int computeNearest(int r, int g, int b, boolean[] enabled, int mode) {
        double[] lab = mode == MODE_RGB ? null : ColorMath.rgbToLab(r, g, b);
        int bestIndex = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < ALL.size(); i++) {
            if (enabled != null && !enabled[i]) {
                continue;
            }
            Wool w = ALL.get(i);
            double d;
            switch (mode) {
                case MODE_CIE76:
                    d = ColorMath.deltaE76(lab, w.labL, w.labA, w.labB);
                    break;
                case MODE_RGB:
                    double dr = r - w.r;
                    double dg = g - w.g;
                    double db = b - w.b;
                    d = dr * dr + dg * dg + db * db;
                    break;
                case MODE_CIEDE2000:
                default:
                    d = ColorMath.deltaE2000(lab[0], lab[1], lab[2], w.labL, w.labA, w.labB);
                    break;
            }
            if (d < bestDistance) {
                bestDistance = d;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static int cacheKey(int r, int g, int b) {
        int shift = 8 - CACHE_BITS;
        return ((r >> shift) << (CACHE_BITS * 2)) | ((g >> shift) << CACHE_BITS) | (b >> shift);
    }

    private static byte[] cacheArray(int mode) {
        switch (mode) {
            case MODE_CIE76: return CACHE_76;
            case MODE_RGB: return CACHE_RGB;
            default: return CACHE_2000;
        }
    }

    private static boolean[] validArray(int mode) {
        switch (mode) {
            case MODE_CIE76: return CACHE_VALID_76;
            case MODE_RGB: return CACHE_VALID_RGB;
            default: return CACHE_VALID;
        }
    }

    public static String modeName(int mode) {
        switch (mode) {
            case MODE_CIE76: return "CIE76（Lab 欧氏距离，快一点）";
            case MODE_RGB: return "RGB 距离（最快，最不准）";
            default: return "CIEDE2000（推荐，最接近人眼）";
        }
    }
}
