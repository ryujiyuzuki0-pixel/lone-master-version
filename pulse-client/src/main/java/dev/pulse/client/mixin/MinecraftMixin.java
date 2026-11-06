package dev.pulse.client.mixin;

import dev.pulse.client.ui.LoneTitleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Swaps the vanilla title screen for the Lone Client one. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true, require = 0)
    private Screen pulse$loneTitle(Screen screen) {
        if (screen != null && screen.getClass() == TitleScreen.class && LoneTitleScreen.enabled()) {
            return new LoneTitleScreen();
        }
        return screen;
    }
}
