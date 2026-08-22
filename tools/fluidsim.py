"""Offline simulator of GravityVS gravity-fluid rules.

Mirrors: vanilla FlowingFluid (spread/spreadToSides/getNewLiquid/getSpread
slope search, from the 1.20.1 decompiled source) + every modification in
FlowingFluidMixin (sector frames, up-entry prohibition, planar feed sets,
falling feeder + mutual-pit guard, conversion suppression) +
GravityCoreBlockEntity.fluidDownAt (dominant axis, tie priority
DOWN > X > Z > UP).

Port any fluid-rule change HERE first: every scenario below must stay
stable, covered, and fully draining before the change ships in Java.
"""
import io
import sys

# Directions in vanilla enum order: DOWN, UP, NORTH(-z), SOUTH(+z), WEST(-x), EAST(+x)
DIRS = [(0,-1,0),(0,1,0),(0,0,-1),(0,0,1),(-1,0,0),(1,0,0)]
DNAMES = ["DOWN","UP","NORTH","SOUTH","WEST","EAST"]
def opp(d): return d ^ 1  # pairs (0,1),(2,3),(4,5)
def axis(d): return 1 if d < 2 else (2 if d < 4 else 0)  # y, z, x
def add(p, d): dx,dy,dz = DIRS[d]; return (p[0]+dx, p[1]+dy, p[2]+dz)

def core_down(core, rng):
    """Sector frames: dominant axis toward the core, ties DOWN > X > Z > UP."""
    cx, cy, cz = core
    def down(p):
        x, y, z = p[0]-cx, p[1]-cy, p[2]-cz
        dist = (x*x + y*y + z*z) ** 0.5
        if dist > rng or dist < 1.0:
            return 0
        ax, ay, az = abs(x), abs(y), abs(z)
        m = max(ax, ay, az)
        if ay == m and y > 0:  return 0
        if ax == m:            return 4 if x > 0 else 5
        if az == m:            return 2 if z > 0 else 3
        return 1
    return down

