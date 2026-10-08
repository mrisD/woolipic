package com.woolipic;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * 一张图片转换成的"羊毛画"数据。
 *
 * <p>转换流程：读文件 -> 旋转/翻转 -> 按目标方块尺寸缩放 -> 逐像素匹配最近羊毛色。
 * 同时算出质量指标（平均色差、局部色调误差）和用色统计，让用户在放之前就知道
 * 结果像不像。
 */
public final class WoolImage {

    /** 一幅转换好的羊毛画：{@code woolIndex[y][x]} 是羊毛下标，y=0 是最上面一行。 */
    public final int[][] woolIndex;
    public final int width;
    public final int height;
    /** 缩放后的原图，用于并排预览对照。 */
    public final BufferedImage sourceImage;
    /** 用羊毛色渲染出来的效果图（1 方块 = 1 像素），也就是游戏里会看到的样子。 */
    public final BufferedImage previewImage;
    public final double meanDeltaE;
    public final double maxDeltaE;
    public final double blockDeltaE;
    public final Map<String, Integer> usage;

    private WoolImage(
        int[][] woolIndex,
        BufferedImage sourceImage,
        BufferedImage previewImage,
        double meanDeltaE,
        double maxDeltaE,
        double blockDeltaE,
        Map<String, Integer> usage
    ) {
        this.woolIndex = woolIndex;
        this.width = woolIndex.length == 0 ? 0 : woolIndex[0].length;
        this.height = woolIndex.length;
        this.sourceImage = sourceImage;
        this.previewImage = previewImage;
        this.meanDeltaE = meanDeltaE;
        this.maxDeltaE = maxDeltaE;
        this.blockDeltaE = blockDeltaE;
        this.usage = usage;
    }

    /** 转换参数。 */
    public static final class Options {
        /** 目标宽度（方块数）。 */
        public int blockWidth = 128;
        /** 目标高度；<=0 表示按原图比例自动计算。 */
        public int blockHeight = 0;
        /** true = 按比例自动算高度（忽略 blockHeight）。 */
        public boolean autoHeight = true;
        /** true = 居中裁剪成目标比例（不变形）；false = 拉伸填满。 */
        public boolean crop = false;
        public int rotate = 0;
        public boolean flipH = false;
        /** 自动增强对比度与饱和度，让 16 色下主体更清楚。 */
        public boolean enhance = false;
        public int colorMode = WoolPalette.MODE_CIEDE2000;
        /** 每种羊毛是否可用；null 表示全部可用。 */
        public boolean[] enabledWools;

        public Options copy() {
            Options o = new Options();
            o.blockWidth = blockWidth;
            o.blockHeight = blockHeight;
            o.autoHeight = autoHeight;
            o.crop = crop;
            o.rotate = rotate;
            o.flipH = flipH;
            o.enhance = enhance;
            o.colorMode = colorMode;
            o.enabledWools = enabledWools == null ? null : enabledWools.clone();
            return o;
        }
    }

    public static WoolImage loadAndConvert(File file, Options options) throws IOException {
        BufferedImage original = ImageIO.read(file);
        if (original == null) {
            throw new IOException(
                "无法识别这个图片格式（支持 PNG / JPG / BMP / GIF）。\n文件：" + file.getName()
            );
        }
        return convert(original, options);
    }

