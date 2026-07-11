# GravityVS — Codebase Breakdown & Issue Analysis

> **STATUS UPDATE (2026-07-11): the issues below have been fixed**, after two
> reworks. The final architecture (restored from the original working Gravity API
> in `src/reference/Gravity-API-main`):
>
> - **Physics is always cardinal.** The hitbox, collision, and movement axes are
>   exact rotated boxes on one of the six cardinal orientations — a Minecraft
>   AABB cannot be tilted, and any attempt to fake it (rotated-corner envelopes)
>   corrupts collision. Fields blend into an arbitrary target *vector*, whose
>   nearest cardinal (with hysteresis + opposite-flip stability) becomes the
>   physics direction.
> - **Everything else is fully unlocked from cardinals.** Each entity carries a
>   continuous, rate-limited VISUAL/AIM frame (`GravityCapabilityImpl.visualRotation`)
>   that chases the true arbitrary-angle field vector: camera, model, look/aim,
>   player movement, and local velocity (and therefore the gravity pull itself)
>   all run through it. It converges bit-exactly onto the canonical cardinal
>   frame when the field is cardinal, so all cardinal behavior is identical to
>   the original mod. Because the frame never jumps, no camera animation and no
>   yaw remap are needed.
> - **F3+B hitboxes render correctly now**: the hitbox/shadow render leg was
>   missing a quaternion conjugation (a bug inherited from the fork) — boxes
>   looked double-rotated/inside walls for the four 90-degree gravity
>   directions while being physically correct.
>
> Also fixed along the way: ship-relative friction replacing the anti-slide hack
> (§2.1), sync rework with initial sync and no ack protocol (§4), plating
> cache/polarity bugs (§5), `MixinServerLevel` deleted (§7), surgical
> `renderLevel` injection replacing the vanilla copy (§8), gravity core block +
> ship force inducer added (§9), command argument type registration (login kick),
> inverted velocity-affecting-position offset, and the §6/§10 cleanups.
>
> Known remaining limitations: on significantly rotated VS ships the hitbox
> aligns to the nearest world cardinal, not the deck; mob AI steering/pathfinding
> remains cardinal-based; plating still scans per block entity (§2.7 perf note).

Analysis of the `experimental` branch as of commit `e131685`, before the rework.
File references use `path:line` of the source at that time.

---

## 1. Root causes of the glitchy arbitrary-angle movement

These are the issues most directly responsible for the "walking direction is glitchy when upside down on a ship" symptom. They compound each other.

### 1.1 The arbitrary-gravity rotation frame has no stable "twist" (the smoking gun)

`RotationUtil.getWorldRotationQuaternion(Vec3)` (`util/RotationUtil.java:318`) builds the player's reference frame with `QuaternionUtil.getRotationBetween(gravityVector, DOWN)` — a stateless shortest-arc rotation. Shortest-arc rotations only constrain *where down points*; the yaw (twist) around the gravity axis is whatever falls out of the math. Three concrete consequences:

- **Upside-down is maximally unstable.** When gravity ≈ UP, `getRotationBetween` hits its antiparallel special case (`util/QuaternionUtil.java:28-39`) and picks an arbitrary axis. Worse, when gravity is *near* UP but slightly tilted (as it constantly is from the plating blend and ship rotation), the shortest-arc axis is perpendicular to the tiny tilt component — so a fraction-of-a-degree wobble in the gravity vector swings the player's yaw frame by up to 180°. Movement input (`Entity.move`, `EntityMixin.java:239-249`) is transformed through this frame, so WASD direction flips around chaotically. This is exactly the reported bug.
- **The Vec3 path and the Direction path disagree.** The six hardcoded cardinal quaternions (`RotationUtil.java:290-307`) each contain a specific yaw convention (e.g. EAST = `XP(-90) * YP(-270)`). The shortest-arc quaternion produced for the *same* cardinal vector has a different twist (e.g. EAST via shortest-arc is `ZP(-90)`). So even at perfectly cardinal gravity, code using the `Direction` overloads and code using the `Vec3` overloads compute **different player-space frames** for the same entity in the same tick.
- **No continuity between ticks.** For a smooth "walk around a small planet" experience the frame must be transported continuously from the previous tick (minimal-twist / parallel transport). A stateless world-down-relative quaternion cannot do that; every gravity change re-derives yaw from scratch.

