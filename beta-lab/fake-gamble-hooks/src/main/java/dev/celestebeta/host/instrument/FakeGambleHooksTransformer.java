package dev.celestebeta.host.instrument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Existing client-local simulation HEAD/TAIL hooks only; no added target members. */
public final class FakeGambleHooksTransformer {
    private static final String BRIDGE = "dev/celestebeta/host/FakeGambleHookBridge";
    private static final String ONE_VOID = "(Ljava/lang/Object;)V";
    private static final String TWO_VOID = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private FakeGambleHooksTransformer() { }

    public static byte[] prepare(String owner, byte[] original) {
        Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(original, "original");
        List<Target> targets = targets(owner);
        ClassReader reader = new ClassReader(original);
        if (!reader.getClassName().equals(owner)) throw reject("class name and bytes differ");
        ClassNode node = new ClassNode(Opcodes.ASM9); reader.accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) {
            // Host-wide exclusion of startup Celeste is still required for
            // obfuscated Mixin handler names that do not retain this prefix.
            if (method.name.startsWith("celeste$")) throw reject("startup Celeste hooks already exist");
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) throw reject("local inventory simulation hooks already prepared");
            }
        }
        Map<String, MethodNode> edits = new LinkedHashMap<>();
        for (Target target : targets) {
            var matches = node.methods.stream().filter(method -> method.name.equals(target.name) && method.desc.equals(target.desc)).toList();
            if (matches.size() != 1) throw reject("expected one " + target.name + target.desc);
            MethodNode method = matches.getFirst();
            validate(method, target.kind);
            edits.put(target.name + target.desc, method);
        }
        for (Target target : targets) {
            MethodNode method = edits.get(target.name + target.desc);
            InsnList callback = callback(target.kind);
            if (target.kind == Kind.ITEM_TICK || target.kind == Kind.SYNC) {
                if (target.kind == Kind.ITEM_TICK) HookArgumentFrames.retain(owner, method, 0);
                AbstractInsnNode lastReturn = null;
                for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() == Opcodes.RETURN) lastReturn = instruction;
                if (lastReturn == null) throw reject("missing final return");
                // Preserve Mixin TAIL, including deliberate skips on earlier returns.
                method.instructions.insertBefore(lastReturn, callback);
            } else {
                if (target.kind.cancel) {
                    LabelNode originalStart = new LabelNode();
                    callback.add(new JumpInsnNode(Opcodes.IFEQ, originalStart));
                    callback.add(new InsnNode(Opcodes.RETURN));
                    callback.add(originalStart);
                    callback.add(argumentFrame(owner, method));
                }
                method.instructions.insert(callback);
            }
            method.maxStack = Math.max(method.maxStack, target.kind == Kind.CLICK ? 5 : target.kind == Kind.WORLD ? 4 : 2);
        }
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor output = super.visitMethod(access, name, desc, signature, exceptions);
                MethodNode edit = edits.get(name + desc);
                if (edit == null) return output;
                edit.accept(output); return null;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void validate(MethodNode method, Kind kind) {
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0 || method.instructions.size() == 0) {
            throw reject("unsupported target shape");
        }
        int returnOpcode = Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN);
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.JSR || opcode == Opcodes.RET) throw reject("legacy subroutine");
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                if (opcode != returnOpcode) throw reject("unexpected return type");
                returns++;
            }
            if (kind == Kind.ITEM_TICK && (instruction instanceof VarInsnNode variable && variable.var == 0
                    && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE
                    || instruction instanceof IincInsnNode increment && increment.var == 0)) {
                throw reject("item tick receiver overwritten");
            }
        }
        if (returns == 0) throw reject("no normal return");
        if (kind.cancel && returnOpcode != Opcodes.RETURN) throw reject("unexpected cancellable target return");
    }

    private static InsnList callback(Kind kind) {
        InsnList code = new InsnList();
        String name;
        String desc;
        switch (kind) {
            case CLICK -> {
                for (int i = 1; i <= 3; i++) code.add(new VarInsnNode(Opcodes.ILOAD, i));
                code.add(new VarInsnNode(Opcodes.ALOAD, 4)); code.add(new VarInsnNode(Opcodes.ALOAD, 5));
                name = "click"; desc = "(IIILjava/lang/Object;Ljava/lang/Object;)Z";
            }
            case WORLD -> {
                code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ILOAD, 1));
                code.add(new VarInsnNode(Opcodes.ALOAD, 2)); code.add(new VarInsnNode(Opcodes.ILOAD, 3));
                name = "worldEvent"; desc = "(Ljava/lang/Object;ILjava/lang/Object;I)Z";
            }
            case PICKUP -> {
                code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                name = "pickup"; desc = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
            }
            case ITEM_TICK -> { code.add(new VarInsnNode(Opcodes.ALOAD, 0)); name = "itemTick"; desc = ONE_VOID; }
            case SYNC -> { name = "afterSync"; desc = "()V"; }
            case CLOSED -> {
                code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                name = "screenClosed"; desc = TWO_VOID;
            }
            case INTERACT -> { code.add(new VarInsnNode(Opcodes.ALOAD, 3)); name = "noteDispenser"; desc = ONE_VOID; }
            case PICK -> { code.add(new VarInsnNode(Opcodes.ALOAD, 0)); name = "pick"; desc = "(Ljava/lang/Object;)Z"; }
            case RENDER -> { code.add(new VarInsnNode(Opcodes.ALOAD, 0)); name = "screenRender"; desc = ONE_VOID; }
            default -> throw reject("unsupported callback kind");
        }
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, name, desc, false));
        return code;
    }
    private static FrameNode argumentFrame(String owner, MethodNode method) {
        List<Object> locals = new ArrayList<>(); locals.add(owner);
        for (Type type : Type.getArgumentTypes(method.desc)) {
            locals.add(switch (type.getSort()) {
                case Type.OBJECT, Type.ARRAY -> type.getInternalName();
                case Type.FLOAT -> Opcodes.FLOAT;
                case Type.DOUBLE -> Opcodes.DOUBLE;
                case Type.LONG -> Opcodes.LONG;
                default -> Opcodes.INTEGER;
            });
        }
        return new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 0, new Object[0]);
    }
    private static List<Target> targets(String owner) {
        boolean named = !owner.startsWith("net/minecraft/class_");
        String player = named ? "net/minecraft/entity/player/PlayerEntity" : "net/minecraft/class_1657";
        return switch (owner) {
            case "net/minecraft/client/network/ClientPlayerInteractionManager", "net/minecraft/class_636" -> {
                String slotAction = named ? "net/minecraft/screen/slot/SlotActionType" : "net/minecraft/class_1713";
                String clientPlayer = named ? "net/minecraft/client/network/ClientPlayerEntity" : "net/minecraft/class_746";
                String hand = named ? "net/minecraft/util/Hand" : "net/minecraft/class_1268";
                String hit = named ? "net/minecraft/util/hit/BlockHitResult" : "net/minecraft/class_3965";
                String result = named ? "net/minecraft/util/ActionResult" : "net/minecraft/class_1269";
                yield List.of(new Target(named ? "clickSlot" : "method_2906", "(IIIL" + slotAction + ";L" + player + ";)V", Kind.CLICK),
                    new Target(named ? "interactBlock" : "method_2896", "(L" + clientPlayer + ";L" + hand + ";L" + hit + ";)L" + result + ";", Kind.INTERACT));
            }
            case "net/minecraft/client/world/WorldEventHandler", "net/minecraft/class_9959" -> {
                String pos = named ? "net/minecraft/util/math/BlockPos" : "net/minecraft/class_2338";
                yield List.of(new Target(named ? "processWorldEvent" : "method_62194", "(IL" + pos + ";I)V", Kind.WORLD));
            }
            case "net/minecraft/entity/ItemEntity", "net/minecraft/class_1542" -> List.of(
                new Target(named ? "onPlayerCollision" : "method_5694", "(L" + player + ";)V", Kind.PICKUP),
                new Target(named ? "tick" : "method_5773", "()V", Kind.ITEM_TICK));
            case "net/minecraft/client/network/ClientPlayNetworkHandler", "net/minecraft/class_634" -> {
                String slot = named ? "net/minecraft/network/packet/s2c/play/ScreenHandlerSlotUpdateS2CPacket" : "net/minecraft/class_2653";
                String inventory = named ? "net/minecraft/network/packet/s2c/play/InventoryS2CPacket" : "net/minecraft/class_2649";
                yield List.of(new Target(named ? "onScreenHandlerSlotUpdate" : "method_11109", "(L" + slot + ";)V", Kind.SYNC),
                    new Target(named ? "onInventory" : "method_11153", "(L" + inventory + ";)V", Kind.SYNC));
            }
            case "net/minecraft/screen/ScreenHandler", "net/minecraft/class_1703" -> List.of(
                new Target(named ? "onClosed" : "method_7595", "(L" + player + ";)V", Kind.CLOSED));
            case "net/minecraft/client/MinecraftClient", "net/minecraft/class_310" -> List.of(
                new Target(named ? "doItemPick" : "method_1511", "()V", Kind.PICK));
            case "net/minecraft/client/gui/screen/ingame/Generic3x3ContainerScreen", "net/minecraft/class_480" -> {
                String context = named ? "net/minecraft/client/gui/DrawContext" : "net/minecraft/class_332";
                yield List.of(new Target(named ? "render" : "method_25394", "(L" + context + ";IIF)V", Kind.RENDER));
            }
            default -> throw reject("unsupported class: " + owner);
        };
    }
    private enum Kind {
        CLICK(true), WORLD(true), PICKUP(true), ITEM_TICK(false), SYNC(false), CLOSED(false), INTERACT(false), PICK(true), RENDER(false);
        private final boolean cancel;
        Kind(boolean cancel) { this.cancel = cancel; }
    }
    private record Target(String name, String desc, Kind kind) { }
    private static IllegalArgumentException reject(String detail) { return new IllegalArgumentException("Local inventory simulation hook preparation rejected: " + detail); }
}
