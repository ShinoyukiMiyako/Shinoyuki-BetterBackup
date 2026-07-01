package com.shinoyuki.betterbackup.restore;

import com.shinoyuki.betterautosave.api.ChunkRestoreOutcome;
import net.minecraft.world.level.ChunkPos;

/**
 * 把 BAS {@link ChunkRestoreOutcome} 翻成给服主看的中文反馈文案 (DESIGN §4.4).
 *
 * <p>UX 文案归 BB 担 (不下沉 BAS): BAS 只返回结构化 outcome 枚举, "怎么跟人说" 是 BB
 * 的职责, 这样 BAS API 保持纯结构化、跨调用方复用. 文案对每种失败都给出"下一步怎么办"
 * (走离线 / 走重启 / 该 chunk 当前状态), 不只报 code.
 *
 * <p>无 emoji / 颜色圆点: 状态用纯文本词. 严重度由 sendSuccess 还是 sendFailure 体现
 * (调用方据 isSuccess 决定), 文案本身不带符号标记.
 */
public final class ChunkRestoreMessages {

    private ChunkRestoreMessages() {
    }

    /** outcome 是否算成功 (决定命令层用 sendSuccess 还是 sendFailure). */
    public static boolean isSuccess(ChunkRestoreOutcome outcome) {
        return outcome == ChunkRestoreOutcome.OK;
    }

    /**
     * 给定 outcome (及失败时的 cause) 产出中文反馈正文.
     *
     * @param outcome BAS 返回的结果枚举
     * @param pos     目标 chunk 坐标 (拼进文案定位)
     * @param cause   失败原因 (PARSE_FAILED / INSTALL_FAILED 时非空, 其余可为 null)
     */
    public static String describe(ChunkRestoreOutcome outcome, ChunkPos pos, Throwable cause) {
        String at = "区块 (" + pos.x + "," + pos.z + ")";
        return switch (outcome) {
            case OK -> at + " 在线回退成功: 地形/方块实体/高度图已原地替换, 已重发给视距内玩家, "
                    + "并标记交由正常存盘持久化.";
            case REJECT_DISABLED -> at + " 在线回退不可用: BetterAutoSave 未安装, 或其异步加载未启用 "
                    + "(load.enabled=false / FULL 兼容模式). 请改走离线 CLI 回退或停服重启回退.";
            case REJECT_DEGRADED -> at + " 在线回退被拒: BetterAutoSave 管线当前处于降级状态, 在线写入暂不可用. "
                    + "请待管线恢复, 或改走离线/重启回退.";
            case REJECT_NOT_LOADED -> at + " 当前未加载 (无玩家在视距内). 在线回退只作用于已加载 chunk; "
                    + "请让玩家靠近使其加载后重试, 或对未加载 chunk 走离线 CLI / 重启回退.";
            case PARSE_FAILED -> at + " 回退失败: 快照里该 chunk 的 NBT 无法反序列化 "
                    + "(字节损坏或版本不符). 世界未改动. 原因: " + causeText(cause);
            case INSTALL_FAILED -> at + " 回退失败: 在内存安装阶段出错, BetterAutoSave 已尽力回滚到回退前状态, "
                    + "不会留下半个 chunk. 世界应仍是回退前的样子. 原因: " + causeText(cause);
        };
    }

    private static String causeText(Throwable cause) {
        if (cause == null) {
            return "(无附加信息)";
        }
        String msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
    }
}
