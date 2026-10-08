package com.woolipic;

import java.io.File;
import java.nio.file.Path;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 羊毛画生成器主类。
 *
 * <p>用法：游戏里输入 {@code /woolipic} 打开界面，选一张本地图片，调好尺寸和朝向，
 * 点"开始放置"，画面就会在你面前用羊毛方块逐层搭出来。
 */
@Mod(WooliPic.MODID)
public class WooliPic {

    public static final String MODID = "woolipic";
    public static final Logger LOGGER = LoggerFactory.getLogger("woolipic");

    /** 最近一次转换好的羊毛画，界面和指令共用。 */
    private static WoolImage lastImage;
    private static Builder.Placement lastPlacement;

    public WooliPic() {
        Config.init();
        Builder.setCommandsPerTick(Config.commandsPerTick);
        LOGGER.info("羊毛画生成器已加载。用 /woolipic 打开界面。");

        // 关键：@EventBusSubscriber 只对**顶层类**自动生效，内部类必须手动注册，
        // 否则客户端指令和 tick 事件一个都收不到。
        if (FMLEnvironment.dist.isClient()) {
            MinecraftForge.EVENT_BUS.register(ClientEvents.class);
        }

        // 色彩算法自检：写错一处整个匹配都会偏，所以启动时验一遍更放心
        double worst = ColorMath.selfTest();
        if (worst > 1e-4) {
            LOGGER.error("CIEDE2000 自检未通过，最大偏差 {}，颜色匹配可能不准", worst);
        } else {
            LOGGER.info("CIEDE2000 自检通过（对照 34 组标准数据，最大偏差 {}）", worst);
        }
    }

    public static WoolImage getLastImage() {
        return lastImage;
    }

    public static Builder.Placement getLastPlacement() {
        return lastPlacement;
    }

    public static void setLastImage(WoolImage image) {
        lastImage = image;
    }

    /** 转换一张图片并记住结果，返回是否成功。 */
    public static WoolImage convertAndRemember(File file, WoolImage.Options options) {
        try {
            WoolImage image = WoolImage.loadAndConvert(file, options);
            lastImage = image;
            return image;
        } catch (Exception e) {
            LOGGER.error("转换失败: {}", file, e);
            return null;
        }
    }

    /** 打开界面。 */
    public static void openScreen() {
        Minecraft.getInstance().setScreen(new WooliPicScreen());
    }

