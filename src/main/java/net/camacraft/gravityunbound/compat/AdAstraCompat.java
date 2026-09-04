package net.camacraft.gravityunbound.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.util.function.Function;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

/**
 * AD ASTRA COMPATIBILITY (soft dependency, wired reflectively — no build
 * dependency, nothing loads unless Ad Astra is present).
 *
 * How Ad Astra does gravity (verified on ad_astra-forge-1.20.1-1.15.20):
 * <ul>
 * <li>Every planet dimension carries a gravity in m/s² (Earth 9.807, Moon
 *     1.622, every orbit 0). {@code GravityApi.API.getGravity(Entity)}
 *     returns it as a FRACTION of Earth (planet / 9.807) for the entity's
 *     position — chunk overrides from Ad Astra's own Gravity Normalizer
 *     machine included — and then fires {@code EntityGravityEvent} so other
 *     mods can change the answer.</li>
 * <li>{@code LivingEntity.travel} is hooked at HEAD. Below 0.05 it fires
 *     {@code ZeroGravityTickEvent} and, unless a listener vetoes, REPLACES
 *     vanilla travel with its own floating physics (cancel). Otherwise it
 *     fires {@code GravityTickEvent} and, unless vetoed, adds
 *     {@code 0.08 * (1 - fraction)} to the vertical velocity so vanilla's
 *     0.08 nets out to the planet's pull. Players on the CLIENT read the
 *     fraction from their synced planet data rather than the entity
 *     event — the tick events are the only hook that reaches them.</li>
 * <li>Items, arrows, boats and fishing hooks get the same
 *     {@code 0.04 * (1 - fraction)} style correction in their tick mixins,
 *     all through {@code getGravity(Entity)}.</li>
 * </ul>
 *
 * Why Gravity Unbound's fields did nothing in an Ad Astra orbit: the field
 * pull travels through Forge's entity-gravity attribute inside vanilla
 * travel, which Ad Astra cancels entirely at zero gravity; and the
 * zero-g deficit pull only engages for no-gravity entities (how VS Genesis
 * implements space), which Ad Astra never sets. The camera turned, nothing
 * pulled.
 *
 * The layer: while an entity is under a Gravity Unbound field (or a
 * non-default base gravity), (1) answer {@code EntityGravityEvent} with
 * Earth gravity — Ad Astra then applies no correction to items, arrows,
 * boats, hooks and mobs — and (2) veto both travel tick events so vanilla
 * travel runs and the field's own attribute pull acts along the gravity
 * frame, on both sides. Outside fields Ad Astra's planets and its Gravity
 * Normalizer behave exactly as before. The mod's per-dimension gravity
 * override is a DIRECTION setting and is not involved.
 */
public final class AdAstraCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "ad_astra";
    private static final String EVENTS = "earth.terrarium.adastra.api.events.AdAstraEvents$";

    private static volatile boolean active = false;
    // GravityApi.API.getGravity(Level) -> Earth fraction, bound to the API instance
    private static @Nullable MethodHandle levelGravity = null;

    private AdAstraCompat() {
    }

    public static boolean isActive() {
        return active;
    }

    /** Called once from common setup; a no-op without Ad Astra. */
    public static void initIfLoaded() {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        try {
            // fielded entities read as Earth gravity to every Ad Astra hook
            registerListener(EVENTS + "EntityGravityEvent", "getGravity",
                args -> gravityOverridden((Entity) args[0]) ? Float.valueOf(1.0f) : args[1]);
            // ...and their travel is vanilla's (the field's attribute pull)
            registerListener(EVENTS + "GravityTickEvent", "tick",
                args -> !gravityOverridden((Entity) args[1]));
            registerListener(EVENTS + "ZeroGravityTickEvent", "tick",
                args -> !gravityOverridden((Entity) args[1]));

            Class<?> api = Class.forName("earth.terrarium.adastra.api.systems.GravityApi");
            Object instance = api.getField("API").get(null);
            levelGravity = MethodHandles.publicLookup()
                .findVirtual(api, "getGravity", MethodType.methodType(float.class, Level.class))
                .bindTo(instance);

            active = true;
            LOGGER.info("[GravityUnbound] Ad Astra detected: gravity fields override planet gravity for the entities inside them");
        }
        catch (Throwable t) {
            active = false;
            LOGGER.warn("[GravityUnbound] Ad Astra is present but its gravity API could not be hooked; fields will not override planet gravity", t);
        }
    }

    private static void registerListener(String interfaceName, String methodName, Function<Object[], Object> body)
        throws ReflectiveOperationException {
        Class<?> listenerInterface = Class.forName(interfaceName);
        Object listener = Proxy.newProxyInstance(
            listenerInterface.getClassLoader(),
            new Class<?>[] { listenerInterface },
            (proxy, method, args) -> {
                if (method.getName().equals(methodName)) {
                    return body.apply(args);
                }
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "GravityUnbound listener for " + interfaceName;
                    default -> null;
                };
            });
        listenerInterface.getMethod("register", listenerInterface).invoke(null, listener);
    }

    private static boolean gravityOverridden(Entity entity) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        return comp != null && comp.isGravityOverridden();
    }

    /** Ad Astra's gravity for the level as a fraction of Earth (1.0 without Ad Astra). */
    public static float planetGravity(Level level) {
        MethodHandle handle = levelGravity;
        if (!active || handle == null) {
            return 1.0f;
        }
        try {
            return (float) handle.invoke(level);
        }
        catch (Throwable t) {
            return 1.0f;
        }
    }

    /**
     * True when Ad Astra is the reason this entity has reduced gravity and
     * the listeners above already restore Earth gravity inside fields — the
     * plating's extra zero-g force must then stand down, or it would stack on
     * the entity's own (restored) gravity. Entities flagged no-gravity (how
     * VS Genesis does space) still need the force.
     */
    public static boolean restoresGravityFor(Entity entity) {
        return active && !entity.isNoGravity() && planetGravity(entity.level()) < 0.99f;
    }
}