### 1.2 Systemic cardinal snapping — two frames used simultaneously

`GravityCapabilityImpl.getCurrGravityDirection()` (`capabilities/GravityCapabilityImpl.java:578`) snaps the arbitrary vector to the nearest cardinal via `Direction.getNearest`. Roughly half the codebase uses the snapped `Direction`, the other half the true `Vec3`:

- **Cardinal (snapped):** all of `LivingEntityMixin` (travel Y redirects, look angle, knockback, limb animation, damage direction), all of `PlayerMixin` (sneak edge-backoff, attack yaw, drop position), `DirectionMixin`, `ServerGamePacketListenerImplMixin`, `LocalPlayerEntityMixin`, `fall_distance/*`, `GravityChangerAPI.getEyeOffset` (`api/GravityChangerAPI.java:138`), non-player movement in `EntityMixin.modify_move_Vec3d_0_0` (`mixin/EntityMixin.java:245-247`).
- **Vector (true direction):** `Entity.move`/`collide` transforms for players, bounding box construction, eye position, camera, rendering.

At 45°-ish blended gravity the two frames differ by up to 45°, so e.g. the *camera* stands at the true angle while *travel physics, knockback, sneaking, anti-cheat move handling, and fall damage* all operate in a snapped frame. This alone guarantees janky movement at any non-cardinal angle, and it means mobs (which use `vecEntityToWorld(Direction)`) can never move correctly at arbitrary angles at all.

Also note `vecEntityToWorld` and the `rotWorldToPlayer`/`rotPlayerToWorld` (yaw/pitch) helpers have **no Vec3 overloads whatsoever** — arbitrary-angle support was only added to some of the vector helpers.

### 1.3 Gravity "changes" fire every single tick while in a plating field

`applyGravityChange()` (`GravityCapabilityImpl.java:632-649`) compares `prevGravityDirection.equals(currGravityDirection)` — exact double equality. The plating blend (`resolveGravityDirection`, weights based on distance recomputed each tick) produces a normalized vector that differs in the last ulps every tick the entity moves. Consequences, *every tick*:

- `entity.fallDistance = 0` (`GravityCapabilityImpl.java:407`) — no fall damage anywhere inside a gravity field (also an exploit).
- Bounding box rebuild + `adjustEntityPosition` collision scan + `entity.setPos` (`:421-432`) — fights the collision system and VS's constraint solver, causes micro-teleports.
- Velocity re-rotation through `QuaternionUtil.getRotationBetween(old, new)` (`:451-461`) — accumulates error and can inject sideways velocity.
- A new rotation animation is started (`:434-449`) — see 1.4.
- A sync packet is broadcast to all tracking clients (see §4).

There is no angular epsilon anywhere ("only react if the direction moved more than X degrees"), and no smoothing/slerp of the gravity vector itself between ticks.

### 1.4 `rotationTime = 0` silently skips the view-preservation step

Plating uses `RotationParameters(true, true, 0)` (`plating/GravityPlatingBlockEntity.java:55`). `RotationAnimation.startRotationAnimation` returns immediately when `durationTimeMs == 0` (`RotationAnimation.java:31-34`) — **before** the yaw/pitch remap at lines 50-66 that re-expresses the entity's look direction in the new gravity frame. So with instant rotation, every frame change leaves yaw/pitch numerically unchanged while the frame under them rotates → the look/walk direction drifts or jumps as you walk around a corner or ride a rotating ship. (For the cardinal-plate floor→wall walk in the original mod this remap was the thing that made it feel smooth.)

### 1.5 Collision is wrong at non-cardinal angles