    /**
     * 开始放置。会先算好位置、检查越界，然后把结果放进待发队列。
     *
     * @return 提示文本（成功或失败原因）
     */
    public static Component startBuild(WoolImage image, int frontDistance) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return Component.literal("§c不在游戏里，无法放置。");
        }
        if (Builder.isBuilding()) {
            return Component.literal("§e正在放置上一幅画，请稍等或输入 /woolipic cancel 取消。");
        }
        Builder.Placement placement = Builder.computePlacement(mc.player, image, frontDistance);
        if (placement.outOfWorld()) {
            return Component.literal(
                "§c放置位置超出世界范围（可能太高或太远）。请换个位置或缩小尺寸。"
            );
        }
        lastPlacement = placement;
        int commands = Builder.start(image, placement);
        return Component.literal(
            "§a开始放置：" + placement.describe() + "，共 §e" + commands + " §a条指令"
        );
    }

    /** 客户端指令注册。 */
    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static final class ClientEvents {

        @SubscribeEvent
        public static void onRegisterCommands(RegisterClientCommandsEvent event) {
            event.getDispatcher().register(
                Commands.literal("woolipic")
                    .executes(ctx -> {
                        openScreen();
                        return 1;
                    })
                    .then(Commands.literal("open").executes(ctx -> {
                        openScreen();
                        return 1;
                    }))
                    .then(Commands.literal("cancel").executes(ctx -> {
                        Builder.cancel();
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("§e已取消剩余放置指令。"), false
                        );
                        return 1;
                    }))
                    .then(Commands.literal("build")
                        .executes(ctx -> {
                            if (lastImage == null) {
                                ctx.getSource().sendFailure(
                                    Component.literal("还没有图片，先用 /woolipic 选一张。")
                                );
                                return 0;
                            }
                            ctx.getSource().sendSuccess(
                                () -> startBuild(lastImage, Config.frontDistance), false
                            );
                            return 1;
                        })
                        .then(Commands.argument("距离", IntegerArgumentType.integer(1, 64))
                            .executes(ctx -> {
                                if (lastImage == null) {
                                    ctx.getSource().sendFailure(
                                        Component.literal("还没有图片，先用 /woolipic 选一张。")
                                    );
                                    return 0;
                                }
                                int d = IntegerArgumentType.getInteger(ctx, "距离");
                                ctx.getSource().sendSuccess(
                                    () -> startBuild(lastImage, d), false
                                );
                                return 1;
                            })))
                    .then(Commands.literal("load")
                        .then(Commands.argument("文件", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String path = StringArgumentType.getString(ctx, "文件");
                                File file = new File(path);
                                if (!file.isFile()) {
                                    ctx.getSource().sendFailure(
                                        Component.literal("找不到文件：" + path)
                                    );
                                    return 0;
                                }
                                WoolImage.Options options = currentOptions();
                                WoolImage image = convertAndRemember(file, options);
                                if (image == null) {
                                    ctx.getSource().sendFailure(
                                        Component.literal("转换失败，看看日志里的原因。")
                                    );
                                    return 0;
                                }
                                Config.lastImage = file.getAbsolutePath();
                                Config.save();
                                ctx.getSource().sendSuccess(
                                    () -> Component.literal(
                                        "§a已载入 " + image.width + "x" + image.height
                                        + " 方块，平均色差 ΔE "
                                        + String.format("%.2f", image.meanDeltaE)
                                        + "。输入 /woolipic build 开始放置。"
                                    ),
                                    false
                                );
                                return 1;
                            })))
                    .then(Commands.literal("info").executes(ctx -> {
                        if (lastImage == null) {
                            ctx.getSource().sendFailure(Component.literal("还没有载入图片。"));
                            return 0;
                        }
                        WoolImage img = lastImage;
                        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                            "§b羊毛画§r %dx%d 方块\n§7平均色差§r ΔE %.2f  §7局部色调§r ΔE %.2f\n§7用色§r %d 种：%s",
                            img.width, img.height, img.meanDeltaE, img.blockDeltaE,
                            img.distinctWools(), img.usageSummary(6)
                        )), false);
                        return 1;
                    }))
            );
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            // 兼容写法：不同 Forge 版本里 tick 事件的载荷位置不一样，
            // 用 phase 判断比依赖子类名（例如 ClientTickEvent.Post）稳。
            if (event.phase == TickEvent.Phase.END) {
                Builder.tick();
            }
        }

        /** 用当前设置生成转换参数。 */
        public static WoolImage.Options currentOptions() {
            WoolImage.Options o = new WoolImage.Options();
            o.blockWidth = Config.blockWidth;
            o.blockHeight = Config.blockHeight;
            o.autoHeight = Config.autoHeight;
            o.crop = Config.crop;
            o.enhance = Config.enhance;
            o.colorMode = Config.colorMode;
            o.rotate = Config.rotate;
            o.flipH = Config.flipH;
            o.enabledWools = null;
            return o;
        }
    }

    static {
        // 开发环境里如果误把模组装进服务端，直接给出明确提示
        if (!FMLEnvironment.dist.isClient()) {
            LOGGER.warn("woolipic 是客户端模组，装在服务端不会生效。");
        }
    }

    /** 供界面调用：保存设置并应用。 */
    public static void applySettings(
        int blockWidth, boolean autoHeight, int blockHeight,
        boolean crop, boolean enhance, int colorMode,
        int rotate, boolean flipH, int frontDistance, int commandsPerTick
    ) {
        Config.blockWidth = blockWidth;
        Config.autoHeight = autoHeight;
        Config.blockHeight = blockHeight;
        Config.crop = crop;
        Config.enhance = enhance;
        Config.colorMode = colorMode;
        Config.rotate = rotate;
        Config.flipH = flipH;
        Config.frontDistance = frontDistance;
        Config.commandsPerTick = commandsPerTick;
        Builder.setCommandsPerTick(commandsPerTick);
        Config.save();
    }

    /** 供界面调用：把选中的图片记进配置。 */
    public static void rememberImage(Path path) {
        Config.lastImage = path == null ? "" : path.toString();
        Config.save();
    }
}
