package com.woolipic;

/**
 * 色彩空间转换与色差公式。
 *
 * <p>CIEDE2000 的实现遵循 Sharma, Wu &amp; Dalal (2005) 的论文，并用该论文的
 * 34 组标准测试数据做了自检（{@link #selfTest()}）。这不是可以直接凭印象写的
 * 公式：里面有若干处"当 a'=b'=0 时色相角按 0 处理""色相差超过 180 度要折算"
 * 之类的边界特例，写错一处结果就整体偏移。
 */
public final class ColorMath {

    /** D65 白点。 */
    private static final double WHITE_X = 0.95047;
    private static final double WHITE_Y = 1.00000;
    private static final double WHITE_Z = 1.08883;

    private static final double SRGB_THRESHOLD = 0.04045;
    private static final double POW25_7 = 6103515625.0; // 25^7

    private ColorMath() {
    }

    /** sRGB(0..255) -> CIE Lab(D65)，返回 [L, a, b]。 */
    public static double[] rgbToLab(int r, int g, int b) {
        double rl = srgbToLinear(r / 255.0);
        double gl = srgbToLinear(g / 255.0);
        double bl = srgbToLinear(b / 255.0);

        double x = 0.4124564 * rl + 0.3575761 * gl + 0.1804375 * bl;
        double y = 0.2126729 * rl + 0.7151522 * gl + 0.0721750 * bl;
        double z = 0.0193339 * rl + 0.1191920 * gl + 0.9503041 * bl;

        x /= WHITE_X;
        y /= WHITE_Y;
        z /= WHITE_Z;

        double fx = labF(x);
        double fy = labF(y);
        double fz = labF(z);

        return new double[] {
            116.0 * fy - 16.0,
            500.0 * (fx - fy),
            200.0 * (fy - fz)
        };
    }

