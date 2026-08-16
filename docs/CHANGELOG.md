# GravityVS Changelog

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
