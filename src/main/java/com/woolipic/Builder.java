package com.woolipic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 把羊毛画"盖"到世界里。
 *
 * <p>为什么不用直接改世界的方式：Mod 装在客户端，而方块数据在服务端（单人游戏里
 * 也是"客户端 + 内置服务端"的结构）。客户端能可靠地影响世界的手段就是发指令。
 * 好处是自带权限校验，也不会因为版本差异导致数据不同步。
 *
 * <p>指令量做了优化：同一行里连续相同的羊毛会合并成一条 {@code /fill}，所以
 * 128x85 的图通常只需要几百条指令，而不是一万条。
 */
public final class Builder {

    /** 单条指令的字符上限（原版是 256，留点余量）。 */
    private static final int MAX_COMMAND_LEN = 250;
    /** 指令中的绝对坐标最多能表示到多少（原版指令解析上限）。 */
    private static final int MAX_COORD = 30_000_000;

    private static final Deque<String> PENDING = new ArrayDeque<>();
    private static final List<String> LAST_BATCH = new ArrayList<>();

    /** 每 tick 发送几条指令。太快会被服务端当成刷屏，太慢又要等很久。 */
    private static int commandsPerTick = 4;

    private Builder() {
    }

    public static void setCommandsPerTick(int n) {
        commandsPerTick = Math.max(1, Math.min(64, n));
    }

    public static int getCommandsPerTick() {
        return commandsPerTick;
    }

    public static boolean isBuilding() {
        return !PENDING.isEmpty();
    }

    public static int remaining() {
        return PENDING.size();
    }

    public static int totalInLastBuild() {
        return LAST_BATCH.size();
    }

    /** 取消还没发完的指令。 */
    public static void cancel() {
        PENDING.clear();
    }

    /**
     * 根据玩家当前朝向计算放置原点与水平方向。
     *
     * <p>墙面垂直于玩家的视线方向，法线朝玩家，所以玩家站在原地看着正前方就能看到
     * 画面正面。图片的"向右"映射成玩家的右手方向，向上映射成 +Y。
     *
     * @param distance 墙面离玩家多少格
     */
    public static Placement computePlacement(Player player, WoolImage image, int distance) {
        Direction facing = player.getDirection();

        // 墙面法线朝玩家 = 与玩家朝向相反
        Direction normal = facing.getOpposite();
        // 水平右方向：图片 x 增大时往玩家右手边走。
        // 注意 Minecraft 的朝向常量是按"实体朝向"定义的，顺时针才是右手方向。
        Direction right = facing.getClockWise();

        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());

        // 墙面中心正对玩家，所以原点要沿着右方向往回退半个宽度，再沿法线推出去
        int halfWidth = image.width / 2;
        int ox = px + normal.getStepX() * distance - right.getStepX() * halfWidth;
        int oz = pz + normal.getStepZ() * distance - right.getStepZ() * halfWidth;
        int oy = py;

