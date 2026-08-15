# GravityVS Vision and Design Roadmap

## Project Philosophy

GravityVS should remain an **API-first project**. The included blocks,
items, and mechanics should primarily serve as reference implementations
and debugging tools. The long-term purpose of the project is to provide
a flexible arbitrary-gravity framework with deep Valkyrien Skies
integration that other mods can build upon.

## Core Principles

-   API first, gameplay second.
-   Fully arbitrary gravity vectors.
-   Strong Valkyrien Skies integration.
-   Modular gravity sources.
-   Extensible systems that encourage other mods.

## Existing Example Content

Current features such as Gravity Anchors, Gravity Plating, and the
Gravity Core should be viewed as demonstrations of the API rather than
the primary gameplay loop.

## Gravity Sources

Gravity should be treated as a generic concept that can originate from
many systems, including directional fields, spherical fields, planetary
gravity, ship-local gravity, equipment, environmental effects, and
custom integrations.

## Gravity Field Modes

Gravity fields support two independent mode selectors: **polarity** and
**falloff**.

### Polarity Modes (existing)

-   **Attract Mode** -- pulls entities toward the gravity source.
-   **Repulse Mode** -- pushes entities away from the gravity source.

These modes remain unchanged.

### Falloff Modes (new)

A new mode selector determines how gravity strength behaves across the
field:

-   **Full Field** -- gravity force is consistent throughout the entire
    field. An entity at the edge of the field experiences the same pull
    (or push) as an entity right next to the source.
-   **Gradual Field** -- gravity force weakens with distance from the
    source. The further an entity is from the gravity source, the weaker
    the pull becomes.

### Gradual Field Behavior

Falloff affects **force only**, not orientation. When a Gravity Core is
set to Gradual:

-   As the player moves away from the core, the pull force of gravity
    steadily lessens.
-   The player's camera and entity model remain rotated toward the
    source for the entire time they are inside the field.
-   Orientation only resets once the player fully leaves the gravity
    field.

This separation of force falloff from orientation keeps movement feeling
natural at the field's edge while still clearly communicating that the
player is inside a gravity zone.

## Gravity Normalizer

A Gravity Normalizer is intended primarily for Valkyrien Skies ships.

Its purpose is to define what "down" means inside a moving ship. Players
inside should experience completely natural gravity relative to the ship
regardless of how the ship rotates or moves through the world.

Possible capabilities include selectable local directions, multiple
gravity zones, configurable strength, power requirements, and ownership
controls.

## Immersive Rotatable Blocks

One of the largest immersion issues is that many vanilla and modded
blocks cannot exist in all grid orientations (upside down, sideways,
wall-mounted, etc.).

Players should be able to create believable upside-down bases, wall
settlements, rotating stations, and ship interiors without visual
inconsistencies.

## Sticky Blocks

A technical variant of blocks could remove orientation restrictions.

The exact recipe is flexible (for example, converting a block into a
"Sticky" version), but the design goals are:

-   Preserve vanilla behavior.
-   Preserve inventories and animations.
-   Preserve interaction.
-   Allow unrestricted placement on any surface.

### Rotation Rules

Sticky gravity blocks are **grid-aligned but rotation-unlocked**:

-   Blocks always snap to the normal block grid. Placement is never
    unaligned or free-floating at arbitrary angles.
-   Within the grid, rotation is fully unlocked. Vanilla rotation
    restrictions are lifted, so a block can occupy any valid grid
    orientation -- upside down, sideways on a wall, facing any
    direction.
-   Example: a chest could be placed upside down and sideways on a
    wall, and still open, animate, and store items normally.

This makes sticky blocks a perfect companion system for GravityVS:
players building on walls or ceilings under rotated gravity can place
functional blocks that match their local "down."

## Gravity Block Framework

Provide a reusable framework that allows other mods to support
fully unlocked, grid-aligned block rotation with minimal effort.

The framework does not enable arbitrary off-grid angles -- it simply
lifts vanilla rotation restrictions so blocks can exist in any grid
orientation.

The framework should encourage a shared ecosystem instead of individual
compatibility patches.

## Long-Term Vision

GravityVS should become the standard gravity platform for Minecraft
modding, enabling ships, planets, rotating stations, artificial gravity,
zero-G environments, engineering systems, and entirely new gameplay
experiences.

## Guiding Principle

Every feature should expand what other mods can build. GravityVS should
focus on enabling creativity rather than providing large amounts of
standalone gameplay.
