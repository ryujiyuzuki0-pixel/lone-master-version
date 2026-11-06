package dev.pulse.client.mixin;

import dev.pulse.client.module.impl.CustomFogModule;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Custom Fog hook. The fog renderer writes color + six floats (environmental start/end, render-distance
 * start/end, sky end, cloud end) into a uniform buffer; Custom Fog rewrites the first four floats and the color
 * as they go in. Everything is require = 0 and @Pseudo, so if a Minecraft version changes this method the game
 * still starts and Custom Fog reports "not supported" instead of crashing. The handlers are static so they are
 * valid whether the target is static or not.
 */
@Pseudo
@Mixin(targets = "net.minecraft.client.renderer.fog.FogRenderer")
public abstract class FogRendererMixin {
    @ModifyVariable(method = "updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private static Vector4f pulse$color(Vector4f color) {
        return CustomFogModule.fogColor(color);
    }

    @ModifyVariable(method = "updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private static float pulse$envStart(float v) {
        return CustomFogModule.fogStart(v);
    }

    @ModifyVariable(method = "updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private static float pulse$envEnd(float v) {
        return CustomFogModule.fogEnd(v);
    }

    @ModifyVariable(method = "updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 0)
    private static float pulse$renderStart(float v) {
        return CustomFogModule.fogStart(v);
    }

    @ModifyVariable(method = "updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 3, require = 0)
    private static float pulse$renderEnd(float v) {
        return CustomFogModule.fogEnd(v);
    }
}