- The rotated bounding box is an axis-aligned *envelope* of the rotated player box (`RotationUtil.boxPlayerToWorld(AABB, Vec3)`, `RotationUtil.java:219-244`). At 45° the envelope is ~1.4–1.7× the player's true extent in each axis, so the player floats above surfaces, can't fit through gaps, and gets phantom collisions. This is a fundamental design limitation of keeping world-axis-aligned AABBs while allowing arbitrary body orientation.
- The ordered per-axis collision resolution is only implemented for cardinal gravity; non-cardinal falls back to a raw `collideWithShapes` (`EntityMixin.java:428-445`), losing step order semantics.
- Vanilla step-up (walking up slabs/stairs) and `maybeBackOffFromEdge` sneaking logic operate in the snapped cardinal frame (`PlayerMixin.java:148-216`), so stepping misbehaves at arbitrary angles — likely a contributor to what felt like "VS slope-sliding logic".

---

## 2. Valkyrien Skies integration issues

All VS logic lives in `plating/GravityPlatingBlockEntity.java`; there is no abstraction layer.

### 2.1 The "anti-slide" logic fights ship motion and player momentum

`GravityPlatingBlockEntity.tick`, lines 416-432: when a `LivingEntity` has no WASD input (`xxa`/`zza` ≈ 0), the code projects the entity's **world** velocity onto the plate normal (in ship space) and **discards all tangential velocity**. Problems:

- It never subtracts the *ship's own velocity*. On a moving ship, an entity standing still relative to the ship has world velocity ≈ ship velocity — mostly tangential to the plate — so this zeroes it every tick and the entity is dragged backward relative to the ship / jitters against VS's own entity-dragging.
- It kills legitimate momentum: jump arcs, knockback, throwable recoil, walking momentum after releasing keys — all snap to zero instantly instead of decaying via friction.
- It runs once per plate-side per plating block entity whose field contains the entity, so the projection is applied multiple times per tick with the *last plate wins* semantics.
- It runs on both client and server (see 2.3); for `ServerPlayer`s the server-side `setDeltaMovement` never reaches the client (no velocity packet), so the server and client physics disagree → rubber-banding.
- `xxa < 0.01` also matches AI mobs whose steering is applied elsewhere; mobs in the field get their pathing velocity erased.

### 2.2 Ship transform is sampled at tick rate, gravity applied in world frame

The plate direction is transformed by `getShipToWorldMatrix()` once per game tick (`GravityPlatingBlockEntity.java:335-339`). VS's physics runs at 60 Hz with render interpolation; between ticks the ship rotates but the entity's gravity vector (and thus their whole movement/camera frame) stays frozen, then snaps. Combined with 1.1's twist instability, standing on a rotating ship makes the frame both stale *and* unstable. There is no use of VS's render transform on the client or physics-tick hooks on the server.

### 2.3 The block entity ticker is identical on both sides — intentionally or not

`GravityPlatingBlock.getTicker` (`plating/GravityPlatingBlock.java:181-186`) has an `if (world.isClientSide)` whose two branches are **identical**. If client ticking is intended (the local player computes gravity client-side, so it is *required*), the branch is dead code; if not, velocity writes to remote entities on the client are a bug. Either way, on the client the plating applies gravity effects to *remote* players/entities whose `updateGravityStatus` early-returns (`shouldAcceptServerSync`, `GravityCapabilityImpl.java:215-217`) — and because the update event never fires for them, every effect lands in `delayApplyDirEffects`, which is **never cleared for remote entities → unbounded list growth (memory leak) on clients** for any synced entity standing in a field.

### 2.4 Attempted "optional VS" handling is dead/incorrect code

- `mods.toml` declares `valkyrienskies` as `mandatory=true`, yet `tick()` wraps `getShipManagingPos` in `try/catch (Exception e)` "in case VS2 is not present" (`GravityPlatingBlockEntity.java:281-286`). If VS were actually absent, class loading would throw `NoClassDefFoundError` — an `Error`, not an `Exception` — and it would be thrown when the class is *loaded* (top-level imports), not inside the try. The catch can never do what the comment says, and it silently swallows real VS exceptions every tick.

