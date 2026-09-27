package dev.celestebeta.host.instrument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Offline composition only. Installation, version verification and rollback belong to the host. */
public final class CombinedHooksTransformer {
    private static final List<Entry> ENTRIES = List.of(
        new Entry("client/MinecraftClient", "310", List.of(Family.RELOAD, Family.FAKE_GAMBLE, Family.GAMEPLAY)),
        new Entry("client/network/ClientPlayerInteractionManager", "636", List.of(Family.INTERACTION, Family.ROTATION, Family.GAMEPLAY, Family.FAKE_GAMBLE)),
        new Entry("client/network/ClientPlayNetworkHandler", "634", List.of(Family.FAKE_GAMBLE, Family.GAMEPLAY)),
        new Entry("entity/LivingEntity", "1309", List.of(Family.VISUAL, Family.ROTATION)),
        new Entry("client/render/GameRenderer", "757", List.of(Family.VISUAL)),
        new Entry("client/gui/hud/InGameHud", "329", List.of(Family.VISUAL)),
        new Entry("client/gui/screen/ChatScreen", "408", List.of(Family.VISUAL)),
        new Entry("client/render/entity/LivingEntityRenderer", "922", List.of(Family.VISUAL)),
        new Entry("client/render/entity/ItemEntityRenderer", "916", List.of(Family.VISUAL)),
        new Entry("client/render/item/HeldItemRenderer", "759", List.of(Family.VISUAL)),
        new Entry("client/render/WorldRenderer", "761", List.of(Family.ROTATION)),
        new Entry("client/render/SkyRendering", "9975", List.of(Family.VISUAL)),
        new Entry("client/network/ClientPlayerEntity", "746", List.of(Family.ROTATION, Family.FAKE_GAMBLE)),
        new Entry("client/world/ClientWorld", "638", List.of(Family.FAKE_GAMBLE)),
        new Entry("client/network/ClientCommonNetworkHandler", "8673", List.of(Family.FAKE_GAMBLE)),
        new Entry("entity/Entity", "1297", List.of(Family.ROTATION)),
        new Entry("client/input/KeyboardInput", "743", List.of(Family.ROTATION)),
        new Entry("entity/player/PlayerInventory", "1661", List.of(Family.ROTATION)),
        new Entry("client/render/fog/FogRenderer", "758", List.of(Family.ATMOSPHERE)),
        new Entry("client/render/LightmapTextureManager", "765", List.of(Family.ATMOSPHERE)),
        new Entry("client/world/WorldEventHandler", "9959", List.of(Family.FAKE_GAMBLE)),
        new Entry("entity/ItemEntity", "1542", List.of(Family.FAKE_GAMBLE)),
        new Entry("screen/ScreenHandler", "1703", List.of(Family.FAKE_GAMBLE)),
        new Entry("client/gui/screen/ingame/Generic3x3ContainerScreen", "480", List.of(Family.FAKE_GAMBLE)));
    private static final Set<String> BRIDGES = Set.of(
        "dev/celestebeta/host/ResourceReloadBridge", "dev/celestebeta/host/VisualHookBridge",
        "dev/celestebeta/host/InteractionHookBridge", "dev/celestebeta/host/RotationHookBridge",
        "dev/celestebeta/host/AtmosphereHookBridge", "dev/celestebeta/host/FakeGambleHookBridge",
        "dev/celestebeta/host/GameplayCallbackBridge");

    private CombinedHooksTransformer() { }

    /** Complete fixed target list. Accessor-only classes are not redefined. */
    public static List<String> targets(boolean intermediary) {
        return ENTRIES.stream().map(entry -> entry.owner(intermediary)).toList();
    }
    public static Set<String> supportedNames() {
        Set<String> names = new LinkedHashSet<>(targets(false)); names.addAll(targets(true));
        return Collections.unmodifiableSet(names);
    }
    public static Set<String> supportedOwners() { return supportedNames(); }
    public static List<String> familiesFor(String owner) { return entry(owner).families.stream().map(family -> family.label).toList(); }