    private static double srgbToLinear(double c) {
        if (c <= 0.0) {
            return 0.0;
        }
        if (c >= 1.0) {
            return 1.0;
        }
        return c <= SRGB_THRESHOLD
            ? c / 12.92
            : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double labF(double t) {
        final double eps = 216.0 / 24389.0;
        final double kappa = 24389.0 / 27.0;
        if (t > eps) {
            return Math.cbrt(t);
        }
        return (kappa * t + 16.0) / 116.0;
    }

    /** CIEDE2000 色差。数值含义：&lt;1 几乎看不出，1~2 细看能发现，&gt;5 明显不同。 */
    public static double deltaE2000(
        double l1, double a1, double b1,
        double l2, double a2, double b2
    ) {
        double c1 = Math.hypot(a1, b1);
        double c2 = Math.hypot(a2, b2);
        double cBar = (c1 + c2) / 2.0;

        double cBar7 = Math.pow(cBar, 7);
        double g = 0.5 * (1.0 - Math.sqrt(cBar7 / (cBar7 + POW25_7)));

        double a1p = (1.0 + g) * a1;
        double a2p = (1.0 + g) * a2;
        double c1p = Math.hypot(a1p, b1);
        double c2p = Math.hypot(a2p, b2);

        double h1p = hueAngle(a1p, b1);
        double h2p = hueAngle(a2p, b2);

        double dLp = l2 - l1;
        double dCp = c2p - c1p;

        double c1pc2p = c1p * c2p;
        double dhp = h2p - h1p;
        if (dhp > 180.0) {
            dhp -= 360.0;
        } else if (dhp < -180.0) {
            dhp += 360.0;
        }
        if (c1pc2p == 0.0) {
            dhp = 0.0;
        }
        double dHp = 2.0 * Math.sqrt(c1pc2p) * Math.sin(Math.toRadians(dhp / 2.0));

        double lBarP = (l1 + l2) / 2.0;
        double cBarP = (c1p + c2p) / 2.0;

        double hSum = h1p + h2p;
        double hDiff = Math.abs(h1p - h2p);
        double hBarP;
        if (c1pc2p == 0.0) {
            hBarP = hSum;
        } else if (hDiff <= 180.0) {
            hBarP = hSum / 2.0;
        } else if (hSum < 360.0) {
            hBarP = (hSum + 360.0) / 2.0;
        } else {
            hBarP = (hSum - 360.0) / 2.0;
        }

        double t = 1.0
            - 0.17 * Math.cos(Math.toRadians(hBarP - 30.0))
            + 0.24 * Math.cos(Math.toRadians(2.0 * hBarP))
            + 0.32 * Math.cos(Math.toRadians(3.0 * hBarP + 6.0))
            - 0.20 * Math.cos(Math.toRadians(4.0 * hBarP - 63.0));

        double dTheta = 30.0 * Math.exp(-Math.pow((hBarP - 275.0) / 25.0, 2));
        double cBarP7 = Math.pow(cBarP, 7);
        double rc = 2.0 * Math.sqrt(cBarP7 / (cBarP7 + POW25_7));
        double rt = -rc * Math.sin(Math.toRadians(2.0 * dTheta));

        double lBarP50 = Math.pow(lBarP - 50.0, 2);
        double sl = 1.0 + (0.015 * lBarP50) / Math.sqrt(20.0 + lBarP50);
        double sc = 1.0 + 0.045 * cBarP;
        double sh = 1.0 + 0.015 * cBarP * t;

        double termL = dLp / sl;
        double termC = dCp / sc;
        double termH = dHp / sh;

        return Math.sqrt(
            termL * termL + termC * termC + termH * termH + rt * termC * termH
        );
    }

    /** 色相角（度），a'=b'=0 时定义为 0。 */
    private static double hueAngle(double ap, double bp) {
        if (ap == 0.0 && bp == 0.0) {
            return 0.0;
        }
        double h = Math.toDegrees(Math.atan2(bp, ap));
        return h < 0.0 ? h + 360.0 : h;
    }

    /** CIE76 色差：Lab 空间欧氏距离。快，但不如 CIEDE2000 贴合人眼。 */
    public static double deltaE76(double[] lab, double l2, double a2, double b2) {
        double dl = lab[0] - l2;
        double da = lab[1] - a2;
        double db = lab[2] - b2;
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    /**
     * 用 Sharma 论文的 34 组标准数据自检 CIEDE2000 实现。
     *
     * @return 最大绝对偏差；小于 1e-4 表示实现正确
     */
    public static double selfTest() {
        double[][] data = {
            {50.0000, 2.6772, -79.7751, 50.0000, 0.0000, -82.7485, 2.0425},
            {50.0000, 3.1571, -77.2803, 50.0000, 0.0000, -82.7485, 2.8615},
            {50.0000, 2.8361, -74.0200, 50.0000, 0.0000, -82.7485, 3.4412},
            {50.0000, -1.3802, -84.2814, 50.0000, 0.0000, -82.7485, 1.0000},
            {50.0000, -1.1848, -84.8006, 50.0000, 0.0000, -82.7485, 1.0000},
            {50.0000, -0.9009, -85.5211, 50.0000, 0.0000, -82.7485, 1.0000},
            {50.0000, 0.0000, 0.0000, 50.0000, -1.0000, 2.0000, 2.3669},
            {50.0000, -1.0000, 2.0000, 50.0000, 0.0000, 0.0000, 2.3669},
            {50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0009, 7.1792},
            {50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0010, 7.1792},
            {50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0011, 7.2195},
            {50.0000, 2.4900, -0.0010, 50.0000, -2.4900, 0.0012, 7.2195},
            {50.0000, -0.0010, 2.4900, 50.0000, 0.0009, -2.4900, 4.8045},
            {50.0000, -0.0010, 2.4900, 50.0000, 0.0010, -2.4900, 4.8045},
            {50.0000, -0.0010, 2.4900, 50.0000, 0.0011, -2.4900, 4.7461},
            {50.0000, 2.5000, 0.0000, 50.0000, 0.0000, -2.5000, 4.3065},
            {50.0000, 2.5000, 0.0000, 73.0000, 25.0000, -18.0000, 27.1492},
            {50.0000, 2.5000, 0.0000, 61.0000, -5.0000, 29.0000, 22.8977},
            {50.0000, 2.5000, 0.0000, 56.0000, -27.0000, -3.0000, 31.9030},
            {50.0000, 2.5000, 0.0000, 58.0000, 24.0000, 15.0000, 19.4535},
            {50.0000, 2.5000, 0.0000, 50.0000, 3.1736, 0.5854, 1.0000},
            {50.0000, 2.5000, 0.0000, 50.0000, 3.2972, 0.0000, 1.0000},
            {50.0000, 2.5000, 0.0000, 50.0000, 1.8634, 0.5757, 1.0000},
            {50.0000, 2.5000, 0.0000, 50.0000, 3.2592, 0.3350, 1.0000},
            {60.2574, -34.0099, 36.2677, 60.4626, -34.1751, 39.4387, 1.2644},
            {63.0109, -31.0961, -5.8663, 62.8187, -29.7946, -4.0864, 1.2630},
            {61.2901, 3.7196, -5.3901, 61.4292, 2.2480, -4.9620, 1.8731},
            {35.0831, -44.1164, 3.7933, 35.0232, -40.0716, 1.5901, 1.8645},
            {22.7233, 20.0904, -46.6940, 23.0331, 14.9730, -42.5619, 2.0373},
            {36.4612, 47.8580, 18.3852, 36.2715, 50.5065, 21.2231, 1.4146},
            {90.8027, -2.0831, 1.4410, 91.1528, -1.6435, 0.0447, 1.4441},
            {90.9257, -0.5406, -0.9208, 88.6381, -0.8985, -0.7239, 1.5381},
            {6.7747, -0.2908, -2.4247, 5.8714, -0.0985, -2.2286, 0.6377},
            {2.0776, 0.0795, -1.1350, 0.9033, -0.0636, -0.5514, 0.9082},
        };
        double worst = 0.0;
        for (double[] d : data) {
            double got = deltaE2000(d[0], d[1], d[2], d[3], d[4], d[5]);
            worst = Math.max(worst, Math.abs(got - d[6]));
        }
        return worst;
    }
}
