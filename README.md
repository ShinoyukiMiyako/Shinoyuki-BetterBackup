# Shinoyuki-BetterBackup

Minecraft 1.20.1 Forge 服务端增量备份 mod。基于内容寻址 (content-addressed) 跨快照 dedup，实测大型生产服 84 份备份占用从 vanilla 全量方案的 16.8 TB 降到 150-300 GB。跟 [Shinoyuki-BetterAutoSave](https://github.com/xiaoxiao-cvs/Shinoyuki-BetterAutoSave) (BAS) 异步化管线深度集成，主线程零阻塞。

> 项目开发中，详细设计与实施路线见 [DESIGN.md](DESIGN.md).

## 致谢

BetterBackup 的核心 dedup 算法 — 直接对 region file (`.mca`) 里的 chunk slot raw 字节做 hash，不经过内存 NBT — 借鉴自 [ChronoVault](https://github.com/Catt1eyaa/ChronoVault) (NeoForge 1.21.1)。

这个思路工程上漂亮地绕开了 vanilla `CompoundTag` 序列化的字节不稳定问题（`HashMap` iteration 顺序 / `tag.merge` / data fixer 重建 tag 都会让同语义内容产生不同字节），跨 JVM 字节恒等保证 dedup 率稳定。ChronoVault 在 NeoForge 1.21.1 上验证了可行性，BetterBackup 把这个范式带到 Forge 1.20.1，并跟 BAS 的 listener API 集成做增量备份（只处理 BAS 刚 save 的 chunk，不主动扫整个世界）。
