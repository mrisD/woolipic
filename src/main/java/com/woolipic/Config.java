package com.woolipic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * 模组设置：窗口里的选项会自动存下来，下次打开还是上次那套参数。
 *
 * <p>同时负责管理"用过的图片"目录 {@code config/woolipic/images/}。把用户选过的
 * 图片复制一份进去，这样：
 * <ul>
 *   <li>界面里能列出历史图片，不用每次重新翻文件夹</li>
 *   <li>原图被移动或删除后，模组这边仍然能重新生成</li>
 * </ul>
 */
public final class Config {

    private static final Properties PROPS = new Properties();
    private static Path dir;
    private static Path imagesDir;
    private static Path propsFile;

    // 界面默认值
    public static int blockWidth = 128;
    public static boolean autoHeight = true;
    public static int blockHeight = 128;
    public static boolean crop = false;
    public static boolean enhance = false;
    public static int colorMode = WoolPalette.MODE_CIEDE2000;
    public static int rotate = 0;
    public static boolean flipH = false;
    public static int frontDistance = 3;
    public static int commandsPerTick = 4;
    public static String lastImage = "";

    private Config() {
    }

    public static void init() {
        dir = FMLPaths.CONFIGDIR.get().resolve("woolipic");
        imagesDir = dir.resolve("images");
        propsFile = dir.resolve("settings.properties");
        try {
            Files.createDirectories(imagesDir);
        } catch (IOException e) {
            WooliPic.LOGGER.warn("无法创建配置目录: {}", imagesDir, e);
        }
        load();
    }

    public static Path getImagesDir() {
        return imagesDir;
    }

    public static Path getDir() {
        return dir;
    }

    private static void load() {
        if (propsFile == null || !Files.isRegularFile(propsFile)) {
            return;
        }
        try (var in = Files.newInputStream(propsFile)) {
            PROPS.load(in);
            blockWidth = intOf("blockWidth", blockWidth);
            autoHeight = boolOf("autoHeight", autoHeight);
            blockHeight = intOf("blockHeight", blockHeight);
            crop = boolOf("crop", crop);
            enhance = boolOf("enhance", enhance);
            colorMode = intOf("colorMode", colorMode);
            rotate = intOf("rotate", rotate);
            flipH = boolOf("flipH", flipH);
            frontDistance = intOf("frontDistance", frontDistance);
            commandsPerTick = intOf("commandsPerTick", commandsPerTick);
            lastImage = PROPS.getProperty("lastImage", "");
        } catch (IOException e) {
            WooliPic.LOGGER.warn("读取设置失败，使用默认值", e);
        }
    }

    public static void save() {
        if (propsFile == null) {
            return;
        }
        PROPS.setProperty("blockWidth", String.valueOf(blockWidth));
        PROPS.setProperty("autoHeight", String.valueOf(autoHeight));
        PROPS.setProperty("blockHeight", String.valueOf(blockHeight));
        PROPS.setProperty("crop", String.valueOf(crop));
        PROPS.setProperty("enhance", String.valueOf(enhance));
        PROPS.setProperty("colorMode", String.valueOf(colorMode));
        PROPS.setProperty("rotate", String.valueOf(rotate));
        PROPS.setProperty("flipH", String.valueOf(flipH));
        PROPS.setProperty("frontDistance", String.valueOf(frontDistance));
        PROPS.setProperty("commandsPerTick", String.valueOf(commandsPerTick));
        PROPS.setProperty("lastImage", lastImage == null ? "" : lastImage);
        try {
            Files.createDirectories(dir);
            try (var out = Files.newOutputStream(propsFile)) {
                PROPS.store(out, "woolipic settings");
            }
        } catch (IOException e) {
            WooliPic.LOGGER.warn("保存设置失败", e);
        }
    }

    private static int intOf(String key, int def) {
        try {
            return Integer.parseInt(PROPS.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean boolOf(String key, boolean def) {
        return Boolean.parseBoolean(PROPS.getProperty(key, String.valueOf(def)).trim());
    }

    /**
     * 把用户选的图片复制进模组目录，返回副本路径。
     *
     * <p>重名时自动加后缀，不会覆盖之前的图。
     */
    public static Path importImage(Path source) throws IOException {
        Files.createDirectories(imagesDir);
        String base = source.getFileName().toString();
        String stem = base;
        String ext = "";
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            stem = base.substring(0, dot);
            ext = base.substring(dot);
        }
        // 文件名净化：只保留字母数字和常见符号，避免中文/空格在某些环境下出问题
        String safe = stem.replaceAll("[^A-Za-z0-9_\\-]", "_").toLowerCase(Locale.ROOT);
        if (safe.isEmpty()) {
            safe = "image";
        }
        Path target = imagesDir.resolve(safe + ext.toLowerCase(Locale.ROOT));
        int n = 1;
        while (Files.exists(target)) {
            if (Files.isSameFile(source, target)) {
                return target;
            }
            target = imagesDir.resolve(safe + "_" + n + ext.toLowerCase(Locale.ROOT));
            n++;
        }
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        return target;
    }
}