        return new Placement(
            ox, oy, oz,
            right.getStepX(), right.getStepZ(),
            normal.getStepX(), normal.getStepZ(),
            image.width, image.height
        );
    }

    /** 放置位置与朝向。 */
    public static final class Placement {
        public final int originX, originY, originZ;
        public final int rightX, rightZ;
        public final int normalX, normalZ;
        public final int width, height;

        Placement(
            int ox, int oy, int oz,
            int rightX, int rightZ,
            int normalX, int normalZ,
            int width, int height
        ) {
            this.originX = ox;
            this.originY = oy;
            this.originZ = oz;
            this.rightX = rightX;
            this.rightZ = rightZ;
            this.normalX = normalX;
            this.normalZ = normalZ;
            this.width = width;
            this.height = height;
        }

        /** 某个图片像素 (dx, dyFromTop) 对应的世界坐标。 */
        public BlockPos posOf(int dx, int dyFromTop) {
            int y = originY + (height - 1 - dyFromTop);
            return new BlockPos(
                originX + rightX * dx,
                y,
                originZ + rightZ * dx
            );
        }

        /** 整幅画的包围范围，用于检查是否越界或超出世界高度。 */
        public boolean outOfWorld() {
            int maxY = originY + height - 1;
            if (originY < -64 || maxY > 320) {
                return true;
            }
            int far = Math.max(
                Math.abs(originX) + Math.abs(rightX) * width,
                Math.abs(originZ) + Math.abs(rightZ) * width
            );
            return far > MAX_COORD;
        }

        public String describe() {
            return String.format(
                "原点 (%d, %d, %d)，向右 (%d, %d)，画面 %d x %d 格",
                originX, originY, originZ, rightX, rightZ, width, height
            );
        }
    }

    /**
     * 统计目标区域内已经有多少非空气方块。
     *
     * <p>放置前拿这个数字提醒用户：如果目标位置已经有建筑，盖上去会把它替换掉。
     */
    public static int countOccupied(Placement p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return -1;
        }
        int occupied = 0;
        // 抽样统计就够了：按行采样，避免十万次方块查询拖慢界面
        int stepY = Math.max(1, p.height / 32);
        int stepX = Math.max(1, p.width / 32);
        int sampled = 0;
        for (int dy = 0; dy < p.height; dy += stepY) {
            for (int dx = 0; dx < p.width; dx += stepX) {
                BlockPos pos = p.posOf(dx, dy);
                BlockState state = mc.level.getBlockState(pos);
                sampled++;
                if (!state.isAir() && state.getBlock() != Blocks.WATER) {
                    occupied++;
                }
            }
        }
        if (sampled == 0) {
            return 0;
        }
        // 按采样比例放大成整幅画的估计值
        return (int) Math.round(occupied * (p.width * (double) p.height) / sampled);
    }

    /**
     * 生成整幅画的放置指令并进入队列，之后每 tick 自动发送。
     *
     * @return 生成的指令条数
     */
    public static int start(WoolImage image, Placement placement) {
        PENDING.clear();
        LAST_BATCH.clear();

        for (int dy = 0; dy < image.height; dy++) {
            int dx = 0;
            while (dx < image.width) {
                int wool = image.woolIndex[dy][dx];
                int run = 1;
                while (dx + run < image.width && image.woolIndex[dy][dx + run] == wool) {
                    run++;
                }
                emitRun(image, placement, dy, dx, run);
                dx += run;
            }
        }
        PENDING.addAll(LAST_BATCH);
        return LAST_BATCH.size();
    }

    /** 把一段连续同色方块拆成若干条合法长度的 /fill 指令。 */
    private static void emitRun(
        WoolImage image, Placement p, int dy, int startDx, int runLength
    ) {
        String blockId = WoolPalette.get(image.woolIndex[dy][startDx]).blockId;
        int placed = 0;
        while (placed < runLength) {
            int chunk = runLength - placed;
            // 先按剩余长度试，太长就逐步减半，直到指令长度合适
            while (chunk > 1) {
                BlockPos a = p.posOf(startDx + placed, dy);
                BlockPos b = p.posOf(startDx + placed + chunk - 1, dy);
                String cmd = fillCommand(a, b, blockId);
                if (cmd.length() <= MAX_COMMAND_LEN) {
                    LAST_BATCH.add(cmd);
                    break;
                }
                chunk = Math.max(1, chunk / 2);
            }
            if (chunk == 1) {
                BlockPos a = p.posOf(startDx + placed, dy);
                LAST_BATCH.add(setblockCommand(a, blockId));
            }
            placed += chunk;
        }
    }

    private static String fillCommand(BlockPos a, BlockPos b, String blockId) {
        return "fill " + a.getX() + " " + a.getY() + " " + a.getZ()
            + " " + b.getX() + " " + b.getY() + " " + b.getZ()
            + " " + blockId;
    }

    private static String setblockCommand(BlockPos a, String blockId) {
        return "setblock " + a.getX() + " " + a.getY() + " " + a.getZ() + " " + blockId;
    }

    /** 由客户端 tick 事件调用：每次发一小批指令。 */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (PENDING.isEmpty() || mc.getConnection() == null || mc.player == null) {
            return;
        }
        int sent = 0;
        while (!PENDING.isEmpty() && sent < commandsPerTick) {
            String cmd = PENDING.poll();
            try {
                mc.getConnection().sendCommand(cmd);
            } catch (Exception e) {
                // 单条指令失败不该中断整幅画，记录后继续
                WooliPic.LOGGER.warn("指令发送失败: {}", cmd, e);
            }
            sent++;
        }
        if (PENDING.isEmpty()) {
            mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("§a羊毛画放置完成！"), false
            );
        } else if (PENDING.size() % 50 == 0) {
            mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                    "§7羊毛画放置中… 还剩 §e" + PENDING.size() + " §7条指令"
                ), true
            );
        }
    }

    /** 内部使用：把已生成的指令放进待发队列。 */
    static void enqueueAll(java.util.Collection<String> commands) {
        PENDING.addAll(commands);
    }
}
