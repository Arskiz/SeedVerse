package arsqz.mod.client.galaxy;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "Are you sure you wish to travel to Planet XXXXX?" with Yes / No.
 *
 * <p>A star on the map is a Minecraft world seed. Confirming here is where the
 * actual "travel" happens. Right now this reports the target seed to the player;
 * see {@link #travel()} for where to hook up real world creation/loading.
 */
public class TravelConfirmScreen extends Screen {

    private final Screen parent;
    private final long seed;
    private final String planetName;

    public TravelConfirmScreen(Screen parent, long seed, String planetName) {
        super(Component.literal("Confirm Travel"));
        this.parent = parent;
        this.seed = seed;
        this.planetName = planetName;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        addRenderableWidget(Button.builder(Component.literal("Yes"), b -> travel())
                .bounds(cx - 105, cy + 20, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("No"), b -> minecraft.setScreenAndShow(parent))
                .bounds(cx + 5, cy + 20, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int cx = this.width / 2;
        int cy = this.height / 2;

        // Dim the world behind, then draw the panel.
        g.fill(0, 0, this.width, this.height, 0xC0000008);
        g.fill(cx - 160, cy - 60, cx + 160, cy + 55, 0xE00A0A1A);
        g.fill(cx - 160, cy - 60, cx + 160, cy - 59, 0xFF3A3A6A);
        g.fill(cx - 160, cy + 54, cx + 160, cy + 55, 0xFF3A3A6A);

        g.centeredText(this.font, "Are you sure you wish to travel to", cx, cy - 44, 0xFFE0E4FF);
        g.centeredText(this.font, "Planet " + planetName + "?", cx, cy - 30, 0xFFFFE9A8);
        g.centeredText(this.font, "Seed: " + seed, cx, cy - 12, 0xFF8A90B0);

        super.extractRenderState(g, mouseX, mouseY, delta); // buttons on top
    }

    /** No-op background so our dim overlay isn't painted over. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // intentionally empty
    }

    private void travel() {
        // TODO: hook up real travel here (create/load a singleplayer world with
        // this seed). Minecraft can't hot-swap the seed of a running world, so
        // this means spinning up a new level via the world-creation APIs.
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.sendSystemMessage(
                    Component.literal("\u00A7bTravelling to \u00A7ePlanet " + planetName
                            + "\u00A7b (seed: \u00A7f" + seed + "\u00A7b)..."));
        }
        minecraft.setScreenAndShow(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