    public static WoolImage convert(BufferedImage original, Options options) {
        BufferedImage img = toRgb(original);
        img = applyTransform(img, options.rotate, options.flipH);
        if (options.enhance) {
            img = enhance(img);
        }

        int gridW = Math.max(1, options.blockWidth);
        int gridH;
        if (options.autoHeight) {
            gridH = Math.max(1, (int) Math.round(
                (double) gridW * img.getHeight() / img.getWidth()
            ));
        } else {
            gridH = Math.max(1, options.blockHeight);
        }
        if (options.crop) {
            img = centerCrop(img, (double) gridW / gridH);
        }

        BufferedImage scaled = scale(img, gridW, gridH);
        int[][] index = new int[gridH][gridW];
        BufferedImage preview = new BufferedImage(gridW, gridH, BufferedImage.TYPE_INT_RGB);
        Map<String, Integer> usage = new LinkedHashMap<>();

        // 逐像素：查询最近羊毛色，并累计色差用于质量评估
        double sumDelta = 0;
        double maxDelta = 0;
        for (int y = 0; y < gridH; y++) {
            for (int x = 0; x < gridW; x++) {
                int rgb = scaled.getRGB(x, y) & 0xFFFFFF;
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                int wool = WoolPalette.nearest(r, g, b, options.enabledWools, options.colorMode);
                index[y][x] = wool;

                WoolPalette.Wool w = WoolPalette.get(wool);
                preview.setRGB(x, y, (w.r << 16) | (w.g << 8) | w.b);
                usage.merge(w.zhName, 1, Integer::sum);

                double[] lab1 = ColorMath.rgbToLab(r, g, b);
                double d = ColorMath.deltaE2000(lab1[0], lab1[1], lab1[2], w.labL, w.labA, w.labB);
                sumDelta += d;
                if (d > maxDelta) {
                    maxDelta = d;
                }
            }
        }

        double blockDelta = blockMeanDeltaE(scaled, preview, 8);
        Map<String, Integer> sorted = sortByValueDesc(usage);

        return new WoolImage(
            index, scaled, preview,
            sumDelta / (gridW * (double) gridH), maxDelta, blockDelta, sorted
        );
    }