### 2.5 Gravity fields never affect ships themselves (feature gap)

The stated goal is fields that "apply pulling or repelling force to any entity or VS ship." Nothing in the codebase applies forces to ships — there is no `ShipForcesInducer`/attachment, no mass query, no ship AABB-vs-field intersection logic. Only `Entity` instances are affected (`world.getEntitiesOfClass(Entity.class, ...)`).

### 2.6 Artificial-gravity force is hardcoded and inconsistent

`GravityPlatingBlockEntity.java:57-68, 387-414`:
- The dimension whitelist (`ad_astra:*`, `genesis:*`) is hardcoded in Java rather than config/tags.
- The 0.08 blocks/tick² acceleration is hardcoded, ignores the plate's `level`, the capability's gravity *strength*, and the `gravityStrengthMultiplier` config (which `LivingEntityMixin.multiplyGravity` does apply elsewhere — so field force and normal gravity disagree).
- It adds velocity every tick with no terminal velocity handling and stacks once per plate side containing the entity, so overlapping plates multiply the force.

### 2.7 Per-plate, per-tick overhead

Every plating block entity, every tick, on both sides: `getShipManagingPos` lookup, 8-corner AABB transform, and a `getEntitiesOfClass` world query (`GravityPlatingBlockEntity.java:270-320`). A fully plated 3×3×3 ship is 26 block entities doing 26 entity scans per tick. There is no shared field manager that aggregates plates into one query.

---

## 3. Gravity resolution & intersecting fields

- `resolveGravityDirection` (`GravityCapabilityImpl.java:290-338`) blends all effects within `BLEND_RANGE = 5.0` priority units of the max. Plating priority is `1000 - distanceToPlate`, so *all plates within 5 blocks of the nearest plate's distance* blend by distance. This produces the arbitrary-angle vectors that the rest of the code can't handle (§1), and near edges/corners the weights shift every tick → constant micro-changes (§1.3).
- **Opposing fields cancel to garbage:** if accumulated gravity ≈ zero (`accumulatedGravity.lengthSqr() <= 0.0001`, line 329), the code silently *keeps the previous direction* — there is no zero-g state, no "rotate freely but keep control" behavior as you described wanting, and no hysteresis. With two exactly opposing fields the entity's gravity flips based on floating-point noise in which weight is larger; combined with §1.3 each flip resets fall distance, rotates velocity 180°, and restarts the animation → the classic "stuck vibrating between two fields" glitch.
- The `isOpposite` −10 priority penalty plus eye-position testing (`GravityPlatingBlockEntity.java:341-344, 379-381`) is the only anti-flip mechanism; it pushes opposite plates entirely out of blend range, so walking across the *inside* of a plated box (floor→ceiling transition) hard-snaps rather than blending.
- There is no per-entity "current field" ownership, no dominant-field selection, no angular rate limit on how fast gravity may rotate. All of the intelligent-intersection behavior you want has no place to live in the current architecture.

---

## 4. Networking & synchronization

### 4.1 Packet storm by design
Two overlapping send paths exist: `updateGravityStatus` sends on every detected change (`GravityCapabilityImpl.java:281-287`) — which per §1.3 is *every tick inside a field* — and `tick()` re-sends every tick while `needsSync` is true (`:203-207`). `needsSync` is only cleared when some client round-trips an ack (`UpdateGravitySyncStatePacket`). Every packet is 65 bytes × every tracking client, and **every receiving client acks every packet** (`GravityCapabilityImpl.sync`, `:348-359`), so N tracking clients generate N acks per tick per entity. With several entities on a plated ship this floods both directions.