class World:
    def __init__(self, solids, down_fn, void_fn=None, field_fn=None):
        self.solids = solids
        self.down_fn = down_fn
        self.field_fn = field_fn or (lambda p: down_fn(p) != 0)
        self.void_fn = void_fn or (lambda p: p[1] < -12 or max(abs(p[0]),abs(p[1]),abs(p[2])) > 24)
        self.fluid = {}     # pos -> (amount, falling, source)
        self.sched = set()
    def down(self, p): return self.down_fn(p)
    def solid(self, p): return p in self.solids
    def f(self, p): return self.fluid.get(p)
    def set_fluid(self, p, st):
        old = self.fluid.get(p)
        if st is None:
            if old is not None: del self.fluid[p]
        else:
            self.fluid[p] = st
        if old != st:
            self.poke(p)
            for d in range(6): self.poke(add(p,d))
    def poke(self, p):
        if p in self.fluid: self.sched.add(p)
    def place_source(self, p):
        self.fluid[p] = (8, False, True)
        self.sched.add(p)

    # ---- mixin helpers ----
    def cross_feeder_amount(self, p, d):
        """If the neighbor at d pours into p CROSS-FRAME (its down points at
        p, different frame), its pour-through contribution: amount+1 so the
        dropOff cancels — the level PASSES THROUGH an edge crossing instead
        of resetting (geodesic decay: lateral hops cost 1, pours cost 0).
        A cycle must contain a lateral hop, so levels strictly decay around
        any loop: no generator can exist. Returns 0 if not a feeder."""
        np_ = add(p, d)
        st = self.f(np_)
        if st is None: return 0
        nd = self.down(np_)
        if nd != opp(d): return 0
        if nd == self.down(p): return 0                 # same-frame = vanilla above-rule
        if not st[2] and self.down(p) == d: return 0    # mutual-pit guard
        return min(8, st[0] + 1)

    def planar_dirs(self, p):
        """Lateral FEED set: judged in the feeder's frame (axis rule) minus
        our own frame-down (up-entry mirror). FIELD-BOUNDARY CUT: out-of-
        field water never laterally feeds in-field cells — water enters a
        field only by FALLING in (cross-frame pour). Without this, water
        that seeps sideways out of a field (rain halo) can feed back into
        the field boundary row, where the same-frame above-rule resets it
        to full: a generator ring that exits and re-enters the field."""
        own = self.down(p)
        in_field = self.field_fn(p)
        return [d for d in range(6)
                if d != own and axis(self.down(add(p,d))) != axis(d)
                and not (in_field and not self.field_fn(add(p,d)))]

    def side_entry_amount(self, p):
        """SOLID-BACKED SIDE-ENTRY: the convex-corner wrap. The neighbor at
        our own frame-down may feed us laterally (net -1, the edge toll)
        when it lives in a DIFFERENT frame and rests directly on solid in
        its own frame (a surface cell pouring over a 1-block lip). The
        solid-backing requirement confines it to the surface: open water
        can never layer outward. Net cost -1 keeps every cycle strictly
        decaying. Returns 0 when not applicable."""
        if not self.field_fn(p): return 0      # corner wraps live inside fields
        own = self.down(p)
        n = add(p, own)
        if not self.field_fn(n): return 0      # feeder too (boundary cut)
        st = self.f(n)
        if st is None: return 0
        nd = self.down(n)
        if nd == own: return 0                 # same frame: vanilla-forbidden
        if nd == opp(own): return 0            # mutual pit
        if not self.solid(add(n, nd)): return 0  # feeder must rest on solid
        return st[0]

    def side_entry_spread_ok(self, src, dst):
        """Spread-side mirror of side_entry_amount (src -> dst where src is
        dst's frame-below)."""
        if not self.field_fn(dst): return False
        sd = self.down(src)
        dd = self.down(dst)
        if sd == dd: return False
        if sd == opp(dd): return False
        return self.solid(add(src, sd))

    def lateral_allowed(self, src, dst):
        """UP-ENTRY PROHIBITION: never spread into a cell from its own
        frame-below — except the solid-backed side-entry (corner wrap)."""
        if add(dst, self.down(dst)) != src:
            return True
        return self.side_entry_spread_ok(src, dst)

    # ---- vanilla getNewLiquid with mixin wraps ----
    def get_new_liquid(self, p):
        if self.solid(p): return None
        i = 0
        j = 0  # source neighbors (round 57: conversion re-enabled in fields)
        for d in self.planar_dirs(p):
            st = self.f(add(p,d))
            if st is not None:
                i = max(i, st[0])
                if st[2]: j += 1
        for d in range(6):
            amt = self.cross_feeder_amount(p, d)
            if amt:
                i = max(i, amt)
                nst = self.f(add(p, d))
                if nst is not None and nst[2]: j += 1
        i = max(i, self.side_entry_amount(p))
        # ROUND 57: vanilla infinite-water conversion, frame-aware inputs
        # (perpendicular-plane source count, frame-below solid-or-source)
        if j >= 2:
            below = add(p, self.down(p))
            bst = self.f(below)
            if self.solid(below) or (bst is not None and bst[2]):
                return (8, False, True)
        up = opp(self.down(p))
        upp = add(p, up)
        st = self.f(upp)
        if st is not None and self.down(upp) == self.down(p):
            return (8, True, False)    # same-frame column: vanilla full reset
        k = i - 1
        return None if k <= 0 else (k, False, False)

    def can_spread_to(self, target):
        if self.solid(target): return False
        if self.f(target) is not None: return False  # water never replaces water
        return True

    def is_water_hole(self, p, belowp):
        """VANILLA semantics (frame-gate removed): same fluid or holdable
        air below counts as a hole, cross-frame or not. Sector frames make
        the vanilla defer correct again: a column landing on cross-frame
        water defers to it instead of splashing sideways."""
        if self.solid(belowp): return False
        return True  # air can hold; same fluid counts too

    def can_pass_through(self, t):
        """vanilla canPassThrough: not a source, wall passable, can hold —
        flowing fluid cells ARE passable for the slope search."""
        st = self.f(t)
        if st is not None and st[2]: return False
        return not self.solid(t)

    def slope_dist(self, origin, p, depth, came_from, max_depth=4):
        best = 1000
        for d in self.perp_dirs(origin):
            if d == came_from: continue
            t = add(p, d)
            if not self.lateral_allowed(p, t): continue
            if not self.can_pass_through(t): continue
            below = add(t, self.down(t))
            if self.is_water_hole(t, below):
                return depth
            if depth < max_depth:
                best = min(best, self.slope_dist(origin, t, depth+1, opp(d)))
        return best

    def perp_dirs(self, origin):
        return [d for d in range(6) if axis(d) != axis(self.down(origin))]

    def get_spread(self, p):
        """Vanilla min-slope-distance channeling OUTSIDE fields; uniform
        all-direction spread INSIDE fields (channeling starves plateau
        corners -- fine on terrain, wrong on a planet face that must be
        covered uniformly). Java port: the hole-flag computeIfAbsent in
        getSpread returns true for in-field origins, so every direction
        ties at j=0."""
        in_field = self.field_fn(p)
        best = 1000
        cands = {}
        for d in self.perp_dirs(p):
            t = add(p, d)
            if not self.lateral_allowed(p, t): continue
            if not self.can_pass_through(t): continue
            if in_field:
                dist = 0
            else:
                below = add(t, self.down(t))
                dist = 0 if self.is_water_hole(t, below) else self.slope_dist(p, t, 1, opp(d))
            if dist <= best:
                cands.setdefault(dist, []).append(d)
                best = min(best, dist)
        return {d: self.get_new_liquid(add(p, d)) for d in cands.get(best, [])}

    def spread(self, p, state):
        if state is None: return
        below = add(p, self.down(p))
        nl_below = self.get_new_liquid(below)
        if self.can_spread_to(below):
            if nl_below is not None:
                self.set_fluid(below, nl_below)
        elif state[2] or not self.is_water_hole(p, below) or (
                not state[1] and self.down(below) != self.down(p)
                and (self.f(below) is None
                     or self.solid(add(below, self.down(below))))):
            # ROUND 57/58: the cross-frame side-spread exception fires when
            # the below cell holds NO same-type fluid (the solid lip of a
            # convex corner) — OR when its water rests DIRECTLY ON SOLID in
            # its own frame (a surface film on a face: the corner wrap's
            # continuation cell must keep spreading onto the next face).
            # Over OPEN cross-frame water (air/water-backed — a stream or
            # the flooded interior) the spread defers and COMBINES instead
            # of sheeting outward over it.
            self.spread_to_sides(p, state)

    def spread_to_sides(self, p, state):
        i = state[0] - 1
        if state[1]: i = 7
        if i <= 0: return
        for d, nl in self.get_spread(p).items():
            t = add(p, d)
            if self.can_spread_to(t) and nl is not None:
                self.set_fluid(t, nl)

    def tick(self, p):
        state = self.f(p)
        if state is None: return
        if not state[2]:
            nl = self.get_new_liquid(p)
            if nl is None:
                self.set_fluid(p, None)
                state = None
            elif nl != state:
                self.set_fluid(p, nl)
                state = nl
                self.sched.add(p)
        self.spread(p, state)

    def run_round(self):
        batch = sorted(self.sched)
        self.sched = set()
        for p in batch:
            if self.void_fn(p):
                self.fluid.pop(p, None); continue
            self.tick(p)
        return len(batch)

    def run(self, rounds=400):
        for r in range(1, rounds+1):
            if self.run_round() == 0:
                return r
        return None


