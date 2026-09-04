# Gravity Unbound Changelog (formerly GravityVS)

## Unreleased (2.0.0-dev) — 2026-09-04 (round 82: the world-cube rejection, particles in the field's frame, scaled limbs, the doll's scale)

- **Plated cubes in the WORLD work again — the server was rejecting the
  client.** Not collision at all: the server replays every client move
  through its OWN gravity frame, which trails the client's by a packet.
  On the tick a face changes, or while a player approaches a plated face
  from outside its field, the server still holds a vanilla upright box,
  and vanilla's `isPlayerCollidingWithAnythingNew` found that box inside
  the very blocks the client's rotated capsule stands on — teleporting
  the player back every tick: stuck at every edge of a world cube, held
  two blocks off its underside (the last accepted position). The same
  cube as a ship never showed it because ship blocks live in the shipyard,
  where the world collision query does not look (Valkyrien Skies only
  skips the "moved wrongly" checks, not this one). The client's report is
  now authoritative whenever the server's own gravity state shows any
  influence OR the reported position lies within reach of any field
  source (world grid, or the grid of a ship whose bounds — inflated by
  the level's largest source reach — hold the point); the "moved wrongly"
  gate and the server-side suffocation test are lifted under the same
  rule. Ship riders were always accepted by the same principle.
- **A float dead zone kept capsule mode latched after leaving a field.**
  The field logs showed the watchdog firing at `w=-0.9999998`: single
  precision resolves |w| in ~6e-8 steps near 1, the 0.9999999 identity
  test admitted only the top two representable values, and a frame that
  settled one step out (0.07 degrees) was neither "default" nor inside
  the twist unwinder's 0.03-degree exact-anchoring window. Both thresholds
  are now ~0.1 and 0.15 degrees, so any frame that close is anchored
  bit-exactly the next tick.
- **Particles live in the field's frame.** Re-aiming the base class's
  gravity store only caught particles that ran the base tick: leaves,
  drips and smoke replace it, mods add their own pull on top (Ad Astra's
  large smoke fell world-down AND field-down at once), and nothing
  touched a smoke column's rise. A particle inside a field now keeps its
  velocity in a frame whose -Y is the field's down; the one place that
  turns velocity into displacement (`Particle.move`) converts through the
  frame, collides in world space and brings the result back for the
  on-ground / blocked-axis bookkeeping. Every particle class's own
  physics — vanilla gravity, a leaf's sway, a column's rise, a mod's extra
  pull — happens along the field's axes without knowing about it. Spawn
  velocities are rotated into the frame as the particle is added and
  re-expressed whenever the frame at its position changes (leaving the
  field, a rotating ship), so the world velocity never jumps. Ship fields
  are found for particles anywhere in the field, not only inside the
  hull's bounds (the ship search is inflated by the level's largest
  source reach — a range-16 plate reaches far outside the hull).
- **Scaled players' limbs swing on every face.** Pehkui scales the limb
  distance with a ModifyArg INSIDE `calculateEntityAnimation`, the method
  this mod replaces to measure the distance in the gravity frame — so the
  scaling never ran on a rotated face. The replacement now applies
  Pehkui's own scaling through a reflective compat layer (nothing loads
  without Pehkui).
- **The paper doll's hitbox and capsule spheres match the doll at any
  scale.** The doll is drawn at its type's natural size (VS Genesis draws
  its mini-scale players full size in GUIs) while the entity's dimensions
  are scaled; GUI-mode hitbox geometry is brought back to the doll's size.

## Unreleased (2.0.0-dev) — 2026-09-04 (round 81: round-80 follow-up — the real stutter, the box-to-capsule edge, cling holds, drops, the doll)

- **The snap stutter's real sources, and the render smoothing is gone.**
  Round 80's render-side easing only slowed the snap; the tick-rate
  stepping it was meant to hide came from elsewhere. (1) The render
  pass re-aligned the drawn up toward the exact radial direction of a
  ship-mounted core, blended in and out by a weight that advanced once
  per TICK (0.15 per step) — while the tick frame was still chasing a
  face or the field, each step moved the camera by up to 0.15 of that
  chase lag, at every tick boundary: the stutter on every landing,
  lift-off and field entry around a ship core (the user's 11x11x11 core
  cube is a ship). That re-alignment is removed; the tick target is
  already re-derived from the live transform and chased at high gain,
  with a raised turn cap while airborne in a ship field, and the
  ship-relative reconstruction carries it onto the drawn pose. (2) The
  ship-attachment engagement weight also stepped per tick; it is now
  interpolated per frame like every other drawn quantity. (3) Creative
  flight let the velocity turn with the frame by the whole chase step
  (up to 15-30 degrees per tick), kinking the flight path by that angle
  at every tick boundary — the choppy lift-off; the drag is capped at 4
  degrees per tick and the remainder preserves world velocity, so orbits
  still close and fast snaps fly straight. (4) The chase's switch from
  its smooth gain to the decisive 0.5 gain after five still ticks was a
  visible speed-up at the end of a chase ("animates, then snaps"); it
  now ramps in over eight ticks.
- **Walking off the top face of a plated cube in the world no longer
  breaks collision.** Round 80 rotated the walking momentum around the
  edge in the identity frame too — correct, but the cardinal only
  snapped at the start of the NEXT tick, so the tick of the face change
  still ran vanilla BOX collision: the 0.6-wide box overlapping the top
  face blocked the velocity now pointing down the side, and the pull
  toward the side dragged the player back over the top, where the
  capsule then engaged lying on its side, pinned to the wrong face —
  stuck on every side face, "floating" off the bottom. A committed face
  change now snaps the cardinal in the same tick, so the tick's move
  already runs through the capsule (as it always did on tilted ships,
  which is why ships never showed it).
- **Wall-Jump VS: the surface stays snapped through a wall-jump
  sequence.** New API `GravityChangerAPI.sustainHeldSurface(Entity)`
  keeps the held planet-walk surface (and its field) alive for another
  grace window; the fork calls it every tick while clinging or airborne
  between wall jumps (the sequence ends on the ground). Before, the hold
  lapsed mid-cling — nothing under the feet — and the frame fell onto the
  raw radial field: the cling's "down" turned away from the surface and
  the snap was gone when the player let go.
- **Drops on ships.** Two fixes. The item is now placed at the very end
  of `drop` (this mixin runs after Pehkui's), at 0.3 body-scaled below
  the eyes along the frame's down, whatever another mod moved it to in
  between — round 80's pre-compensation for Pehkui's world-Y correction
  put the item a body height off whenever that correction did not run.
  And a fresh entity's instant frame snap now re-expresses its velocity
  (projectiles excepted): the spawn velocity had been written through
  the identity frame and read through the snapped one, which bent a
  drop's throw by the whole frame rotation — sideways or behind the
  thrower on a ship's wall face.
- **The inventory paper doll shows its capsule spheres again**, stacked
  along the doll's own up.

## Unreleased (2.0.0-dev) — 2026-09-04 (round 80: twelve-finding pass — snaps, seats, edges, scale, particles, wall jump)

- **Surface snaps no longer look like a dropped frame rate — two causes.**
  (1) The level renderer only re-tests chunk visibility against the
  frustum when the camera's own pitch/yaw change, which a rotating
  gravity frame never touches; the old workaround requested a FULL
  render-chunk graph rebuild (`needsUpdate`) on every frame of every
  rotation — the asynchronous breadth-first walk of every loaded chunk,
  re-queued frame after frame — and that was the frame-rate collapse
  during every snap and on every spinning ship. It now raises only the
  frustum re-test flag (a new LevelRenderer accessor), the same thing
  vanilla does when the view turns. (2) The committed face adoption
  turns the tick frame 15-30 degrees per tick and is over in 3-6 ticks,
  which reads as a jerky whip. The drawn frame (camera, model, eye,
  crosshair) now follows the tick frame through a render-only smoothing:
  exact for anything slower than 6 degrees per tick (orbits, field
  drift) and for ship carries (composed verbatim), eased (30% of the lag
  per tick, ~0.4 s) through the fast snaps. Physics, collision and
  movement keep the tick frame. The lag composes as a world-space offset,
  so the twist unwinder's yaw compensation stays invisible through it;
  every hard-snap site (init, spectator, sync, fresh spawn, watchdog,
  rail override) lands the drawn frame with the tick frame.
- **Seats on ships under a field: down is the seat's down.** A rider
  inherited its VS mounting entity's gravity — the raw radial pull under a
  core (server) or the seat's nearest cardinal (client, where a seat's
  field target is never synced) — so looking straight down from a chair
  did not look at the chair. While mounted to a ship whose field covers
  the seat cell (decided from the block grid on both sides), the rider's
  gravity is the ship's own down, ship-anchored so the view turns with
  the ship exactly like a standing rider's. Passengers skip the surface
  probe, the pull deficit and the transition pull; seats on ships without
  a field keep vanilla behavior. Plain vehicles (horses, boats) now also
  hand their field state to the rider instead of leaving a stale one.
- **No more standing-still oscillation at a cube edge.** The single
  ground ray at the feet misses when the feet sit a hair past a face's
  edge while the collider still rests the capsule on that face; the hold
  lapsed, the diagonal field re-acquired the next face, its contact
  disagreed, the first face was re-adopted — forever. Physical contact
  with the held face now confirms the hold (after the convex wrap probe,
  so walking off an edge still wraps).
- **Walking off the UP face of a plated cube keeps its momentum.** The
  momentum rotation across a face change bailed out in the identity
  frame (vanilla box mode), which is exactly the frame the top face
  stands in: the walking velocity stayed pointing out over the edge —
  "up" in the new frame — so the player lifted off the corner and fell
  back onto it instead of carrying on down the side. The rotation runs
  in every frame now (it is exact for the identity frame).
- **Stair risers and walls are no longer adopted as floors under gravity
  cores.** A radial pull has a tangential component everywhere on a
  planet face (45 degrees at the rim), so the relative half-gates of the
  concave-wall and convex-wrap probes read a riser — or a wall built on
  the face — as endorsed once the player stood far enough from the face
  center. Core effects now carry a RADIAL flag (world cores included);
  under a radial field a candidate face must be endorsed at least as well
  as the face being stood on (90% for the wrap), which on a convex planet
  only happens past its edge — risers and walls stay walls.
- **Dropped items fall at once on a level ship in zero-g, and on ships'
  cores at all.** The zero-g pull for non-living entities ran only on the
  controlling side; a client-predicted item floated weightless until the
  server's next position packet (twenty ticks at a vanilla frame, one
  tick under a rotated one — which is why oblique ships looked
  immediate). The pull (now a shared capability helper, projectiles
  handled world-frame) runs for every non-living entity on the client
  too, and gravity cores apply it alongside plating.
- **Particles follow ship fields.** VS moves ship-spawned particles into
  world coordinates while a ship's sources register in its own grid, so
  the world-position lookup never found them. The particle query now
  asks every ship whose bounds hold the point in that ship's grid and
  rotates the grid cardinal back to world (cached per world cell per
  tick).
- **F3+B frames the inventory paper doll.** The hitbox transform and the
  capsule debug spheres stood down in GUI rendering like the model pose
  already did; the doll now shows its plain body box and local look line.
