# The Capsule Collider — How Gravity Unbound's Rotated Hitbox Works

*Source: `util/CapsuleCollider.java`, integrated through `mixin/EntityMixin.java`
and `capabilities/GravityCapabilityImpl.java`.*

---

## 1. The problem: Minecraft hitboxes cannot tilt

Every entity in Minecraft collides as an **axis-aligned bounding box** (AABB):
a rectangle whose edges are always parallel to the world's X/Y/Z axes. The
entire vanilla collision engine — `Entity.collide`, `Shapes.collide`, step-up,
`onGround` — assumes this. An AABB has no orientation; you cannot rotate one.

That's fine when gravity only points down (or even along another cardinal —
you can swap axes and still have an axis-aligned box). But this mod's gravity
is **continuous**: standing on a tilted Valkyrien Skies ship, or on the face of
a small gravity-core planet, the player's "up" can point in any direction, and
it changes smoothly as they walk. There is no way to express "a player-shaped
box tilted 30 degrees" as an AABB. Wrapping the tilted player in a bigger
world-aligned box makes them collide with everything *near* them; keeping the
unrotated box makes them collide as if they were still standing upright.

So under an active gravity frame, entities stop being boxes entirely.

## 2. The idea: a stack of spheres

A **sphere looks identical from every direction** — it is the one shape that
"rotates" for free. The capsule collider represents an entity as a short stack
of spheres (a discrete capsule) lined up along the entity's *gravity-frame up*:

```
      world up
         │        entity's frame up (gravity-dependent)
         │       ↗
         │      ( )   ← top sphere      (head)
         │     ( )    ← middle sphere   (torso, only for tall entities)
         │    ( )     ← bottom sphere   (feet)
         │   ·        ← "feet point" = entity.position()
   ──────┴──────────────────
```

- **Radius** = `width / 2 − 0.02` (slightly slimmer than the vanilla box, so a
  capsule fits anywhere the box did; floor of 0.1 for tiny entities).
- **Sphere centers** sit along the up axis at offsets from the feet point:
  bottom at `radius`, top at `height − radius`, plus a middle sphere when the
  entity is tall enough (a player gets three; a chicken effectively one or two).
- The entity's **position stays the feet point**, exactly like vanilla — it is
  simply interpreted along the rotated up axis instead of world +Y.

Because the spheres are positioned by a *direction vector*, the hitbox
genuinely follows the continuous visual frame — every tilt angle, not just the
six cardinals.

## 3. The collision algorithm

Vanilla asks `Entity.collide(movement)` → "given this intended movement, how
far do I actually get?" The capsule path answers the same question in four
stages (`CapsuleCollider.collide`):

### 3.1 Gather obstacles

Take the world-aligned **envelope** of the capsule at the start and end of the
movement, inflate it a little, and collect every block collision box inside it
— straight from block states, decomposed into their `VoxelShape` AABBs. Each
box also records an **exposed-face mask**: which of its six faces actually
border air (used in 3.4).

Valkyrien Skies ships are handled *exactly* rather than approximately: for
each ship intersecting the search region, the region is transformed into the
ship's own **shipyard coordinate space** and the ship's real blocks are
collected there, tagged with their ship. (VS's own world-space collision for
entities is an approximation; the capsule bypasses it entirely.)

### 3.2 Substepped sweep

The movement is split into substeps no longer than half a sphere radius (so a
fast entity cannot tunnel through a thin wall). At each substep position, the
capsule is **depenetrated** (3.3), and the push-out is accumulated into a
running `correction` vector.

A subtle but critical detail: substep positions are computed as
`start + movement × (i/n) + correction`, **never** by summing per-step
increments. Any axis that no contact touches therefore comes back
**bit-identical** to the input. Vanilla decides `verticalCollision` — and with
it `onGround`, fall damage, and jump physics — by comparing the requested and
achieved movement with exact `!=` on doubles. Summing `movement/3` three times
already differs in the last bit, which vanilla reads as a phantom collision
(symptoms we fixed the hard way: jumps cancelling mid-air, flight stuttering,
friction flickering).

### 3.3 Depenetration: push out along the contact normal

For a given position, check every sphere against every obstacle box. The
closest point of a box to a sphere center is just the center clamped into the
box; if that point is closer than the radius, the sphere penetrates.

Take the **deepest** contact, push the whole capsule out along that contact's
normal by the penetration depth (plus a hair of skin, 1e-4), and repeat up to
12 iterations until nothing penetrates. Pushing deepest-first and re-checking
makes multiple simultaneous contacts (a corner between floor and wall) resolve
stably.

This push-out *is* the sliding behavior: moving diagonally into a wall, the
wall's normal removes only the into-wall component of each substep, and the
tangential remainder survives — smooth sliding along surfaces at any angle,
with no axis-by-axis special cases.

### 3.4 Two corrections that make it feel right

**Support-face promotion.** A sphere resting on the *edge* between two blocks
would get a diagonal edge normal, and every block seam would become its own
tiny contact plane — the player would float on corner points and stutter over
flat floors made of many blocks. So when a contact's obstacle has an exposed
face that (a) the sphere center lies beyond and (b) points roughly along up,
the push is *promoted* onto that face normal instead of the raw sphere-to-edge
normal. Only **exposed** faces qualify — a wall of stacked blocks has interior
"top" faces at every seam, and promoting onto those used to hoist the player
up a little at each seam (jumping against a wall would stick instead of
sliding down).