def scenario(name, world, sources, checks, rounds=400):
    for s in sources:
        world.place_source(s)
    settle = world.run(rounds)
    ok = True
    msgs = []
    if settle is None:
        ok = False; msgs.append(f"NOT STABLE after {rounds} rounds ({len(world.fluid)} cells)")
    else:
        msgs.append(f"stable@{settle} cells={len(world.fluid)}")
    for label, fn in checks:
        good, detail = fn(world)
        ok = ok and good
        msgs.append(("PASS " if good else "FAIL ") + label + (f" [{detail}]" if detail else ""))
    # drainage: remove ALL sources (placed AND converted — round 57 allows
    # in-field conversion, and converted sources are real sources), then
    # everything must drain: no source-free water sustains itself
    for s in [p for p, st in world.fluid.items() if st[2]]:
        world.set_fluid(s, None)
    dr = world.run(rounds)
    drained = dr is not None and len(world.fluid) == 0
    ok = ok and drained
    msgs.append(f"{'PASS' if drained else 'FAIL'} drain "
                f"[{'0 cells@'+str(dr) if drained else str(len(world.fluid))+' stuck'}]")
    if not drained and world.fluid:
        sample = sorted(world.fluid.items())[:10]
        msgs.append("stuck sample: " + " ".join(
            f"{p}={st[0]}{'F' if st[1] else ''}{'S' if st[2] else ''}(d={DNAMES[world.down(p)]})"
            for p, st in sample))
    print(("OK  " if ok else "BAD ") + name + ": " + "; ".join(msgs))
    return ok


