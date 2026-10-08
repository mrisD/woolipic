package com.woolipic;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 羊毛画生成器界面。
 *
 * <p>布局：左边是"原图 / 羊毛效果"并排预览和本地图片列表，右边是尺寸、朝向、色彩
 * 算法和各种开关。改任何参数都会立刻重算预览，所以能一边调一边看效果。
 *
 * <p>性能上的考虑：预览图会先缩到 256 像素以内再上传成贴图，避免大图占用显存；
 * 图片的读取和转换放在后台线程，不阻塞渲染。
 */
public class WooliPicScreen extends Screen {

    private static final int PREVIEW_MAX = 256;
    private static final int BG = 0xC0101010;
    private static final int PANEL = 0x40FFFFFF;
    private static final int TEXT = 0xFFFFFF;
    private static final int TEXT_DIM = 0xFFA0A0A0;
    private static final int ACCENT = 0xFF7FD4FF;

    // 界面上不变的文字预先建好：render 每帧都会跑，别在里面反复 new
    private static final Component TITLE = Component.literal("羊毛画生成器");
    private static final Component LBL_SOURCE = Component.literal("原图");
    private static final Component LBL_PREVIEW = Component.literal("羊毛效果（游戏里就是这样）");
    private static final Component LBL_SIZE = Component.literal("宽度  高度（方块）");
    private static final Component LBL_MODE = Component.literal("色彩匹配算法");
    private static final Component LBL_WOOLS = Component.literal("允许使用的羊毛（点方块开关）");
    private static final Component LBL_DISTANCE = Component.literal("放置在身前（格）");
    private static final Component LBL_CONVERTING = Component.literal("转换中…");
    private static final Component HINT_CLOSE = Component.literal("按 Esc 关闭；放置时请站在空地上");
    private static final Component NO_IMAGE = Component.literal("先选一张图片（点右边的「浏览本地图片…」）");
    private static final Component DIALOG_TITLE = Component.literal("选择一张图片");

    private final List<Path> imageFiles = new ArrayList<>();
    private int selectedIndex = -1;

    /** 当前选中的图片文件和它的原始尺寸。 */
    private Path selectedFile;
    private int sourceWidth;
    private int sourceHeight;

    /** 转换结果与它的预览贴图。 */
    private WoolImage result;
    private String status = "先选一张图片（点右边的「浏览本地图片…」）";
    private boolean statusIsError = false;
    private boolean converting = false;

    private DynamicTexture sourceTexture;
    private DynamicTexture previewTexture;
    private ResourceLocation sourceTextureId;
    private ResourceLocation previewTextureId;
    private int sourceTexW;
    private int sourceTexH;
    private int previewTexW;
    private int previewTexH;

    /** 界面参数（改完存回 Config）。 */
    private int blockWidth = Config.blockWidth;
    private boolean autoHeight = Config.autoHeight;
    private int blockHeight = Config.blockHeight;
    private boolean crop = Config.crop;
    private boolean enhance = Config.enhance;
    private int colorMode = Config.colorMode;
    private int frontDistance = Config.frontDistance;
    private boolean[] enabledWools = WoolImage.allEnabled();

    private EditBox widthBox;
    private EditBox heightBox;
    private EditBox distanceBox;
    private Button browseButton;
    private Button buildButton;
    private Button colorModeButton;
    private Button cropButton;
    private Button enhanceButton;
    private Button autoHeightButton;
    private Button prevButton;
    private Button nextButton;

    /** 图片列表的滚动位置。 */
    private int listScroll;

    public WooliPicScreen() {
        super(Component.literal("羊毛画生成器"));
    }

