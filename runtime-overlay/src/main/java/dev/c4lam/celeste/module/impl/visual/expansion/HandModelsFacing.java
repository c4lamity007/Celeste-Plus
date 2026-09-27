package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.item.model.ItemModel;
import net.minecraft.client.render.item.model.special.SpecialModelRenderer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.Transformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Public model-state probe: never renders, reflects into, or replaces a held model. */
public final class HandModelsFacing {
    private static final int MAX_LAYERS = HandModelsBasis.MAX_LAYERS;
    private static final int MAX_QUADS = 2048;
    private static final Cache[] HANDS = {new Cache(), new Cache()};

    private HandModelsFacing() { }

    /** Null retains the authored orientation of a 3D, special, or unsupported model. */
    public static Quaternionfc correction(ItemStack stack, ItemDisplayContext context, LivingEntity holder) {
        if (stack == null || stack.isEmpty() || holder == null
            || context != ItemDisplayContext.FIRST_PERSON_RIGHT_HAND && context != ItemDisplayContext.FIRST_PERSON_LEFT_HAND) return null;
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread() || client.world == null || holder.getEntityWorld() != client.world) return null;
        Cache cache = HANDS[context.isLeftHand() ? 1 : 0];
        try {
            ItemModelManager manager = client.getItemModelManager();
            Identifier modelId = stack.get(DataComponentTypes.ITEM_MODEL);
            ItemModel model = modelId == null ? null : client.getBakedModelManager().getItemModel(modelId);
            long tick = client.world.getTime();
            boolean same = cache.manager == manager && cache.model == model && cache.world == client.world
                && cache.holder == holder && cache.snapshot != null && cache.count == stack.getCount()
                && ItemStack.areItemsAndComponentsEqual(cache.snapshot, stack);
            // Dynamic model selection (using a bow, range-dispatch, etc.) is
            // re-evaluated once per world tick, not once per rendered frame.
            if (same && cache.tick == tick) return cache.correction;
            if (!same) cache.snapshot = stack.copy();
            cache.manager = manager; cache.model = model; cache.world = client.world; cache.holder = holder;
            cache.count = stack.getCount(); cache.tick = tick; cache.correction = null;
            if (model == null) return null;
            manager.updateForLivingEntity(cache.probe, stack, context, holder);
            cache.correction = cache.probe.correction(context.isLeftHand());
            return cache.correction;
        } catch (RuntimeException | LinkageError unsupported) {
            // Resource-pack/custom models may require state this read-only probe
            // cannot provide. Never let optional facing hide the actual item.
            cache.correction = null;
            return null;
        }
    }

    public static void clear() {
        for (int i = 0; i < HANDS.length; i++) HANDS[i] = new Cache();
    }

    private static final class Cache {
        final Probe probe = new Probe();
        ItemModelManager manager;
        ItemModel model;
        Object world, holder;
        ItemStack snapshot;
        int count;
        long tick;
        Quaternionf correction;
    }

    /** Forwarding keeps ItemRenderState's own layer count and aggregate queries valid. */
    static final class Probe extends ItemRenderState {
        final List<Layer> observed = new ArrayList<>();
        int quads;

        @Override public void clear() {
            super.clear();
            observed.clear();
            quads = 0;
        }

        @Override public void addLayers(int count) {
            if (count < 0 || observed.size() + count > MAX_LAYERS) throw new UnsupportedOperationException("Complex held model");
            super.addLayers(count);
        }

        @Override public LayerRenderState newLayer() {
            if (observed.size() >= MAX_LAYERS) throw new UnsupportedOperationException("Complex held model");
            Layer layer = new Layer(this, super.newLayer());
            observed.add(layer);
            return layer;
        }

        Quaternionf correction(boolean left) {
            List<HandModelsBasis.Layer> layers = new ArrayList<>(observed.size());
            for (Layer layer : observed) {
                HandModelsBasis.Layer metadata = metadata(layer);
                if (metadata == null) return null;
                layers.add(metadata);
            }
            return HandModelsBasis.correction(layers, left);
        }
    }

    private static HandModelsBasis.Layer metadata(Layer layer) {
        List<BakedQuad> quads = layer.delegate.getQuads();
        if (layer.special || quads.isEmpty()) return null;
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (BakedQuad quad : quads) {
            if (quad == null) return null;
            for (int i = 0; i < 4; i++) {
                Vector3fc point = quad.getPosition(i);
                if (!finite(point)) return null;
                minX = Math.min(minX, point.x()); maxX = Math.max(maxX, point.x());
                minY = Math.min(minY, point.y()); maxY = Math.max(maxY, point.y());
                minZ = Math.min(minZ, point.z()); maxZ = Math.max(maxZ, point.z());
            }
        }
        return new HandModelsBasis.Layer(false, new Vector3f(minX, minY, minZ), new Vector3f(maxX, maxY, maxZ),
            layer.transform.rotation(), layer.transform.scale());
    }

    private static boolean finite(Vector3fc vector) {
        return vector != null && Float.isFinite(vector.x()) && Float.isFinite(vector.y()) && Float.isFinite(vector.z());
    }

    private static final class Layer extends ItemRenderState.LayerRenderState {
        final ItemRenderState.LayerRenderState delegate;
        final List<BakedQuad> boundedQuads;
        Transformation transform = Transformation.IDENTITY;
        boolean special;

        Layer(Probe owner, ItemRenderState.LayerRenderState delegate) {
            owner.super();
            this.delegate = delegate;
            boundedQuads = new AbstractList<>() {
                @Override public BakedQuad get(int index) { return delegate.getQuads().get(index); }
                @Override public int size() { return delegate.getQuads().size(); }
                @Override public void add(int index, BakedQuad quad) {
                    if (++owner.quads > MAX_QUADS) throw new UnsupportedOperationException("Complex held model");
                    delegate.getQuads().add(index, quad);
                }
                @Override public boolean addAll(Collection<? extends BakedQuad> values) {
                    if (values.size() > MAX_QUADS - owner.quads) throw new UnsupportedOperationException("Complex held model");
                    owner.quads += values.size();
                    return delegate.getQuads().addAll(values);
                }
            };
        }

        @Override public List<BakedQuad> getQuads() { return boundedQuads; }
        @Override public void clear() {
            delegate.clear();
            transform = Transformation.IDENTITY;
            special = false;
        }
        @Override public void setTransform(Transformation value) { transform = value; delegate.setTransform(value); }
        @Override public void setVertices(Supplier<Vector3fc[]> value) { delegate.setVertices(value); }
        @Override public void setUseLight(boolean value) { delegate.setUseLight(value); }
        @Override public void setRenderLayer(RenderLayer value) { delegate.setRenderLayer(value); }
        @Override public void setParticle(Sprite value) { delegate.setParticle(value); }
        @Override public void setGlint(ItemRenderState.Glint value) { delegate.setGlint(value); }
        @Override public int[] initTints(int length) { return delegate.initTints(length); }
        @Override public <T> void setSpecialModel(SpecialModelRenderer<T> renderer, T data) {
            special = true;
            delegate.setSpecialModel(renderer, data);
        }
    }
}