def cube_faces_covered(cube_half=1):
    def check(w):
        missing = []
        k = cube_half + 1
        for name, cells in {
            "top":    [(x, k, z) for x in range(-cube_half,cube_half+1) for z in range(-cube_half,cube_half+1)],
            "bottom": [(x,-k, z) for x in range(-cube_half,cube_half+1) for z in range(-cube_half,cube_half+1)],
            "east":   [(k, y, z) for y in range(-cube_half,cube_half+1) for z in range(-cube_half,cube_half+1)],
            "west":   [(-k,y, z) for y in range(-cube_half,cube_half+1) for z in range(-cube_half,cube_half+1)],
            "south":  [(x, y, k) for x in range(-cube_half,cube_half+1) for y in range(-cube_half,cube_half+1)],
            "north":  [(x, y,-k) for x in range(-cube_half,cube_half+1) for y in range(-cube_half,cube_half+1)],
        }.items():
            n = sum(1 for c in cells if w.f(c) is not None)
            if n < len(cells):
                missing.append(f"{name} {n}/{len(cells)}")
        return (not missing), ",".join(missing)
    return check

def confined_to_shell(max_r):
    def check(w):
        far = [p for p in w.fluid if (p[0]**2+p[1]**2+p[2]**2)**0.5 > max_r]
        return (not far), f"{len(far)} cells beyond r={max_r}"
    return check

def no_water_at(region_fn, label=""):
    def check(w):
        bad = [p for p in w.fluid if region_fn(p)]
        return (not bad), f"{len(bad)} cells {label}: {sorted(bad)[:6]}" if bad else ""
    return check


