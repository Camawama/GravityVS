# Upstream Issue Reports

Bugs discovered during Gravity Unbound development whose root cause lives in
**another mod** (or in vanilla/Forge). Each entry records the evidence
gathered here and a ready-to-paste issue draft for the upstream tracker, so a
report can be filed without re-doing the investigation.

Conventions: newest entries first. Update the **Status** line when a report is
filed (link the issue) or when an upstream release fixes it.

---

## 1. Valkyrien Skies — entity-section corruption from off-thread player position writes

| | |
|---|---|
| **Upstream** | Valkyrien Skies 2 — https://github.com/ValkyrienSkies/Valkyrien-Skies-2/issues |
| **Status** | NOT YET FILED (no matching public issue found as of 2026-08-22) |
| **Affected version** | VS `1.20.1-forge-2.4.11` (latest 1.20.1 build at time of writing) |
| **Environment** | Forge 1.20.1-47.4.16, integrated server; mods: VS 2.4.11, Gravity Unbound, Cloth Config, Kotlin For Forge, MixinExtras, MixinSquared |
| **Severity** | Server crash ("Ticking block entity") or player disconnect; runtime-only corruption (world saves unaffected) |

### Symptom

The backing `ArrayList` of an entity section's `ClassInstanceMultiMap` ends
up in an internally impossible state. Every subsequent touch of that section
throws, with two observed terminal outcomes:

- `java.util.NoSuchElementException` inside `EntitySection.getEntities`
  during any full iteration (crashed the server inside a Gravity Unbound
  block-entity tick, which got the "Suspected Mod" blame; that iteration is
  hardened on our side since round 62);
- `java.lang.ArrayIndexOutOfBoundsException: Index -1 out of bounds for
  length 10` inside `ClassInstanceMultiMap.add/remove` during move-packet
  handling — surfacing either as a "Failed to handle packet ... suppressing
  error" storm or as a player kick
  (`Dev lost connection: Internal Exception: ...`).

### Root-cause evidence

**(a) The corrupting write happens OFF the server thread.** During a lag
spike (server 155 ticks behind from mass fluid updates), the player's
entity-section transfer executed on a **Netty IO thread**:

```
[21Aug2026 23:01:11.953] [Server thread/WARN] ...: Can't keep up! Is the server overloaded? Running 7791ms or 155 ticks behind
[21Aug2026 23:02:03.840] [Netty Server IO #1/WARN] [net.minecraft.world.level.entity.PersistentEntitySectionManager/]:
    Entity ServerPlayer['Dev'/34, ...] wasn't found in section SectionPos{x=-1, y=3, z=-8} (moving to -7340029)
```

Section transfers are server-thread-only in vanilla (`handleMovePlayer` is
gated by `PacketUtils.ensureRunningOnSameThread`). A section move on a Netty
thread racing the server thread tears the list; ~28 seconds later the
AIOOBE storm began, and the first full iteration crashed the server.

**(b) The position writer in the failing stacks is VS's move-packet mixin.**
The post-corruption AIOOBE stacks all pass through a TAIL injection into
`handleMovePlayer` doing a raw `Entity.setPos` (VS's handler; it fires even
for rotation-only packets):

```
java.lang.ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 10
    at java.util.ArrayList.fastRemove(ArrayList.java:642)
    at net.minecraft.util.ClassInstanceMultiMap.remove(ClassInstanceMultiMap.java:43)
    at net.minecraft.world.level.entity.EntitySection.remove(EntitySection.java:27)
    at net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback.onMove(PersistentEntitySectionManager.java:380)
    at net.minecraft.world.entity.Entity.setPosRaw(Entity.java:3278)
    at net.minecraft.world.entity.Entity.setPos(Entity.java:394)
    at net.minecraft.server.network.ServerGamePacketListenerImpl.handler$zjg000$afterHandleMovePlayer(ServerGamePacketListenerImpl.java:3477)
    at net.minecraft.server.network.ServerGamePacketListenerImpl.handleMovePlayer(ServerGamePacketListenerImpl.java:947)
    at net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.handle(...)
    at net.minecraft.network.protocol.PacketUtils.lambda$ensureRunningOnSameThread$0(PacketUtils.java:22)
```

**(c) Lag is a catalyst, not a requirement.** A second occurrence
(2026-08-22 02:46:00) happened with NO lag events for 100 minutes, while the
player stood on a **moving VS ship** (`EntityDragger` active, ship carrying
the player ~0.4 blocks/render-tick — i.e. constant section transfers):

```
[22Aug2026 02:46:00.798] [Server thread/INFO] [...ServerGamePacketListenerImpl/]:
    Dev lost connection: Internal Exception: java.lang.ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 10
```

**(d) Other mods' involvement ruled out at the failure moment.** Gravity
Unbound's move-path hooks were passthrough during (c): the player was not in
capsule mode (both capsule-specific overrides gated off), the two remaining
move-packet mixins only transform vectors under non-default gravity (player
was default), and all Gravity Unbound network handlers use
`ctx.enqueueWork` (verified — nothing of ours touches the world from Netty
threads). The crash frames that carried the blame were read-only iterations.

### Ready-to-paste issue draft

> **Title:** Entity section corruption (AIOOBE "Index -1" / NoSuchElementException) from player position writes racing section transfers — seen on Netty IO thread and while riding moving ships
>
> **Version:** 1.20.1-forge-2.4.11, Forge 47.4.16, integrated server.
>
> **Symptom:** After riding a moving ship (sometimes catalyzed by server
> lag), the player's entity section's `ClassInstanceMultiMap` becomes
> internally corrupted. All subsequent operations on that section throw —
> `ArrayIndexOutOfBoundsException: Index -1 out of bounds for length N` in
> `ClassInstanceMultiMap.add/remove` during `handleMovePlayer`, and
> `NoSuchElementException` in `EntitySection.getEntities` for any iterator —
> ending in a server crash or player disconnect. Restart clears it (runtime
> state only).
>
> **Evidence:** (1) a `PersistentEntitySectionManager` "wasn't found in
> section (moving to ...)" warning logged from `Netty Server IO #1` during a
> lag spike — a section transfer executing off the server thread; (2) the
> post-corruption AIOOBE stacks run through
> `handler$...$afterHandleMovePlayer` -> `Entity.setPos` (fires on every
> move packet, including rotation-only ones); (3) reproduction without lag
> while standing on a moving ship (EntityDragger active), where world
> position changes every tick. Full logs and stacks attached.
>
> **Suspected mechanism:** the move-packet position rewrite (and/or the
> dragged-rider position path) can execute a `setPosRaw` section transfer
> concurrently with server-thread entity operations; one torn
> `ClassInstanceMultiMap` then poisons the section permanently.

Attach: `run/crash-reports/crash-2026-08-21_23.02.32-server.txt`, the
`latest.log` excerpts above (23:01:11-23:02:32 window and the 02:46:00
disconnect with the preceding `vs-drag` context lines).

### Local mitigations already shipped (Gravity Unbound)

- Round 62: field block entities' entity queries survive torn sections
  (`GCUtil.safeFieldEntityQuery` — degrade to the previous cache, warn,
  never crash the tick).
- Rounds 63-64: fluid field-query cost reduced (~19x on the hot scan), which
  removes the mass-update lag spikes that catalyzed occurrence (a).