    @Override
    protected void init() {
        refreshFileList();
        // 恢复上次用过的那张图
        if (selectedFile == null && !Config.lastImage.isEmpty()) {
            Path p = Path.of(Config.lastImage);
            if (Files.isRegularFile(p)) {
                int idx = imageFiles.indexOf(p);
                if (idx < 0) {
                    imageFiles.add(0, p);
                    idx = 0;
                }
                selectedIndex = idx;
                selectFile(p);
            }
        }

        int rightX = rightPanelX();
        int y = 34;
        int w = 150;

        browseButton = Button.builder(
            Component.literal("浏览本地图片…"), b -> openFileDialog()
        ).bounds(rightX, y, w, 20).build();
        addRenderableWidget(browseButton);
        y += 26;

        prevButton = Button.builder(Component.literal("◀"), b -> stepSelection(-1))
            .bounds(rightX, y, 20, 20).build();
        nextButton = Button.builder(Component.literal("▶"), b -> stepSelection(1))
            .bounds(rightX + w - 20, y, 20, 20).build();
        addRenderableWidget(prevButton);
        addRenderableWidget(nextButton);
        y += 30;

        // --- 尺寸 ---
        widthBox = new EditBox(font, rightX, y, 60, 18, Component.literal("宽度"));
        widthBox.setValue(String.valueOf(blockWidth));
        widthBox.setResponder(s -> {
            Integer v = parseInt(s);
            if (v != null && v >= 4 && v <= 512) {
                blockWidth = v;
                scheduleConvert();
            }
        });
        addRenderableWidget(widthBox);

        heightBox = new EditBox(font, rightX + 90, y, 60, 18, Component.literal("高度"));
        heightBox.setValue(String.valueOf(blockHeight));
        heightBox.setResponder(s -> {
            Integer v = parseInt(s);
            if (v != null && v >= 4 && v <= 512) {
                blockHeight = v;
                scheduleConvert();
            }
        });
        addRenderableWidget(heightBox);
        y += 24;

        autoHeightButton = Button.builder(autoHeightLabel(), b -> {
            autoHeight = !autoHeight;
            b.setMessage(autoHeightLabel());
            heightBox.setEditable(!autoHeight);
            heightBox.setVisible(!autoHeight);
            scheduleConvert();
        }).bounds(rightX, y, w, 20).build();
        addRenderableWidget(autoHeightButton);
        heightBox.setEditable(!autoHeight);
        heightBox.setVisible(!autoHeight);
        y += 26;

        // --- 色彩 ---
        colorModeButton = Button.builder(
            Component.literal(shortModeName()), b -> {
                colorMode = (colorMode + 1) % 3;
                b.setMessage(Component.literal(shortModeName()));
                scheduleConvert();
            }
        ).bounds(rightX, y, w, 20).build();
        addRenderableWidget(colorModeButton);
        y += 24;

        cropButton = Button.builder(cropLabel(), b -> {
            crop = !crop;
            b.setMessage(cropLabel());
            scheduleConvert();
        }).bounds(rightX, y, w, 20).build();
        addRenderableWidget(cropButton);

        enhanceButton = Button.builder(enhanceLabel(), b -> {
            enhance = !enhance;
            b.setMessage(enhanceLabel());
            scheduleConvert();
        }).bounds(rightX, y + 24, w, 20).build();
        addRenderableWidget(enhanceButton);
        y += 52;

        // --- 颜色开关 ---
        y += 16; // 给"允许使用的羊毛"标题留位置
        int swatch = 14;
        int perRow = 4;
        for (int i = 0; i < WoolPalette.size(); i++) {
            final int index = i;
            int cx = rightX + (i % perRow) * (w / perRow);
            int cy = y + (i / perRow) * (swatch + 4);
            Button b = Button.builder(
                Component.literal(""), btn -> {
                    enabledWools[index] = !enabledWools[index];
                    scheduleConvert();
                }
            ).bounds(cx, cy, swatch, swatch).build();
            b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal(WoolPalette.get(i).zhName)
            ));
            addRenderableWidget(b);
        }
        y += (WoolPalette.size() / perRow + 1) * (swatch + 4) + 6;

        // --- 放置 ---
        distanceBox = new EditBox(font, rightX + 78, y, 42, 18, Component.literal("距离"));
        distanceBox.setValue(String.valueOf(frontDistance));
        distanceBox.setResponder(s -> {
            Integer v = parseInt(s);
            if (v != null && v >= 1 && v <= 64) {
                frontDistance = v;
            }
        });
        addRenderableWidget(distanceBox);
        y += 24;

        buildButton = Button.builder(
            Component.literal("▶ 开始放置"), b -> doBuild()
        ).bounds(rightX, y, w, 22).build();
        addRenderableWidget(buildButton);
        buildButton.active = result != null;

        // 如果 init 之前就已经有图，补算一次预览
        if (selectedFile != null && result == null) {
            scheduleConvert();
        }
    }

    // ------------------------------------------------------------ 文件列表

    private void refreshFileList() {
        imageFiles.clear();
        Path dir = Config.getImagesDir();
        if (dir != null && Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                    .filter(WooliPicScreen::looksLikeImage)
                    .sorted(Comparator.comparingLong(WooliPicScreen::lastModified).reversed())
                    .forEach(imageFiles::add);
            } catch (Exception e) {
                WooliPic.LOGGER.warn("读取图片目录失败", e);
            }
        }
    }

    private static boolean looksLikeImage(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
            || n.endsWith(".bmp") || n.endsWith(".gif");
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    private void stepSelection(int delta) {
        if (imageFiles.isEmpty()) {
            return;
        }
        if (selectedIndex < 0) {
            selectedIndex = 0;
        } else {
            selectedIndex = (selectedIndex + delta + imageFiles.size()) % imageFiles.size();
        }
        selectFile(imageFiles.get(selectedIndex));
    }

    private void selectFile(Path path) {
        selectedFile = path;
        result = null;
        updateButtons();
        scheduleConvert();
    }

    /** 打开系统文件选择框，选完复制一份进模组目录。 */
    private void openFileDialog() {
        // AWT 的文件对话框必须在非渲染线程使用，否则会和 Minecraft 的窗口互相阻塞
        Thread t = new Thread(() -> {
            java.awt.FileDialog dialog = new java.awt.FileDialog(
                (java.awt.Frame) null, "选择一张图片", java.awt.FileDialog.LOAD
            );            dialog.setFilenameFilter((dir, name) -> {
                String n = name.toLowerCase();
                return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                    || n.endsWith(".bmp") || n.endsWith(".gif");
            });
            dialog.setVisible(true);
            String dir = dialog.getDirectory();
            String file = dialog.getFile();
            if (dir == null || file == null) {
                return;
            }
            File chosen = new File(dir, file);
            try {
                Path imported = Config.importImage(chosen.toPath());
                Minecraft.getInstance().execute(() -> {
                    refreshFileList();
                    selectedIndex = imageFiles.indexOf(imported);
                    if (selectedIndex < 0 && Files.isRegularFile(imported)) {
                        imageFiles.add(0, imported);
                        selectedIndex = 0;
                    }
                    WooliPic.rememberImage(imported);
                    selectFile(imported);
                });
            } catch (Exception e) {
                WooliPic.LOGGER.error("导入图片失败", e);
                Minecraft.getInstance().execute(() -> setStatus("导入图片失败：" + e.getMessage(), true));
            }
        }, "woolipic-file-dialog");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ 转换

    private long lastConvertRequest;

    /** 参数变了就重算，但做 180ms 防抖，避免拖输入框时疯狂重算。 */
    private void scheduleConvert() {
        if (selectedFile == null) {
            return;
        }
        lastConvertRequest = System.currentTimeMillis();
        long token = lastConvertRequest;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(180);
            } catch (InterruptedException e) {
                return;
            }
            if (token != lastConvertRequest) {
                return; // 期间又改了参数，这一轮作废
            }
            doConvertNow(token);
        }, "woolipic-convert");
        t.setDaemon(true);
        t.start();
    }

    private void doConvertNow(long token) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            converting = true;
            updateButtons();
        });

        WoolImage.Options options = new WoolImage.Options();
        options.blockWidth = blockWidth;
        options.blockHeight = blockHeight;
        options.autoHeight = autoHeight;
        options.crop = crop;
        options.enhance = enhance;
        options.colorMode = colorMode;
        options.enabledWools = enabledWools;

        try {
            WoolImage converted = WoolImage.loadAndConvert(selectedFile.toFile(), options);
            if (token != lastConvertRequest) {
                return;
            }
            mc.execute(() -> {
                result = converted;
                converting = false;
                WooliPic.setLastImage(converted);
                uploadTextures(converted);
                setStatus(String.format(
                    "%d x %d 方块 · 平均色差 ΔE %.2f · 局部色调 ΔE %.2f · 用色 %d 种",
                    converted.width, converted.height,
                    converted.meanDeltaE, converted.blockDeltaE, converted.distinctWools()
                ), false);
                updateButtons();
            });
        } catch (Exception e) {
            WooliPic.LOGGER.error("转换失败", e);
            mc.execute(() -> {
                converting = false;
                setStatus("转换失败：" + e.getMessage(), true);
                updateButtons();
            });
        }
    }

    private void setStatus(String text, boolean error) {
        status = text;
        statusIsError = error;
    }

    private void updateButtons() {
        if (buildButton != null) {
            buildButton.active = result != null && !Builder.isBuilding();
        }
        if (browseButton != null) {
            browseButton.active = !converting;
        }
    }

    // ------------------------------------------------------------ 贴图

    private void uploadTextures(WoolImage image) {
        releaseTextures();
        Minecraft mc = Minecraft.getInstance();

        sourceTexW = image.sourceImage.getWidth();
        sourceTexH = image.sourceImage.getHeight();
        sourceTexture = toDynamicTexture(image.sourceImage);
        // 注意 1.21.4 的 TextureManager.register 返回 void，贴图 ID 得自己造。
        // 这里的命名空间/路径只是运行时占位，不会和资源包里的文件冲突。
        sourceTextureId = ResourceLocation.fromNamespaceAndPath("woolipic", "runtime/source");
        mc.getTextureManager().register(sourceTextureId, sourceTexture);

        BufferedImage small = WoolImage.scaleNearest(image.previewImage, PREVIEW_MAX, PREVIEW_MAX);
        previewTexW = small.getWidth();
        previewTexH = small.getHeight();
        previewTexture = toDynamicTexture(small);
        previewTextureId = ResourceLocation.fromNamespaceAndPath("woolipic", "runtime/preview");
        mc.getTextureManager().register(previewTextureId, previewTexture);
    }

    private void releaseTextures() {
        Minecraft mc = Minecraft.getInstance();
        if (sourceTextureId != null) {
            mc.getTextureManager().release(sourceTextureId);
            sourceTextureId = null;
        }
        if (previewTextureId != null) {
            mc.getTextureManager().release(previewTextureId);
            previewTextureId = null;
        }
        sourceTexture = null;
        previewTexture = null;
    }

    private static DynamicTexture toDynamicTexture(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                // 1.21.4 的 NativeImage 只有 setPixel，颜色按 0xAABBGGRR（ABGR）打包
                nativeImage.setPixel(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return new DynamicTexture(nativeImage);
    }

    // ------------------------------------------------------------ 绘制

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(
            font, Component.literal("羊毛画生成器"), width / 2, 10, ACCENT
        );

        int leftX = leftPanelX();
        int leftW = leftPanelWidth();

        // --- 预览标题与图片 ---
        graphics.drawString(font, Component.literal("原图"), leftX, 24, TEXT_DIM, false);
        graphics.drawString(
            font, Component.literal("羊毛效果（游戏里就是这样）"),
            leftX + leftW / 2 + 6, 24, TEXT_DIM, false
        );

        int boxW = leftW / 2 - 6;
        int boxH = Math.min(210, Math.max(60, height - 210));

        drawPanel(graphics, leftX, 36, boxW, boxH);
        drawPanel(graphics, leftX + boxW + 12, 36, boxW, boxH);

        if (sourceTextureId != null && sourceTexW > 0) {
            drawFitted(graphics, sourceTextureId, sourceTexW, sourceTexH,
                leftX + 2, 38, boxW - 4, boxH - 4, false);
        }
        if (previewTextureId != null && previewTexW > 0) {
            drawFitted(graphics, previewTextureId, previewTexW, previewTexH,
                leftX + boxW + 14, 38, boxW - 4, boxH - 4, true);
        } else if (converting) {
            graphics.drawCenteredString(
                font, Component.literal("转换中…"),
                leftX + boxW + 12 + boxW / 2, 36 + boxH / 2, TEXT_DIM
            );
        }

        // --- 图片列表 ---
        int listY = 36 + boxH + 8;
        graphics.drawString(
            font, Component.literal("最近的图片（" + imageFiles.size() + "）"),
            leftX, listY, TEXT_DIM, false
        );
        listY += 12;
        int rowH = 12;
        int maxRows = Math.max(1, (height - listY - 30) / rowH);
        int start = Math.min(listScroll, Math.max(0, imageFiles.size() - maxRows));
        for (int i = 0; i < maxRows && start + i < imageFiles.size(); i++) {
            int idx = start + i;
            Path p = imageFiles.get(idx);
            boolean sel = idx == selectedIndex;
            String name = p.getFileName().toString();
            int color = sel ? ACCENT : TEXT;
            if (sel) {
                graphics.fill(leftX - 2, listY - 1, leftX + leftW, listY + rowH - 2, PANEL);
            }
            graphics.drawString(font, Component.literal(name), leftX, listY, color, false);
            listY += rowH;
        }

        // --- 右侧标题 ---
        int rightX = rightPanelX();
        graphics.drawString(font, Component.literal("宽度  高度（方块）"), rightX, 24, TEXT_DIM, false);
        graphics.drawString(font, Component.literal("色彩匹配算法"), rightX, 82, TEXT_DIM, false);
        graphics.drawString(font, Component.literal("允许使用的羊毛（点方块开关）"),
            rightX, 152, TEXT_DIM, false);
        graphics.drawString(font, Component.literal("放置在身前（格）"), rightX, 236, TEXT_DIM, false);

        // --- 颜色开关的色块：按钮本身是空的，这里把颜色画上去 ---
        drawWoolSwatches(graphics, rightX, 168);

        // --- 状态栏 ---
        graphics.drawString(
            font, Component.literal(status), leftX, height - 22,
            statusIsError ? 0xFF8080 : TEXT, false
        );
        if (Builder.isBuilding()) {
            graphics.drawString(
                font, Component.literal("放置中… 剩余 " + Builder.remaining() + " 条指令"),
                leftX, height - 12, ACCENT, false
            );
        } else if (result != null) {
            graphics.drawString(
                font, Component.literal("按 Esc 关闭；放置时请站在空地上"),
                leftX, height - 12, TEXT_DIM, false
            );
        }
    }

    private void drawWoolSwatches(GuiGraphics graphics, int rightX, int y) {
        int w = 150;
        int swatch = 14;
        int perRow = 4;
        for (int i = 0; i < WoolPalette.size(); i++) {
            int cx = rightX + (i % perRow) * (w / perRow);
            int cy = y + (i / perRow) * (swatch + 4);
            WoolPalette.Wool wool = WoolPalette.get(i);
            int argb = 0xFF000000 | (wool.r << 16) | (wool.g << 8) | wool.b;
            // 可用 = 正常颜色；禁用 = 压暗到 1/4
            if (!enabledWools[i]) {
                argb = 0xFF000000
                    | ((wool.r / 4) << 16) | ((wool.g / 4) << 8) | (wool.b / 4);
            }
            graphics.fill(cx, cy, cx + swatch, cy + swatch, argb);
            if (enabledWools[i]) {
                graphics.renderOutline(cx, cy, swatch, swatch, 0xFFFFFFFF);
            } else {
                graphics.renderOutline(cx, cy, swatch, swatch, 0xFF404040);
            }
        }
    }

    private void drawPanel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, BG);
        graphics.renderOutline(x, y, w, h, PANEL);
    }

    /** 在给定区域里按比例居中画一张贴图。 */
    private void drawFitted(
        GuiGraphics graphics, ResourceLocation tex, int texW, int texH,
        int x, int y, int w, int h, boolean nearest
    ) {
        float scale = Math.min(w / (float) texW, h / (float) texH);
        int dw = Math.max(1, (int) (texW * scale));
        int dh = Math.max(1, (int) (texH * scale));
        int dx = x + (w - dw) / 2;
        int dy = y + (h - dh) / 2;
        // 1.21.4 的 blit 第一个参数是"贴图 -> 渲染类型"的函数。
        // RenderType::guiTextured 就是原版给 GUI 贴图用的那个（见 Screen.java 里的用法），
        // 这样能保证在 1.21.4 新的渲染管线下走对分支。
        int v = texH - dh; // 贴图坐标原点在左下，这里换算成从上方裁剪
        graphics.blit(
            RenderType::guiTextured, tex,
            dx, dy, 0.0F, (float) Math.max(0, v), dw, dh, texW, texH
        );
    }

    // ------------------------------------------------------------ 布局与事件

    private int leftPanelX() {
        return 14;
    }

    private int leftPanelWidth() {
        return Math.max(220, (int) (width * 0.55) - 20);
    }

    private int rightPanelX() {
        return leftPanelX() + leftPanelWidth() + 16;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < rightPanelX()) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void doBuild() {
        if (result == null) {
            return;
        }
        WooliPic.applySettings(
            blockWidth, autoHeight, blockHeight, crop, enhance, colorMode,
            0, false, frontDistance, Builder.getCommandsPerTick()
        );
        Component message = WooliPic.startBuild(result, frontDistance);
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(message, false);
        }
        onClose();
    }

    @Override
    public void onClose() {
        // 记住这次的参数，下次打开还是这套
        WooliPic.applySettings(
            blockWidth, autoHeight, blockHeight, crop, enhance, colorMode,
            0, false, frontDistance, Builder.getCommandsPerTick()
        );
        releaseTextures();
        super.onClose();
    }

    @Override
    public void removed() {
        releaseTextures();
        super.removed();
    }

    private Component autoHeightLabel() {        return Component.literal("高度自动：" + (autoHeight ? "开" : "关"));
    }

    private Component cropLabel() {
        return Component.literal("居中裁剪：" + (crop ? "开（不变形）" : "关（拉伸）"));
    }

    private Component enhanceLabel() {
        return Component.literal("增强对比：" + (enhance ? "开" : "关"));
    }

    private String shortModeName() {
        switch (colorMode) {
            case WoolPalette.MODE_CIE76: return "算法：CIE76";
            case WoolPalette.MODE_RGB: return "算法：RGB 距离";
            default: return "算法：CIEDE2000";
        }
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