def main():
    all_ok = True
    CUBE = {(x,y,z) for x in (-1,0,1) for y in (-1,0,1) for z in (-1,0,1)}

    # --- core cube: source on every face must wrap the whole cube ---
    core_field = lambda p: 1.0 <= (p[0]**2+p[1]**2+p[2]**2)**0.5 <= 8
    # bottom source: water must CLIMB the whole planet to reach the top —
    # the 8-level flat-ground budget honestly runs out (top face partial)
    def faces_reached():
        def check(w):
            k = 2
            missing = []
            for name, cells in {
                "top": [(x,k,z) for x in (-1,0,1) for z in (-1,0,1)],
                "bottom": [(x,-k,z) for x in (-1,0,1) for z in (-1,0,1)],
                "east": [(k,y,z) for y in (-1,0,1) for z in (-1,0,1)],
                "west": [(-k,y,z) for y in (-1,0,1) for z in (-1,0,1)],
                "south": [(x,y,k) for x in (-1,0,1) for y in (-1,0,1)],
                "north": [(x,y,-k) for x in (-1,0,1) for y in (-1,0,1)],
            }.items():
                if not any(w.f(c) for c in cells):
                    missing.append(name)
            return (not missing), ",".join(missing)
        return check
    for face, src, chk in [("top",(0,2,0),cube_faces_covered()),
                           ("side",(2,0,0),cube_faces_covered()),
                           ("bottom",(0,-2,0),faces_reached()),
                           ("north",(0,0,-2),cube_faces_covered()),
                           ("corner-ish",(1,2,0),cube_faces_covered())]:
        w = World(CUBE, core_down((0,0,0), 8), field_fn=core_field)
        all_ok &= scenario(f"core-cube source@{face}{src}", w, [src],
                           [("coverage", chk),
                            ("shell only", confined_to_shell(3.8))])

    # --- bare core (single block): must form the symmetric 3x3x3 shell ---
    CORE1 = {(0,0,0)}
    w = World(CORE1, core_down((0,0,0), 8), field_fn=core_field)
    all_ok &= scenario("bare core 3x3x3 shell", w, [(0,1,0)],
        [("26-cell shell", lambda wd: (len(wd.fluid) == 26, f"{len(wd.fluid)} cells")),
         ("x/z symmetric", lambda wd: (
             all((wd.f((x,y,z)) is None) == (wd.f((-x,y,z)) is None)
                 and (wd.f((x,y,z)) is None) == (wd.f((x,y,-z)) is None)
                 for x in range(-2,3) for y in range(-2,3) for z in range(-2,3)), ""))])

    # --- diagonal source near a bare core: the round-57 regression — the
    # stream crossing sectors must COMBINE with the other frame's water,
    # not sheet outward over it ("huge mess") ---
    w = World(CORE1, core_down((0,0,0), 8), field_fn=core_field)
    all_ok &= scenario("diagonal source, bounded", w, [(3,3,0)],
        [("bounded (no sheet)", lambda wd: (len(wd.fluid) <= 80, f"{len(wd.fluid)} cells"))])

    # --- water planet: a sources cube around the core with punched air
    # pockets must self-heal (fill, then convert) — the swimmable planet ---
    pockets = [(2,2,0),(0,2,2),(-2,-2,0),(1,1,1),(2,0,2),(0,-2,2),(-2,0,-2)]
    planet_srcs = [(x,y,z) for x in range(-3,4) for y in range(-3,4) for z in range(-3,4)
                   if (x,y,z) != (0,0,0) and (x,y,z) not in pockets]
    w = World(CORE1, core_down((0,0,0), 8), field_fn=core_field,
              void_fn=lambda p: max(abs(p[0]),abs(p[1]),abs(p[2])) > 24)
    all_ok &= scenario("water planet pockets heal", w, planet_srcs,
        [("pockets filled", lambda wd: (
            all(wd.f(pk) is not None for pk in pockets),
            "empty: " + str([pk for pk in pockets if wd.f(pk) is None]))),
         ("pockets full-level", lambda wd: (
            all(wd.f(pk) is not None and wd.f(pk)[0] == 8 for pk in pockets),
            str([(pk, wd.f(pk)) for pk in pockets if wd.f(pk) is None or wd.f(pk)[0] != 8][:4])))],
        rounds=800)

    # --- vertical stream into a sideways (WEST) plating field ---
    # field box x in [4..10], y in [-2..2], z in [-2..2], down=WEST inside;
    # wall at x=3 (the "plate face"); stream falls at x=7 from y=6
    wall = {(3,y,z) for y in range(-3,4) for z in range(-3,4)}
    ground = {(x,-6,z) for x in range(-24,25) for z in range(-24,25)}
    def plate_down(p):
        x,y,z = p
        if 4 <= x <= 10 and -2 <= y <= 2 and -2 <= z <= 2:
            return 4  # WEST
        return 0
    w = World(wall | ground, plate_down,
              field_fn=lambda p: 4 <= p[0] <= 10 and -2 <= p[1] <= 2 and -2 <= p[2] <= 2,
              void_fn=lambda p: p[1] < -10 or max(abs(p[0]),abs(p[2])) > 26 or p[1] > 12)
    all_ok &= scenario("stream into sideways field", w, [(7,6,0)],
        [("no boundary splash (stream+umbrella only at y=3)",
          no_water_at(lambda p: p[1] == 3 and not (abs(p[0]-7) <= 1 and abs(p[2]) <= 1),
                      "at y=3 off-stream")),
         ("water reaches the wall", lambda wd: ((4,0,0) in wd.fluid or (4,1,0) in wd.fluid
                                                or (4,2,0) in wd.fluid, ""))])

    # --- flat plane over a DOWN field: must behave exactly like vanilla ---
    plane = {(x,0,z) for x in range(-9,10) for z in range(-9,10)}
    plane_ground = {(x,-5,z) for x in range(-24,25) for z in range(-24,25)}
    w = World(plane | plane_ground, lambda p: 0,
              field_fn=lambda p: abs(p[0]) <= 9 and abs(p[2]) <= 9 and 0 <= p[1] <= 4)
    all_ok &= scenario("flat plane, DOWN field", w, [(0,1,0)],
        [("uniform disc", lambda wd: (
            all(wd.f((x,1,z)) is not None for x in range(-7,8) for z in range(-7,8)
                if abs(x)+abs(z) <= 7), ""))])

    # --- water poured from above ONTO an UP field (the mutual frontier) ---
    shelf = {(x,8,z) for x in range(2,9) for z in range(-3,4)}
    frontier_ground = {(x,-4,z) for x in range(-24,25) for z in range(-24,25)}
    def upfield_down(p):
        x,y,z = p
        if -6 <= x <= 6 and 0 <= y <= 4 and -6 <= z <= 6:
            return 1
        return 0
    w = World(shelf | frontier_ground, upfield_down,
              field_fn=lambda p: -6 <= p[0] <= 6 and 0 <= p[1] <= 4 and -6 <= p[2] <= 6,
              void_fn=lambda p: p[1] < -8 or max(abs(p[0]),abs(p[2])) > 26)
    all_ok &= scenario("pour onto UP field (mutual frontier)", w, [(3,9,0)],
        [("settles", lambda wd: (True, str(len(wd.fluid)) + " cells"))])

    # --- ceiling: UP field under a slab, hanging water spreads across it ---
    slab = {(x,5,z) for x in range(-8,9) for z in range(-8,9)}
    ceil_ground = {(x,-3,z) for x in range(-24,25) for z in range(-24,25)}
    def ceil_down(p):
        x,y,z = p
        if -6 <= x <= 6 and 0 <= y <= 4 and -6 <= z <= 6:
            return 1  # UP: attract toward the slab above
        return 0
    w = World(slab | ceil_ground, ceil_down,
              field_fn=lambda p: -6 <= p[0] <= 6 and 0 <= p[1] <= 4 and -6 <= p[2] <= 6,
              void_fn=lambda p: p[1] < -8 or max(abs(p[0]),abs(p[2])) > 26)
    all_ok &= scenario("hanging ceiling water (UP field)", w, [(0,4,0)],
        [("hangs at ceiling", lambda wd: (
            sum(1 for p in wd.fluid if p[1] == 4) >= 40, f"{sum(1 for p in wd.fluid if p[1]==4)} cells"))])

    print("ALL SCENARIOS PASS" if all_ok else "SOME SCENARIOS FAILED")
    return all_ok

if __name__ == "__main__":
    sys.exit(0 if main() else 1)
