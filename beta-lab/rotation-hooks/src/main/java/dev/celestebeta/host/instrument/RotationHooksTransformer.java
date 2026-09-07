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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Exact 1.21.11 rotation, input-tail and selected-stack HEAD hooks; no new target members. */
public final class RotationHooksTransformer {
    private static final String BRIDGE = "dev/celestebeta/host/RotationHookBridge";
    private static final String ARRAY = "[Ljava/lang/Object;";

    private RotationHooksTransformer() { }

    public static byte[] prepare(String owner, byte[] original) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(original, "original");
        List<Target> targets = targets(owner);
        ClassReader reader = new ClassReader(original);
        if (!reader.getClassName().equals(owner)) throw reject("class name and bytes differ");
        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) {
            // This catches clear startup hooks, not obfuscated startup installs.
            // The host must also perform its global startup-Celeste exclusion.
            if (method.name.startsWith("celeste$")) throw reject("startup Celeste hooks already exist");
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) throw reject("rotation hooks already prepared");
            }
        }
        Map<String, MethodNode> edits = new LinkedHashMap<>();
        for (Target target : targets) {
            List<MethodNode> matches = node.methods.stream().filter(method -> method.name.equals(target.name) && method.desc.equals(target.desc)).toList();
            if (matches.size() != 1) throw reject("expected one " + target.name + target.desc);
            MethodNode method = matches.getFirst();
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0 || method.instructions.size() == 0) {
                throw reject("unsupported target method shape");
            }
            validate(method, target.kind == Kind.KEYBOARD);
            for (Redirect redirect : target.redirects) calls(method, redirect);
            edits.put(target.name + target.desc, method);
        }
        for (Target target : targets) {
            MethodNode method = edits.get(target.name + target.desc);
            for (Redirect redirect : target.redirects) replaceCalls(method, redirect);
            if (target.kind == Kind.KEYBOARD) keyboardTail(method);
            if (target.kind == Kind.GET_STACK || target.kind == Kind.SET_STACK) stackHead(owner, method, target.kind == Kind.SET_STACK);
        }
        // Preserve the existing frames and untouched methods; never load target
        // classes to guess COMPUTE_FRAMES common superclasses during preparation.
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor output = super.visitMethod(access, name, desc, signature, exceptions);
                MethodNode edit = edits.get(name + desc);
                if (edit == null) return output;
                edit.accept(output);
                return null;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void validate(MethodNode method, boolean keyboard) {
        int returnOpcode = Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN);
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.JSR || opcode == Opcodes.RET) throw reject("legacy subroutine");
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                if (opcode != returnOpcode) throw reject("unexpected return opcode");
                returns++;
            }
            if (keyboard && (instruction instanceof VarInsnNode variable && variable.var == 0
                    && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE
                    || instruction instanceof IincInsnNode increment && increment.var == 0)) {
                throw reject("keyboard callback receiver overwritten");
            }
        }
        if (returns == 0) throw reject("no normal return");
    }

    private static List<MethodInsnNode> calls(MethodNode method, Redirect redirect) {
        List<MethodInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(redirect.owner)
                    && call.name.equals(redirect.name) && call.desc.equals(redirect.desc)) {
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || call.itf) throw reject("unsupported redirect invocation");
                matches.add(call);
            }
        }
        if (matches.size() != redirect.count) throw reject("expected " + redirect.count + " calls to " + redirect.owner + "." + redirect.name + " in " + method.name);
        return matches;
    }

    private static void replaceCalls(MethodNode method, Redirect redirect) {
        for (MethodInsnNode call : calls(method, redirect)) {
            Type returned = Type.getReturnType(redirect.desc);
            boolean object = returned.getSort() == Type.OBJECT;
            MethodInsnNode bridge = new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, redirect.hook,
                object ? "(Ljava/lang/Object;)Ljava/lang/Object;" : "(Ljava/lang/Object;)F", false);
            method.instructions.set(call, bridge);
            if (object) method.instructions.insert(bridge, new TypeInsnNode(Opcodes.CHECKCAST, returned.getInternalName()));
        }
    }

    private static void keyboardTail(MethodNode method) {
        AbstractInsnNode lastReturn = null;
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() == Opcodes.RETURN) lastReturn = instruction;
        if (lastReturn == null) throw reject("missing input TAIL return");
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "keyboardTick", "(Ljava/lang/Object;)V", false));
        // Mixin @At("TAIL") means the final return instruction, not every return.
        method.instructions.insertBefore(lastReturn, hook);
        method.maxStack = Math.max(method.maxStack, 1);
    }

    private static void stackHead(String owner, MethodNode method, boolean set) {
        InsnList hook = new InsnList();
        LabelNode originalStart = new LabelNode();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(set ? new VarInsnNode(Opcodes.ALOAD, 1) : new InsnNode(Opcodes.ACONST_NULL));
        hook.add(new InsnNode(set ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "selectedStack", "(Ljava/lang/Object;Ljava/lang/Object;Z)" + ARRAY, false));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new JumpInsnNode(Opcodes.IFNULL, originalStart));
        hook.add(new InsnNode(Opcodes.ICONST_0));
        hook.add(new InsnNode(Opcodes.AALOAD));
        hook.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getReturnType(method.desc).getInternalName()));
        hook.add(new InsnNode(Opcodes.ARETURN));
        hook.add(originalStart);
        List<Object> locals = new ArrayList<>();
        locals.add(owner);
        for (Type argument : Type.getArgumentTypes(method.desc)) locals.add(argument.getInternalName());
        hook.add(new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 1, new Object[] {ARRAY}));
        hook.add(new InsnNode(Opcodes.POP));
        method.instructions.insert(hook);
        method.maxStack = Math.max(method.maxStack, 3);
    }

    /** Fixed mapping pairs and invocation counts verified against this checkout's Yarn 1.21.11 build 6. */
    private static List<Target> targets(String owner) {
        boolean named = !owner.startsWith("net/minecraft/class_");
        String yaw = named ? "getYaw" : "method_36454";
        String pitch = named ? "getPitch" : "method_36455";
        String vector = named ? "net/minecraft/util/math/Vec3d" : "net/minecraft/class_243";
        String stack = named ? "net/minecraft/item/ItemStack" : "net/minecraft/class_1799";
        return switch (owner) {
            case "net/minecraft/client/network/ClientPlayerEntity", "net/minecraft/class_746" -> List.of(
                new Target(named ? "tick" : "method_5773", "()V", Kind.REDIRECT,
                    List.of(new Redirect(owner, yaw, "()F", "outgoingYaw", 1), new Redirect(owner, pitch, "()F", "outgoingPitch", 1))),
                new Target(named ? "sendMovementPackets" : "method_3136", "()V", Kind.REDIRECT,
                    List.of(new Redirect(owner, yaw, "()F", "outgoingYaw", 4), new Redirect(owner, pitch, "()F", "outgoingPitch", 4))));
            case "net/minecraft/client/network/ClientPlayerInteractionManager", "net/minecraft/class_636" -> {
                String player = named ? "net/minecraft/entity/player/PlayerEntity" : "net/minecraft/class_1657";
                String hand = named ? "net/minecraft/util/Hand" : "net/minecraft/class_1268";
                String packet = named ? "net/minecraft/network/packet/Packet" : "net/minecraft/class_2596";
                yield List.of(new Target("method_41929", "(L" + hand + ";L" + player + ";Lorg/apache/commons/lang3/mutable/MutableObject;I)L" + packet + ";", Kind.REDIRECT,
                    List.of(new Redirect(player, yaw, "()F", "itemYaw", 1), new Redirect(player, pitch, "()F", "itemPitch", 1))));
            }
            case "net/minecraft/entity/Entity", "net/minecraft/class_1297" -> List.of(
                new Target(named ? "updateVelocity" : "method_5724", "(FL" + vector + ";)V", Kind.REDIRECT,
                    List.of(new Redirect(owner, yaw, "()F", "movementYaw", 1))));
            case "net/minecraft/client/input/KeyboardInput", "net/minecraft/class_743" -> List.of(
                new Target(named ? "tick" : "method_3129", "()V", Kind.KEYBOARD, List.of()));
            case "net/minecraft/entity/LivingEntity", "net/minecraft/class_1309" -> List.of(
                new Target(named ? "calcGlidingVelocity" : "method_61430", "(L" + vector + ";)L" + vector + ";", Kind.REDIRECT,
                    List.of(new Redirect(owner, named ? "getRotationVector" : "method_5720", "()L" + vector + ";", "glidingVector", 1),
                        new Redirect(owner, pitch, "()F", "glidingPitch", 1))),
                new Target(named ? "jump" : "method_6043", "()V", Kind.REDIRECT,
                    List.of(new Redirect(owner, yaw, "()F", "sprintJumpYaw", 1))));
            case "net/minecraft/entity/player/PlayerInventory", "net/minecraft/class_1661" -> List.of(
                new Target(named ? "getSelectedStack" : "method_7391", "()L" + stack + ";", Kind.GET_STACK, List.of()),
                new Target(named ? "setSelectedStack" : "method_67531", "(L" + stack + ";)L" + stack + ";", Kind.SET_STACK, List.of()));
            default -> throw reject("unsupported class: " + owner);
        };
    }

    private enum Kind { REDIRECT, KEYBOARD, GET_STACK, SET_STACK }
    private record Target(String name, String desc, Kind kind, List<Redirect> redirects) { }
    private record Redirect(String owner, String name, String desc, String hook, int count) { }
    private static IllegalArgumentException reject(String reason) { return new IllegalArgumentException("Rotation hook preparation rejected: " + reason); }
}
