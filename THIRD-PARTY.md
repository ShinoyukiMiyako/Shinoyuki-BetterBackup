# 第三方组件署名 / Third-Party Notices

本文件列出随 BetterBackup 分发物 (`shinoyuki_betterbackup-<version>-all.jar`) 一同打包的
第三方组件。BetterBackup 本身以 AGPL-3.0-or-later 授权 (见 `LICENSE`)。

This file lists third-party components bundled in the distributed BetterBackup artifact
(`shinoyuki_betterbackup-<version>-all.jar`). BetterBackup itself is licensed under
AGPL-3.0-or-later (see `LICENSE`).

## 打包进 jar 的组件 / Bundled in the jar

### net.openhft:zero-allocation-hashing 0.16

- 用途 / Purpose: XXH128 内容寻址哈希 (纯 Java, 无 native 依赖), 平铺进 `-all` jar 的顶层
  `net/openhft/hashing/*` 供 FML 模块层与 `java -jar` 离线 CLI 的应用类加载器解析。
  XXH128 content-addressing hash (pure Java, no native dependency), flattened into the
  top-level `net/openhft/hashing/*` of the `-all` jar.
- 许可 / License: Apache License 2.0
- 项目 / Project: https://github.com/OpenHFT/Zero-Allocation-Hashing
- 版权 / Copyright: Higher Frequency Trading (http://www.higherfrequencytrading.com)
- 完整许可与 NOTICE 已随 jar 内置于 / Full license and NOTICE are bundled at:
  `META-INF/licenses/zero-allocation-hashing/LICENSE.txt` 与 `.../NOTICE.txt`

## 运行时强依赖 (非打包) / Runtime hard dependency (not bundled)

### Shinoyuki BetterAutoSave (BAS)

- BetterBackup 经 `mods.toml` 声明对 BAS 的强依赖 (mandatory=true), 编译期 link 其
  `com.shinoyuki.betterautosave.api` 包, 运行时要求服务器另装 BAS mod jar。BAS **不**打包进
  本 jar, 故不构成本 jar 的分发署名义务; 此处列出仅为依赖透明。
  BetterBackup declares a mandatory dependency on BAS via `mods.toml` and links its
  `com.shinoyuki.betterautosave.api` package at compile time; the BAS mod jar must be
  installed separately at runtime. BAS is **not** bundled in this jar.
- 许可 / License: AGPL-3.0-or-later
- 项目 / Project: https://github.com/xiaoxiao-cvs/Shinoyuki-BetterAutoSave
