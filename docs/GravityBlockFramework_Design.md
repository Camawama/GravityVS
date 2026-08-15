# Gravity Block Framework — Design

Status: **design + API foundation** (August 2026). The placement-orientation
API (`api/GravityBlockHelper`) ships now; the sticky-block wrapper system
described below is the next major work item and is deliberately NOT stubbed
out in code — a half-implemented block wrapper is worse than none.

## Goal (from the roadmap)

Grid-aligned but rotation-unlocked blocks:

- Blocks always snap to the normal block grid — never free-floating or at
  arbitrary angles.
- Within the grid, all 24 orientations are legal: upside down, sideways on a
  wall, facing any direction.
- Vanilla behavior, inventories, animations and interactions are preserved
  (a chest placed upside down on a wall still opens, animates and stores
  items).

## What ships today

`net.cama.gravityapivs.api.GravityBlockHelper`:

- `localToWorld(Direction, Entity)` / `worldToLocal(Direction, Entity)` —
  map grid directions between an entity's gravity frame and the world.
- `placementDown(Entity)` — the grid direction a placed block should treat
  as down for this player.
- `placementHorizontalFacing(Entity)` — gravity-correct replacement for
  `getHorizontalDirection()` in placement logic.

Plus the existing placement-context mixins (`UseOnContextMixin`,
`ItemMixin`, `DirectionMixin`) which already reorient vanilla placement
raycasts through the player's aim frame.

## The sticky-block system (next milestone)

### Chosen architecture: wrapper block entity ("Framed Blocks" model)

A single `sticky_block` block + block entity that:

1. Stores the WRAPPED block state (any full-cube-ish block) plus one of the
   24 grid orientations (a `Rotation24` enum = facing × 4 spins).
2. **Rendering**: a baked-model wrapper that re-bakes the wrapped state's
   quads through the orientation's rotation matrix. Item/BE renderers of the
   wrapped block (chests, shulkers) need a BlockEntityRenderer that pushes
   the orientation pose and delegates to the wrapped BE's renderer.
3. **Interaction**: `use`/`attack`/`getCloneItemStack`/comparator output
   forward to a captive instance of the wrapped block entity, with hit
   locations transformed through the orientation.
4. **Shapes**: collision/outline `VoxelShape`s of the wrapped state rotated
   through the 24-orientation matrix (all axis-aligned, so shapes stay
   exact).
5. **Conversion recipe**: crafting or right-click tool that converts a
   placeable block item into its sticky variant and back, preserving BE NBT.

### Why not blockstate extension

Adding orientation properties to EXISTING blocks (the alternative) requires
runtime blockstate injection for every registered block — it explodes the
blockstate space (24× every state), breaks recipe/tag/state matching in
other mods, and cannot preserve vanilla `canSurvive` logic safely. The
wrapper isolates all of that.

### Framework surface for other mods

- `StickyBlockPolicy` registry: mods opt their blocks in/out and can supply
  custom interaction/rendering adapters where the generic wrapper is not
  enough (e.g. multiblocks, connected textures).
- `Rotation24` utility (facing × spin quaternion/matrix tables, Direction
  mapping, VoxelShape rotation) exposed as public API — useful on its own
  for any mod implementing rotatable machines.
- Placement flow: on place, orientation defaults to
  `GravityBlockHelper.placementDown(player)` + the player's local horizontal
  facing, so blocks naturally match the builder's gravity.

### Known hard problems (why this is a milestone, not a patch)

- BE renderer delegation for modded BEs is fragile (renderers assume their
  own BE type and level context).
- Light emission/occlusion for rotated states is fine, but ambient
  occlusion/culling against neighbors needs the wrapper to report rotated
  face occlusion.
- Redstone: rotated comparator/repeater semantics need the signal graph
  transformed per orientation; first version should exclude redstone
  components.
- Piston movement of wrapper BEs (contraption mods) needs NBT-preserving
  moves.

## Relationship to GravityVS core

The framework is independent of the gravity capability — it only CONSUMES
`GravityBlockHelper`. It should live in its own package
(`net.cama.gravityapivs.sticky`) so it can later be split into a separate
module/jar, per the API-first philosophy.
