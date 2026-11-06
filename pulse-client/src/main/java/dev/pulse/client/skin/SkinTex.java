package dev.pulse.client.skin;

//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

/** A skin texture id, typed for the Minecraft version being built. */
public final class SkinTex {
    //? if >=1.21.11 {
    public final Identifier id;

    public SkinTex(Identifier id) {
        this.id = id;
    }

    public static SkinTex of(String namespace, String path) {
        return new SkinTex(Identifier.fromNamespaceAndPath(namespace, path));
    }
    //?} else {
    /*public final ResourceLocation id;

    public SkinTex(ResourceLocation id) {
        this.id = id;
    }

    public static SkinTex of(String namespace, String path) {
        return new SkinTex(ResourceLocation.fromNamespaceAndPath(namespace, path));
    }
    *///?}
}
