package com.woolipic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 找出玩家电脑上"可能放着图片"的目录，并列出里面的图片。
 *
 * <p>为什么不用系统文件对话框：实测在这台机器上 AWT 的 FileDialog 在游戏进程里
 * 根本显示不出来（日志显示 openFileDialog 被调用了十几次，但既没有"选中"也没有
 * "取消"记录，说明卡在 setVisible 上），而且 AWT 在我们的后台线程里抛异常时不会
 * 有人接，排查起来很痛苦。所以改成纯游戏内界面：直接扫描常见目录让你点选，
 * 另外给一个可以手动粘贴路径的输入框。
 */
public final class ImageFinder {

    /** 支持的图片扩展名。 */
    private static final List<String> EXTS = List.of(
        ".png", ".jpg", ".jpeg", ".bmp", ".gif", ".webp"
    );

    /** 单个目录最多列多少个文件，避免用户目录过大时卡顿。 */
    private static final int MAX_FILES = 400;

    /** 一个候选目录。 */
    public record Folder(String label, Path path) {
    }

    private ImageFinder() {
    }

    public static boolean isImage(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String e : EXTS) {
            if (n.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集常见图片目录，按"最可能用到"排序。
     *
     * <p>会跳过不存在的目录，所以列表长度不固定。
     */
    public static List<Folder> commonFolders() {
        Path home = Paths.get(System.getProperty("user.home", "."));
        Map<String, Path> candidates = new LinkedHashMap<>();
        candidates.put("游戏截图", Paths.get(System.getProperty("user.dir", "."), "screenshots"));
        candidates.put("图片", home.resolve("Pictures"));
        candidates.put("下载", home.resolve("Downloads"));
        candidates.put("桌面", home.resolve("Desktop"));
        candidates.put("文档", home.resolve("Documents"));
        candidates.put("模组图片", Config.getImagesDir());

        List<Folder> out = new ArrayList<>();
        for (Map.Entry<String, Path> e : candidates.entrySet()) {
            Path p = e.getValue();
            if (p != null && Files.isDirectory(p)) {
                out.add(new Folder(e.getKey(), p));
            }
        }
        return out;
    }

    /** 列出目录里顶层的图片，按修改时间从新到旧。 */
    public static List<Path> listImages(Path dir) {
        List<Path> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                .filter(ImageFinder::isImage)
                .sorted(Comparator.comparingLong(ImageFinder::lastModified).reversed())
                .limit(MAX_FILES)
                .forEach(out::add);
        } catch (IOException | SecurityException e) {
            WooliPic.LOGGER.warn("无法列出目录 {}: {}", dir, e.toString());
        }
        return out;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 把用户输入的路径解析成实际文件。
     *
     * <p>会去掉常见的手滑：首尾空白、外层引号（从资源管理器复制路径会带）、
     * 以及开头的 {@code file://} 前缀。
     */
    public static Path parseUserPath(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.toLowerCase(Locale.ROOT).startsWith("file://")) {
            s = s.substring("file://".length());
        }
        if (s.isEmpty()) {
            return null;
        }
        try {
            Path p = Paths.get(s);
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }
}