### 4.2 The ack protocol is wrong in several ways
- Any client's ack clears `needsSync` for everyone — a player who starts tracking the entity later never receives the state.
- **There is no initial sync at all:** `PlayerListMixin` is an empty class (`mixin/PlayerListMixin.java`) — clearly gutted during the port — and there's no `PlayerEvent.StartTracking` handler. A player who logs in or comes into range sees every non-default-gravity entity standing in the wrong orientation until its gravity next changes.
- The server trusts the UUID in the ack with no validation (`UpdateGravitySyncStatePacket.Handler`, any client can clear sync state / `noAnimation` for any entity).
- `UpdateGravityCapabilityPacket.Handler` (`network/UpdateGravityCapabilityPacket.java:68`) does not null-check `getEntityByUUID` — a packet arriving before the entity spawns client-side throws an NPE.
- `RotationParameters` are never synced; remote clients always animate with defaults.

### 4.3 The local player's gravity is client-authoritative with no reconciliation
`shouldAcceptServerSync` makes the client player compute gravity purely client-side from block entities, while the server computes its own copy. Any divergence (chunk not loaded client-side, tick ordering, ship transform differences between client and server VS states) means client and server disagree about which way is down — and `handleMovePlayer` rotates the movement vector by the *server's* idea of gravity (`ServerGamePacketListenerImplMixin.java:140-154`), amplifying the disagreement into rubber-banding. There is no detection or correction mechanism.

---

## 5. Plating block entity bugs

- **Always-false comparison:** `SideData.getEffectBox` line 142: `if (isAttracting != this.isAttracting) {continue;}` compares a field to itself (both resolve to the same `SideData` field). It clearly meant to compare against the *neighbor's* side data (`be` is fetched on line 140 and then never used). Result: effect boxes merge across neighboring plates even when polarities differ, and the neighbor's `level`/range is ignored (uses `this.getEffectRange()` on line 144).
- **Cache never invalidates for most positions:** `refreshCache` line 240 uses `worldPosition.hashCode() % 5 == world.getGameTime() % 5`. In Java, `%` of a negative hash is negative (−4..0), while `gameTime % 5` is 0..4 — so any plate whose position hash mod 5 is negative-nonzero **never clears its effect-box caches**. Break a neighboring plate or change a side's level and the old field geometry persists indefinitely for those plates.
- `getRoughEffectBox` computes the box but never assigns `roughAreaBoxCache` (`GravityPlatingBlockEntity.java:250-268`) — the "cache" recomputes every tick (harmless but wrong), while the *invalidation* logic above pretends it's cached.
- `interact` blind-casts `player` to `ServerPlayer` (`:562, :572`) — fake players (mod block-interactors) that aren't `ServerPlayer` subclasses would crash.
- Upgrade material (`Items.AMETHYST_CLUSTER`) and `MAX_LEVEL = 64` are hardcoded leftovers from AmethystGravity.

---

## 6. Capability & API design issues

- **Dummy capability with null entity:** `GravityChangerAPI.getGravityComponent` returns `orElse(new GravityCapabilityImpl())` (`api/GravityChangerAPI.java:110`) — a fresh impl whose `entity` field is null. Any caller that then invokes `setBaseGravityDirection`/`tick`/`getRotationAnimation` on an entity that lacks the capability (invalidated, or during shutdown) NPEs inside `canChangeGravity`; mutations are silently discarded. `getGravityComponentEarly` is a lie — it's the same call with a `@Nullable` annotation.
- **Client class referenced in common code:** `GravityCapabilityImpl.applyGravityDirectionChange` calls `net.minecraft.client.Minecraft.getInstance().getFrameTime()` (`GravityCapabilityImpl.java:440`) inside a method that also runs on the dedicated server (guarded only by a runtime `isClientSide()` check). This is the canonical Forge dist-crash hazard; it must be behind `DistExecutor`/a client-only helper class.
- **Config is partially dead:** `RotationParameters.updateDefault()` is never called from anywhere, so the `rotationTime` and `worldVelocity` config options have no effect — defaults stay hardcoded at 500 ms / rotate-velocity.
- **`/gravity` command is dead:** `GravityCommand.register` exists but its registration is commented out (`event/CommonEvents.java:24`), and the argument types in `command/` are registered but unused. No way to debug/set gravity in game.
- `EntityTags.canChangeGravity` returns `true` unconditionally (`EntityTags.java:6`) — every entity including item frames, VS's own utility entities, and other mods' technical entities gets gravity processing and capability ticking. No tag, no config, no opt-out.
- AT exposes the private `Entity.level` field (`accesstransformer.cfg`) and code mixes `entity.level` and `entity.level()` inconsistently — fragile and needless since the getter exists.
- `serializeNBT` never writes rotation parameters or the `initialized` flag; `deserializeNBT`'s side effects (starting an animation, setting `needsSync`) make it dangerous to call casually — which `CommonEvents.onPlayerClone` does for **every entity in the level** on every player death (`CommonEvents.java:45-57`).