**Two "up" references for ground classification.** A contact counts as ground
when its normal points along up (`dot > 0.55`) — but "up" is checked against
*both* the current visual frame's up **and** the gravity target's up. During a
landing on a steep new face, the frame is still rotating from the old
orientation; if only the frame's up counted, the new floor would never
classify as ground and the planet-walk alignment could never engage.

### 3.5 Step assist

Vanilla's 0.5-block step-up is reimplemented for the capsule: if grounded and
horizontal movement was mostly blocked, retry as *lift by 0.6 along up → move
→ settle back down*, and accept the result only if it ends **grounded** and
actually got further. Two guards exist because their absence produced
launches: never during upward motion (jump + wall = yo-yo), and never while
the frame's up disagrees with the stood-on surface (mid-transition, the
diagonal "up" turned each step attempt into a 0.6-block escalator that threw
players off tilted walls).

## 4. What the collider returns, and where it goes

`collide()` returns the achieved movement plus ground info: `grounded`, the
**ground normal** (post-promotion, so it is a clean face normal), and the
**ship** stood on, if any. `EntityMixin`'s `collide` inject feeds these into:

- `comp.capsuleGrounded` / `capsuleGroundNormal` / `capsuleGroundShip` — the
  authoritative grounded state under rotated gravity. (Vanilla's `onGround`
  tracks world-down collisions and is meaningless on a wall; everything in the
  mod that cares uses `isGroundedInFrame()` instead.)
- The planet-walk system: the ground normal seeds the surface probe and the
  frame-alignment target.
- **Valkyrien Skies dragging**: standing on a ship reports
  `setLastShipStoodOn` + `setIgnoreNextGroundStand` so VS carries the entity
  with the ship, interpolates it client-side, and exempts it from the
  server's "moved wrongly" anticheat. (VS normally does this in its own
  collision hook, which the capsule bypasses.)

## 5. The envelope: the AABB that still exists

Other systems (rendering culling, entity queries, network tracking) still
expect *some* AABB, so a capsule entity stores a **world-aligned envelope**:
the smallest box containing all the spheres. Block collision **never uses
it** — but it is deliberately loose (up to ~a block larger than the body when
tilted), which poisoned every vanilla system that iterated it. Each of those
is re-pointed at the real capsule:

| Vanilla system | Problem with the envelope | Capsule replacement |
|---|---|---|
| `collide` | n/a (replaced) | full capsule algorithm above |
| `checkInsideBlocks` (fire, cobwebs, portals) | touched blocks a block away | re-run visiting only cells the spheres overlap |
| `canEnterPose` (crouch/stand checks) | poked into the floor when tilted → crouch force-cancelled | `CapsuleCollider.fits()` — sphere-vs-box penetration test |
| suffocation / camera | similar envelope artifacts | sphere-based checks |

## 6. The pins: why standing still doesn't slide

A box on a tilted floor doesn't slide in vanilla because the collision
*blocks* the tangential component of gravity. A sphere stack on a tilted
plane, resolved by push-out, instead reaches an equilibrium where gravity
regenerates a small tangential creep every tick — physically correct for a
ball, wrong for a standing player. Three cooperating systems remove it:

1. **Static pin** (pre-collision, in the move path): grounded + no input →
   tangential movement under 0.15/tick is stripped to the normal component.
   While *walking*, only the component along the input direction is kept
   (the **directional pin**) so shallow slopes don't drag you downhill.
2. **Static-friction brake** (post-move): idle tangential *velocity* is
   damped ×0.3/tick.
3. **Ship idle anchor**: standing idle on a ship pins the feet to a fixed
   shipyard-space point, which cannot drift on a rotating contraption.

All three deliberately stand down: during surface transitions (the "creep"
is genuine momentum), on ice/slime (vanilla sliding must survive), **in
water/lava** (fluid currents push at ~0.005/tick — exactly the magnitude the
pins exist to delete), and for real pushes above the threshold (knockback,
pistons pass through).

## 7. Which entities get the capsule

`useCapsuleCollision()` = **every entity except projectiles**, whenever its
visual frame is not identity (`!isVisuallyDefault()`). Mobs, players, boats,
minecarts, items, modded vehicles — anything that moves through
`Entity.move()` and therefore keeps a *local-frame* `deltaMovement`.

Projectiles are the deliberate exception: arrows and thrown entities
integrate position by raw world-space addition and hit-detect by **raycast**,
not box collision — they are "world-frame" entities and a capsule would buy
nothing while disturbing their hit detection.

Outside any gravity influence the frame converges exactly back to identity
and the entity returns to bit-exact vanilla box collision (a watchdog
force-exits capsule mode if it ever lingers with no gravity influence).

## 8. Why spheres and not a rotated-box (OBB) engine

- **Rotation-invariance for free**: no orientation math in the narrow phase;
  a sphere-vs-AABB test is a clamp and a distance.
- **Exact ship collision**: a sphere transformed into shipyard space is still
  a sphere — collision against a rotated, moving ship is computed against the
  ship's actual blocks with zero approximation. An OBB-vs-AABB engine would
  need full separating-axis tests in every ship's frame.
- **Natural sliding**: push-out along contact normals gives correct sliding
  on arbitrary slopes without vanilla's per-axis resolution order.
- **Cheap robustness**: 2–3 spheres × nearby boxes × ≤16 substeps is small,
  and the iteration count and search volume are hard-capped.

The trade-offs are known and managed: the silhouette is slightly rounded (a
capsule has no shoulders — compensated by the slim radius), edge contacts
need the support-face promotion of §3.4, and resting equilibrium needs the
pins of §6.