- **Scaled players (Pehkui, VS Genesis) — four fixes.** The step assist's
  acceptance threshold (a flat 0.01 block — more than a 1/16 player's
  whole step) is body-scaled: tiny players climb slabs and stairs again.
  The walk animation hands its frame-horizontal distance to vanilla's
  own `updateWalkAnimation` — where Pehkui scales the limb distance —
  instead of feeding the animation state directly, so scaled players'
  limbs swing under fields. Drops spawn a body-scaled distance below the
  eyes, pre-compensated for Pehkui's own world-Y drop correction. The
  convex-wrap probe is body-scaled like every other probe (unscaled it
  started two ship-blocks below a tiny player's feet).
- **A full-size player collides with a scaled-down ship's faces.** The
  obstacle gatherer clamped an oversized shipyard reach box to 16 cells
  around its center, leaving the head and feet spheres (29 shipyard units
  apart on a 1/16 ship) touching nothing — stuck on every face but the
  deck (whose identity frame runs VS's own collision). Large reaches now
  gather the cells around each sphere at the start, end and step-lift
  positions, skipping buried cells.
- **Ladders in zero-g.** The pull deficit excluded climbables; vanilla
  applies gravity on ladders too (the climbable clamp only caps descent),
  so in the Great Unknown a player on a ladder hung weightless and could
  never climb down. Included now.
- **Wall-Jump VS fork: gravity-aware wall jumping.** Under a rotated
  frame the world-axis wall probes found the very floor the player stood
  beside — the cling-to-air. A reflective Gravity Unbound compat layer
  (no link dependency) supplies the frame; wall probes are short rays in
  the frame's tangent plane (VS raycasts ships natively, so ship walls
  register for the moving-ship anchor), the ground test looks down the
  frame, the cling pins the tangential position to a 3D anchor, ship
  velocities are brought into the frame, particles and jump heights use
  it. Vanilla gravity runs the original code paths untouched. Forge and
  Fabric.



- **Gravity fields now work in Ad Astra dimensions.** Verified on
  ad_astra-forge-1.20.1-1.15.20: Ad Astra hooks `LivingEntity.travel` at
  its head and, at zero gravity (every orbit dimension), REPLACES vanilla
  travel with its own floating physics; at partial gravity (Moon, Mars) it
  pre-adds a velocity correction so vanilla's 0.08 nets out to the planet's
  pull. Gravity Unbound's field pull rides Forge's entity-gravity attribute
  inside vanilla travel, which never ran in orbit — and the zero-g deficit
  pull only engages for no-gravity entities, which is how VS Genesis does
  space and which Ad Astra never sets. So the camera turned and nothing
  pulled.
- **The layer.** Ad Astra fires events at every decision point, and its
  API reports gravity as a fraction of Earth. A soft compatibility layer
  (reflective; no build dependency; nothing loads without Ad Astra)
  registers three listeners: while an entity is under a Gravity Unbound
  field (or a non-default base gravity) it answers Ad Astra's
  entity-gravity event with Earth gravity — items, arrows, boats, fishing
  hooks and mobs then get no planet correction — and vetoes both travel
  tick events, so vanilla travel runs and the field's own attribute pull
  acts along the gravity frame on both client and server (client-side
  players read planet gravity from their synced data, so the travel veto
  is the only hook that reaches them). Outside fields, Ad Astra's planets
  and its own Gravity Normalizer behave exactly as before; inside one of
  its normalizer zones a field simply wins, as it does over any ambient
  gravity. The plating's extra zero-g force for non-living entities stands
  down in Ad Astra dimensions, where the restored planet gravity already
  supplies it (VS Genesis's no-gravity space still gets it).
- Not the right tool: the per-dimension gravity override in the API sets a
  DIRECTION for a dimension's ambient gravity; the conflict here was about
  strength and about whose travel code runs.



## Unreleased (2.0.0-dev) — 2026-09-02 (round 78: cling lets go, the crosshair, the crawl flicker, the body, particles)

- **Surface Cling no longer pulls in zero-g and lets go on a jump.** The
  boots used to seed a pull toward "whatever down currently is" before any
  surface was held, and an active field owns gravity — so in the Great
  Unknown the wearer simply fell. Now the boots only act when a surface is
  actually in reach: the face underfoot (probed), a face the wearer pushes
  into, or the face around a walked-off edge. Free of every surface they
  do nothing: ambient gravity applies and a zero-g dimension stays zero-g.
  Jumping off a held face releases on the spot — the field's grace
  pull-back is dropped (unless an engineered field owns the entity), the
  boots stay off for half a second so nothing re-clings mid-leap, and the
  next touch clings again. The client reports active/inactive/released
  state to the server, which mirrors it.
- **The block outline no longer hops between blocks on a spinning ship.**
  The crosshair pick runs every frame with a partial tick: its eye
  position followed the RENDER frame, and Valkyrien Skies raycasts ship
  blocks at the ship's drawn pose — but the view DIRECTION still came from
  the tick frame, one tick behind. The direction is now re-expressed
  through the render frame for sub-tick queries; whole-tick gameplay
  raycasts (shared with the server) keep the tick frame.
- **No more split-second crawl pose on rotating ships.** The capsule's
  pose-fit test counted any overlap over a millimeter as "does not fit",
  while the collider itself lets a resting capsule sit up to 0.02 deep
  before pushing it out (residuals of the idle anchor, the frame carry and
  the network-lumpy ship carry) — vanilla then dropped the player into the
  swimming pose for a tick. The fit test now uses the collider's own
  resting tolerance.
- **The body no longer spins under a still head in third person.** VS's
  dragger writes riders' body yaw as a direct field store, which the yaw
  setter wraps never saw; an attached rider's torso received the ship's
  world yaw on top of the frame's own twist compensation every tick. The
  body-yaw stores (living entities and server players) are now projected
  like the yaw setters.
- **Particles fall with gravity fields.** Vanilla accelerates every
  particle straight down; inside a field the acceleration is re-aimed
  along the field's down at the particle's position — in the particle's
  own grid, so particles on a ship follow the ship's fields. Particle
  types that replace the base tick with their own physics (drips, water
  splashes, smoke) keep vanilla behavior. New config
  `gravityAffectsParticles` (default on).



## Unreleased (2.0.0-dev) — 2026-09-02 (round 77: hands, bodies, doorways and Surface Cling)

- **The first-person hand no longer sways or snaps on a rotating ship.**
  Vanilla sways the hand by the gap between the view yaw and a smoothed
  copy of it that only the local player keeps. The gravity frame
  re-parametrizes yaw whenever it unwinds a twist — every tick on a
  spinning ship, about ninety degrees at each cardinal flip on its walls —
  with exact camera compensation, but the smoothed copy still registered
  a "turn": a per-tick hand sawtooth while riding, and a big swing four
  times per revolution. The compensation now shifts the smoothed copy by
  the same amount; the sway again only reacts to the player actually
  turning.
- **Bodies stand still on the deck in third person.** Valkyrien Skies
  places riders on the ship's DRAWN pose each frame by rewriting the
  interpolation base the camera uses — but the level renderer draws
  entity MODELS from a second base VS never touches, so a rider's body was
  drawn along the chord between its tick positions while the camera and
  the deck rode the render transform: in F5 the body slid and bobbed
  against the deck it stood still on. Entity render positions now include
  the same per-frame ride delta (exactly zero for anything VS is not
  riding), so models, shadows and nametags sit where the camera says.
- **Gravity plating connects across doorways.** A door, trapdoor or fence
  gate can never share a cell with plating, so a plated floor or ceiling
  always had a one-cell hole at each doorway where gravity lapsed (the
  hidden bleed covered it only as a blend-support, which is dropped when
  standing still). When the cell beside a plate holds such a block and the
  cell beyond holds a plate with the same facing and polarity, both plates
  extend their primary field across the gap — the doorway is fully inside
  the field — and each draws a connecting half-panel into it with the
  plate texture, so the plating reads as one continuous surface through
  the door. The field visualization merges the doorway into the group.
- **New: Surface Cling, an API-showcase enchantment for boots.** Wearers
  cling to whatever surface they stand on and walk up walls, across
  ceilings and around any structure — Valkyrien Skies ships included,
  moving or spinning, because the effect is anchored to the ship exactly
  like a ship-mounted plate. It is built entirely on the public API: one
  `applyGravityDirectionEffect` call per tick (priority 500, below
  engineered fields, above ambient and potion gravity), the public
  held-surface accessors, and two short probes that endorse the face the
  wearer pushes into or steps around, which is exactly how a plate field
  lets players walk around a cube's edges. Endorsement needs the movement
  input only the client sees, so the client reports its cling target to
  the server (a small packet, sent only when the direction changes) and
  the server mirrors it; mobs wearing the boots cling to what they stand
  on and follow their own velocity. Enchanting-table and trade obtainable
  (rare, boots only); an enchanted book sits in the creative tab.



## Unreleased (2.0.0-dev) — 2026-09-02 (round 76: ship attachment — one mechanism for riding a rotating ship)

- **The axis-dependent stutter on rotating ships had a structural cause:
  the ship's rotation reached the rider through two different paths.** The
  SWING part (the stood-on face's normal turning) was carried into the
  visual frame and rebuilt against the ship's drawn pose every frame; the
  TWIST part (rotation about the rider's own up) was written into yaw at
  tick rate, with a camera-side correction on top. Faces whose normal is
  parallel to the spin axis — the deck under a yaw spin, the north/south
  walls under roll, the east/west walls under pitch — are pure twist for
  the rider, so they only ever got the tick-rate path, standing or moving;
  faces perpendicular to the axis got the exact path, which is why walls
  under a yaw spin walked smoothly. Flying in an up/down field under yaw
  spin stuttered for the same reason.
- **Verified against the Valkyrien Skies 2.4.11 bytecode**: on the client,
  ship tick transforms advance and the entity dragger carries riders at the
  TAIL of `Minecraft.tick`, after every entity tick, and VS's per-frame
  render-ride rewrites the interpolation base so the camera lands on the
  DRAWN ship pose. So every ship transform read during the player tick is
  the pose the rider was placed on at the end of the previous tick.
  Round 75's "pin the idle anchor to the prev-tick transform" was built on
  the opposite ordering and left the standing player permanently one tick
  of ship motion behind the deck (the "standing still stutters, walking
  doesn't" report on wall faces). The anchor pins to the current transform
  again, which is a no-op in steady state.
- **Fix — SHIP ATTACHMENT, one mechanism.** While the entity rides a ship
  (the dominant field is mounted on it, or one of its faces is the held
  surface): the capability composes the ship's FULL tick rotation — swing
  AND twist — onto the visual frame and the held normals each tick (the
  twist unwinder then moves twist into yaw only when the frame is settled,
  invisibly, with both interpolation endpoints shifted); at render time
  the two tick frames are expressed in the SHIP's own coordinates,
  interpolated there, and re-expressed against the pose the ship is drawn
  at. A rider's ship-relative orientation is constant while standing, so
  the render frame follows the drawn deck exactly, whatever the spin axis
  or face, and however lumpy the network-fed tick poses are. The old
  spin-follow yaw writes and the camera's sub-tick twist are gone; VS's own
  dragger yaw stays suppressed while attached. The engagement eases over
  ~7 ticks so entering and leaving a ship's field never snaps. The physics
  gate now judges the current frame only, so a spinning ship's level deck
  keeps bit-vanilla physics (VS's own riding stack) while the render frame
  carries the deck's sub-tick lead; render code has its own gate.
- **Flying up inside a ship's field no longer leaves you behind.** VS stops
  dragging an entity 25 ticks after it last stood on the ship and nothing
  re-registered an airborne rider. While attached and airborne the entity
  is re-registered with VS's dragging every tick (its own ground-stand
  hand-off paths are untouched), so creative flight inside a spinning
  ship's plating or normalizer field rides the ship exactly.
- **Walking off plating releases again.** Round 47's held-surface field
  sustain is a self-sustaining loop (probe -> sustain -> gravity active ->
  probe) and on a continuous floor the probe never misses, so stepping from
  the plated part of a deck onto its unplated part stayed snapped forever.
  Every field source now reports its REGION in its own block grid (plating:
  the primary column the visual shows; normalizer: its zone; core: its
  bounding cube), and the sustain only bridges dropouts while the entity is
  still inside it — one grid block of tolerance on ships for the
  server-side packet lag the sustain exists for, none in the world. Past
  that the grounded release (2 ticks) runs as before.



## Unreleased (2.0.0-dev) — 2026-08-22 (round 75: one pull per field, one carry per tick)

- **The Great Unknown "plating slams me down at scale 1" was a
  TRIPLE-application of the pull.** Since rounds 68/70 the capability's
  applyFieldPullDeficit supplies a living entity's FULL intended pull
  (BASE x strength) every field tick — but the plating's older
  artificial-gravity force (zero-g dimensions) was still applied on
  top, and it fired once for EVERY plate whose field contains the
  entity (the 1-block bleed means a plated floor is several plates),
  stacking to many times vanilla gravity. Scaled ships accidentally
  hid it: transformDirection through the scaled ship matrix shrank the
  redundant force by the ship scale (16x at 1/16), which is exactly
  why 0.0625 felt normal while 1.0 slammed. The force now skips living
  entities entirely (the deficit owns them, at every scale), and for
  the non-living entities that still need it (items, carts — the
  deficit's known gap) it is normalized after the ship transform
  (scale-invariant) and applied at most once per entity per tick via a
  capability claim, no matter how many plates overlap.
- **The rotating-ship wall stutter: the ship idle anchor and VS's
  dragger both carried the player.** VS's client EntityDragger runs
  AFTER the entity tick and carries dragged entities by the ship's
  full tick delta; the anchor pinning to the CURRENT tick pose during
  the player tick meant the carry applied twice — the tick-end
  position permanently led the anchor by one tick of ship motion,
  addedMovementLastTick stopped matching the real displacement, and
  VS's per-frame render-ride (which rewrites xo/yo/zo so the camera
  lands on the drawn ship pose) was fed inconsistent inputs: a
  tick-rate positional sawtooth. Up/down faces never showed it (the
  deck's identity frame skips the anchor; on the ceiling the residual
  is a twist about the VERTICAL eye arm — invisible), but on N/E/S/W
  faces the horizontal 1.62-block eye arm and the hull radius
  amplified it into the "major stuttering the moment I step onto a
  wall face" report. While VS is actively dragging, the anchor now
  pins to the PREV-tick pose so the dragger's own carry lands the
  player exactly on the anchor — in steady state the anchor's setPos
  is a no-op, VS owns the whole carry, and its render-ride math is
  exact. Undragged entities keep the current-transform behavior.



## Unreleased (2.0.0-dev) — 2026-08-22 (round 74: switch-easing, not caps — the smoothness architecture settles)

- **Jumping STILL unsnapped after round 73 because the render branch
  gated on the wrong ship reference**: capsuleGroundShip clears on the
  first airborne frame, so the drawn-ship alignment flipped
  surface->radial the instant a hop left the deck, bypassing the
  held-normal grace entirely. The held surface's shipyard image (and
  its ship) now survives through the grace via lastGroundShip — a hop
  keeps the surface branch for its whole arc.
- **The round-72 blanket 10-degree/frame arc cap is removed — it WAS
  the fly-mode stutter.** Capping every correction throttled the
  radial branch's legitimate full-strength per-frame tracking while
  flying fast or close around a core (exactly the smoothness round 52
  built). Replaced by BRANCH-SWITCH EASING: the capability tracks
  which alignment branch the render pass takes; on any switch
  (landing: radial->surface; hold expiry: surface->radial) the
  correction strength ramps 0->1 over ~7 ticks, letting the smooth
  tick chase show through the transition — while steady-state tracking
  (standing on a spinning deck, orbiting a core, flying through the
  field) runs at full strength with NO cap. Ease the switches, never
  the tracking: the rule this whole smoothness saga converged on.



## Unreleased (2.0.0-dev) — 2026-08-22 (round 73: the sawtooth found — and jumps keep their snap)

- **The spinning-ship jitter had an exact cause: the sub-tick camera
  twist DOUBLE-COUNTED.** Vanilla's yRotO lerp already interpolates the
  spin-follow's tick-rate yaw across each tick; adding the full
  drawn-vs-tick delta on top showed the intra-tick rotation twice — a
  sawtooth with exactly one tick of rotation amplitude, resetting at
  every tick boundary. The camera now subtracts the partialTick
  fraction of the tick's applied twist and contributes only the
  REMAINDER (the render transform's true lead): constant during steady
  spin, zero on stationary ships. This is also, in hindsight, what the
  round-69 "low refresh rate" complaint likely was en route to.
- **Jumping no longer unsnaps.** A 10-tick hold grace already existed —
  but the cliff early-release (moving against the held normal at
  0.08/tick) fired on every ordinary jump's DESCENT, and a full jump
  (~13 ticks) outlived the budget anyway. The early release now probes
  2.5 blocks (body-scaled) along the held down first: the plate still
  underneath means it is a JUMP — keep the hold; nothing underneath
  means a real cliff — release fast so the field can catch the fall.
  Grace raised to 16 ticks so the whole arc stays held: hopping around
  on plating never unsnaps, exactly the requested cooldown behavior.



## Unreleased (2.0.0-dev) — 2026-08-22 (round 72: smooth landings and exact yaw-follow)

- **Landing snap eased.** Round 71's branch fix meant landing swaps the
  render alignment's target from radial to the held surface — and the
  correction arc applied the whole radial-vs-face angle in one frame
  (the "abrupt snap"). Large arcs are now rate-capped at 10 degrees
  per frame: the landing transient sweeps at the tick chase's own
  smooth convergence rate, while steady-state residuals (always far
  below the cap) are untouched.
- **Yaw follows the ship exactly — ownership + sub-tick camera twist,
  landed in isolation from the reverted arc.** The /vs torque test
  (mostly ROLL) proved the gap again: as roll tilts the rider's
  frame-up, the twist about their up flows through axes VS's
  world-yaw-only dragger cannot express, so the camera slid in yaw
  against the deck. While a ship-anchored field holds the entity, the
  capability now computes the EXACT twist about the frame's up from
  the full tick rotation delta and applies it to yaw/head/body every
  tick (VS's dragger yaw suppressed at the source — nothing
  double-turns); the tick-vs-drawn-pose gap that made this stutter in
  the round-69 attempt is closed by a SUB-TICK CAMERA TWIST — the view
  rotates by the twist of renderTransform o tickTransform^-1 each
  frame. Purely visual, identity on stationary ships. These two pieces
  were designed together in the reverted overhaul but never
  field-tested in isolation until now.



## Unreleased (2.0.0-dev) — 2026-08-22 (round 71: the split-frame snap — held surface wins the render pass)

- **"Only the AABB snaps to the surface; the capsule spheres and
  camera don't" — a render-pass branch-order bug from round 52,
  exposed by F3+B on ship cores.** The drawn-ship alignment preferred
  the RADIAL field anchor over the held surface unconditionally: the
  tick-side frame and the physics box correctly snapped to the
  stood-on face (which is also why jumps followed the box's up), but
  every frame the render pass dragged the camera and the capsule debug
  spheres back toward the core's raw radial direction — sharply
  divergent from the face normal on small ships, visible on all of
  them. The held surface (grounded, shipyard-space normal) now wins
  the render alignment; the radial anchor applies exactly when no
  surface is held (circling, falling, flying in the field), which is
  the case round 52 built it for.



## Unreleased (2.0.0-dev) — 2026-08-22 (round 70: fields own no-gravity entities — heartbeat-diagnosed)

- **The core-field "no pull in the Great Unknown" is solved, and the
  heartbeat named it in one capture.** The log showed the core's
  effect arriving every tick (fx/t=1), the radial direction correct,
  the frame perfectly aligned (gapDeg=0.0) — and pull[C]: excluded.
  VS Genesis implements the Great Unknown's zero-g by setting
  NO-GRAVITY on entities: vanilla travel then skips gravity entirely,
  and the deficit's isNoGravity exclusion was the only thing standing
  between the player and the field. Plating "worked" only because
  Genesis clears the flag on/near ship decks; the "broken" core
  surface snapping was pure downstream — never pulled, never landed,
  nothing to snap to.
- **Fix: no-gravity entities are the POINT, not an exclusion.** For
  them vanilla contributes exactly zero (the attribute never matters),
  so the deficit computes actual = 0 and supplies the field's whole
  pull. An active field owns gravity. The remaining exclusions
  (flying, elytra, swimmers, slow-falling) stand.



## Unreleased (2.0.0-dev) — 2026-08-22 (round 69: body-scaled probes and thresholds; pull heartbeat)

- **Surface snapping works on scaled ships now — the probe geometry
  scales with the body.** Every surface-probe offset was authored for
  a 1.8-block player in world units: the 0.2-block back-off started
  the ray body-heights behind a 1/16 player's feet (inside or beyond a
  matching-scale ship's hull) and the concave wall probe reached many
  ship-blocks ahead, adopting far walls — snapping simply never
  engaged correctly. All three probes (field, wall-adoption,
  frame-support) now scale their start offsets and depths by
  bbHeight/1.8.
- **Body-scaled movement thresholds.** The capsule's step assist
  (0.6-block lift = many body heights for a tiny player — a teleport,
  not a step) and its upward-motion gate, and the ship idle anchor's
  jump-release/push thresholds (a tiny player's jump velocity sat
  below the full-size 0.05 release gate, pinning them to the deck),
  all scale with bbHeight/1.8 now. Full-size players are bit-identical.
- **Pull heartbeat diagnostics.** The 40-tick debug block now logs a
  pull[] line: the deficit state (applied/covered/nofield/excluded/
  flying), the attribute value, field strength, grounded flags, and
  local deltaMovement — so "no downward velocity in the field" names
  its own cause from the log instead of another remote guess.



## Unreleased (2.0.0-dev) — 2026-08-22 (round 68: fields overpower zero-gravity dimensions)

- **Field pull is authoritative now.** Field strength reached vanilla
  travel as a MULTIPLIER on Forge's entity-gravity attribute — correct
  in normal dimensions, but VS Genesis's zero-g Great Unknown
  suppresses that attribute, and any multiple of (near-)zero stays
  (near-)zero: cores/plating barely pulled (plate accel maxed at 1.0
  gave only a sliver) and jumps sailed clean out of the field with
  nothing decelerating them. New applyFieldPullDeficit: each tick a
  field is active, the DEFICIT between the field's intended
  acceleration (BASE x strength) and what the attribute actually
  delivers is applied directly to local deltaMovement along the
  frame's down — the same axis travel uses, composing to exactly the
  intended pull. Inert in normal dimensions (deficit ~0). Deliberately
  skipped for flying/fall-flying players, no-gravity entities,
  swimmers, and slow-falling (an intentionally reduced attribute is
  not suppression). Known limit: non-living entities (items, carts) in
  zero-g fields still follow their own hardcoded gravity paths and may
  stay floaty there — separate follow-up if wanted.



## Unreleased (2.0.0-dev) — 2026-08-22 (round 67: field range respects ship scale)

- **A gravity core's field now scales with its ship.** The range is
  authored in the core's OWN block grid, but the core computed
  everything in raw world units — on a scaled-down ship the field
  reached its full unscaled radius, dwarfing the ship. The world reach
  is now range x the ship's measured transform scale, and the
  grid-authored quantities — the gradual falloff ("full strength
  within 4 blocks" of the CORE's blocks), the priority distance, the
  inner core-block exclusion, and the ship-attraction thresholds — run
  in grid units (world distance / scale), so a scaled field keeps its
  authored shape at its own size. The field-ring visual already
  followed the render transform and now receives the grid-space range
  so physics and visual agree. Plating and normalizer zones were
  already scale-correct (their membership tests run through the full
  world->ship matrices), and fluid queries are same-grid and untouched.



## Unreleased (2.0.0-dev) — 2026-08-22 (round 66: scaled-ship collision, re-applied)

- Rounds 66-73 of the previous session were fully reverted after a bad
  interaction (the ship-frame follow / fly-smoothing / steering-engine
  arc — to be revisited deliberately). This round RE-APPLIES only the
  verified scaled-ship collision work that was lost with them:
- **Scaled ships are solid (VS Genesis / Pehkui-scaled ships).** Three
  stacked fixes in CapsuleCollider: (1) the obstacle gatherer CLAMPS an
  oversized shipyard-space search box around its center instead of
  bailing to an empty list — on a 0.0625-scale ship the fixed
  world-unit reach margins span ~30 shipyard blocks per axis, past the
  old cell budget, which made the whole ship intangible; (2) obstacles
  carry their ship's measured world-to-ship scale (from the transform
  matrix): sphere radii scale into shipyard units for the tests and
  penetration depths divide back to world units for the push; (3) the
  sweep substep cap is 256 instead of 16 — a tiny player's modest fall
  needed dozens of substeps against plating panels ~0.004 world-blocks
  thin (crouching survived only because sneak speed fit under the old
  cap).
- **Pehkui-scaled players get a matching capsule**: capsuleRadius's
  flat -0.02 shrink with a 0.1 floor is now a proportional 14/15
  shrink (identical at vanilla width) with a 0.01 floor — at scale 0.2
  the old floor alone was larger than the entire vanilla box.



## Unreleased (2.0.0-dev) — 2026-08-22 (round 65: the upstream dossier)

- **New docs/UpstreamIssueReports.md** — bugs whose root cause lives in
  OTHER mods, with the evidence gathered here and ready-to-paste issue
  drafts. First entry: the Valkyrien Skies entity-section corruption
  (the round-62 crash and the 02:46 disconnect share one fingerprint:
  a player section transfer executing off the server thread tears the
  section's ClassInstanceMultiMap; VS's afterHandleMovePlayer setPos is
  in every failing stack, reproduces without lag while riding a moving
  ship, and all Gravity Unbound move-path hooks were verifiably inert
  at the failure moments). Status tracked in the file; VS 2.4.11 is
  current, so it needs a fresh upstream report.



## Unreleased (2.0.0-dev) — 2026-08-22 (round 64: the lag hunt, part 2 — profiled)

- **The real profiler capture landed the attribution.** Over a 487 s
  sample (healthy session, ~120 s of actual tick work): ~40 s — a third
  of all tick time — sat inside GravityFieldLookup.bestFieldAt via the
  fluid hooks' fluidDownAt, and the self-time split showed WHY:
  GravityPlatingBlockEntity.sourceMaxRange 19.6 s and
  ConcurrentHashMap traversal 15.8 s — every field query iterated
  EVERY registered source in the level, and a world full of
  accumulated test plates made each of the millions of fluid-tick
  queries pay for all of them. During the earlier mass-update session
  this concentrated into the multi-second monster ticks.
- **Fix — chunk-bucket spatial index.** The per-level source registry
  now buckets sources by chunk (bucket key ignores Y); a query scans
  only the buckets within the level's largest source range instead of
  the whole registry. The round-63 per-tick memo stays on top. The
  monotonic max-range ring means a removed large source leaves only
  null bucket lookups behind. Tie-breaking is unchanged (priority,
  then distance, then the DOWN > X > Z > UP direction rank), so field
  resolution is identical — just no longer O(every source in the
  world) per query.
- **Verified by a follow-up capture under the same workload**: the
  registry scan dropped ~19x (37.4 s -> 2.0 s), sourceMaxRange ~49x
  (19.6 s -> 0.4 s), total field-query cost ~4.6x per wall-clock
  second, and the whole-registry traversal vanished from the profile.
  Field queries now sit at ~11% of a much lighter tick load.



## Unreleased (2.0.0-dev) — 2026-08-21 (round 63: the lag hunt, part 1)

- **The shared spark link was a HEALTH report, not a profiler capture**
  (no stack samples). Its tick stats still narrow things: median tick
  0.34 ms, mean 12-16 ms, worst tick 9.6 SECONDS, one window at ~7.7
  TPS — giant one-off cascades and between-tick starvation, not
  uniformly slow ticks. A sampler run is needed for final attribution.
- **Ruled out: fluid-rule tick storms.** fluidsim gained an ASYNC
  scheduling mode (per-cell 5-tick delays, random ordering — the
  MC-like conditions where a limit cycle could hide from the
  synchronous rounds): a 720-source planet with punched pockets
  settles in 737 cell-ticks, perturbation cycles in ~24, the
  diagonal-source combine in 30. The round 57/58 rules do not
  self-sustain.
- **Optimization — per-tick field-query memo.** Every fluid tick makes
  ~20-40 fluidDownAt queries and each one iterated ALL registered
  sources with distance math; neighboring cells re-ask the same
  positions within the same tick. GravityFieldLookup now memoizes the
  resolved (down, source) per position per thread per game tick —
  about an order of magnitude off our share of a mass water re-settle.
  Staleness is bounded to one tick (registrations mutate in the BE
  phase, and the 40-tick expiry semantics already tolerate that).



## Unreleased (2.0.0-dev) — 2026-08-21 (round 62: the torn-section crash)

- **Crash triage (NoSuchElementException in the plating tick): not our
  bug, but now our problem to survive.** Full chain from the logs of
  the 23:02 crash: mass water updates put the server 155 ticks behind;
  during the spike, a move-packet position rewrite (a TAIL mixin into
  handleMovePlayer doing a raw setPos — Valkyrien Skies' handler; both
  of this mod's packet paths verify clean: enqueueWork in the network
  handlers, no position writes in the packet-listener mixins) executed
  a player ENTITY-SECTION MOVE on a Netty IO thread, racing the server
  thread and corrupting the section's backing ArrayList. From then on
  every touch of that section threw (the AIOOBE "Index -1" storm in
  move handling), and 28 seconds later the first full iteration — the
  plating BE's entity-cache refresh — took the crash and the
  "Suspected Mod: Gravity Unbound" blame.
- **Hardening — the field BEs' entity queries survive torn sections**:
  the plating/core/normalizer cache refresh now goes through
  GCUtil.safeFieldEntityQuery, which catches a torn-section iteration
  failure, logs a rate-limited warning naming the real mechanism, and
  serves the previous tick's cache instead of crashing the server (the
  refresh simply retries next expiry). One bad tick of stale entity
  lists is invisible; a hard server crash is not.



## Unreleased (2.0.0-dev) — 2026-08-21 (round 61: rails fold around corners)

- **Vanilla-rail isolation (the misalignment root).** A rotated sticky
  rail is a BaseRailBlock in the rails tag (so minecart placement
  works), which let ALL of vanilla's world-frame rail logic treat a
  WALL rail as an ordinary flat rail: vanilla RailState curved real
  rails toward wall-rail cells and wrote world-frame shapes into
  local-frame SHAPE properties (the kinked ground rails and ceiling
  S-bends in the screenshots), and a corrupted shape fed the cart ride
  garbage track chords — the cart clamped diagonally, misaligned and
  sunk into the ground. New BaseRailBlockMixin: {@code isRail} returns
  FALSE for non-DOWN sticky rails, so vanilla rail logic and vanilla
  cart physics can no longer see them at all. Cart placement still
  works (the item checks the TAG, not isRail). NOTE: rails corrupted
  before this fix keep their stored shape until re-settled — break and
  re-place one rail in an affected run.
- **CROSS-FRAME JUNCTIONS — rails now connect between axes.** The
  deferred feature, implemented via three junction patterns in
  StickyRailState (each identified by the partner's position AND
  bottom, mutual by construction):
  - CONCAVE (floor runs into a wall, wall meets a ceiling): the side
    whose frame-up holds the partner becomes an ASCENDING ramp toward
    it — the slope the user asked for, and a physical climb path: a
    cart riding the ramp exits into the partner's cell where the
    existing attach rules take over;
  - WALL-BASE (the same corner from the wall side) and CONVEX (over an
    edge): flat on both sides, the two rail planes folding around the
    corner line.
  Partners count for shape resolution (axis, curves, ascent) but are
  never written across frames — each side independently discovers the
  other, so settling converges and cannot oscillate (partner probes
  read only position+bottom, never shape). Placement order does not
  matter: settling wakes cross-frame partners (convex partners are
  diagonal, out of vanilla's neighbor-update reach).
- **DOWN rails join junctions too** (floor rails are real vanilla
  rails): a conservative post-vanilla touch-up orients a LONE floor
  rail toward an adjacent junction partner, or upgrades the straight
  axis vanilla already chose into the matching concave ascending —
  vanilla layouts are never re-routed, and the onPlace self-heal
  re-applies the touch-up if a vanilla cascade flattens it. Junctions
  require sticky rails on BOTH sides.



## Unreleased (2.0.0-dev) — 2026-08-21 (round 60: the flush planet skin)

- **Core-field water now renders as a taut FLUSH skin — the perfect
  cube.** Round 59's body-contact reads connected the bare-core shell,
  but it still looked wrong, and no honest-level rendering ever could:
  every shell cell is an outermost "surface" cell (frame-up is air), so
  each drew an inset, sloped panel at its own level (5-7) along its own
  sector axis — connected, but a lumpy lantern, never a snug wrap.
  Vanilla ponds only look clean because settled ponds are level-8
  sources; a one-source shell can never be.
- **Fix — radial fields get a skin rule**: in a GRAVITY CORE's field
  (new Source.radialSkin() hint + GravityFieldLookup.isRadialFieldAt),
  fluid that is SUPPORTED at its frame-down (solid or same-type fluid)
  renders at FULL cell height. The 26-cell shell — every cell supported
  — becomes a crisp 3x3x3 cube hugging the core. Flush cells also count
  as COVERING for culling and read full in shaping (coversCell), so the
  cube shows no internal translucent seams. Free-FALLING fluid
  (frame-down empty) keeps vanilla shapes; plates and normalizers are
  deliberately untouched (planar surfaces keep honest levels).
  Rendering only — levels, flow, and drainage are unchanged.



## Unreleased (2.0.0-dev) — 2026-08-21 (round 59: perpendicular body contact)

- **The bare-core shell now renders as one connected blob.** The
  physics was already right — fluidsim shows a perfectly symmetric
  26-cell cube (levels 5-7) around the core — but every edge and
  corner cell drew its partial height along its own sector axis and
  read all its cross-frame water neighbors as EMPTY (none are falling
  columns, so none of the pour/drain/full-column reads fired). Each
  cell sloped to nothing at every sector boundary: the vest-shaped
  taper and claw fins in the screenshots.
- **Fix — the missing read is geometric, not a special case**: a
  cross-frame same-type neighbor on a PERPENDICULAR down axis keeps
  its partial height on a DIFFERENT axis, so along the querying cell's
  height axis its water genuinely spans the cell — the two cells are
  one connected body. It now reads at its OWN level height (not 0, not
  1): chunky shell cells knit their surfaces together into a rounded
  cube, while a thin film on a wrapped solid cube ramps DOWN toward
  its low level — vanilla's pour-over droop at the lip, NOT the
  round-30 full-height bulge. The axis-equal opposite frame (the
  mutual pit) keeps its cliff. Rendering-only; the flow rules and the
  sim are untouched this round.
- Expectation note: a single source yields shell levels 5-7, so the
  cube renders with vanilla-honest tapers at its outer edges. More
  sources (or letting in-field conversion knit a fed shell into
  sources) raise the shell to level 8 for the crispest cube.



## Unreleased (2.0.0-dev) — 2026-08-21 (round 58: the solid planet — screenshots round)

- **The "air pockets" shattering a solid water planet were mostly NOT
  air** — they were phantom internal water surfaces: the renderer's
  cross-frame culling isolation treated the other sector's water as
  empty, so every sector boundary deep inside the flooded cube rendered
  internal faces, in the uniform core-surrounding pattern the
  screenshots show. FIX: a neighbor that renders as a FULL COLUMN in
  its own frame fills its entire cell, so it genuinely covers the
  shared face whatever its frame — such neighbors now pass through the
  culling mask (and read full in height shaping). Deep inside a water
  planet every cell is a full column, so internal sector boundaries
  stop rendering entirely; near the real surface, partial cells keep
  the isolation.
- **fluidsim caught a round-57 physics regression the screenshots also
  show (the jagged single-source blob)**: deferring over ALL cross-frame
  water killed the corner wrap's continuation — the wrap cell sits over
  the previous face's water film and must keep spreading onto the next
  face; with it deferred, coverage died past the first face (cube faces
  0/9, the bare-core 26-cell shell down to 18 lopsided cells). FIX: the
  defer now distinguishes OPEN water (air/water-backed — a stream, the
  flooded interior: COMBINE, no sheet) from a SOLID-BACKED surface film
  (the wrap continuation: keep spreading) — the same solid-backing
  criterion the side-entry feed already uses. Full suite green,
  including the round-57 no-sheet scenario.
- **fluidsim brought up to date and extended** (it had drifted to
  round-52 rules): round-57 combine gate and in-field source conversion
  ported in; new regression scenarios for the diagonal-source sheet
  (bounded) and the punched water planet (all pockets fill AND convert
  to sources); the drain invariant now strips CONVERTED sources too, so
  "no source-free water sustains itself" stays proven under conversion.



## Unreleased (2.0.0-dev) — 2026-08-21 (round 57: water planets combine)

- **Streams now COMBINE with cross-frame water instead of sheeting over
  it.** The spread hole-gate deliberately let non-falling water over a
  cross-frame below cell side-spread (the corner wrap needs it over a
  solid lip) — but it never checked what the below cell HOLDS. The
  spreading ring around a stream treated another sector's water surface
  as solid ground and sheeted outward in all four directions (the
  uniform in-field spread amplified it) — the "huge mess" from a source
  placed diagonal to a core. Now, water over same-type cross-frame
  water DEFERS exactly like a falling column landing on it: the flows
  merge at the seam (the pour/cross-feed relations carry the level
  across), and spreading stops. The solid-lip corner wrap is untouched.
- **Water planets self-heal — in-field source conversion re-enabled.**
  The old blanket suppression ("fields move water, they never create
  it") left flooded fields un-healable: flowing cells starve at sector
  boundaries under the cross-frame feed rules, and with conversion off
  those air pockets were PERMANENT — the un-swimmable water planet.
  Conversion is vanilla's own infinite-water rule and all its inputs
  were already frame-aware (perpendicular-plane neighbor count,
  frame-below solid-or-source check), so flooded regions now knit
  themselves back into sources like a vanilla pool: enclosed pockets
  fill, then convert. The one suppression that remains is PLATE CELLS
  (a source minted inside a plate is undrainable and blocks flow).
  Deliberate consequence: sources minted in a field are real sources
  and outlive it, exactly like vanilla infinite water.



## Unreleased (2.0.0-dev) — 2026-08-21 (round 56: the drain funnel)

- **The logged plate's own surface now slopes into its outflow.** Round
  54 made the column BELOW a fluid-logged plate render full (the
  through-fall seam), but the plate cell's sheet still shaped that
  column as empty — a thin sheet perched on a full-width column, a hard
  T-junction instead of a slope.
- **Fix — DRAIN, the feeder-side mirror of through-fall**: a cross-frame
  neighbor whose own frame-up faces the queried cell (directly for face
  neighbors, corner-adjacent for diagonal samples) is RECEIVING that
  cell's flow and carrying it onward — it now reads FULL in the cell's
  height shaping, so the plate sheet's surface sweeps from its thin
  height down to full extent at the outflow edge, funneling into the
  column below. Works for water and lava alike, and symmetrically for
  any field-exit direction. No column-shape gate on the drain side (the
  receiving cell is laterally fed, never FALLING — its same-type
  presence at the drain is the proof the flow continues). The wrapped
  cube's side-by-side sector sheets still read empty: a sector
  neighbor's down points AT the rim, not away from it, so the drain
  test cannot fire there — cliff edges and the round-30/53 no-bulge
  behavior are preserved.



## Unreleased (2.0.0-dev) — 2026-08-21 (round 55: lava-logging)

- **Gravity plating can now hold LAVA** the same way it holds water —
  source or flowing, full level range, following the plate's gravity.
  A new LAVA blockstate flag retypes the stored fluid; the existing
  water_level/water_falling properties are reused unchanged, so old
  worlds load intact (their plates simply read as water). All the
  fluid machinery was already fluid-agnostic (the FlowingFluid mixins,
  the container-preservation hook, the rotated renderer, the
  pour/through-fall seam shaping) — the plate's encode/decode was the
  only water-only gate.
- Details: lava-logged plates emit light 15 like real lava, burn
  entities, and fire-spread via the fluid's own random tick; lava
  buckets fill and empty against plate cells (with the lava pickup
  fizz); placing a plate into standing lava lava-logs it.
- **Deliberate limit — no mixing inside a plate cell**: a plate holding
  one fluid rejects the other outright (water flowing at a lava-logged
  plate just stops, and vice versa). There is no block space for the
  obsidian/cobble a real interaction would mint, and silently swapping
  fluids would be worse; the stored fluid must drain or be bucketed
  before the other can enter.



## Unreleased (2.0.0-dev) — 2026-08-21 (round 54: the through-fall seam)

- **Water dropping out the back of a field connects too.** Round 53's
  pour test covers water arriving BY THE NEIGHBOR'S gravity (its
  frame-down points at the cell) — but a seam has a second form: water
  leaving a field by THE RECEIVING CELL'S gravity. Below a waterlogged
  east-facing plate, the vanilla cell's water is pulled out of the
  plate cell by plain world gravity; the plate's own frame-down is
  sideways, so the pour test never fires, and the falling cell rendered
  at its own 8/9 height with tapered corners — the visible gap between
  the waterlogged plate and the stream under it.
- **Fix — THROUGH-FALL**: a cell with same-type cross-frame water at
  its own frame-up renders as a full column when that water can
  actually FEED it — the neighbor's down axis is PERPENDICULAR to the
  cell's, so its spread plane contains the cell's down (exactly the
  engine's feed arithmetic; note the gap cell is laterally fed and NOT
  marked FALLING, so no falling-state test could catch it). Applied in
  the ownHeight rule and getHeight's above-check so same-frame
  neighbors agree about shared corners. An axis-equal opposite frame at
  the frame-up (the mutual-pit seam, where neither side feeds the
  other) keeps its cliff edge, and lateral/diagonal shaping is
  untouched — the cliff-edge/no-bulge behavior of rounds 30 and 53 is
  preserved on wrapped cubes.



## Unreleased (2.0.0-dev) — 2026-08-21 (round 53: the pour-aware ramp)

- **Water crossing a gravity boundary connects with a slope again**
  (round 30 regression). Round 30 flipped the renderer's cross-frame
  height shaping from "full column" to cliff-edge to kill the bulging
  edges on wrapped cubes — but that also severed the visual connection
  where a falling stream actually crosses into a differently-oriented
  frame: the receiving cell truncated to its own thin height and the
  stream ended in a floating disconnected column.
- **Fix — the ramp follows the water**: cross-frame fluid now shapes as
  a FULL column exactly when a genuine column pours toward the queried
  cell — the neighbor is a source or FALLING in its own frame, and one
  step along its own frame-down carries its water strictly closer (onto
  the cell for face neighbors, onto a corner-adjacent cell for diagonal
  samples). The receiving surface ramps up to meet the incoming stream,
  entering AND exiting fields. All other cross-frame water (the
  side-by-side sector sheets of a wrapped cube, thin edge cells whose
  down merely points at the next face) still shapes as empty — the
  round-30 cliff-edge fix is preserved. This is the renderer's mirror of
  the flow engine's crossFeeds relation and getFlow's pour-aware
  isolation: the visual ramp exists precisely where water actually moves
  between frames. Physics untouched — flow behavior is unchanged.



## Unreleased (2.0.0-dev) — 2026-08-21 (round 52: the radial anchor)

- **Fixed the circling jitter around ship-mounted gravity cores** (round
  51 regression). The field-ship anchor assumed a ship field's direction
  is a block-grid constant — true for plates and normalizers, false for
  a core: its pull is RADIAL, recomputed from the entity's position
  every tick. The core was sampling that direction into shipyard space
  once per tick and the render-time drawn-ship alignment then snapped
  the camera's up to it at full strength every frame — a target that
  holds still between ticks and jumps at each tick boundary, i.e. a
  20 Hz staircase. Worldspace cores never set the anchor, which is why
  they stayed smooth.
- **Fix — anchor the center, not the direction**: a radial field's true
  ship-space constant is the CORE'S POSITION. Core effects now carry the
  source center in shipyard coordinates (plus the attract/repulse sign);
  the tick target re-derives the pull from the live ship transform and
  live entity position, and the drawn-ship alignment re-derives it per
  frame from the RENDER transform and the INTERPOLATED entity position —
  smooth at frame rate, exact on moving ships. The cardinalized
  grounded-mob direction is sector-constant and keeps the rotation-only
  anchor; plates and normalizers are untouched.



## Unreleased (2.0.0-dev) — 2026-08-19 (round 51: the field-ship anchor)

- **Alignment now belongs to the FIELD, not to ground contact.** A
  ship-mounted field's direction is a block-grid constant in the ship's
  own coordinates, so field effects now carry their source ship and
  shipyard-space direction (all three field blocks wired). While the
  dominant field is ship-sourced, the player's frame anchors to that
  ship — standing, jumping, or flying: the gravity target is re-derived
  from the live ship transform every tick, and the render alignment uses
  the drawn ship pose, airborne included. Jumping on plating no longer
  unsnaps; flying inside a ship-mounted normalizer's zone stays aligned
  with the moving ship.
- **Snapping is now smooth**: alignment strength eases in and out over
  ~0.4 seconds (matching the mod's transition feel) instead of engaging
  instantly on landing and releasing instantly on jumping. The eased,
  field-keyed anchor also removes the random camera snaps while walking
  on a spinning ship (previously a binary grounded-state gate flapping
  on per-step probe dropouts).



## Unreleased (2.0.0-dev) — 2026-08-19 (round 50: the single-arg straggler)

- Round 49's drawn-ship alignment confirmed working in-game: the player
  model rides the moving ship perfectly. The two leftovers — the camera
  trailing the model (visible in F5) and the F3+B capsule debug spheres
  not rotating with it — shared one root: both used the one-argument
  `getRenderRotation` overload, which still had its own uncorrected
  interpolation; the model uses the two-argument overload that carries
  the alignment. The single-argument overload now routes through the
  corrected one, fixing the camera and the debug spheres together.



## Unreleased (2.0.0-dev) — 2026-08-19 (round 49: the drawn-ship alignment — measured fix)

- **The vs-drag diagnostics delivered the verdict**: capsule players are
  FULLY inside VS's pipeline (draggable, actively dragged, both sides),
  and VS's render-ride measurably carries the local player's POSITION
  onto the drawn ship every frame (~0.4 blocks at the test spin rate).
  The fault was split ownership: body position from the drawn ship pose
  (VS) while the frame's ROTATION interpolated tick poses (ours) — a few
  degrees of rotational lag on the 1.62-block eye arm was the camera
  jitter, and the same lag was the visible model/hitbox tilt on a body
  whose feet were position-glued to the deck.
- **Fix — drawn-ship alignment**: the held surface normal is maintained
  in shipyard coordinates each tick; at render time the interpolated
  frame is parallel-transported onto that normal as the ship is DRAWN
  (render transform). Identity when the drawn pose matches the tick pose
  (stationary ships) and inactive off-ship or on level decks; the render
  slerp is also hemisphere-aligned. This is the round-38 idea returned
  with its true justification — it originally drowned under the
  since-fixed target flips and server field starvation.



## Unreleased (2.0.0-dev) — 2026-08-19 (round 48: the two-pipelines synthesis)

- The user's observation that DOWN-gravity plating on a ship looks fine
  while any tilted gravity misaligns identified the architecture-level
  cause: with down gravity, capsule mode is off and Valkyrien Skies'
  complete riding stack (collision, dragging, and per-frame RENDER-RIDING
  — VS teleports draggable entities to render-interpolated ship poses
  every frame) handles the player end to end. With tilted gravity,
  capsule mode bypasses VS ship collision and only partially re-enters
  VS's pipeline — the seams between the two half-engaged pipelines are
  the jitter and tilt.
- New vs-drag heartbeat logs whether VS considers the player draggable,
  whether it is actively dragging, and whether the camera-time position
  diverges from the tick position (proof of VS render-riding the local
  player). The three possible outcomes each map to a specific,
  single-mechanism fix.



## Unreleased (2.0.0-dev) — 2026-08-19 (round 47: the starving server — measured root cause of the ship saga)

- **The chain diagnostics found it**: on a fast-rotating ship the SERVER
  receives zero field effects for seconds at a time (the server-side
  player position lags the client by packets, mapping outside the swept
  field column in ship space), the grace expires, and the server's
  gravity collapses to vanilla world-down MID-RIDE — the cause of the
  jump fling, the periodic camera snap-arounds, and the upright-player
  screenshots. The frozen grace vector also drifted ~40° stale during
  dropouts.
- **Fix — standing on a held surface sustains its field**: while the
  under-feet probe says the player stands on the surface the field
  endorsed, effect dropouts no longer collapse gravity: the grace stays
  open and the sustained field points onto that surface, rotating WITH
  the ship (probe-fresh) instead of freezing. Release is untouched when
  there's no surface underfoot: airborne keeps the full jump grace,
  grounded on plain ground releases in 2 ticks.



## Unreleased (2.0.0-dev) — 2026-08-19 (round 46: chain diagnostics)

- Round 45's surface glue did not resolve the tilt (new screenshot:
  still near-world-upright on a ~30° deck). Two theories eliminated by
  code reading: field containment is already ship-space-correct, and a
  static ~30° error cannot be chase lag. Also discovered the committed
  baseline still contains the round-35 swing feed-forward (the only
  ship-experiment piece the user's commits captured).
- New CHAIN HEARTBEAT (both client [C] and server [S], every 2 seconds
  when gravity-relevant): effects-received count, grace, held surface
  normal, raw field target vector, target up, frame up, chase gap, and
  capsule state — one test run now shows exactly which link between the
  plating and the player's tilt is failing.



## Unreleased (2.0.0-dev) — 2026-08-19 (round 45: surface glue + grounded grace release)

First post-revert round: two small, self-limiting changes, one file.

- **Surface glue (the constant tilt on rotating ships)**: the frame chase
  tracked at 8-35% gain, whose steady-state lag on a continuously
  rotating target was the ~25-30° player tilt in the user's screenshot.
  While standing on a held surface AND the target actually points along
  that surface's normal, the chase now tracks at 0.9 gain — glued to the
  deck. Self-limiting by construction: if the target flickers away from
  the surface (the stale-field flips that wrecked the reverted
  experiment), the gain instantly falls back to the smooth baseline
  path, so flicker gets smeared instead of amplified.
- **Grounded grace release (the walk-off delay)**: the 6-tick field grace
  is sized for jumps and edge pockets — both airborne. Standing on solid
  ground with no live field (walked off the plating), it now releases in
  2 ticks instead of holding the stale pull for the full window — the
  "gravity takes a second to update" delay, on ships and in world space.
- Explicitly deferred, one thing at a time: the render-level sub-tick
  stutter (next round, as its own single mechanism inside VS's update
  pipeline), and the small residual one-tick-staleness tilt.



## Unreleased (2.0.0-dev) — 2026-08-19 (rounds 35-43: ship-sync experiment — REVERTED)

Nine iterations attempted to perfect standing on moving/rotating VS ships
(tick feed-forward, render locks, camera rides, late tick alignment,
hemisphere alignment, anchors). Each fixed a real, measured defect — but
their interactions made the in-game result WORSE than the baseline, and
the whole uncommitted pile was reverted. What the instrumentation proved,
kept for the next attempt (see the project memory for full details):

- VS updates client ship transforms AFTER entity ticks (all mid-tick
  reads are one tick stale) and updates render transforms at frame start.
- The frame chase is a proportional controller: it inherently lags a
  continuously rotating target; but making tracking exact AMPLIFIES any
  noise in the target signal (stale field vectors flickered the target by
  15-57 degrees on fast spins — the old sluggish chase had been masking
  it).
- The field's region sweeps with the ship, so field presence flickers for
  riders at high spin rates; any ship-riding design must derive its
  target from ship-constant data (shipyard-space surface normals), not
  from per-tick world-space field samples.

The next attempt should be ONE mechanism, built inside VS's own update
pipeline (its dragger/transform events), validated in-game at each step —
not corrections layered around vanilla's tick/render loop.

## Unreleased (2.0.0-dev) — 2026-08-18 (round 34: GUI overhaul restored)

NOTE: the user reverted most of rounds 29-33 in-tree (kept: namespace
rename, mimic sounds, capsule feel fixes, per-tick projectile sync).
Entries below this note describe work that may no longer be present.
New workflow: one issue per round.

- **Settings GUI overhaul re-applied** (was reverted together with the
  unwanted changes): labeled sections — Field / Gravity / Ship & Visuals
  — with gray headers and separator lines; 260px column so preset labels
  render fully; gravity-acceleration presets (Overworld 0.08, Moon
  0.0133, Zero-G 0, Jupiter 0.2); "Done" renamed "Apply"; "Apply to
  Connected Plates" is now "Copy to Connected Plates" — a button that
  applies the on-screen values to the whole connected group IMMEDIATELY
  (with "Copied!" feedback) without closing the screen; connected-copy
  flood-fill cap raised 256 → 4096.


## Unreleased (2.0.0-dev) — 2026-08-17 (round 30: sync cadence, cliff-edge water, ramps, TNT)

- **Universal in-field smoothness (arrows/items/orbs/tridents)**: entities
  under an active gravity frame now sync position AND velocity every tick
  (new ServerEntityMixin). Vanilla's sparse cadence (items every 20
  ticks) works because plain gravity is a constant vector and client
  prediction is exact; inside a radial field prediction inevitably
  drifts, and each rare correction arrived as a visible teleport — the
  item/orb "teleporting around" and arrow "skipping". Per-tick
  corrections are tiny and smooth; only entities inside fields pay the
  bandwidth.
- **TNT (and falling blocks) now obey gravity**: PrimedTnt and
  FallingBlockEntity override tick() without calling super, so their
  gravity capabilities NEVER ticked (same root as the round-28 minecart
  find). Both mixins now drive the capability.
- **Field blocks are explosion-immune**: core, plating, and normalizer
  get bedrock-class blast resistance.
- **Sneak edge-guard actually works now**: the support test was a
  capsule-fit check, and the fat bottom sphere kept "finding support" on
  the cliff's SIDE face until past the point where the grounded flag had
  already dropped — the guard never engaged. Replaced with a raycast
  straight down the frame from the destination feet: crouching stops at
  edges like vanilla.
- **Stair ramps between faces (snap on)**: new RAMP CONSENSUS adoption —
  standing on a collision plane that disagrees with the held face for
  several consecutive ticks re-adopts the plane actually stood on (same
  relative-endorsement gate as other adoptions). Walking a stair ramp
  between two faces of a hollow repulse cube transitions cleanly instead
  of snapping wrong. (Snap OFF on stairs remains hard mode by design —
  pure radial with no assists, per the snap-off spec.)
- **Camera-in-block during transitions fixed**: rotating the frame
  rotates the capsule in place, and nothing re-resolved contacts until
  the next move — the head could spend frames inside a block near walls.
  After any significant frame-chase step the capsule now depenetrates
  immediately at the current position.
- **Water boundary shape**: the renderer's cross-frame height-shaping
  policy is flipped from "full column" to CLIFF-EDGE — cross-frame water
  shapes as empty, so each face's water renders its own closed vanilla
  cliff edge at cube boundaries instead of bulging to full height (the
  "shape doesn't appear right" on the 3×3×3).
- **Minecarts on rotated rails fixed** (rails v3): the cart body now
  visually aligns to the track bed via the new render-frame override
  (gravity stays the cart's own, with exact velocity re-expression when
  the pin engages/releases); seating uses vanilla's exact offset along
  the rail's up, applied on the attach tick too. The real "wall carts
  don't slide" culprit turned out to be the BOUNDING BOX: a plain-gravity
  cart on a wall rail kept its world-frame box embedded ~0.4 into the
  wall, so movement clipped against the wall column every tick — the
  cart accelerated but never moved (and the pick ray hit the wall,
  making carts hard to break). While riding a non-DOWN rail the box is
  now built in the rail frame; the gravity projection itself was already
  correct on every shape.
- Trident note: a full-speed trident genuinely exceeds a small field's
  escape velocity — that is real orbital mechanics, not a bug. For
  orbit play with fast projectiles, raise the core's Gravity Accel in
  the GUI (it scales projectile pull too).



## Unreleased (2.0.0-dev) — 2026-08-17 (round 29: feel, snap, rails v2, desync — 12-finding pass)

- **Capsule feel on flat ground**: all anti-slide pins/brakes now engage
  ONLY on genuinely tilted ground (they exist to stop slope creep; on
  frame-flat ground they were eating turning momentum and diagonal
  movement — the "catches on nothing", jitter, and the molasses at
  concave plating seams).
- **Sneak edge-guard restored**: frame-aware reimplementation of vanilla's
  crouch-at-the-edge protection (the world-down original is bypassed in
  capsule mode). Shrinks tangential movement per local axis until the
  capsule still finds ground within step height below.
- **Surface snap professionalized**:
  - snap-off is now truly snap-free for players — pure smooth radial
    (the grounded-cardinal substitution from round 27 was the "still
    snaps when I touch a face, unsnaps when I jump" behavior; mobs keep
    it — they cannot manage radial drag);
  - RELATIVE endorsement gates: a candidate face must be field-endorsed
    at least half as strongly as the held one — stair risers and door
    faces under a core's tilted radial field no longer capture the frame;
  - anti-flap: a just-released face is only re-adopted by actually
    standing on it (slow edge crossings no longer snap back and forth);
  - creative flight never surface-adopts (flying between plate groups
    kept snapping the frame onto faces, including the 45° lock);
  - the secondary-bleed sustain is BOUNDED (12 ticks): walking off a
    plated region no longer keeps pulling until well outside the field.
- **Mob jump spam fixed**: vanilla MoveControl decides "path point is
  above me → jump" in world-Y; walking along a wall changes world-Y
  constantly, so mobs hopped nonstop. The jump decision is now evaluated
  in the mob's local frame (new MoveControlMixin). Wander-goal target
  selection is still world-biased (vanilla LandRandomPos) — mobs may
  still prefer strolling toward world-valid ground; deeper AI work noted
  as future.
- **Item / XP-orb rubber-banding fixed**: non-living remote entities are
  client-predicted physics objects, but the client never applied field
  effects to them — their prediction ran in a stale frame and every
  server packet was a rubber-band. Clients now apply fields to non-living
  entities locally, their frames snap instantly (no camera), and the
  locally computed target wins over the lagging sync while field effects
  are fresh.
- **Sticky rails v2**:
  - StickyRailBlock now EXTENDS BaseRailBlock (+ minecraft:rails tag):
    minecart placement by right-click works, and DOWN-oriented sticky
    rails are fully native rails — two-way interconnection with vanilla
    rails, vanilla movement, vanilla everything;
  - the gravity PIN is gone: carts keep their own gravity, and on-track
    acceleration is the cart's gravity vector projected onto the track
    (vanilla's slope constant, exactly vanilla for plain gravity on
    ascending shapes). A wall rail under normal gravity holds a cart at
    rest on a horizontal track and lets it roll where the track runs
    along gravity — the oscillation is gone;
  - rider seating rotated into the cart's frame (no more raised/offset
    riding); cart yaw no longer mirror-flips on UP-frame rails (the yaw
    used the entity-convention reflection while rendering used the
    visual convention);
  - within-frame ascending SLOPES are fully wired (connection promotion,
    support checks, movement); the concave cross-face link (floor rail →
    wall rail around an inside corner) is deferred — it needs a
    cross-frame handoff in both connection search and movement and was
    judged too unstable to ship blind.
- **Mimic sounds**: doors/trapdoors/fence gates open and close with their
  exact per-material vanilla sounds + sculk game events; the placing
  player now hears the captured block's place sound (client-side BE
  prefill — also makes mimics render on the first frame); step/break/hit
  already delegated to the captured block.



## Unreleased (2.0.0-dev) — 2026-08-16 (round 28: Gravity Unbound — rename, rails, refinement)

- **RENAMED: the mod is now "Gravity Unbound"** — mod id `gravityunbound`,
  package `net.camacraft.gravityunbound` (was GravityVS /
  `net.cama.gravityapivs`). 178 files rewritten, all registries, assets,
  mixin config, refmap, network channel and capability IDs follow.
  NOTE: existing test worlds lose this mod's blocks/items (registry
  namespace changed) — recreate test setups.
- **Sticky rails + minecarts (v1)**: new Sticky Rail block placeable in
  all 24 orientations (straight + all four curves, vanilla-identical
  connection logic ported to the rail's local frame; junctions respond to
  redstone; rails pop off when support is lost). Minecarts attach and
  ride them under rotated gravity: a line-for-line port of vanilla's
  on-track physics runs in the rail frame, the cart's gravity is pinned
  to the rail while riding, and momentum is conserved on attach. Renders
  the real vanilla rail model rotated. DEFERRED (documented in-code):
  cross-frame links around cube edges, slopes, powered/detector
  variants, carts on VS ships.
  - Root fix discovered en route: minecart gravity capabilities NEVER
    ticked (AbstractMinecart.tick doesn't call super) — carts now tick
    the capability and respond to all gravity fields.
- **Sticky mimic refinement**: doors place BOTH halves (paired placement,
  paired breaking, paired open/close); mimic orientation now derives from
  the CLICKED face (redstone/carpets no longer stand upright); break and
  hit particles use the captured block; pick-block returns a loaded
  caster; clearer refusal for block-entity captures + honest tooltips.
  Rotated block LOGIC (redstone conduction, growth) remains future
  virtual-space work.
- **Sticky chest container titles are vanilla** ("Chest" / "Large Chest")
  so gravity zones read as normal play.
- **Projectile field-entry desync fixed at the root**: the frame chase's
  per-tick world-velocity re-expression was bending world-frame
  projectiles' real velocity during the whole transit ("teleports around
  before settling"); projectile frames now snap instantly (no camera) and
  are exempt from the re-expression.
- **Capsule for ALL entities except projectiles** (boats, modded planes,
  items, TNT — anything that moves through move()).
- **Snap-off walking fixed for real**: grounded detection for the
  cardinal-direction rule now uses the capsule's grounded state (vanilla
  onGround() is world-down based and stayed false on rotated faces,
  leaving standing entities under the radial center-pull).
- **Water no longer freezes grounded players**: the pre-collision static
  pin (a third pin, missed last round) stripped the current's small
  tangential movement every tick; it now yields in water/lava.
- **Per-dimension gravity**: config `dimensionGravity` entries
  ("minecraft:the_nether=up,0.5") and a runtime API
  (DimensionGravity.set/clear) apply ambient gravity as a low-priority
  effect — any field overrides it.
- **Spawn-egg mobs adopt field gravity the same tick**: entity joins
  invalidate nearby field sources' entity-query caches (the staggered
  caches left new mobs unaffected for a few visible ticks).
- **Per-core "Affects Ships" GUI toggle** (ANDed with the global config)
  — ship-planets attracting other VS ships is per-block controllable.
  Core-as-ship attracting other ships already works via the existing
  force system.
- Gravity tipped arrows removed from the creative tab (reverted).
- Simulator: bare-core scenario added — a single core + one source
  settles as the exact symmetric 26-cell 3×3×3 water shell and drains;
  if the in-game shape still looks oblong, the remaining suspect is
  renderer height-shaping at frame boundaries, not physics.



## Unreleased (2.0.0-dev) — 2026-08-16 (round 27: entities, projectiles, sticky system, polish)

- **Capsule collider extended to ALL living entities** under active gravity
  frames (was players-only): mobs on rotated plating no longer catch on
  their own tilted-envelope AABB (glitchy immobile creepers). Mobs get the
  full continuous frame: surface probe, planet-walk alignment, twist
  anchoring, transition pull, capsule grounding, exit watchdog.
- **Core fields no longer drag grounded entities to the face center.**
  Grounded mobs (and players with surface snap off) get the SECTOR-FRAME
  CARDINAL direction (same dominant-axis rule as fluids) — clean
  face-normal gravity per face; the raw radial pull (whose tangential
  component dragged everything toward the face center: stuck pigs,
  unwalkable snap-off cubes) now applies only to airborne entities, where
  it belongs (orbits, edge falls). Snap-on players keep radial+alignment.
- **Projectile model unified: projectiles are WORLD-frame.** They integrate
  position by raw world addition (never move()), so their deltaMovement is
  world-space with gravity applied along the rotated axis — but three
  newer local-frame systems corrupted them: the lerpMotion packet
  conversion, the fluid-push conversion, and the frame-adoption velocity
  re-expression all now exempt Projectile; cardinal crossings never run
  the legacy reposition (which also rewrote projectile yaw/pitch with
  per-cardinal conventions — the "trident flipping all over"). Arrow and
  throwable gravity now pulls along the CONTINUOUS field vector (frame-
  down fallback on remote clients, cardinal last) — smooth orbits.
- **Flowing liquids can push capsule entities**: the anti-slide brake and
  the ship idle-anchor ate the ~0.005/tick current push; both now yield
  while the entity is in water or lava.
- **Gradual falloff is now inverse-square** (full strength within 4
  blocks) for entities and ships — a linear ramp stayed too strong at
  range to sustain orbits.
- **Rotated fluids shade correctly**: each face uses its LOCAL role's
  vanilla brightness (frame-up surface = full top shade) instead of the
  rotated world direction's (sideways water looked uniformly darker).
- **Flow arrows respect frame boundaries**: getFlow gained the renderer's
  asymmetric cross-frame isolation (pouring feeder reads full, other
  cross-frame water reads empty; DOWN cells bordering rotated water get
  the same treatment) — water no longer appears to flow UP a face toward
  the rim before wrapping. Note: small level differences between faces
  are inherent to the lattice feed arithmetic (pour-through vs corner
  tolls) and are not fully removable.
- **Sticky chests combine into double chests** (vanilla-mirrored TYPE
  pairing in the chest's local frame, DoubleBlockCombiner menu, shared
  lid animation, left/right chest models).
- **Dynamic sticky system**: new Sticky Caster (creative-only): sneak-click
  any block to capture it, click to place a Sticky Mimic — an invisible
  shell BE that renders and collides as the captured block in any of the
  24 grid orientations (doors/trapdoors/gates toggle OPEN on use).
  Mimics are static visual/collision experiments: block LOGIC (redstone
  power, rail connections, growth) does not run rotated — documented in
  the class javadoc. Rails that carry minecarts around a cube would need
  a minecart-physics port and remain future work.
- **Creative-only configuration**: the settings GUI (and its packet) now
  requires creative mode, like command blocks; all legacy item-shortcut
  interactions (echo shard, amethyst, glow ink) removed — the GUI is the
  only configuration path. No crafting recipes exist for any mod item.
  Gravity tipped arrows added to the GravityVS creative tab, and all 12
  "Arrow of ..." names added to the lang file.

## Unreleased (2.0.0-dev) — 2026-08-16 (the feed arithmetic: pour-through, corner wraps, boundary cut)

- **Complete cross-frame feed arithmetic**, replacing the previous round's
  full-reset pours after simulator-driven diagnosis of three in-game
  reports (boundary splash "flowing across like a solid block", lingering
  undrainable branches, incomplete cube coverage). Every fluid transfer now
  has a cost that makes all cycles strictly level-decreasing — no
  source-free water can sustain itself, so removal always drains:
  - **Pour-through (net 0)**: a cross-frame pour passes the feeder's LEVEL
    through the edge crossing instead of resetting to full. Full resets are
    exclusive to same-frame columns (pure vanilla). One top-center source
    on a 3×3×3 core cube: the level budget exactly wraps the whole cube
    with fading levels.
  - **Solid-backed side-entry (net -1)**: the convex-corner wrap. A cell's
    own frame-below neighbor may feed it when both are in a field, frames
    differ (non-opposite), and the feeder rests directly on solid — a
    surface cell pouring over a one-block lip. Solid-backing confines water
    to the surface (no outward layering); the in-field gate keeps walls
    inside sideways fields from overflowing into normal space. This opens
    face-to-face wraps in every direction the budget allows: side sources
    cover the cube; bottom sources climb (top face partial — the flat-ground
    budget honestly runs out).
  - **Field-boundary cut**: out-of-field water never LATERALLY feeds
    in-field cells (it may only fall in). Without this, the rain halo
    beside a falling sheet fed back into the field's boundary row where the
    same-frame above-rule reset it to full — a generator ring that exited
    and re-entered the field (the simulator's frozen 370-cell state).
  - **Defer-to-column, frame-aware**: FALLING water over cross-frame field
    water defers exactly like vanilla — a world stream landing on a
    sideways field is absorbed and redirected, never splashing sideways
    over the boundary (the round-23 unconditional gate caused exactly that
    splash). Only LATERAL water over a cross-frame below cell side-spreads,
    which is the corner wrap continuing instead of dead-ending.
  - **Uniform in-field spreading**: vanilla's min-slope-distance channeling
    starves plateau corners (fine on terrain, wrong on planet faces).
    In-field origins tie every direction at distance 0 — uniform discs and
    full face coverage; vanilla terrain untouched.
  - All validated in `tools/fluidsim.py` (9 scenarios: 5 cube-source
    positions, stream into sideways field, plated plane, pour onto UP
    field, hanging ceiling water — all stable, covered, fully draining).
    The flat-plane frontier oscillation of the previous build (rendered as
    glitchy, non-connecting water at the end of the spread) converges
    cleanly under the new arithmetic.

## Unreleased (2.0.0-dev) — 2026-08-16 (sector frames + the up-entry prohibition — feeds superseded same day)

- **Field fluid mechanics redesigned on a provable foundation** after the
  DAG-rule fix below still left runaway flooding (verified by building an
  offline cellular-automaton simulator of the exact vanilla + GravityVS
  fluid rules; the simulator reproduced the runaway immediately and showed
  the real loop: an edge cell whose frame treats world-UP as "lateral"
  pushed water above the rim, where vanilla's own above-rule legitimately
  reset it to a full column — a 4-cell fountain no feeder-side condition
  can see). Two structural rules replace the patchwork:
  - **Sector frames** (`GravityCoreBlockEntity.fluidDownAt`): a core field's
    down is the dominant-axis cardinal toward the core, with lattice ties
    resolved by fixed priority world-DOWN > X > Z > world-UP. The field
    partitions into six face sectors with one-way boundaries (top → sides →
    bottom, Z → X): an acyclic sector graph, each sector internally pure
    vanilla. Convex edge cells become pour-over lips of the upstream face —
    real vanilla cliff rims. The old continuous tie-nudges (y×0.93 shrink +
    0.05 rotational tangent) are gone; tangential edge downs were the
    enabling defect.
  - **The up-entry prohibition** (`FlowingFluidMixin`): water may NEVER
    spread into a cell from that cell's own frame-below — enforced in
    getSpread, the slope search, and mirrored in getNewLiquid's lateral
    feed set. With it, the falling feeder returns to vanilla-lenient (any
    neighbor pouring in its own frame feeds full, like a rim pouring over
    a cliff) because acyclicity now comes from the transfer relation, not
    feeder-state conditions.
  - `GravityFieldLookup` resolves exact source ties (priority + distance,
    e.g. a plated cube's edge cells) by the same DOWN > X > Z > UP rank
    instead of map iteration order, so plated builds get the same proven
    sector layout.
  - Simulator verdict (3×3×3 cube, one source top-center): stable in 11
    rounds, 98 cells, all six faces fully covered with levels fading from
    the source, ZERO cells beyond the surface shell, and complete drainage
    to zero on source removal. The simulator is committed at
    `tools/fluidsim.py` — port any future fluid-rule change there FIRST.
  - Cleanup for worlds already flooded: stuck water has no pending ticks —
    break and replace the field block (or place a block beside the water)
    to wake it; it drains fully under the new rules.

## Unreleased (2.0.0-dev) — 2026-08-16 (the water generator: feeding must stay a DAG — superseded same day)

- **Runaway field flooding fixed.** The cross-frame "falling feeder" rule
  violated the invariant that keeps vanilla fluids stable: the feeding
  graph must be acyclic (full-strength feeds go strictly down, lateral
  feeds strictly lose a level). Cross-frame, a face cell could laterally
  feed an edge cell at level-1 while the edge cell — whose gravity points
  back at the face — "falling-fed" the face cell back to FULL: a two-cell
  water GENERATOR at every frame boundary. It flooded the entire field
  with regenerating water and kept running after the source was removed
  (the undrainable pollution). Full-strength feeds now require a GENUINE
  column — the feeder must itself be falling or a source; lateral feeds
  never produce falling states, so every falling chain traces back to a
  real fall or a real source and no cycle can bootstrap. A companion guard
  stops two mutually-attracted cells (opposing fields) from sustaining
  each other. Spread is now bounded like flat ground: levels fade with
  distance and only reset across a REAL fall over an edge into air — the
  vanilla cliff behavior.
  - Cleanup for worlds flooded by the bug: the stuck water has no pending
    ticks — break and replace the field block (core/plate), or place any
    block beside the water, to wake it; it then drains under the fixed
    rules.

## Unreleased (2.0.0-dev) — 2026-08-16 (core-cube wrap: the edge dead-end)

- **Water wraps around bare core cubes — the edge dead-end removed.**
  Vanilla suppresses side-spreading for a fluid cell sitting over a "hole"
  so a falling column defers to the water surface beneath it. At a
  gravity-frame boundary that rule misfires: the edge cell past a face rim
  "falls" toward the face water it came from (a different frame), can
  never actually enter it (same-type fluid never replaces), and was then
  ALSO forbidden from side-spreading — a dead end at every cube edge
  ("flows 4 blocks, gets stuck on the edge"). The defer-to-column rule now
  applies only when the cell and its frame-below share a gravity frame;
  cross-frame, the edge cell side-spreads along its own plane, which is
  what carries flow onto the next face. Traced end-to-end on a 3x3x3 core
  cube: one top source channels to the rim, side faces disperse fully
  (no holes in their planes, so spread goes all directions with fading
  levels), bottom-edge crossings are genuine falls that refresh the level,
  and the top corners fill via the rim — full planet coverage with the
  water level fading away from the source.

## Unreleased (2.0.0-dev) — 2026-08-16 (fluid freeze fix: scheduled-tick type match)

- **Water no longer freezes inside plates (the regression's single root
  cause).** Minecraft's fluid ticker validates scheduled ticks with an
  EXACT type match (`fluidState.is(scheduledFluid)`), and the plate's
  container code scheduled the source singleton (`WATER`) while the plate
  held `FLOWING_WATER` — every scheduled tick for flowing water inside a
  plate was silently discarded. Water entered a plate cell and froze
  forever: it never spread onward ("stopped flowing once it waterlogged
  the plating", "stopped at the face edge" — edge cells are plates) and
  never drained (the reported undrainable "lingering water"). Vanilla
  never hits this because vanilla waterlogging stores sources only.
  Plates now schedule the actual stored fluid type.

- **Source conversion suppressed inside ALL field regions, not just
  rotated ones.** Floor and top-face plates have field-down == world-down,
  so the rotation-keyed suppression didn't cover them — the vanilla
  infinite-water rule could still mint sources inside plate cells, which
  are undrainable and block flow through them. Conversion is now keyed on
  "inside any field source's region" (new `hasFieldAt` query); vanilla
  ground outside fields keeps normal infinite-water behavior.

## Unreleased (2.0.0-dev) — 2026-08-16 (water planets: flowing water in plates + cross-frame feeding)

- **Flowing water passes through gravity plating.** Vanilla waterlogging
  is source-only (one boolean), so FLOWING water could never enter a plate
  cell — and plate cells are exactly where face-hugging flow lives, so the
  whole face was walled off. Plating now stores the full fluid level in
  its state (dry / flowing 1-7 / source / falling), accepts flowing water
  through the container API, and the fluid engine's tick updates the
  plate's stored level in place instead of replacing the plate. Buckets
  still pick up sources only; breaking a plate releases its water.

- **Cross-frame fluid feeding — the water-planet rule.** Vanilla
  `getNewLiquid` judges "who feeds me" in the cell's OWN frame; at a
  gravity-frame boundary the feeder's flow direction lives in the
  FEEDER's frame, so the receiving edge cell recomputed its level as zero
  and evaporated the tick after the water arrived — spread re-placed it,
  and the edge flickered forever without wrapping ("flows to the edge and
  stops", "single strand instead of dispersing"). Feeding is now judged in
  the feeder's frame: a neighbor feeds laterally when the direction to us
  is perpendicular to ITS down, and feeds as a FULL falling column when
  its down points AT us — the vanilla above-check is the uniform-gravity
  special case of this rule. Falling feeds carry full level, so the spread
  budget REFRESHES at every edge crossing: one source can now wrap and
  cover an entire plated or core cube — water planets work.

- **Source-conversion suppression fixed (was gating the wrong counter).**
  The infinite-water rule lives in `getNewLiquid`'s own neighbor loop
  behind `FluidState.canConvertToSource` — the earlier fix emptied
  `sourceNeighborCount`, which gates a different mechanic. Conversion is
  now suppressed at the real site for rotated frames (fields still never
  create sources), and `sourceNeighborCount` is restored to the proper
  perpendicular plane.

## Unreleased (2.0.0-dev) — 2026-08-16 (fluid wrap-around v2 + seamless boundary rendering)

- **Liquid wraps over every edge of a plated/core cube.** The first
  tie-break fix only covered horizontal-vs-horizontal (equator) ties; the
  top/bottom EDGE cells of a cube sit at exact vertical ties
  (horizontal axis vs Y), where the tangential bias is perpendicular to
  both tied axes — so `getNearest`'s Y-first enum order still won, and
  side-face streams reaching a top edge "fell" straight back onto the face
  they came from ("reaches the edge and just stops"). Vertical near-ties
  now prefer the horizontal (core-ward) axis: the edge cell falls sideways
  into the next face's cell and crosses the edge, while the reverse
  direction still works through planar spread. Every edge crossing is a
  falling step, which refreshes the spread budget — one source can cover
  an entire 3x3x3 cube on every face.

- **Seamless fluid rendering into gravity fields.** The cross-frame
  isolation now uses ASYMMETRIC semantics: culling decisions still treat
  foreign-frame fluid as empty (holes stay closed), but height-shaping
  decisions (full-column checks, side and diagonal corner-height
  averaging) treat present foreign-frame fluid as a FULL column — both
  sides of a boundary ramp up to meet each other, giving the vanilla
  higher-touches-lower connected-ramp look instead of a truncated elbow.
  Also found by audit and fixed: the boundary routing only scanned the 6
  face neighbors, but vanilla rendering consults an 18-cell neighborhood
  (diagonals and the 3x3 ring above) — cells touching a rotated cell only
  diagonally stayed on raw vanilla semantics while their neighbor used
  isolation, producing mismatched shared corner heights (visible slits
  along the one-cell boundary strip, part of the reported "cut off"). The
  routing scan now covers the exact consult set, so adjacent cells can
  never disagree about shared corners.

## Unreleased (2.0.0-dev) — 2026-08-16 (shadows, fluid seams, core wrap-around)

- **Entity shadows adapt to gravity.** The circle shadow stays
  world-oriented (it stands in for sunlight), but for rotated entities it
  is now an ELLIPSE centered under the model's center: the long axis
  follows the model's world-horizontal lying direction and grows from the
  vanilla radius toward half the model height as the model tips over — a
  player on a wall shades a person-length strip of ground under their
  body; upright and upside-down players keep the vanilla-size circle
  (centered under the body either way). Faithful port of the vanilla
  shadow pass with per-corner elliptical UVs and the fade referenced to
  the model's lowest extent.

- **Fluid rendering: no more transparent gaps at gravity boundaries.**
  Cells rendered in different gravity frames each culled faces and
  averaged corner heights assuming their neighbors shared their frame —
  at a world-down/rotated boundary each side culled faces the other never
  covered. Cross-frame neighbors are now treated as containing no fluid
  for every fluid-derived render decision (face culling, same-fluid
  merging, corner-height averaging, full-column checks), so both sides of
  a boundary render complete closed surfaces that visually meet (vanilla's
  0.001 face insets prevent z-fighting). Vanilla-framed cells adjacent to
  a rotated cell route through the ported renderer's identity basis;
  scenes with no rotated fluid never leave the vanilla path.

- **Liquid wraps all the way around gravity cores.** On the integer
  lattice every corner cell around a core sits at an exact 45-degree tie
  between two cardinals, and vanilla's `Direction.getNearest` breaks ties
  by a fixed axis order — so wrapping flow always stalled at half the
  corners (the observed "only flowed out to two faces"). Core fluid
  gravity now applies a small deterministic tangential bias that resolves
  every equatorial tie in one consistent rotational sense, letting liquid
  circulate around all faces; the bias is far too small to affect
  non-tied cells, and pole ties keep their downward preference.

## Unreleased (2.0.0-dev) — 2026-08-16 (gravity fluids: test feedback round)

- **Gravity plating is waterloggable.** Plates are thin panels sharing
  their cell with fluid; a water source no longer destroys them (placement
  into water waterlogs the new plate, buckets work on placed plates).

- **Rotated fields can no longer manufacture permanent water sources.**
  The vanilla infinite-water rule (two source neighbors over solid ground
  form a new source) ran in the rotated frame and its products are
  PERMANENT blocks — removing the gravity source left walls studded with
  water sources. Source conversion is now disabled entirely inside rotated
  fields: fields move water, they never create it.

- **Fluids flow back to normal when a field is removed or changed.**
  Settled fluid has no pending ticks, so a wall-pinned puddle used to
  freeze in its impossible shape when its field disappeared. Breaking a
  plating/core/normalizer (or removing a plate side) now drops the source
  from the fluid-gravity registry immediately and wakes every fluid block
  in its former range so it re-settles under current rules; changing
  settings through the GUI does the same (covering polarity/direction/range
  changes, including apply-to-connected). Item-shortcut tweaks (amethyst /
  echo shard) don't trigger the wake — use the GUI for live re-settling, or
  poke the water with a block update.

- **Rotated fluid rendering.** The liquid renderer now draws
  gravity-affected fluid cells in the field's frame: the surface sits
  perpendicular to the field's down, side faces and corner heights follow
  the rotated column, and the flowing-texture direction tracks the actual
  (gravity-aware) flow vector — a stream toward a core now looks like it
  flows toward the core.

## Unreleased (2.0.0-dev) — 2026-08-16 (gravity-aware fluids)

- **Liquids flow along gravity fields.** A water or lava source inside a
  gravity field now falls along the field's (cardinal) down, spreads across
  the plane perpendicular to it, forms falling columns along it, converts
  to new sources in the rotated frame, and pushes entities along the
  rotated current. Works for water, lava and any modded fluid extending
  `FlowingFluid`.
  - Implementation: every direction-sensitive site in the vanilla fluid
    engine (`Direction.DOWN`/`UP`, `below()`/`above()`,
    `Plane.HORIZONTAL` — verified exhaustively against the Forge
    1.20.1-47.4.16 bytecode, including the slope-search lambda) is rewired
    through a new block-position gravity query; `getFlow` is replaced
    wholesale under rotated gravity because its vanilla accumulator only
    tracks X/Z steps and would silently drop the Y components of a
    sideways spread plane. Outside fields every hook calls the vanilla
    original verbatim — behavior there is bit-identical.
  - New `util/GravityFieldLookup`: plating sides (primary column only,
    never the hidden bleed), cores (radial, snapped to cardinals) and
    normalizers (zone, ship-clamped) re-register every tick into a
    self-expiring per-level registry; the dominant source by priority
    (normalizer &gt; plating &gt; core), then proximity, answers each query.
    SAME-GRID only: ship fields bend ship fluids (in shipyard space, where
    ship fluids simulate anyway); cross-grid influence is out of scope.
  - Config: `gravityAffectsFluids` (server-synced, default true).
  - Known visual limitation: falling streams render correctly (falling
    fluid draws full cells), but thin spread layers on walls/ceilings
    still render their surface world-oriented — the fluid RENDERER is
    untouched; a rotated liquid renderer is its own future project. The
    simulation underneath is correct.

## Unreleased (2.0.0-dev) — 2026-08-16 (sticky chest fixes, second round)

- **Sticky chest yaw on rotated ships — real root cause found and fixed.**
  Valkyrien Skies' own block_placement mixins wrap `BlockItem.place` in
  `PlayerUtil.transformPlayerTemporarily`: for ship placements, while
  `getStateForPlacement` runs, the player's rotation fields have ALREADY
  been rewritten to ship-grid look angles (derived from the true world
  look, which includes this mod's gravity frame) and their position to
  shipyard coordinates. Both previous spin derivations therefore
  double-rotated: the look-based one re-applied the gravity frame on top of
  the already-grid-space angles, and the position-based one ran the
  already-shipyard position through the world-to-ship transform again. The
  spin now uses the raw rotation fields verbatim during ship placement
  (exactly what VS prepared) and the gravity-frame-aware world look off
  ships. The full pitched look is used — the spin candidates are
  perpendicular to the bottom face, so the dot product inherently projects
  out the bottom component (wall placements looking "up the wall" work).
  The bottom face was always computed from the frame quaternion (untouched
  by VS's temporary transform), which is why up/down was correct in every
  round. `GravityBlockHelper.worldPositionToGrid` carries a prominent
  warning about VS's temporary placement transform.

  An earlier entry here attributed the failure to client/server frame-twist
  divergence — that diagnosis was wrong and is superseded by the above.

## Unreleased (2.0.0-dev) — 2026-08-16 (sticky chest fixes)

- **Sticky chest orients correctly on rotated Valkyrien Skies ships.**
  Placement orientation was computed as world-space cardinals, but a ship
  block's state lives in the SHIPYARD grid — correct only while the ship's
  rotation was identity. The placer's gravity down and facing are now
  carried as world-space vectors, re-expressed in the target block grid
  (world → ship transform), and only then snapped to grid cardinals; the
  spin is chosen by best-alignment against the facing vector so it stays
  robust when the facing lands between ship-grid cardinals. New grid-aware
  framework API: `GravityBlockHelper.worldDirectionToGrid`,
  `gravityDownVector`, `placementFacingVector`, and a ship-aware
  `placementDown(entity, level, pos)` — this is the pattern every Gravity
  Block Framework block should use for placement.

- **Sticky chest item looks like a chest.** Chest geometry lives in entity
  textures, so a JSON model cannot draw it — the item now uses the vanilla
  chest item's mechanism: a `builtin/entity` model (with the vanilla chest
  display transforms) plus a custom item renderer that draws a closed
  sticky chest through the block-entity render dispatcher.

## Unreleased (2.0.0-dev) — 2026-08-15 (thirteenth pass: twelfth-pass test feedback)

All items from the first in-game test of the twelfth pass.

### Fixed

- **Crouching is no longer forcibly cancelled near block edges.** Vanilla's
  pose-fit check (`canEnterPose`) tests the stored bounding box — in capsule
  mode that is the loose world-aligned ENVELOPE, which pokes into the very
  floor the player stands on the moment the frame tilts (field blends near
  edges tilt it a few degrees). Vanilla concluded no pose fits and cleared
  the crouch state. Pose fits are now tested against the actual capsule.

- **Projectiles no longer rubber-band under rotated gravity** (arrows,
  tridents, eggs, ender pearls, xp bottles). Two causes, both fixed:
  (1) server motion packets carry WORLD-space velocity, but deltaMovement is
  interpreted in the entity's LOCAL frame — the raw write made the client
  dead-reckon flight along the wrong axes between position packets, and each
  position packet yanked the projectile back (the violent back-and-forth);
  `lerpMotion` now converts into the entity's frame, and the instant
  frame-adoption on first sync re-expresses the spawn velocity so world
  momentum is preserved. (2) entering a field used to ROTATE existing
  velocity with the gravity change (one sharp turn — the "pearl stops all
  its momentum" feel); field sources now conserve world momentum and only
  redirect future acceleration.

- **Gravity strength actually works now — through Forge's gravity
  attribute.** Forge's patched `LivingEntity.travel` reads its gravity from
  the `forge:entity_gravity` ATTRIBUTE; the 0.08 constant the old
  ModifyConstant hook scaled is loaded and immediately overwritten, so
  strength (and the gradual-falloff feel) never changed the real pull for
  living entities. The capability now maintains a transient MULTIPLY_TOTAL
  modifier on that attribute from its computed strength — gradual cores are
  genuinely orbit-able: the pull fades with distance. The transition pull
  reads the same attribute (so slow falling and strength compose exactly).

- **Spawned entities adopt field gravity instantly.** Spawn-egg mobs,
  fireworks and thrown items used to spawn upright and rotate a few moments
  later (effect latency + snap hysteresis + the 3-tick opposite-flip
  stability). Freshly spawned non-player entities now bypass the hysteresis
  and snap their frame to the field on their first influenced tick — a
  firework placed on the relative ground under inverted gravity fires along
  the local up immediately instead of phasing into the block above.

- **Tall-thin-tower edge standing inside core fields.** The support hold
  released only under active repulsion (dot &lt; -0.1), so a face the field is
  nearly PERPENDICULAR to — the side of a 1x1 tower along the field's pull —
  still counted as standable ground. Support now requires the field to
  endorse the face at least slightly (dot &gt; 0.15): 45-degree planet faces
  and edge blends keep working, tower sides release.

### Added

- **Per-block gravity acceleration.** Plating sides, cores and normalizers
  now carry a configurable gravity acceleration (default 0.08 = vanilla),
  applied through the strength pipeline (living entities via the Forge
  attribute, projectiles via their scaled gravity constants). Full Field
  applies it uniformly; Gradual Field fades it with distance.

- **Per-block surface-snap toggle** (plating and cores): disables
  planet-walk surface snapping for entities under that field — gravity then
  follows the raw field vector only. The normalizer is uniform by design and
  has no snap toggle.

- **Settings GUI.** Right-clicking a plating side, core or normalizer with
  an empty hand opens a settings screen (polarity, range, falloff, surface
  snap, gravity acceleration, field visualization — plus local down for the
  normalizer), applied via a validated server packet. Plating has an "apply
  to connected plates" option that copies the settings to every in-plane
  connected plate with the same facing. The amethyst/glow-ink/echo-shard
  item shortcuts still work.

- **Gravity Normalizer: ship containment.** On a Valkyrien Skies ship the
  normalizer's zone is clamped to the ship's actual block extent (plus a
  1-block skin so crews on hull surfaces stay inside): a range larger than
  the ship cuts off at the hull, the field can never leak into the non-ship
  world, and building onto the ship dynamically extends the field (the
  ship's block AABB grows as blocks are placed). World-placed normalizers
  are unchanged; cores and plating are unchanged.

- **Sticky Chest — first full Gravity Block Framework example.** A chest
  placeable in any of the 24 grid orientations (it orients to the placer's
  gravity: upside down, sideways on walls, any spin) that opens, animates
  and stores 27 slots like a vanilla chest — the working demonstration of
  the framework's `Rotation24` + placement-orientation design.

- **Inventory paper doll stays upright and centered.** GUI entity rendering
  (the inventory player model) no longer applies the gravity model rotation,
  so the doll cannot stick out of its portrait box over other GUI elements.

## Unreleased (2.0.0-dev) — 2026-08-14 (twelfth pass: full-codebase audit + roadmap features)

A full-spectrum audit of the codebase (three parallel review passes over the
physics core, the client/render layer, the field blocks/network layer, and
every small entity mixin) followed by a fix pass for everything found, plus
the first roadmap feature drop.

### Added (roadmap)

- **Falloff modes for gravity fields (Full / Gradual).** Every plating side
  and every gravity core now has a falloff selector, toggled by
  right-clicking with an **echo shard**: *Full Field* (default, unchanged
  behavior) applies the same force everywhere in the field; *Gradual Field*
  weakens the force linearly with distance from the source, reaching zero at
  the field edge. Falloff affects FORCE only — orientation is never scaled,
  so a player at the edge of a gradual field is still fully oriented by it
  and only reverts once they actually leave the field (exactly the roadmap's
  "Gradual Field Behavior"). Internally this is a per-effect strength scale
  blended with the same priority weights as the direction, so overlapping
  gradual/full fields compose sensibly; gradual cores also scale the force
  they apply to Valkyrien Skies ships.

- **Gravity Normalizer block.** Defines what "down" means inside a cubic
  zone (default half-extent 8, upgradeable with amethyst clusters to a
  configurable max). The chosen down direction is GRID-local: on a Valkyrien
  Skies ship the zone and its gravity rotate with the ship, so crews
  experience natural ship-relative gravity through any maneuver. Empty hand
  cycles the local down through all six directions; sneak + empty hand
  shrinks the zone (amethyst refunded); glow ink sac toggles the field
  visualization. Its field is uniform and sits above plating/core priority
  inside the zone, so a normalized room stays normalized even where exterior
  plating fields bleed in.

- **Gravity Block Framework — API foundation.** New
  `api/GravityBlockHelper` (local↔world grid-direction mapping,
  gravity-correct placement down and horizontal facing for other mods'
  placement code) plus `docs/GravityBlockFramework_Design.md` capturing the
  full sticky-block wrapper architecture (the 24-orientation wrapper BE
  system) as the next major milestone. The wrapper system itself is design
  only for now — deliberately not stubbed.

### Fixed — transition smoothness / physics core

- **Ship motion no longer contaminates the surface probes.** The per-tick
  movement delta that drives the convex-wrap and concave-adoption probes
  included the ship CARRYING the player (Valkyrien Skies repositions dragged
  entities every tick). Standing still on a moving deck therefore read as
  fast tangential movement — firing the edge probes spuriously (random face
  adoptions from deck clutter) — and a descending ship read as "falling away
  from the held face", releasing the surface hold mid-ride. The ship's own
  carry at the player's position (current vs previous-tick ship transform)
  is now subtracted before any probe logic runs, kept alive through jumps
  via the last-stood-on ship.

- **Slow (crawl-speed) convex edge traversal wraps correctly** — the wrap
  probe's speed gate is lowered 0.02 → 0.01 now that the ship-carry noise it
  partly guarded against is gone.

- **Transition pull matches slow falling.** The transition-pull correction
  (which redirects gravity toward the target frame during rotation) assumed
  the full 0.08 gravity; with slow falling active it now uses the real 0.01,
  so slow-falling players in fields are no longer yanked sideways harder
  than gravity actually pulls.

- **Water/lava currents push in the right direction.** Vanilla accumulates
  the fluid flow push in world space and adds it straight onto the local
  velocity vector; under rotated gravity the push landed on the wrong axes
  entirely. The push is now re-expressed through the entity's movement frame.
  Note: Forge's fluid-API patch moves the real push logic out of the
  vanilla-named method into a per-fluid-type lambda of its
  `updateFluidHeightAndDoFluidPushing(Predicate)` overload — the wrap targets
  that lambda (verified against 1.20.1-47.4.16 bytecode) with `require = 0`,
  so a future Forge build that renumbers lambdas falls back to vanilla
  behavior instead of crashing at launch.

- **Fire/cobweb/berry-bush/portal contact matches the real capsule.** In
  capsule mode the stored AABB is a loose world-aligned envelope (up to a
  block larger than the body when tilted); the inside-block check iterated
  it, so tilted players burned in fire or stuck in cobwebs they were nowhere
  near. The check now only visits cells the capsule's spheres actually
  overlap.

- **Vanilla sneak edge-backoff disabled for ALL capsule players.** It was
  only disabled for non-DOWN cardinals, so tilted-frame players whose
  cardinal was still DOWN ran the box-based backoff against the capsule —
  arbitrary movement clamps felt as hitches while sneaking on rotated
  surfaces.

- **Gravity-direction potions/effects work again.** The effect lookup map
  was filled with six unregistered orphan instances; `getEffect()` is keyed
  by instance, so every lookup missed and the potions silently did nothing.
  The registered instances now self-register into the map.

- **Invert-gravity effect no longer oscillates.** It inverted the CURRENT
  direction each tick — after the flip committed it inverted the inverted
  result, a permanent flip-flop. It now inverts the base direction.

- **Config moved to SERVER type (was COMMON).** Field strength/range and
  artificial-gravity settings feed gravity computation on both sides (the
  local player computes its own gravity from fields client-side); COMMON
  configs are never synced, so any client/server mismatch was a genuine
  physics desync. SERVER configs sync on login. Note: the config file moves
  to the per-world serverconfig folder.

### Fixed — client/render layer

- **Surface swimming works under non-default gravity** — the swim-surface
  probe offset was fully sign-inverted (only correct for DOWN gravity), so
  the surface-swim boost never fired for rotated players.
- **Suffocation overlay renders again under rotated gravity** — the overlay
  scan overwrote every hit with null instead of returning on the first
  view-blocking block.
- **Nametags billboard correctly** on tilted ships and through transitions
  (they now use the same smooth interpolated frame as the model, instead of
  the snapped cardinal — and no longer skip compensation when the cardinal
  is DOWN but the frame tilted).
- **Explosion knockback (client), melee/shield knockback, item drops,
  elytra model roll, and the fishing line** all converted through the
  snapped cardinal frame while the quantity they feed lives in the smooth
  visual frame — each was wrong by the tilt angle on tilted ships and
  mid-transition. All now use the movement/aim/render frame with the
  matching gates.
- **Entity render pose stack is reentrancy-safe** (nested dispatcher renders
  from other mods could desync the push/pop pairing and corrupt the frame),
  and the per-entity-per-frame quaternion allocations in the render hot path
  are gone (new allocation-free `getRenderRotation(partialTick, dest)`).
- **Dead code removed:** the legacy `RotationAnimation` snap-animation
  system (unused since the continuous-frame architecture), the empty
  `RemotePlayerEntityMixin`, and the disabled bodies in the push-out /
  sneak-backoff handlers.

### Fixed — field blocks / performance

- **Gravity cores ignore stale client rollbacks** — the same
  `dataInitialized` latch plating already had: a client BE resurrected by a
  rejected break prediction no longer applies a phantom default field.
- **Corner auto-jump computes in one frame on ships** — it dotted a
  ship-local offset against a world-frame gravity vector and double-rotated
  the gravity component of the kick; everything is now composed ship-local
  and rotated to world once (also the orthogonality test).
- **Amethyst refunds can't be destroyed by a full inventory** (plating
  level-down, core/normalizer range-down) — overflow drops at the player.
- **Entity queries halved:** plating, cores and normalizers reuse their
  entity query result for one extra tick (phase-staggered by position), with
  per-tick position tests unchanged — field ENTRY can lag at most one tick,
  which the field-grace machinery already absorbs. This was the dominant
  per-tick cost of large plated builds.

### Fixed — entity/projectile mixins (audit findings)

- See the audit reports: wither skull aim (copy-paste returning X for Y/Z —
  broken for ALL targets, even vanilla-gravity ones), villager item throws
  (velocity applied to the villager instead of the item), unregistered
  BoatMixin/ItemEntityMixin, scaffolding/powder-snow isAbove math, mob melee
  knockback frame, projectile inherited shooter momentum, arrow gravity
  strength mismatch, mob ranged-attack arc compensation gated on the wrong
  entity, XP-orb attraction frame, projectile spawn position for rotated
  shooters, move-packet clamps + movement-frame conversion (the rising-flag
  baseline was verified vanilla-correct and left as-is), area-effect-cloud
  particle drift + per-particle overhead, llama spit gravity direction, pathfinding
  floor-level/step-clearance math, GravityBlockPos tables contradicting
  RotationUtil, sync-packet entity lookup cost, `noAnimation` latch after
  respawn sync, death-clone dropping base gravity strength, field-visual
  ghosts across world changes, multi-core force races, and the unused
  broadcast-to-all network path.

### Fixed

- **Capsule mode no longer "catches on nothing" on flat ground (the big
  one).** Whenever the visual frame sits even a degree or two off cardinal
  (field blends near plate edges, ships, any mid-transition frame), the
  ground legitimately shaves sin(tilt)·speed off the tilted movement vector
  every tick. The per-axis collision test treated ANY opposing correction as
  a collision, so vanilla zeroed that axis's velocity every single tick —
  felt as constant catching/stuttering on flat ground, being stuck on convex
  cube edges until backing out, jitter at concave corners, and no ice
  sliding. A glancing clamp (less than 10% of the intended axis motion) now
  passes through as a non-collision; real obstacles eat a large fraction of
  the motion and still register.

- **Anti-creep pins stand down when they would fight real movement.** The
  static/directional pins (which stop downhill creep on tilted decks) no
  longer run (1) during a surface transition — their input frame is
  half-rotated there and the "creep" they removed was the genuine transition
  momentum (stuck at corners/edges), (2) on slippery blocks — ice gliding in
  capsule mode works again, or (3) against reversal momentum — turning
  around now decelerates through friction like vanilla instead of stopping
  dead. The idle velocity brake got the same slippery/transition gates.

- **Mobs on plated ships: collision flags are now computed like players'.**
  The legacy non-player path re-rotated the collide result through the
  frame, so Valkyrien Skies' per-tick ship adjustments (and rotation noise)
  registered as collisions every tick — phantom onGround, per-tick velocity
  zeroing, and MoveControl reacting to junk: bouncing/hopping on flat plated
  ships, "stuck then suddenly launched" at 90 degrees. All entities now use
  the bit-exact stash/restore with per-axis semantic resolution (with the
  correct entity-convention inverse at settled cardinals, which differs from
  the player convention for UP gravity).

### Known limitations (next major work items)

- Mob pathfinding is world-grid based and cannot see Valkyrien Skies ship
  blocks (a base VS limitation): mobs standing on a ship path toward
  terrain targets — walking off the deck edge, or standing idle when no
  node is reachable (upside-down). Needs ship-aware navigation.
- Mobs still collide as cardinal-aligned boxes; on a 45-degree ship the box
  geometry cannot match the deck, so mob movement there stays rough. The
  clean fix is extending capsule collision to mobs (see roadmap).

## Unreleased (2.0.0-dev) — 2026-07-18 (tenth pass)

### Fixed

- **Gravity no longer cuts out at cube edges ("sometimes I fall off the first
  face before reaching the second").** The corner pocket just past a cube edge
  lies outside BOTH adjacent faces' primary field columns — it is covered only
  by their hidden sideways bleeds, and bleed-only regions count as "no field"
  (so that standing beside a plate on plain ground is unaffected). Crossing the
  pocket slowly (or pausing on the edge) let the short field grace expire
  mid-transition, which reset every surface hold and snapped gravity back to
  world-down — the player simply fell off the face they were standing on.
  While surface-walking (a face is held, was just released, or a face change
  is committed), bleed-only blends now sustain the field until the next face's
  primary takes over; the plain-ground protection is unchanged.

- **Concave floor→wall corners are no longer "walking through honey".** Two
  causes, both in the concave adoption probe: (1) the probe was aimed by the
  ACTUAL movement tangent, which is exactly useless at a wall — blocked to
  zero head-on, and sliding parallel to the face on any angled approach — so
  the flip only fired if movement happened to die below a tiny threshold; the
  probe is now aimed by the INPUT direction whenever the player is steering.
  (2) The "field endorses the wall as up" gate (0.35) failed near the bottom
  of a wall, where a large plated floor dominates the blend — relaxed to 0.2
  (unplated walls contribute nothing to the field and still never pass). The
  honey feel was the player pressed into the corner under old-face gravity,
  creeping until the flip finally fired.

- **Mobs no longer shoot off moving plated ships.** The ninth pass registered
  mobs with Valkyrien Skies' positional drag (fixing "mobs phase through
  moving ships") — but the plating's ship-grip friction still pushed each
  mob's velocity toward the ship SURFACE velocity, which is only correct for
  undragged entities. Dragged mobs were carried twice (position drag + ship
  speed velocity) and slid straight off any moving or rotating ship. The
  friction now damps the mob's own tangential velocity only.

- **Mobs on tilted-frame fields get bit-exact collision flags.** Mobs standing
  in a plated field on a VS ship (never exactly level) ran their movement
  through a lossy local↔world quaternion round trip; the 1e-17 noise trips
  vanilla's exact-double verticalCollision compare every tick — phantom
  onGround and per-tick vertical-velocity zeroing, so gravity never really
  pressed them onto the deck (the same bug class fixed for players in v5.1).
  The bit-exact stash/restore and per-axis semantic collision resolution now
  apply to every entity whose frame is not settled on a cardinal; the exact
  cardinal switch-math path for settled mobs is unchanged.

## Unreleased (2.0.0-dev) — 2026-07-17 (ninth pass)

### Fixed

- **Momentum is no longer fabricated by the rotating frame (the big one).**
  Player velocity is stored in the local gravity frame, so whenever the frame
  rotated (chasing a new surface or returning upright), the WORLD-space
  velocity silently rotated with it. This single mechanism caused three
  reported bugs at once: momentum "carrying over way too far" across cube
  faces (it was rotated twice — once implicitly by the frame, once by the
  explicit face-change rotation), the floor→wall concave transition failing
  (the deliberate up-the-wall velocity conversion was immediately swung back
  off the wall by the frame chase — the "lift up then drop back" jitter), and
  the launch when sliding off a spinning contraption (the built-up velocity
  was swung through world space as the frame chased upright — free energy).
  World velocity is now invariant under the frame chase; the explicit
  face-change rotation is the single owner of momentum redirection. Creative
  flight keeps the old frame-following behavior (it is what closes orbits
  around gravity cores).

- **Walking off one plated face onto another now snaps you onto the new face.**
  When the edge-wrap probe missed (running speed, shallow approach), the old
  face was released, a few ticks of falling built velocity, and the new face
  was acquired WITHOUT a momentum rotation — the fall velocity stayed
  tangential to the new face and slid the player far along it, or straight
  past it and down to the ground. A just-released face is now remembered for
  15 ticks; catching a different face inside that window counts as a full face
  change (committed + momentum rotated around the edge), gated on the field
  actually endorsing the new face as up.

- **Floor→wall (concave) plated corners are now walkable.** The concave
  adoption probe was driven by last tick's ACTUAL movement — but walking into
  the wall zeroes exactly that movement, so the probe that was supposed to
  rotate you onto the wall could never fire once you touched it. While pressed
  against an obstacle, the probe now falls back to the player's INPUT
  direction (what they are trying to do), so walking into plated walls rotates
  you up onto them. Combined with the momentum fix above, floor→wall→ceiling
  walking works in both convex and concave directions.

- **No more sliding downhill while WALKING on shallow ships in capsule mode.**
  The eighth-pass static pin only engaged when idle; while moving, the
  downhill gravity creep on a tilted deck reaches an equilibrium comparable to
  walk speed — players slid downward no matter which direction they walked.
  The pin is now directional: the tangential movement component along the
  player's input direction is kept, the residue (the creep) is removed. Real
  pushes (knockback, pistons) still pass through.

- **Standing still on moving/rotating ship plating no longer drifts.** While
  idle, grounded on a ship in capsule mode, the feet are anchored to a fixed
  SHIPYARD-space point and re-pinned every tick — drift-free on rotating
  contraptions by construction (the anchor rotates exactly with the ship), and
  the tangential velocity is stripped while pinned so nothing accumulates to
  discharge as a launch. Any input, jump, knockback or ground change releases
  the anchor instantly.

- **Mobs no longer slide to the corners of plated cubes / off plated
  platforms.** The static-friction pin was capsule-players-only; mobs under
  blended fields have a permanently tilted pull whose tangential component
  vanilla friction only slows to a constant creep. Idle grounded mobs under
  any non-default gravity frame now get the same tangential brake.

- **Mobs no longer phase through moving plated ships.** Under rotated gravity
  our collision mixins change the collide result, which tripped Valkyrien
  Skies' "movement changed → wipe ship-standing state" heuristic every tick —
  mobs were never registered as standing on the ship, so a moving ship slid
  out from under them. Grounded non-player entities inside a plated field on a
  ship are now registered with VS's dragging every tick (the same thing the
  capsule path already did for players).

- **Capsule mode can no longer stay stuck on ("the 3 spheres never go
  away").** Three layers: (1) the visual-default check now treats the negative
  quaternion identity as identity (q and −q are the same rotation; long
  transform chains could converge onto −identity and latch capsule mode on
  forever), (2) a watchdog force-exits capsule mode — with exact look/velocity
  compensation, nothing visibly jumps — if it persists for 2 seconds with zero
  gravity influence (no field, no grace, no held surface, default base, not
  riding), and logs a warning so the next report tells us which path caused
  it, (3) the sticky state was what made "capsule mode on a plain ship slides
  me around" possible at all; with the exit guaranteed, normal ships get
  vanilla-VS behavior again.

- **Gravity field visuals no longer read one block too long.** The effect
  range counts the plate's own block cell (a level-64 field reaches 64 blocks
  from the plate's mounting face), but the glow-ink visual extended the full
  range PAST that cell — a 64 field drew 65 blocks. The visual now matches the
  real primary field box exactly.

## Unreleased (2.0.0-dev) — 2026-07-16 (eighth pass)

### Fixed

- **Multiplayer: the remaining on-ship freeze (visible to other players).**
  Valkyrien Skies replaces vanilla position broadcasts for ship-dragged
  entities with its own ship-relative packets, and other clients position the
  player from those via a lerper — the whole pipeline stalls the moment the
  SERVER-side player position stops updating. The last way that could happen:
  the server's movement acceptance forgives a replay mismatch only if the
  player's bounding box was ALREADY colliding with something — and a capsule
  player's envelope often sits in open air (standing upside down under ship
  plating), while gravity snapping on a ship guarantees a mismatch window
  (client and server ship transforms lag differently). The moment gravity
  snapped, every packet fell into the reject/teleport branch: server position
  froze, VS's broadcast replacement went silent, and observers saw the player
  frozen mid-air with only head rotation updating. Capsule players' reported
  positions are now always accepted (mirroring VS's own dragged-entity
  exemptions — the capsule replay and VS's server-side drag keep them sane).

- **No more sliding off ships (or any slope) while idle in capsule mode.**
  A sphere resting on a tilted surface re-gains a small downhill gravity
  component every tick before friction can react, so capsule players crept
  off ship decks at any angle. Idle grounded players now get a true static
  pin: the small tangential part of the movement is removed in the collision
  step itself (like a box resting on a slope), which also zeroes the
  corresponding velocity through the vanilla collision flags. Walking input,
  jumps and real pushes (knockback etc.) pass through unchanged.

- **Ship yaw-follow is now smooth (no more jittery camera).** The seventh-pass
  camera correction ran one tick after Valkyrien Skies applied its (wrongly
  signed for rotated frames) yaw step — the two alternated every tick, a yaw
  sawtooth felt as heavy jitter on moving ships. The frame-projected yaw
  (delta x frameUp.worldUp) is now applied in one step inside VS's own drag
  pass, same phase, via wraps on its yaw writes. Upright players see exactly
  vanilla VS behavior.

## Unreleased (2.0.0-dev) — 2026-07-16 (seventh pass)

### Fixed

- **Multiplayer: players on gravity ships no longer freeze/float for other
  clients.** After replaying a movement packet, the server only accepts the
  new position if the player's bounding box doesn't newly collide with
  anything. In capsule mode that stored box is a loose world-aligned envelope
  that legitimately overlaps ship geometry (Valkyrien Skies feeds ship shapes
  into the collision query), so the server silently rejected EVERY packet and
  teleported the player back: the server-side position froze, remote clients
  saw the player floating behind the moving ship, while the on-ship client
  looked normal locally (their ship drag instantly re-applied). The envelope
  collision test is now skipped for capsule players — the capsule replay has
  already resolved real collisions at that point.

- **Cube edge traversal (plating and core).** Three combined fixes:
  - Momentum is now rotated across a surface change: stepping off the top
    face used to keep the built-up fall velocity pointing old-down, which is
    tangential to the side face — players slid straight past it before the
    0.08/tick field pull could catch them. The velocity now turns with the
    face (old-normal → new-normal arc), so walking around an edge keeps
    walking speed pressed onto the new face.
  - Ground/support classification during a transition now uses the ADOPTED
    surface normal instead of the raw blended field (which is diagonal at
    edges and kept promoting contacts back onto the OLD face) — this was the
    "stuck at every edge for 1–2 seconds while the hitbox rotates" pin.
  - The frame turns faster (30°/tick, firmer proportional gain) during a
    committed surface change, and the stale-face grace is released almost
    immediately when FALLING away from a face with nothing under it (the
    long grace is only needed for jump ascent).

- **Footstep sounds under gravity cores.** Step sounds require the "block I
  stand on" probe to find a non-air block, and that probe (`getOnPos`) was
  still straight world-down — air, when standing on a wall or ceiling face.
  Plated cubes only worked by accident (the feet cell contains the non-air
  plating block itself). The probe now follows the gravity frame like the
  other ground probes.

- **Camera no longer counter-rotates on rotating ships.** Valkyrien Skies
  yaw-follow adds the ship's world yaw delta directly to the player's yaw,
  but yaw is frame-relative under rotated gravity: stood upside down the
  same delta turns the camera the OPPOSITE way. The correct local delta is
  the ship's yaw projected onto the frame's up axis; the difference is now
  applied each tick (upright players are untouched — exactly vanilla VS).

- **Plating fields no longer affect entities standing beside them.** Plate
  effect volumes keep their hidden ~1-block sideways bleed (it's what blends
  gravity smoothly at cube edges), but the bleed is now a SECONDARY
  contribution: it only participates when some primary field (a plate's own
  footprint-plus-range column, or a core) also applies. Standing on plain
  ground one block next to a plate no longer flips gravity or switches the
  capsule hitbox on — which was also the main way the capsule "stuck around"
  glitchily after leaving a field.

- **Sneaking on ships in capsule mode.** VS's sneak edge-protection
  (`EntityDragger.backOff`) assumes a vanilla world-down player box and slid
  capsule players around (e.g. slowly uphill on a tilted deck). It is now
  skipped while the capsule owns collision.

## Unreleased (2.0.0-dev) — 2026-07-16 (sixth pass)

### Fixed

- **Breaking gravity plating on a Valkyrien Skies ship now reliably removes
  the field.** The server's block-break reach check compares its own idea of
  the player's eye position against the block; on a moving ship the
  server-side player position lags behind the ship, so the check
  intermittently failed — and Forge's "too far" branch drops the break
  SILENTLY (no corrective packet). The client's break prediction then got
  rolled back into a fresh plating block whose block entity had default data
  (attracting, level 1, visual off): the plate looked broken, but an invisible
  gravity field kept applying — which also kept the sphere-capsule collision
  mode alive and glitching on plain ground ("can't touch walls without
  getting stuck"). Two-part fix: (1) a new `ServerPlayerGameMode` mixin widens
  only the reach decision for plating on ships with a generous ship-aware
  distance, so the break flows through the normal vanilla path; (2) a
  client-side plating block entity that never received authoritative server
  data (a prediction artifact) no longer applies fields or visuals at all.

- **Ship drag state now survives jumps in capsule mode.** The fifth-pass fix
  protected the "standing on ship" info while grounded; mid-air (jumping
  across a moving deck) a capsule brush against geometry could still trigger
  Valkyrien Skies' wipe and lose the drag mid-jump. The escape hatch is now
  also raised on airborne ticks while VS still considers the player
  ship-dragged; landing on world ground deliberately skips it so VS's normal
  hand-off still occurs.

## Unreleased (2.0.0-dev) — 2026-07-15 (fifth pass)

### Fixed

- **Ships move with the player again when standing on their gravity plating.**
  Valkyrien Skies' collision wrapper clears its "standing on ship" state
  whenever the collision result differs from its own ship-adjusted movement —
  and the capsule legitimately changes the movement every grounded tick, so
  the ship-standing info was wiped the moment we set it: the ship slid out
  from under a standing player, while hovering just above the plate (movement
  untouched, nothing wiped) still dragged correctly. The capsule now raises
  VS's own `ignoreNextGroundStand` escape hatch each ship-grounded tick, which
  makes the wrapper skip exactly that wipe.

## Unreleased (2.0.0-dev) — 2026-07-15 (fourth pass)

### Fixed

- **Valkyrien Skies no longer collides the player's box AABB against ships in
  capsule mode.** Found by inspecting the VS jar: VS wraps the collision call
  inside `Entity.move` and applies its own entity-vs-ship collision to the
  result using the entity's stored AABB — which for capsule players is only a
  loose world-aligned envelope of the rotated capsule. Its corners hit ship
  geometry the spheres were nowhere near: players floated above plating on
  rotated ships, and ship walking stuttered as the two systems fought. VS's
  adjustment is now skipped while the capsule owns collision (the capsule
  already collides ships exactly, against their real blocks in shipyard
  space). This was the "AABB corner still collides with ships" observation —
  vanilla block collision was already fully replaced; this VS path was the one
  remaining consumer of the AABB.

- **No more clinging to walls / getting held up at wall block seams.** The
  capsule's internal-edge smoothing promoted contacts onto any block face that
  pointed "up" — including the buried top faces at every seam of a stacked
  wall, which produced a small upward push per contact: jumping against a wall
  while holding forward stuck to it instead of sliding down, and walking near
  walls stuttered "on nothing". Support promotion now only considers faces
  that are actually exposed (bordering air), so floors stay smooth and walls
  behave like walls.

- **Standing on a surface now always keeps you on that surface (support-first
  alignment).** Gravity plate fields extend a hidden ~1 block sideways and
  their full range outward; standing on plain ground inside such a sideways
  field used to yank the frame toward the plate — this is what made the sphere
  hitbox "stay after leaving the field", walking feel stuck on nothing, and
  1-block ledges impassable (the player was inside an invisible field the
  whole time). The surface being stood on now wins over the field unless the
  field actively opposes it (repulsion still launches); fields orient the
  player when airborne, and walls/ceilings are still adopted through the
  explicit corner transitions. Jump grace was extended so small hops inside
  fields never wobble alignment.

## Unreleased (2.0.0-dev) — 2026-07-15 (third pass)

### Changed

- **Flight feedforward / orbit assist reverted** (per feedback): the camera
  chase is back to the plain proportional controller from before — the slight
  outward drift while circling a core in creative flight is accepted in
  exchange for zero glitchiness.

### Fixed

- **Gravity now pulls toward the TARGET surface immediately, instead of along
  the still-rotating camera frame.** This was the real cause of sliding down
  90-degree plated ship walls: vanilla gravity accelerates along the frame's
  local down, and the frame takes up to a second to rotate onto a wall — for
  that whole second the pull still had a world-down component, so the player
  slid down and off the ship at a tilt-dependent speed. (Not a Valkyrien Skies
  sliding system: in capsule mode VS entity collision is bypassed entirely —
  the sliding force was our own lagging gravity.) A per-tick correction now
  makes the net pull equal the chase target's direction from the first tick;
  it is exactly zero once aligned.

- **Face-to-face transitions now COMMIT.** After any change of the held
  surface (walking over a cube edge, floor→wall, wall→ceiling), the choice is
  locked for 8 ticks while the frame finishes rotating, and the held face is
  kept alive even when the ground probe briefly misses. This kills the
  remaining oscillation by construction — the "camera snaps back and forth
  between the two faces and I can never move forward" deadlock at plate-field
  boundaries was the two faces alternately winning the probe while the
  half-rotated frame scrambled the controls.

- **Walking from floor plating onto wall plating no longer launches the player
  into the air.** The legacy inner-corner auto-jump (built for instant
  cardinal snaps) kept its trigger condition true for many ticks during the
  new smooth transition and stacked its 0.4-block hop every tick. It is now
  disabled for capsule players, whose frame rotation handles inner corners;
  mobs and items keep it.

## Unreleased (2.0.0-dev) — 2026-07-15 (second pass)

### Fixed

- **Right-clicking plating or a core with an unrelated item no longer shows the
  interaction message.** Only the empty hand, amethyst clusters and glow ink
  sacs interact with the blocks; everything else (spawn eggs, blocks, tools…)
  passes through to its normal behavior.

- **Breaking gravity plating in creative mode no longer drops the item.**

- **Gravity now releases when walking off gravity plating.** The surface hold
  had a self-sustaining condition (the held surface keeps the gravity cardinal
  non-default, which kept the hold active…), so gravity stayed glued to the
  plate face after leaving the field until a jump broke it. The hold now
  releases as soon as field influence ends — this also fixes the capsule
  (sphere) hitbox lingering after leaving a field.

- **No more being launched off tilted/plated ship walls.** The capsule's
  step-up assist fired every tick while gravity pressed the player against a
  surface the frame hadn't aligned to yet, teleporting them 0.6 blocks along a
  diagonal "up" per tick — an escalator whose speed depended on the ship's tilt
  angle. Step assist is now disabled during upward motion (this also fixes
  jump-jitter against walls in fields), requires the frame to agree with the
  surface being stood on, and only accepts steps that actually land on ground.

- **Walking across plate-face edges reworked.** Two explicit edge transitions
  replace the previous fall-and-reacquire behavior:
  - *Convex edges* (top of a plated cube → side): when the feet walk off a
    face, a short backward probe finds the adjacent face and flips alignment
    once, cleanly — instead of the blended diagonal field ping-ponging the
    frame between the two faces (the "stuck at the edge, camera jittering"
    loop). Works for plating and gravity cores.
  - *Concave corners* (plated floor → plated wall, plated wall → plated
    ceiling): a short probe along the movement direction adopts a blocking
    face when the field actually wants it as the new floor — walking up walls
    and onto ceilings now works instead of getting stuck pushing into them.
    Unplated walls never trigger this (the field gate rejects them).

- **Flight orbit assist.** Holding only forward while flying inside a gravity
  core's field now orbits at constant distance instead of slowly spiraling
  out: the outward radial velocity component is bled off while no vertical
  input is held. Entering a field (inward flight) is untouched, and space /
  shift still move the player out deliberately. The camera feedforward is also
  clamped instead of hard-switched, removing the occasional stutter when
  changing directions at high flight speed.

## Unreleased (2.0.0-dev) — 2026-07-15 (first pass)

### Fixed

- **Players no longer snap to Valkyrien Skies ship surfaces without a gravity field.**
  The ground probe that drives surface alignment ran even under plain default
  gravity, so standing on any tilted VS ship captured the deck's face normal,
  tilted the player's gravity frame, and pinned them to the ship face — with no
  gravity plating or core anywhere near (this also caused the glitchy "slides
  uphill" movement and the mixed AABB/sphere collision behavior on unplated
  ships). Surface alignment now only engages while a gravity field (plating,
  core, effect, anchor) is influencing the player or their gravity is
  non-default. Without fields, ships behave exactly like vanilla Valkyrien
  Skies.

- **No more sliding off plated ship walls (e.g. a 90°-tilted plated plane).**
  Standing-still friction used to brake only the frame's local x/z axes; while
  the frame was still rotating toward the wall, the leftover world-down slide
  sat on the frame's local Y axis and was never braked, so players slid
  straight off the ship before alignment finished. Friction now brakes the
  velocity component tangential to the actual surface (the normal component is
  untouched, so jumping and landing behave the same).

- **Fixed getting stuck (with camera jitter) on the edge between two gravity
  plate faces** — e.g. walking from the top of a plated 3x3x3 cube onto a side.
  The ground probe raycast along the blended field direction, which is diagonal
  near an edge, so it hit the NEXT face while the player still stood on the
  current one; the alignment flipped early and wedged the player into the
  corner, ping-ponging between the two faces. The probe now casts along the
  currently held surface normal while one exists, releasing only when the feet
  genuinely leave the face — after which the raw field cleanly acquires the
  next face once.

- **The circle shadow no longer rotates with gravity.** It is a stylistic
  stand-in for sunlight, so it now always renders in world orientation on the
  ground below, exactly like vanilla — even when the player is on a wall or
  upside down.

- **Entering spectator mode now resets gravity.** Spectators are unaffected by
  gravity fields; the frame smoothly rotates back to normal downward gravity
  instead of freezing in the last orientation. Base gravity set by command is
  kept and re-applies on leaving spectator mode.

- **Creative flight no longer drifts out of a gravity core's field while
  flying forward.** The camera-frame chase is a proportional controller; when
  orbiting a core the continuously rotating target left it with a permanent lag
  angle, so "forward" pointed slightly outward and the player slowly spiraled
  out of the field. The target's own per-tick motion is now fed forward into
  the frame before the chase, eliminating the steady-state lag: holding forward
  inside a radial field orbits at constant distance. (The original
  flying-smoothness fix is unchanged.)