    /**
     * The host must first verify Minecraft/Fabric versions and exclude startup
     * Celeste globally. Bytecode provenance annotations are compatibility hints,
     * not proof of an artifact's identity or authorization.
     */
    public static byte[] prepare(String owner, byte[] original) {
        Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(original, "original");
        Entry entry = entry(owner);
        byte[] prepared = original.clone();
        ClassNode baseline = read(prepared);
        if (!baseline.name.equals(owner)) throw reject("class name and bytes differ");
        rejectExistingHooks(baseline);
        List<String> schema = schema(baseline);
        // Preparation is offline and side-effect free. Discover the methods the
        // families actually edit, rather than maintaining a second target list.
        prepared = compose(owner, prepared, entry, schema, (method, returned) -> false);
        Set<String> edited = new LinkedHashSet<>();
        for (MethodNode method : read(prepared).methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && BRIDGES.contains(call.owner)) {
                    edited.add(method.name + method.desc);
                    break;
                }
            }
        }
        var review = FabricCancellationCompatibility.inspect(baseline, edited);
        if (!review.cancellations().isEmpty()) {
            // Ordinary Mixin injection points were selected before its callback
            // guards added early exits. Replay only when classification matters.
            prepared = compose(owner, original.clone(), entry, schema, (method, returned) -> {
                var cancellation = review.cancellations().get(method.name + method.desc);
                if (cancellation == null) return false;
                int ordinal = 0;
                for (var instruction : method.instructions) {
                    if (instruction.getOpcode() < Opcodes.IRETURN || instruction.getOpcode() > Opcodes.RETURN) continue;
                    if (instruction == returned) return cancellation.returnOrdinals().contains(ordinal);
                    ordinal++;
                }
                throw reject("return anchor disappeared during preparation");
            });
        }
        return prepared;
    }

    private static byte[] compose(String owner, byte[] prepared, Entry entry, List<String> schema,
                                   BiPredicate<MethodNode, AbstractInsnNode> cancellationReturn) {
        for (Family family : entry.families) {
            prepared = family.prepare(owner, prepared, cancellationReturn);
            if (!schema.equals(schema(read(prepared)))) throw reject("schema changed after " + family.label);
        }
        return prepared;
    }

    /** All-or-nothing preparation: no output map is returned when any class fails validation. */
    public static Map<String, byte[]> prepareAll(Map<String, byte[]> originals) {
        Objects.requireNonNull(originals, "originals");
        if (originals.isEmpty()) throw reject("empty preparation batch");
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (var original : originals.entrySet()) result.put(original.getKey(), prepare(original.getKey(), original.getValue()));
        return Collections.unmodifiableMap(result);
    }

    private static void rejectExistingHooks(ClassNode node) {
        for (var method : node.methods) {
            if (method.name.startsWith("celeste$")) throw reject("readable startup Celeste hooks already exist");
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && BRIDGES.contains(call.owner)) throw reject("class already contains a beta hook bridge");
            }
        }
    }
    private static Entry entry(String owner) {
        for (Entry entry : ENTRIES) if (owner.equals(entry.owner(false)) || owner.equals(entry.owner(true))) return entry;
        throw reject("unsupported class: " + owner);
    }
    static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node;
    }
    private static List<String> schema(ClassNode node) {
        List<String> values = new ArrayList<>();
        values.add(node.version + ":" + node.access + ":" + node.name + ":" + node.signature + ":" + node.superName + ":" + node.interfaces);
        values.add(node.outerClass + ":" + node.outerMethod + ":" + node.outerMethodDesc + ":" + node.nestHostClass + ":" + node.nestMembers + ":" + node.permittedSubclasses);
        node.fields.forEach(field -> values.add("F:" + field.access + ":" + field.name + ":" + field.desc + ":" + field.signature + ":" + field.value));
        node.methods.forEach(method -> values.add("M:" + method.access + ":" + method.name + ":" + method.desc + ":" + method.signature + ":" + method.exceptions));
        if (node.recordComponents != null) node.recordComponents.forEach(component -> values.add("R:" + component.name + ":" + component.descriptor + ":" + component.signature));
        return values;
    }
    private record Entry(String named, String intermediary, List<Family> families) {
        private String owner(boolean mapped) { return "net/minecraft/" + (mapped ? "class_" + intermediary : named); }
    }
    private enum Family {
        RELOAD("reload"), VISUAL("visual"), INTERACTION("interaction"), ROTATION("rotation"),
        ATMOSPHERE("atmosphere"), FAKE_GAMBLE("fake-gamble"), GAMEPLAY("gameplay");
        private final String label;
        Family(String label) { this.label = label; }
        private byte[] prepare(String owner, byte[] bytes, BiPredicate<MethodNode, AbstractInsnNode> cancellationReturn) {
            return switch (this) {
                case RELOAD -> ResourceReloadTransformer.prepare(owner, bytes);
                case VISUAL -> VisualHooksTransformer.prepare(owner, bytes, cancellationReturn);
                case INTERACTION -> InteractionHooksTransformer.prepare(owner, bytes, cancellationReturn);
                case ROTATION -> RotationHooksTransformer.prepare(owner, bytes, cancellationReturn);
                case ATMOSPHERE -> AtmosphereHooksTransformer.prepare(owner, bytes);
                case FAKE_GAMBLE -> FakeGambleHooksTransformer.prepare(owner, bytes, cancellationReturn);
                case GAMEPLAY -> GameplayCallbacksTransformer.prepare(owner, bytes, cancellationReturn);
            };
        }
    }
    static IllegalArgumentException reject(String detail) { return new IllegalArgumentException("Combined hook preparation rejected: " + detail); }
}
