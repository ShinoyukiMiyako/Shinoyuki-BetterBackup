# Shinoyuki-BetterBackup

[简体中文](README.md) | **English**

An **incremental backup** mod for Minecraft 1.20.1 Forge servers. From the second backup onward it only processes changed chunks, cutting the footprint of 84 backups from the ~16.8 TB a vanilla approach would need down to roughly 150-300 GB (98% saved). Backups run on background worker threads with zero main-thread cost. It integrates deeply with [Shinoyuki-BetterAutoSave](https://github.com/ShinoyukiMiyako/Shinoyuki-BetterAutoSave) (BAS).

## Contents

- [What problem does it solve](#what-problem-does-it-solve)
- [How it differs from existing backup mods](#how-it-differs-from-existing-backup-mods)
- [Installation](#installation)
- [Configuration](#configuration)
- [In-game commands](#in-game-commands)
- [Restore flow](#restore-flow)
- [Performance expectations](#performance-expectations)
- [Data safety](#data-safety)
- [Known limitations](#known-limitations)
- [Building](#building)
- [Credits](#credits)

## What problem does it solve

The two pain points of mainstream 1.20.1 backup mods (FTB Backups Z / AromaBackup, etc.):

1. **Footprint explosion**: every backup zips the entire world, so 84 backups = 84 x world size. A 500 MB world balloons to 4 GB overnight; a 200 GB world piles up past 16 TB in a year.
2. **Main-thread freeze**: when a backup starts the server stalls for tens of seconds to minutes, and players get kicked.

BetterBackup's approach:
- **Back up only changed chunks**: a vanilla backup copies the whole world; BetterBackup only processes the chunks BAS just saved (other chunks reference the same bytes across snapshots and are never stored twice).
- **The whole thing runs on worker threads**: once BAS finishes a chunk save it notifies BetterBackup, and the backup work runs on a background worker the main thread never feels.

## How it differs from existing backup mods

| | FTB Backups Z | fastback (git) | BetterBackup |
|---|---|---|---|
| Algorithm | full zip | git object, file-level | **chunk-level content-addressed** |
| Large world (>5 GB) | footprint explosion | git GC chokes 5+ min | OK |
| Main-thread freeze | 30s - 5min | a few seconds | **0ms** |
| Save-mod integration | none | none | **BAS listener** |
| 84-backup footprint (200 GB world) | ~16.8 TB | unbounded | **~150-300 GB** |

[ChronoVault](https://github.com/Catt1eyaa/ChronoVault) (NeoForge 1.21.1) already validated the chunk-slot content-addressing idea on NeoForge; BetterBackup brings that paradigm to Forge 1.20.1 and integrates with BAS for incremental backups. See [Credits](#credits).

## Installation

Requirements:
- Minecraft 1.20.1
- Minecraft Forge 47.3.22+
- Java 17 (Eclipse Temurin)
- **Required**: [Shinoyuki-BetterAutoSave](https://github.com/ShinoyukiMiyako/Shinoyuki-BetterAutoSave) v0.16.2+ (this lower bound matches the dependency versionRange in mods.toml; below it Forge refuses to load BetterBackup). BetterBackup receives chunk-save events through the BAS Listener API; the online single-chunk restore (`restore-chunk-live`) additionally depends on the `SaveCoordination` / `ChunkRestoreOutcome` / `ChunkRestoreResult` API that only exists in BAS v0.16.2+, so on an older BAS the interfaces that feature needs are absent.

Drop `shinoyuki_betterbackup-0.2.0-all.jar` (the one with the `-all` suffix, which bundles the hashing library) into `mods/`; after startup the config file is generated at `config/Shinoyuki-Optimize/shinoyuki_betterbackup/common.toml`.

> Do not install the jar without the `-all` suffix; it lacks the hashing library and throws `ClassNotFoundException` on startup.

## Configuration

`config/Shinoyuki-Optimize/shinoyuki_betterbackup/common.toml`:

| Key | Default | Description |
|---|---|---|
| general.enabled | true | Master switch. Set false and the mod still loads but stops backing up |
| general.backupDirectory | "backup-store" | Backup directory, relative to the server root (sibling to world/). Absolute paths also work |
| storage.hashAlgorithm | XXH128 | Hash algorithm. XXH128 is 5-10x faster than SHA256 and its collision probability is still below a disk bit flip. Switch to SHA256 for integrity-sensitive setups |
| storage.maxStoreSizeGB | 500 | Soft threshold, checked once at startup: prunes per the retention policy, then runs a full GC. With retention disabled there is almost nothing to reclaim; this is not a hard disk quota |
| schedule.mode | INTERVAL | Backup mode: INTERVAL (scheduled) / MANUAL (command only) |
| schedule.intervalMinutes | 120 | Backup interval under INTERVAL mode |
| retention.enabled | false | Master switch for rolling retention pruning. Off by default: every snapshot is kept forever and no history is ever pruned. While off the four quotas below are ignored. Before turning it on, run `/betterbackup retention preview` to see exactly which snapshots one prune pass would delete |
| retention.hourly | 24 | Keep the most recent 24 hourly backups (applied only when retention.enabled=true) |
| retention.daily | 7 | Daily (applied only when retention.enabled=true) |
| retention.weekly | 4 | Weekly (applied only when retention.enabled=true) |
| retention.monthly | 12 | Monthly (applied only when retention.enabled=true) |
| workers.backupWorkerThreads | 2 | Backup worker thread count. CPU-bound (hashing); raise to 4 on large servers |
| safety.verifyOnStartup | true | On startup, clean up orphan .tmp files (left behind by kill -9) |
| prometheus.enabled | false | Enable the Prometheus monitoring HTTP endpoint |
| prometheus.bindAddress | "0.0.0.0" | Monitoring endpoint bind address. On public servers firewall port 9451 or change to 127.0.0.1 |
| prometheus.port | 9451 | Monitoring endpoint port (avoids BAS's 9450) |

## In-game commands

Requires OP level 2.

| Command | Effect |
|---|---|
| `/betterbackup status` | Show running status, queue depth, dirty counts |
| `/betterbackup snapshot create [name]` | Create a backup immediately (async); name is optional |
| `/betterbackup snapshot list` | List the 20 most recent backups |
| `/betterbackup snapshot info <id>` | Show a backup's details (chunk count / dimensions / level.dat status, etc.) |
| `/betterbackup snapshot delete <id>` | Delete a backup's reference (disk is not reclaimed until you run gc). Refused when the id hits one of the three guards (the latest snapshot / the latest baseline-complete snapshot / the pending-restore target), preventing you from deleting the only restorable point |
| `/betterbackup restore <id>` | Prepare a restore (writes a flag file + prompts you to stop the server). **Auto-restores on next startup** |
| `/betterbackup restore-chunk-live [<id>] [<radius>]` | **Online** single-chunk / area restore, applied live without stopping the server. When a player runs it with no args, the chunk they stand in is restored; `<id>` accepts a snapshot id or `latest` (defaults to the newest); `<radius>` is 0-8, a (2r+1)^2 area centered on the player. Console / RCON use the explicit form `restore-chunk-live <id> <dim> <x> <z> [radius]`. Above 9 chunks (radius>=2) it requires `confirm` within 30 seconds |
| `/betterbackup confirm` | Confirm the pending area online restore (valid for 30 seconds) |
| `/betterbackup gc` | Full GC: scan every hash file and delete orphans no backup references |
| `/betterbackup retention preview` | Dry-run preview: compute the rolling-retention "would delete / would keep" lists from the current quotas + the three guards, without deleting anything. Check it before enabling retention |

**Automatic GC**: after each snapshot creation an incremental GC runs automatically, clearing intermediate-version hashes that workers wrote this cycle but no manifest references. Steady-state store size ~= the unique hashes referenced by all current manifests x average bytes -- **this steady state only holds once `retention.enabled` rolling pruning is turned on**. With retention off (the default) every snapshot is kept forever, each pinning the historical versions it references, so the store keeps growing over time (active chunks produce a new version on every save, with no cross-snapshot dedup).

## Restore flow

Restore is **offline mode**: the command does not run immediately. It writes a flag file prompting the player to stop the server manually, and the restore runs on the next startup. This is safe (overwriting files directly, before vanilla has loaded the world, cannot conflict).

```
In-game:
  /betterbackup restore 2026-05-09T23-32-57Z
  -> "Restore prepared. STOP THE SERVER NOW..."

Player stops the server -> restarts

Startup log:
  [BetterBackup] pending restore detected: 2026-05-09T23-32-57Z - rebuilding world before vanilla load
  [BetterBackup] restore complete: chunks=20036 entity=1403 savedData=4 levelDat=true backupDir=.../world.bak-1715293567000
  [BetterAutoSave] pipeline starting ...
  [other mods start normally]
```

Restore **keeps your current world**: it moves `region/`, `entities/`, `data/`, `level.dat`, `playerdata/`, etc. to `<worldRoot>.bak-<timestamp>/` and rebuilds from the backup. Player inventory / advancements / statistics return to the **same snapshot** as the world, so you never get the "world rolled back but inventory did not, letting players dupe" mismatch. If a restore goes wrong you can recover manually from the `.bak` directory.

A failed restore (missing backup store data, etc.) logs an ERROR but **does not block server startup**; the flag is not removed, so once you fix the issue a restart runs it again.

**First baseline scan**: after the mod is installed, the first startup runs a rate-limited background scan of the entire existing world (50 chunks/s by default) to bring the whole existing map into the baseline. Restore is refused until the baseline completes (otherwise the restored world would be missing a large area); `/betterbackup status` shows the scan progress.

### When the server will not start: the offline CLI

The mod jar doubles as a command-line tool (no Minecraft / Forge needed, a bare JRE 17 is enough), so you can self-rescue from a backup even when the server is down:

```bash
java -jar shinoyuki_betterbackup-0.2.0-all.jar list    --store <backup dir>
java -jar shinoyuki_betterbackup-0.2.0-all.jar info    --store <backup dir> --id <snapshot id>
java -jar shinoyuki_betterbackup-0.2.0-all.jar verify  --store <backup dir> [--id <snapshot id>]
java -jar shinoyuki_betterbackup-0.2.0-all.jar restore --store <backup dir> --id <snapshot id> --world <world dir>
java -jar shinoyuki_betterbackup-0.2.0-all.jar fsck    --store <backup dir> [--rebuild-index]
```

`restore` defaults to restoring the entire snapshot; it also supports **partial restore** of terrain only (everything else is left untouched):

```bash
# Single chunk (explicitly named; errors if it was never captured)
java -jar shinoyuki_betterbackup-0.2.0-all.jar restore --store <backup dir> --id <snapshot id> --world <world dir> \
    --dim <dimension id> --chunk <x>,<z>
# Rectangular area (centered on center, side length 2r+1; uncaptured chunks in the area are skipped)
java -jar shinoyuki_betterbackup-0.2.0-all.jar restore --store <backup dir> --id <snapshot id> --world <world dir> \
    --dim <dimension id> --center <x>,<z> --radius <r>
```

`--dim` is a dimension id (like `minecraft:overworld`); `--chunk` / `--center` are chunk coordinates. CLI restore may only be used while the server is **stopped**: a full restore first moves the existing world to `.bak-<timestamp>/` and then rebuilds from the backup. `fsck --rebuild-index` rebuilds the snapshot index from the store when it is corrupted.

## Performance expectations

Real-world reference (a 60-player mid-size server + 2M chunks + 7-day retention + a backup every 2 hours = 84 backups; capping at 84 requires `retention.enabled`, with the default off the count and footprint accumulate over time):

| Metric | FTB Backups Z | BetterBackup |
|---|---|---|
| First backup | ~200 GB | ~140 GB |
| Single increment | ~200 GB (full) | ~1-5 GB (measured ~1%) |
| Total for 84 backups | ~16.8 TB | ~150-300 GB |
| Per-backup time (main thread) | 30s - 5min (server stall) | 0ms |
| Per-backup time (wall clock) | 30s - 5min | 5-30s (worker) |

The dedup ratio depends on the "loaded chunks / total world" ratio:
- Mature large server (2M chunks, 10k-20k loaded): **~98%+**
- Mid-size server (500k, 5k-10k loaded): ~95-98%
- New server (50k, 5k loaded): ~70-90%
- Small private server (20k, 2k loaded): 50-90%

## Data safety

- **Backups never touch world/**: throughout a backup it only reads `.mca` files and writes into `backup-store/`
- **Only restore writes the world**: an in-game restore requires writing a flag + a manual stop and restart before it runs; an offline CLI restore rebuilds the world directly (first moving the existing world to `.bak`) and is only for the stopped state
- **Bad data never enters a backup**: chunks read from disk are integrity-checked; hitting half-written data mid-write is caught and retried, so no garbage bytes end up in a backup
- **BAS degradation is not silent**: when a BAS background worker degrades on error, backups pause automatically and subsequent snapshots are marked incomplete; after a restart the changes within the degraded window are re-captured automatically
- **Automatic backup on shutdown**: on a normal server stop a final snapshot runs, ensuring every change before shutdown is captured
- **kill -9 tolerance**: all writes use `tmp + fsync + atomic rename`, so a force-killed process never leaves a half-written file polluting the store; leftover `.tmp` files are cleaned up on startup
- **A corrupt manifest never wrongly deletes the store**: a full GC that hits an unreadable manifest aborts, never treating the hashes it references as orphans

## Known limitations

- **BAS is required**: BetterBackup receives chunk-save events through the BAS Listener API. With BAS uninstalled, BetterBackup fails to start
- **Coexisting with other backup mods wastes space**: they can run in parallel in theory, but the backup-store and the zip backups both take up space; pick one
- **Modded custom dimensions' SavedData is not fully covered**: the BAS Listener carries no dimension info (an API limitation), so BetterBackup probes overworld -> nether -> end in order to find which dimension's `data/` each `.dat` actually lives in, records the dimension-scoped path, and on restore places it back in the original dimension's `data/`. The three vanilla dimensions are covered; a mod that writes SavedData into a custom dimension's `data/` (very rare) is not yet fully covered
- **Loaded chunks are not deduplicated across backups**: a chunk in an active area changes its bytes every save (vanilla updates timestamp fields), so every snapshot stores a new version of it. The bulk of the space savings comes from unloaded areas -- which is why the performance table above is tiered by server size
- **Mods that bypass the vanilla save path are not seen**: the rare mod that writes region files directly will not have its changes captured
- **Non-overworld poi is out of backup scope**: the Nether/End `poi/` (villager workstation cache) is rebuilt automatically by vanilla after a restore and does not affect gameplay data
- **The store path on NFS / SMB is untested**: it should work in theory, but lock semantics are inconsistent and performance is poor; use a local disk

## Building

```bash
# 1. Publish the BAS repo first (produces the mod jar into the local maven)
cd Shinoyuki-BetterAutoSave
./gradlew publish

# 2. Build the BetterBackup repo
cd Shinoyuki-BetterBackup
./gradlew build

# Artifacts in build/libs/:
#   shinoyuki_betterbackup-0.2.0-all.jar    <- install this one
#   shinoyuki_betterbackup-0.2.0.jar         <- lacks the hashing library, do not use
```

Tests: `./gradlew test` (store / snapshot / GC / torn read / baseline / player data / CLI / degraded re-capture, etc. -- 266 unit tests in total).

Dev server testing (no client install needed): `./gradlew runServer`, then in the server console run `/op <your name>` and connect a client to localhost.

## Credits

BetterBackup's core dedup algorithm -- hashing the raw chunk-slot bytes inside the region file (`.mca`) directly, without going through in-memory NBT -- is borrowed from [ChronoVault](https://github.com/Catt1eyaa/ChronoVault) (NeoForge 1.21.1).

This approach elegantly sidesteps the byte-instability of vanilla `CompoundTag` serialization (`HashMap` iteration order / `tag.merge` / data fixer rebuilding tags all make semantically identical content produce different bytes), giving cross-JVM byte identity that keeps the dedup ratio stable. ChronoVault proved it viable on NeoForge 1.21.1; BetterBackup brings the paradigm to Forge 1.20.1 and integrates with BAS's listener API for incremental backups (processing only the chunks BAS just saved, never actively scanning the whole world).

## License

AGPL-3.0-or-later, with two section 7 additional permissions ([LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md)): a modpack distribution exception — unmodified official release jars may be included verbatim in modpacks and server packs with no obligation beyond keeping the project name and a repository link — and a Minecraft linking exception explicitly permitting combination with Minecraft itself and LGPL-licensed mod loaders. Modified versions of this mod remain under the full AGPL, including its section 13 network terms.
