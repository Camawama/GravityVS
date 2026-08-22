# Gravity Unbound — How the Gravity System Works

*A general explainer for players and developers. Companion documents:
[CapsuleCollider_Explained.md](CapsuleCollider_Explained.md) (collision
internals), [GravityBlockFramework_Design.md](GravityBlockFramework_Design.md)
(rotatable block architecture), [GravityVS_Vision_and_Roadmap.md](GravityVS_Vision_and_Roadmap.md)
(project goals).*

---

## 1. The core idea

Gravity Unbound gives **every entity its own gravity** — a direction and a
strength — instead of the global world-down. A player can walk on walls,
ceilings, or around a small cube "planet", and the guiding principle is
**seamlessness**: someone standing upside down should not be able to tell.
Water flows their way, doors open, chests work, projectiles arc correctly,
the camera is smooth. The mod is API-first: the included blocks and items
are reference implementations for developers as much as gameplay content.

Two layers make this work for each entity:

- **The physics cardinal** — gravity's direction snapped to one of the six
  block-grid directions (down/up/north/south/east/west). Hitboxes,
  block collision, and most vanilla logic operate against this, because
  Minecraft's world is a grid.
- **The continuous visual frame** — a quaternion that can point anywhere,
  smoothly rotating as fields change. The camera, the rendered body, and
  movement input all follow this frame, so transitions look and feel
  continuous even though the physics snaps between cardinals underneath.

An entity's velocity (`deltaMovement`) is stored in its **local frame**:
"down" in that vector always means "toward my gravity". The movement engine
converts to world space at the moment of collision. (Projectiles are the
deliberate exception — they fly in world space and are pulled along the
field's continuous direction; that is what makes smooth orbits possible.)

When gravity is plain world-down, every one of these systems reduces to
bit-exact vanilla behavior.

## 2. Where gravity comes from

An entity's gravity each tick is resolved from, in increasing precedence:

1. **Base gravity** — normally world-down; can be changed by items/effects.
2. **Per-dimension gravity** — a low-priority ambient direction+strength for
   a whole dimension, set via the `dimensionGravity` config
   (`"minecraft:the_nether=up,0.5"`) or the `DimensionGravity` API. Any
   field overrides it.
3. **Gravity fields** — the three field blocks below. When several overlap,
   effects within a small priority window **blend** (weighted average), so
   walking between two plates' fields curves smoothly instead of snapping.
   Overall source priority: **Normalizer > Plating > Core**, and closer
   sources beat farther ones.
4. **Direct effects** — gravity potions / tipped arrows (timed direction or
   strength changes) and handheld **Gravity Anchor** items (hold to align
   yourself to a fixed direction).

Strength is applied through Forge's entity-gravity attribute, so it
composes correctly with slow falling and other modifiers. Default strength
0.08 blocks/tick² = vanilla.

## 3. The three gravity field blocks

All three are configured through a GUI (right-click with an empty hand —
**creative mode only**, like a command block; survival players cannot
obtain or configure them). Each has: field size, gravity acceleration (with
Overworld / Moon / Zero-G / Jupiter presets), falloff mode, and a field
visualization toggle.

### Gravity Plating
The workhorse. A plate occupies a block face (any of the six sides of its
cell; multiple sides can be configured independently) and projects a
**rectangular field column** outward from that face, `level` blocks deep.
Entities inside are pulled **onto the plate** (attract) or pushed away
(repulse). Per-side settings: level/range, attract/repulse, falloff
(full/gradual), gravity acceleration, **surface snapping** (whether players
planet-walk-align to surfaces in this field), and visualization. "Copy to
Connected Plates" applies the current settings to the whole connected group
of same-facing plates in one click. Plating is waterloggable and stores
full fluid levels, so gravity-driven water flows *through* plated surfaces.
Walking off a plate releases its pull almost immediately when you step onto
plain ground; jumping keeps a short grace so arcs feel natural.

### Gravity Core
A **spherical, radial field**: everything within `range` blocks is pulled
toward (or pushed from) the core's center — the planet block. Players with
surface snapping enabled planet-walk around whatever structure surrounds
the core; grounded mobs get a stabilized face-aligned pull so they can
walk. Falloff: FULL (constant) or GRADUAL (inverse-square, full strength
within 4 blocks — tuned so orbital slingshots are possible; projectiles
orbit visibly). Cores also define the gravity for **fluids**, using fixed
"sector frames" per face so water can wrap a cube planet stably. A per-core
**Affects Ships** toggle marks whether the core should pull Valkyrien Skies
ships (see §6 — the ship-force delivery is currently being reworked).

### Gravity Normalizer
The "safe room" block: a **cubic zone** (half-extent `range`) inside which
gravity is normalized to a single chosen direction — its *local down*,
which is defined in the normalizer's own block grid. Its priority is far
above plating and cores, so the zone is uniform regardless of what fields
pass through it. On a ship, the zone is clamped to the ship's own block
extent (it grows as you build and never leaks off the hull), and its local
down **rotates with the ship**.

## 4. What gravity affects (the seamlessness checklist)

- **Players**: capsule-based collision that genuinely rotates with gravity
  (see the capsule document), planet-walk surface snapping with smooth
  face-to-face transitions, frame-aware camera, sneak edge-guard, correct
  swimming/eye-in-fluid checks in rotated water.
- **Mobs**: same capsule collision, ground-aligned pulls, frame-aware jump
  logic; spawn-egg mobs adopt field gravity the tick they appear.
- **Projectiles** (arrows, tridents, pearls, snowballs, thrown items):
  world-frame flight pulled along the continuous field vector, with
  per-tick position/velocity sync inside fields — smooth visible orbits.
- **Items, XP orbs, TNT, falling blocks**: all field-aware (several vanilla
  classes skip the shared tick path and get their own hooks).
- **Fluids**: water and lava flow along the field's down — falling toward
  plates, wrapping around core planets (with a matching rotated fluid
  renderer, frame-aware flow directions, and swim/fog detection). Field
  water never creates permanent sources and drains when the field is
  removed.
- **Explosions, knockback, fishing lines, elytra, nametags, shadows**:
  computed in the entity's frame.

## 5. The sticky block framework (rotatable blocks)

`Rotation24` defines the 24 grid orientations a block can take
(bottom face × spin). Built on it:

- **Sticky Chest** — a fully working chest in any orientation; pairs into
  double chests; opens/animates correctly. Container titles read exactly
  like vanilla ("Chest"/"Large Chest") per the seamlessness doctrine.
- **Sticky Caster / Sticky Mimic** — a creative tool: sneak-click any
  simple block to capture it, click a surface to place a mimic that
  renders, collides, and sounds like the captured block in any of the 24
  orientations (doors place as proper two-block pairs and open/close with
  their real sounds). Mimics are visual/collision experiments — block
  *logic* (redstone power, rail function, growth) does not run rotated;
  that requires a future virtual-space architecture.
- **Sticky Rails** — rails placeable on walls/ceilings/any face, with
  vanilla connection logic run in the rail's local frame, and minecart
  riding ported to the rail's frame (cart gravity projected onto the
  track). Same-face networks work; corner links between faces are the next
  milestone. A creative debug readout (sneak + empty hand) prints any
  rail's orientation and track direction.