    /**
     * 8x8 分块比较"平均色"的色差。
     *
     * <p>这个指标比逐像素色差更接近人的实际感受：远看一幅羊毛画，看到的是小区域内
     * 的平均颜色，而不是单个方块。
     */
    private static double blockMeanDeltaE(BufferedImage a, BufferedImage b, int block) {
        int w = a.getWidth();
        int h = a.getHeight();
        int bw = Math.max(1, w / block);
        int bh = Math.max(1, h / block);
        int stepX = Math.max(1, w / bw);
        int stepY = Math.max(1, h / bh);

        double total = 0;
        int count = 0;
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                long ar = 0, ag = 0, ab = 0, br = 0, bg = 0, bb = 0;
                int n = 0;
                for (int y = by * stepY; y < Math.min(h, (by + 1) * stepY); y++) {
                    for (int x = bx * stepX; x < Math.min(w, (bx + 1) * stepX); x++) {
                        int p = a.getRGB(x, y);
                        ar += (p >> 16) & 0xFF;
                        ag += (p >> 8) & 0xFF;
                        ab += p & 0xFF;
                        int q = b.getRGB(x, y);
                        br += (q >> 16) & 0xFF;
                        bg += (q >> 8) & 0xFF;
                        bb += q & 0xFF;
                        n++;
                    }
                }
                if (n == 0) {
                    continue;
                }
                double[] lab1 = ColorMath.rgbToLab((int) (ar / n), (int) (ag / n), (int) (ab / n));
                double[] lab2 = ColorMath.rgbToLab((int) (br / n), (int) (bg / n), (int) (bb / n));
                total += ColorMath.deltaE2000(
                    lab1[0], lab1[1], lab1[2], lab2[0], lab2[1], lab2[2]
                );
                count++;
            }
        }
        return count == 0 ? 0 : total / count;
    }

    /** 把预览图放大成适合界面上显示的大小（最近邻，保留方块颗粒感）。 */
    public static BufferedImage scaleNearest(BufferedImage src, int maxW, int maxH) {
        double s = Math.min(maxW / (double) src.getWidth(), maxH / (double) src.getHeight());
        if (s <= 0) {
            s = 1;
        }
        int w = Math.max(1, (int) Math.round(src.getWidth() * s));
        int h = Math.max(1, (int) Math.round(src.getHeight() * s));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        );
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    // ---------------------------------------------------------------- 内部工具

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(
            src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g = out.createGraphics();
        // 透明像素合成到白底上：羊毛没有透明色，得给个合理的底色
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage applyTransform(BufferedImage src, int rotate, boolean flipH) {
        BufferedImage img = src;
        if (flipH) {
            BufferedImage out = new BufferedImage(
                img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB
            );
            Graphics2D g = out.createGraphics();
            g.drawImage(
                img, img.getWidth(), 0, 0, img.getHeight(), 0, 0, img.getWidth(), img.getHeight(), null
            );
            g.dispose();
            img = out;
        }
        int deg = ((rotate % 360) + 360) % 360;
        if (deg == 0) {
            return img;
        }
        boolean swap = deg == 90 || deg == 270;
        int w = swap ? img.getHeight() : img.getWidth();
        int h = swap ? img.getWidth() : img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        // 顺时针旋转：以画布中心旋转后平移回来
        g.rotate(Math.toRadians(deg), w / 2.0, h / 2.0);
        g.drawImage(img, (w - img.getWidth()) / 2, (h - img.getHeight()) / 2, null);
        g.dispose();
        return out;
    }

    private static BufferedImage centerCrop(BufferedImage src, double targetRatio) {
        double srcRatio = src.getWidth() / (double) src.getHeight();
        if (Math.abs(srcRatio - targetRatio) < 1e-6) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        if (srcRatio > targetRatio) {
            w = Math.max(1, (int) Math.round(h * targetRatio));
        } else {
            h = Math.max(1, (int) Math.round(w / targetRatio));
        }
        int x = (src.getWidth() - w) / 2;
        int y = (src.getHeight() - h) / 2;
        return src.getSubimage(x, y, w, h);
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        // 缩小时用双三次，能得到平滑的平均结果；否则会出现摩尔纹
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC
        );
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage enhance(BufferedImage src) {
        BufferedImage out = new BufferedImage(
            src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB
        );
        // 先统计亮度直方图，做 1% 截断的自动对比度；再略微提饱和度
        int[] hist = new int[256];
        int[] pixels = src.getRGB(0, 0, src.getWidth(), src.getHeight(), null, 0, src.getWidth());
        for (int p : pixels) {
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            hist[(int) (0.299 * r + 0.587 * g + 0.114 * b)]++;
        }
        int total = pixels.length;
        int lowCut = total / 100;
        int highCut = total - lowCut;
        int low = 0;
        int high = 255;
        int acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= lowCut) {
                low = i;
                break;
            }
        }
        acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= highCut) {
                high = i;
                break;
            }
        }
        double range = Math.max(1, high - low);
        final double saturation = 1.15;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            r = clamp((int) Math.round((r - low) * 255.0 / range));
            g = clamp((int) Math.round((g - low) * 255.0 / range));
            b = clamp((int) Math.round((b - low) * 255.0 / range));
            int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
            r = clamp((int) Math.round(lum + (r - lum) * saturation));
            g = clamp((int) Math.round(lum + (g - lum) * saturation));
            b = clamp((int) Math.round(lum + (b - lum) * saturation));
            pixels[i] = (r << 16) | (g << 8) | b;
        }
        out.setRGB(0, 0, src.getWidth(), src.getHeight(), pixels, 0, src.getWidth());
        return out;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static Map<String, Integer> sortByValueDesc(Map<String, Integer> map) {
        Map<String, Integer> out = new LinkedHashMap<>();
        map.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** 供调试/自检：把方块数组转成方便比对的整数列表。 */
    public int[] flatten() {
        int[] out = new int[width * height];
        int i = 0;
        for (int[] row : woolIndex) {
            System.arraycopy(row, 0, out, i, row.length);
            i += row.length;
        }
        return out;
    }

    @Override
    public String toString() {
        return "WoolImage[" + width + "x" + height + ", ΔE=" + String.format("%.2f", meanDeltaE) + "]";
    }

    /** 用色统计的可读形式，取前几名。 */
    public String usageSummary(int topN) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        int total = width * height;
        for (Map.Entry<String, Integer> e : usage.entrySet()) {
            if (n++ >= topN) {
                break;
            }
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append(e.getKey()).append(' ')
              .append(String.format("%.0f%%", e.getValue() * 100.0 / total));
        }
        return sb.toString();
    }

    /** 用到的羊毛种类数。 */
    public int distinctWools() {
        return usage.size();
    }

    /** 全部可用时的默认开关数组。 */
    public static boolean[] allEnabled() {
        boolean[] e = new boolean[WoolPalette.size()];
        Arrays.fill(e, true);
        return e;
    }
}