---

## 7. `MixinServerLevel` + `GCUtil.ENTITY_MAP` — should be removed entirely

This is the most alarming code in the repo (`mixin/MixinServerLevel.java`, `util/GCUtil.java:25-26`, `event/CommonEvents.java:28-33`):

- Every entity that ever joins any level (client or server, any dimension) is stored in two static maps keyed by `entity.getClass().hashCode()` with **strong references, never removed** → permanent memory leak of entity objects (which pin their level).
- Keying by class hash means only the *most recent* entity of each class is remembered, from *any* dimension.
- On every `ServerLevel.addFreshEntity`, a `SecurityManager` subclass (deprecated-for-removal; removed in newer JVMs) walks the **entire call stack**, and if any class on the stack matches a remembered entity class, the *new* entity inherits the *remembered* entity's gravity. The intent is evidently "projectiles/drops spawned by a rotated entity inherit its gravity," but the mechanism is a heuristic stack inspection that: picks the wrong source entity whenever two entities of the same class exist; fires on completely unrelated spawns (any spawn whose call stack passes through an entity class); runs an expensive stack walk on every spawn; and holds stale references to despawned entities.
- The correct data (the actual owner/shooter) is available at every real spawn site; several mixins (`ProjectileMixin`, `ItemEntityMixin`, etc.) already exist for exactly these cases, making this global hack redundant as well as broken.

---

## 8. Rendering / client issues

