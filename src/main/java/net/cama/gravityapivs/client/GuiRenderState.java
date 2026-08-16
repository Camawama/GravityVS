package net.cama.gravityapivs.client;

/**
 * Client render-thread flags for GUI entity rendering.
 *
 * While an entity is being drawn inside a GUI (the inventory "paper doll",
 * and any other screen using the same helper), the gravity model rotation
 * must NOT apply: the doll is a portrait, not a world entity — rotating it
 * pushed the model outside its view box and over other GUI elements.
 */
public final class GuiRenderState {

    /** True while InventoryScreen.renderEntityInInventory is on the stack. */
    public static boolean renderingGuiEntity = false;

    private GuiRenderState() {}
}
