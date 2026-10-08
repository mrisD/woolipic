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

    // ---------------------------------------------------------------- 布局
    // 全部布局尺寸都在 init() 里按当前屏幕算出来。
    // 之前用的是写死的像素值（控件宽 150、间距硬编码），在 1536x864 这种显示器上
    // Minecraft 自动 GUI 缩放会把逻辑空间压到 400~600 像素宽，结果控件互相重叠。
    private int panelTop;
    private int panelBottom;
    private int leftPanelX;
    private int leftPanelW;
    private int rightPanelX;
    private int rightPanelW;
    private int gap;        // 两栏之间的间距，也是通用的紧凑间距
    private int widgetW;    // 右栏控件宽度
    private int widgetH;    // 按钮高度
    private int rowH;       // 行距
    private int contentBottom;
    /** 颜色开关区的实际位置，绘制色块时要用。 */
    private int swatchX;
    private int swatchY;
    private int swatchPerRow;
    private int swatchSize;

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

    /** 内容区底部；布局会被裁掉时为 true，此时折叠掉次要控件。 */
    private boolean compactUi;

    /**
     * 按当前屏幕尺寸算出全部布局尺寸。
     *
     * <p>为什么不用写死的像素：Minecraft 的 GUI 缩放把物理像素除以缩放系数，
     * 1536x864 的显示器在自动缩放下逻辑空间只有 400~600 像素宽、300~500 高。
     * 写死的 150 宽控件加硬编码间距一定重叠。
     *
     * <p>纵向尤其紧张（GUI 缩放 3x 时只有 288 高）。所以先算"控件预算高度"，
     * 再从三档密度里挑第一档装得下的：舒适 -> 紧凑 -> 极简。
     */
    private void computeLayout() {
        int margin = Math.max(6, Math.min(16, width / 60));
        int colGap = Math.max(8, Math.min(16, width / 70));

        leftPanelX = margin;
        panelTop = 26;
        panelBottom = height - 26;

        int usable = width - margin * 2 - colGap;
        leftPanelW = Math.max(140, (int) (usable * 0.54));
        rightPanelX = leftPanelX + leftPanelW + colGap;
        rightPanelW = Math.max(120, width - margin - rightPanelX);
        widgetW = rightPanelW;
        swatchX = rightPanelX;

        int budget = panelBottom - panelTop;

        // 三档密度：{按钮高, 间距, 色块边长, 色块区标题留白}
        int[][] presets = {
            {20, 4, 14, 12},
            {18, 3, 12, 10},
            {16, 2, 10, 8},
        };

        compactUi = false;
        for (int i = 0; i < presets.length; i++) {
            int[] p = presets[i];
            widgetH = p[0];
            gap = p[1];
            swatchSize = p[2];
            int labelLead = p[3];
            rowH = widgetH + gap;
            compactUi = i == presets.length - 1;

            swatchPerRow = 8;
            int minCell = swatchSize + 20;
            while (swatchPerRow > 2 && swatchPerRow * minCell > rightPanelW) {
                swatchPerRow--;
            }
            int rows = (WoolPalette.size() + swatchPerRow - 1) / swatchPerRow;
            swatchY = panelTop
                + (rowH + 2) * 2            // 浏览 + 翻页两行
                + (18 + gap + 2)            // 宽高输入框
                + rowH * 4                  // 高度自动 / 算法 / 裁剪 / 增强
                + 4
                + labelLead;
            int bottom = swatchY + rows * (swatchSize + gap) + 4
                + (18 + gap + 2) + 22;
            if (bottom - panelTop <= budget) {
                contentBottom = bottom;
                return;
            }
        }

        // 连最紧凑的完整布局都放不下：进入精简模式，只保留最关键的控件。
        // 这通常意味着 GUI 缩放开到了 4x（逻辑空间只剩 200 多像素高），
        // 折叠掉的是"裁剪/增强/颜色开关/距离"，它们都有默认值，不影响主流程。
        compactUi = true;
        widgetH = 16;
        gap = 2;
        swatchSize = 10;
        rowH = widgetH + gap;
        swatchPerRow = Math.max(2, Math.min(8, rightPanelW / (swatchSize + 20)));
        swatchY = 0; // 精简模式不画颜色开关
        contentBottom = panelTop
            + (rowH + 2) * 2        // 浏览 + 翻页
            + (18 + gap + 2)        // 宽高
            + rowH                  // 高度自动
            + rowH                  // 算法
            + rowH * 2              // 距离 + 开始放置
            + 8;
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

        computeLayout();

        int x = rightPanelX;
        int w = widgetW;
        int y = panelTop;

        browseButton = Button.builder(
            Component.literal("浏览本地图片…"), b -> openFileDialog()
        ).bounds(x, y, w, widgetH).build();
        addRenderableWidget(browseButton);
        y += rowH + 2;

        // 前后翻历史图片；两块窄按钮放在同一行
        int arrowW = Math.max(18, Math.min(24, w / 4));
        prevButton = Button.builder(Component.literal("◀"), b -> stepSelection(-1))
            .bounds(x, y, arrowW, widgetH).build();
        nextButton = Button.builder(Component.literal("▶"), b -> stepSelection(1))
            .bounds(x + w - arrowW, y, arrowW, widgetH).build();
        addRenderableWidget(prevButton);
        addRenderableWidget(nextButton);
        y += rowH + 2;

        // --- 尺寸：宽高两个输入框并排 ---
        int boxGap = 6;
        int halfW = (w - boxGap) / 2;
        widthBox = new EditBox(font, x, y, halfW, 18, Component.literal("宽度"));
        widthBox.setValue(String.valueOf(blockWidth));
        widthBox.setResponder(s -> {
            Integer v = parseInt(s);
            if (v != null && v >= 4 && v <= 512) {
                blockWidth = v;
                scheduleConvert();
            }
        });
        addRenderableWidget(widthBox);

        heightBox = new EditBox(font, x + halfW + boxGap, y, halfW, 18, Component.literal("高度"));
        heightBox.setValue(String.valueOf(blockHeight));
        heightBox.setResponder(s -> {
            Integer v = parseInt(s);
            if (v != null && v >= 4 && v <= 512) {
                blockHeight = v;
                scheduleConvert();
            }
        });
        addRenderableWidget(heightBox);
        y += 18 + gap + 2;

        autoHeightButton = Button.builder(autoHeightLabel(), b -> {
            autoHeight = !autoHeight;
            b.setMessage(autoHeightLabel());
            heightBox.setEditable(!autoHeight);
            heightBox.setVisible(!autoHeight);
            scheduleConvert();
        }).bounds(x, y, w, widgetH).build();
        addRenderableWidget(autoHeightButton);
        heightBox.setEditable(!autoHeight);
        heightBox.setVisible(!autoHeight);
        y += rowH;

        // --- 色彩 ---
        colorModeButton = Button.builder(
            Component.literal(shortModeName()), b -> {
                colorMode = (colorMode + 1) % 3;
                b.setMessage(Component.literal(shortModeName()));
                scheduleConvert();
            }
        ).bounds(x, y, w, widgetH).build();
        addRenderableWidget(colorModeButton);
        y += rowH;

        if (!compactUi) {
            // 以下都是"有默认值、不调也能用"的选项，空间不足时折叠掉
            cropButton = Button.builder(cropLabel(), b -> {
                crop = !crop;
                b.setMessage(cropLabel());
                scheduleConvert();
            }).bounds(x, y, w, widgetH).build();
            addRenderableWidget(cropButton);
            y += rowH;

            enhanceButton = Button.builder(enhanceLabel(), b -> {
                enhance = !enhance;
                b.setMessage(enhanceLabel());
                scheduleConvert();
            }).bounds(x, y, w, widgetH).build();
            addRenderableWidget(enhanceButton);
            y += rowH + 4;

            // --- 颜色开关 ---
            // 标题由 render() 画在 swatchY - 行高 的位置
            swatchY = y + 12;
            int cell = w / swatchPerRow;
            for (int i = 0; i < WoolPalette.size(); i++) {
                final int index = i;
                int cx = x + (i % swatchPerRow) * cell;
                int cy = swatchY + (i / swatchPerRow) * (swatchSize + gap);
                Button b = Button.builder(
                    Component.literal(""), btn -> {
                        enabledWools[index] = !enabledWools[index];
                        scheduleConvert();
                    }
                ).bounds(cx, cy, swatchSize, swatchSize).build();
                b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.literal(WoolPalette.get(i).zhName)
                ));
                addRenderableWidget(b);
            }
            y = swatchY
                + (WoolPalette.size() + swatchPerRow - 1) / swatchPerRow * (swatchSize + gap)
                + 4;
        } else {
            swatchY = y;
        }

        // --- 放置 ---
        if (!compactUi) {
            distanceBox = new EditBox(font, x, y, Math.max(30, w / 3), 18,
                Component.literal("距离"));
            distanceBox.setValue(String.valueOf(frontDistance));
            distanceBox.setResponder(s -> {
                Integer v = parseInt(s);
                if (v != null && v >= 1 && v <= 64) {
                    frontDistance = v;
                }
            });
            addRenderableWidget(distanceBox);
            y += 18 + gap + 2;
        } else {
            distanceBox = null;
        }

        buildButton = Button.builder(
            Component.literal("▶ 开始放置"), b -> doBuild()
        ).bounds(x, y, w, 22).build();
        addRenderableWidget(buildButton);
        buildButton.active = result != null;
        contentBottom = y + 22;

        if (compactUi) {
            setStatus("界面空间紧张：已隐藏裁剪/增强/颜色开关（用默认值）。"
                + "想看到完整选项，请把窗口调大或把 GUI 缩放调到 2~3。", false);
        } else if (contentBottom > panelBottom) {
            setStatus("界面空间不足，建议把窗口调大或把 GUI 缩放调小"
                + "（选项→视频设置→界面尺寸）", true);
        }

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

    /**
     * 打开系统文件选择框，选完复制一份进模组目录。
     *
     * <p>两个容易踩的坑：
     * <ul>
     *   <li>Windows 上 {@code setFilenameFilter} 会被忽略（它是给 X11 用的），
     *       必须在 {@code setFile} 里给通配符才会显示"只看图片"的效果。</li>
     *   <li>对话框不能建在渲染线程上，否则和 Minecraft 的渲染循环互相阻塞，
     *       表现就是"点了没反应"。这里开独立线程，并且用 setLocationRelativeTo(null)
     *       让它居中显示在屏幕上、出现在游戏窗口前面。</li>
     * </ul>
     */
    private void openFileDialog() {
        WooliPic.LOGGER.info("打开文件选择对话框…");
        Thread t = new Thread(() -> {
            java.awt.FileDialog dialog = new java.awt.FileDialog(
                (java.awt.Frame) null, "选择一张图片", java.awt.FileDialog.LOAD
            );
            // Windows 只认 setFile 的通配符
            dialog.setFile("*.png;*.jpg;*.jpeg;*.bmp;*.gif");
            dialog.setMultipleMode(false);
            dialog.setLocationRelativeTo(null);

            // 从上一次用过的图片所在目录开始，省得每次从头翻
            if (selectedFile != null && selectedFile.getParent() != null) {
                dialog.setDirectory(selectedFile.getParent().toString());
            }

            dialog.setVisible(true);

            String dir = dialog.getDirectory();
            String file = dialog.getFile();
            if (dir == null || file == null || file.isEmpty()) {
                WooliPic.LOGGER.info("用户取消了文件选择");
                return;
            }
            File chosen = new File(dir, file);
            WooliPic.LOGGER.info("选中文件: {}", chosen);
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
                Minecraft.getInstance().execute(
                    () -> setStatus("导入图片失败：" + e.getMessage(), true));
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

        int leftX = leftPanelX;
        int leftW = leftPanelW;

        // 标题：左右两栏都画一个，明确分区
        graphics.drawCenteredString(font, TITLE, width / 2, 8, ACCENT);

        // --- 左栏：原图 / 羊毛效果 并排 ---
        int boxGap = Math.max(6, leftW / 20);
        int boxW = (leftW - boxGap) / 2;
        // 预览高度按可用空间算，并给底部的图片列表和状态栏留位置
        int listReserve = Math.min(90, Math.max(40, height / 6));
        int boxH = Math.max(50, Math.min(320, panelBottom - panelTop - listReserve - 26));
        int boxTop = panelTop + 12;

        graphics.drawString(font, LBL_SOURCE, leftX, panelTop, TEXT_DIM, false);
        graphics.drawString(font, LBL_PREVIEW, leftX + boxW + boxGap, panelTop, TEXT_DIM, false);

        drawPanel(graphics, leftX, boxTop, boxW, boxH);
        drawPanel(graphics, leftX + boxW + boxGap, boxTop, boxW, boxH);

        if (sourceTextureId != null && sourceTexW > 0) {
            drawFitted(graphics, sourceTextureId, sourceTexW, sourceTexH,
                leftX + 2, boxTop + 2, boxW - 4, boxH - 4, false);
        } else if (NO_IMAGE != null) {
            graphics.drawCenteredString(font, NO_IMAGE,
                leftX + boxW / 2, boxTop + boxH / 2, TEXT_DIM);
        }
        if (previewTextureId != null && previewTexW > 0) {
            drawFitted(graphics, previewTextureId, previewTexW, previewTexH,
                leftX + boxW + boxGap + 2, boxTop + 2, boxW - 4, boxH - 4, true);
        } else if (converting) {
            graphics.drawCenteredString(font, LBL_CONVERTING,
                leftX + boxW + boxGap + boxW / 2, boxTop + boxH / 2, TEXT_DIM);
        }

        // --- 图片列表 ---
        int listY = boxTop + boxH + 6;
        graphics.drawString(font,
            Component.literal("最近的图片（" + imageFiles.size() + "）"),
            leftX, listY, TEXT_DIM, false);
        listY += font.lineHeight + 1;
        int rowHeight = font.lineHeight + 1;
        int maxRows = Math.max(1, (panelBottom - 12 - listY) / rowHeight);
        int start = Math.min(listScroll, Math.max(0, imageFiles.size() - maxRows));
        for (int i = 0; i < maxRows && start + i < imageFiles.size(); i++) {
            int idx = start + i;
            Path p = imageFiles.get(idx);
            boolean sel = idx == selectedIndex;
            if (sel) {
                graphics.fill(leftX - 2, listY - 1, leftX + leftW, listY + rowHeight - 2, PANEL);
            }
            // 文件名过长时截断，避免压到右栏
            String name = font.plainSubstrByWidth(
                p.getFileName().toString(), Math.max(20, leftW - 6));
            graphics.drawString(font, Component.literal(name), leftX, listY,
                sel ? ACCENT : TEXT, false);
            listY += rowHeight;
        }

        // --- 右栏标题 ---
        // 位置跟着控件走，不再写死，避免和控件重叠
        drawLabelAbove(graphics, LBL_SIZE, widthBox);
        drawLabelAbove(graphics, LBL_MODE, colorModeButton);
        if (!compactUi) {
            drawLabelAbove(graphics, LBL_WOOLS, swatchY);
            drawLabelAbove(graphics, LBL_DISTANCE, distanceBox);
            // --- 颜色开关的色块（按钮本身是空的，这里把颜色画上去）---
            drawWoolSwatches(graphics);
        }

        // --- 状态栏 ---
        int statusY = height - font.lineHeight - 4;
        String statusText = font.plainSubstrByWidth(status, width - leftX * 2);
        graphics.drawString(font, Component.literal(statusText), leftX, statusY,
            statusIsError ? 0xFF8080 : TEXT, false);
        String hint = null;
        if (Builder.isBuilding()) {
            hint = "放置中… 剩余 " + Builder.remaining() + " 条指令";
        } else if (result != null) {
            hint = "按 Esc 关闭；放置时请站在空地上";
        }
        if (hint != null) {
            graphics.drawString(font,
                Component.literal(font.plainSubstrByWidth(hint, width - leftX * 2)),
                leftX, statusY - font.lineHeight - 1,
                Builder.isBuilding() ? ACCENT : TEXT_DIM, false);
        }
    }

    /** 在控件上方画标题；空间不够就画到控件右侧空白处，不硬挤。 */
    private void drawLabelAbove(GuiGraphics graphics, Component label,
                                net.minecraft.client.gui.components.AbstractWidget widget) {
        if (widget == null) {
            return;
        }
        drawLabelAbove(graphics, label, widget.getY());
    }

    private void drawLabelAbove(GuiGraphics graphics, Component label, int y) {
        int ly = y - font.lineHeight - 1;
        if (ly < panelTop - 2) {
            return; // 顶部没空间就不画，总比压住上面的控件好
        }
        graphics.drawString(font, label, rightPanelX, ly, TEXT_DIM, false);
    }

    private void drawWoolSwatches(GuiGraphics graphics) {
        int cell = widgetW / swatchPerRow;
        for (int i = 0; i < WoolPalette.size(); i++) {
            int cx = swatchX + (i % swatchPerRow) * cell;
            int cy = swatchY + (i / swatchPerRow) * (swatchSize + gap);
            WoolPalette.Wool wool = WoolPalette.get(i);
            int argb = 0xFF000000 | (wool.r << 16) | (wool.g << 8) | wool.b;
            // 可用 = 正常颜色；禁用 = 压暗到 1/4
            if (!enabledWools[i]) {
                argb = 0xFF000000
                    | ((wool.r / 4) << 16) | ((wool.g / 4) << 8) | (wool.b / 4);
            }
            graphics.fill(cx, cy, cx + swatchSize, cy + swatchSize, argb);
            graphics.renderOutline(cx, cy, swatchSize, swatchSize,
                enabledWools[i] ? 0xFFFFFFFF : 0xFF404040);
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < rightPanelX) {
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
