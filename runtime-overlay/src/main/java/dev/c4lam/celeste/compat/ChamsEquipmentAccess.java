package dev.c4lam.celeste.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Map;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.equipment.EquipmentModelLoader;
import net.minecraft.util.Identifier;

/** Read-only, cached 1.21.11 access; also works when the payload is loaded after startup. */
public final class ChamsEquipmentAccess {
    private ChamsEquipmentAccess() { }

    public static EquipmentModelLoader equipmentModels(EntityRenderManager manager) {
        try { return (EquipmentModelLoader) Equipment.LOADER.invokeExact(manager); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }

    /** Preserve the actual cutout texture, including resource-pack item atlases. */
    public static Identifier texture(RenderLayer layer) {
        try {
            RenderSetup setup = (RenderSetup) Textures.SETUP.invokeExact(layer);
            Map<?, ?> textures = (Map<?, ?>) Textures.TEXTURES.invokeExact(setup);
            Object spec = textures.get("Sampler0");
            if (spec == null) return null;
            return (Identifier) Textures.LOCATION.get(spec.getClass()).invokeExact(spec);
        } catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }

    private static final class Equipment {
        static final MethodHandle LOADER = NamedOrIntermediaryMembers.getter(EntityRenderManager.class,
            EquipmentModelLoader.class, "equipmentModelLoader", "field_55290");
    }

    private static final class Textures {
        static final MethodHandle SETUP = NamedOrIntermediaryMembers.getter(RenderLayer.class,
            RenderSetup.class, "renderSetup", "field_64013");
        static final MethodHandle TEXTURES = NamedOrIntermediaryMembers.getter(RenderSetup.class,
            Map.class, "textures", "field_63987");
        static final ClassValue<MethodHandle> LOCATION = new ClassValue<>() {
            @Override protected MethodHandle computeValue(Class<?> type) {
                return NamedOrIntermediaryMembers.getter(type, Identifier.class, "location", "comp_5228")
                    .asType(MethodType.methodType(Identifier.class, Object.class));
            }
        };
    }
}