- **`GameRendererMixin.renderLevel` is a full vanilla copy-paste override.** When gravity ≠ down or an animation is running, it cancels vanilla `renderLevel` and re-implements it (`mixin/client/GameRendererMixin.java:53-137`). Any other mod that injects into `renderLevel` (Embeddium/Rubidium, Oculus/shaders, VS's own rendering hooks, camera mods) is silently bypassed the moment gravity activates — a huge compat trap and a maintenance hazard (the copy already omits vanilla details like the confusion-effect FOV interaction nuances and any future Forge patches).
- **Entity rotation is gated on the shadow flag:** `EntityRenderDispatcherMixin.inject_render_0/1/2` all early-return when `!this.shouldRenderShadow` (`EntityRenderDispatcherMixin.java:60, 82, 101`). Turn entity shadows off (video settings / `reduceDebugInfo` contexts) and rotated entities render **unrotated**. The shadow flag has nothing to do with the model transform; this looks like a copy-paste of the guard from the shadow code.
- **Animation clock is tick-quantized:** all animation timing uses `gameTime * 50 + partialTick * 50` (`CameraMixin.java:84`, `GameRendererMixin.java:64`, `GravityCapabilityImpl.java:441`). `gameTime` freezes when the server lags, and mixing `Minecraft.getFrameTime()` (capability) with per-call `tickDelta` (camera) can sample slightly different clocks in the same frame. Fine-grained camera smoothing for a 60 Hz rotating ship cannot be built on this clock.
- The camera/eye offset path ignores VS entirely — no interaction with VS's camera transform when standing on a ship, so the two systems each apply their own idea of where the camera should be.

---

## 9. Feature gaps vs. your stated goals

| Goal | Current state |
|---|---|
| Smooth walking around a plated cube (small planet) | Blending produces the vectors, but §1.1–1.5 make the movement frame unstable; no minimal-twist frame continuity exists |
| Fields pull/repel VS ships | Not implemented at all (§2.5) |
| Intelligent intersecting-field handling / zero-g tumble with control | Not implemented; opposing fields keep-last-direction with per-tick flapping (§3) |
| Gravity core block (radial field, configurable range) | Does not exist; only plating. Nothing in the field system supports radial (point-source) direction — plate logic is face/box-based, and `resolveGravityDirection` has no concept of a field *shape* |
| Smooth ground↔ship transitions | Direction is computed in world space so blending across the boundary is theoretically possible, but per-tick change events (§1.3), the skipped view remap (§1.4), client/server ship-transform divergence (§4.3), and the anti-slide velocity kill (§2.1) each break it in practice |

Architecturally: gravity *sources* (plating, future core) push effects directly at entities they find via per-block-entity AABB scans. For cores, ship forces, and intersection logic you will eventually need the inverse — a per-level field registry that entities/ships query — otherwise every new source re-implements scanning, ship transforms, sync, and blending.

---

## 10. Smaller items

- `GravityNetwork` uses the long-deprecated `NetworkRegistry.newSimpleChannel` pattern with `ID` mutable static — fine on 1.20.1 Forge but worth noting.
- `mods.toml` has no `displayTest` and no `issueTrackerURL`; description still says "example mods.toml" boilerplate at the top.
- `RotationParameters.rotateView` is documented as ignored (`api/RotationParameters.java:9`) but is passed around everywhere as if meaningful.
- `vecToRot` divides by `cosPitch` (`RotationUtil.java:274-284`) — NaN yaw when looking straight up/down along the gravity axis; with arbitrary gravity this happens more often (feeds NaN into yaw remaps).
- `EntityMixin` fast paths allocate `new Vec3(0, -1, 0)` and compare with exact `equals` on every call of hot methods (move/collide/getEyePosition) — constant + epsilon check would be cheaper and more correct for near-down blended vectors.
- `GravityPlatingBlock.canSurvive` can `IndexOutOfBounds` (`plating/GravityPlatingBlock.java:217-222`) if all candidate directions are removed by the existing-state subtraction.
- `updateShape` in `GravityPlatingBlock` is missing `@Override` (it *does* override; if the signature ever drifts after a mapping change it would silently stop applying).
- Duplicated `getY` redirect methods ×4 in `LivingEntityMixin` and large blocks of commented-out dead code throughout (`ServerGamePacketListenerImplMixin`, `PlayerMixin`, `LivingEntityMixin`) — port residue that obscures what is actually active.
- Two block variants exist in assets (`plating`, `plating_dense`) but the code registers behavior only around one plating block type; verify `GravityBlocks` registrations match the assets.
- `GravityCapabilities.attachEntityCapability` never invalidates its `LazyOptional` on entity removal (Forge logs warnings and it delays GC).

---

## Priority summary

If you fix things in order of impact on your goals:

1. **Frame stability (§1.1, §1.4):** a continuous, minimal-twist player frame carried tick-to-tick, used *everywhere* (one source of truth), with the cardinal fast-path quaternions made consistent with it.
2. **Kill the per-tick change storm (§1.3, §4.1):** angular epsilon + rate-limited slerp of the gravity vector; separate "direction drifted slightly" from "gravity changed, run the big event."
3. **Unify cardinal/vector code paths (§1.2):** every mixin that calls `getGravityDirection()` (Direction) is a latent arbitrary-angle bug.
4. **Redo the VS velocity handling (§2.1–2.3):** work relative to ship-frame velocity, single application point, server→client velocity sync or client-side-only application for players.
5. **Delete `MixinServerLevel`/`ENTITY_MAP` (§7)** and replace with owner-based inheritance at spawn sites.
6. **Sync rework (§4):** initial sync on start-tracking, drop the ack protocol, null-check handlers.
7. Then the new features (gravity core, ship forces, zero-g intersection state) on top of a field-registry architecture (§9).