## 6. Valkyrien Skies compatibility

The foundational rule is **same-grid semantics**: a gravity field block on
a ship defines its field in the *ship's own block grid* (the "shipyard"
space). As the ship moves and rotates, the field's world-space direction
is derived through the ship's transform every tick — so a plate on a
ship's wall keeps pulling onto that wall no matter how the ship is
oriented. Field containment tests transform the *entity* into ship space,
which makes them rotation-proof. Ship fields affect entities in the world;
world fields deliberately do **not** reach into ships' grids.

What this enables and how it works:

- **Standing and walking on moving ships**: the capsule collider collides
  against the ship's actual blocks in shipyard space (exact, not
  approximated), reports standing to VS's dragging system (so VS carries
  you, interpolates you client-side, and exempts you from anticheat), and
  a surface probe raycast under your feet — which VS transforms through
  ships natively — anchors your gravity to the face you stand on. Because
  effect delivery to a rider can flicker on a fast-rotating ship (network
  timing), **standing on a held surface sustains its field**: as long as
  the probe says you're on the plate, your gravity stays glued to it and
  rotates with the ship.
- **Placing oriented blocks on ships**: VS rewrites the player's look
  angles into ship-grid space during placement; all sticky-block placement
  goes through helpers that account for this, so blocks orient correctly
  on arbitrarily rotated ships.
- **Normalizers on ships** clamp to the hull and rotate with it (§3).
- **Fluids on ships** simulate in shipyard space (where VS simulates them
  anyway), so ship water obeys ship fields.
- **Ships as objects in fields**: cores expose an **Affects Ships** toggle
  (plus a global config) intended to pull field-less VS ships — with flip
  semantics, i.e. a field *replaces* the ship's own gravity so an upward
  field makes a ship genuinely fall up, and ships carrying their own field
  source are exempt (two field-ships don't yank each other). **Status:
  the force-delivery path into VS's physics is currently being reworked**
  (VS 2.4's attachment API changed) — treat ship attraction as
  work-in-progress.
- **Known rough edge**: riders on *fast-rotating* ships are the most
  demanding case (tick-timing between VS's transform updates, the server's
  view of the player, and the renderer). Standing/walking is stable;
  residual visual stutter and fling edge-cases at high spin rates are an
  active work area — see CHANGELOG rounds 35+ for the full engineering
  history.

## 7. Configuration highlights (`gravityunbound` server config)

- `gravityStrengthMultiplier`, `worldVelocity`, `resetGravityOnRespawn`
- `platingMaxLevel`, `gravityCoreDefaultRange/MaxRange`,
  `normalizerDefaultRange/MaxRange`
- `gravityAffectsFluids` — master switch for fluid gravity
- `gravityCoreAffectsShips`, `gravityCoreShipForceMultiplier`
- `dimensionGravity` — per-dimension ambient gravity entries
- `artificialGravityDimensions` — zero-g dimensions where fields also
  apply accelerating force

## 8. For developers

The mod's API surface (package `net.camacraft.gravityunbound.api`):
`GravityChangerAPI` (query/modify an entity's gravity, aim/movement/render
rotations), `RotationParameters` (how transitions behave),
`GravityBlockHelper` + `Rotation24` (grid-aware oriented placement, ship
safe), `DimensionGravity` (per-dimension gravity at runtime), and the
`GravityFieldLookup.Source` interface for adding new field-emitting blocks
(register per tick; the fluid engine and entity systems pick them up
automatically). The three field blocks are intended as reference
implementations to copy from.
