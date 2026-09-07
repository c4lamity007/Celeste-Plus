package dev.celestebeta.host.instrument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Existing server observations and sword click scope, without target schema changes. */
public final class GameplayCallbacksTransformer {
    private static final String BRIDGE = "dev/celestebeta/host/GameplayCallbackBridge";
    private GameplayCallbacksTransformer() { }
    private record Target(String name, String descriptor, int kind) { }

    public static byte[] prepare(String owner, byte[] bytes) {
        boolean named = !owner.startsWith("net/minecraft/class_");
        List<Target> targets = targets(owner, named);
        ClassReader reader = new ClassReader(bytes);
        if (!reader.getClassName().equals(owner)) throw reject("class identity mismatch");
        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("celeste$")) throw reject("startup Celeste hooks exist; global host exclusion is also required");
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) throw reject("callbacks already prepared");
            }
        }
        Map<String, MethodNode> edits = new LinkedHashMap<>();
        for (Target target : targets) {
            List<MethodNode> found = node.methods.stream().filter(m -> m.name.equals(target.name) && m.desc.equals(target.descriptor)).toList();
            if (found.size() != 1) throw reject("expected one " + target.name + target.descriptor);
            MethodNode method = found.getFirst();
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) throw reject("unsupported method shape");
            int returns = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                int code = instruction.getOpcode();
                if (code >= Opcodes.IRETURN && code <= Opcodes.RETURN) {
                    if (code != (target.kind == 6 ? Opcodes.IRETURN : Opcodes.RETURN)) throw reject("unexpected return");
                    returns++;
                }
                if (code == Opcodes.JSR || code == Opcodes.RET) throw reject("legacy subroutine");
                if (target.kind < 5 && (instruction instanceof VarInsnNode variable && variable.var <= 1
                    && code >= Opcodes.ISTORE && code <= Opcodes.ASTORE
                    || instruction instanceof IincInsnNode increment && increment.var <= 1)) throw reject("packet callback argument overwritten");
            }
            if (returns == 0) throw reject("no normal return");
            if (target.kind == 5 || target.kind == 6) calls(method, owner, named, target.kind);
            edits.put(target.name + target.descriptor, method);
        }
        for (Target target : targets) {
            MethodNode method = edits.get(target.name + target.descriptor);
            if (target.kind < 5) {
                // Mixin TAIL is the final return instruction, unlike RETURN
                // which would also observe branches that return early.
                AbstractInsnNode tail = method.instructions.getLast();
                while (tail.getOpcode() != Opcodes.RETURN) tail = tail.getPrevious();
                InsnList callback = new InsnList();
                callback.add(new InsnNode(Opcodes.ICONST_0 + target.kind));
                callback.add(new VarInsnNode(Opcodes.ALOAD, 1));
                callback.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "serverUpdate", "(ILjava/lang/Object;)V", false));
                method.instructions.insertBefore(tail, callback);
                method.maxStack = Math.max(method.maxStack, 2);
            } else if (target.kind == 7) {
                InsnList callback = new InsnList();
                callback.add(new VarInsnNode(Opcodes.ALOAD, 2));
                callback.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "entityAttack", "(Ljava/lang/Object;)V", false));
                method.instructions.insert(callback);
                method.maxStack = Math.max(method.maxStack, 1);
            } else if (target.kind == 5) {
                for (MethodInsnNode call : calls(method, owner, named, target.kind)) {
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "attack", "(Ljava/lang/Object;)Z", false));
                }
            } else {
                int handLocal = method.maxLocals++;
                for (MethodInsnNode call : calls(method, owner, named, target.kind)) {
                    InsnList save = new InsnList();
                    save.add(new VarInsnNode(Opcodes.ASTORE, handLocal));
                    save.add(new VarInsnNode(Opcodes.ALOAD, handLocal));
                    method.instructions.insertBefore(call, save);
                    InsnList filter = new InsnList();
                    filter.add(new VarInsnNode(Opcodes.ALOAD, handLocal));
                    filter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "mainHand", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
                    filter.add(new TypeInsnNode(Opcodes.CHECKCAST, named ? "net/minecraft/item/ItemStack" : "net/minecraft/class_1799"));
                    method.instructions.insert(call, filter);
                }
                method.maxStack++;
            }
        }
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
                MethodNode edited = edits.get(name + descriptor);
                if (edited == null) return output;
                edited.accept(output); return null;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static List<MethodInsnNode> calls(MethodNode method, String owner, boolean named, int kind) {
        String callOwner = kind == 5 ? owner : named ? "net/minecraft/client/network/ClientPlayerEntity" : "net/minecraft/class_746";
        String name = kind == 5 ? named ? "doAttack" : "method_1536" : named ? "getStackInHand" : "method_5998";
        String descriptor = kind == 5 ? "()Z" : named ? "(Lnet/minecraft/util/Hand;)Lnet/minecraft/item/ItemStack;"
            : "(Lnet/minecraft/class_1268;)Lnet/minecraft/class_1799;";
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(callOwner) && call.name.equals(name) && call.desc.equals(descriptor)) {
                if (call.itf || call.getOpcode() == Opcodes.INVOKESTATIC) throw reject("unexpected invocation kind");
                result.add(call);
            }
        }
        if (result.isEmpty()) throw reject("expected original call " + name);
        return result;
    }
    private static List<Target> targets(String owner, boolean named) {
        if (owner.equals("net/minecraft/client/network/ClientPlayNetworkHandler") || owner.equals("net/minecraft/class_634")) {
            String[] names = { "onWorldTimeUpdate", "onBlockUpdate", "onChunkDeltaUpdate", "onEntitySpawn", "onEntitiesDestroy" };
            String[] mapped = { "method_11079", "method_11136", "method_11100", "method_11112", "method_11095" };
            String[] packets = { "WorldTimeUpdateS2CPacket", "BlockUpdateS2CPacket", "ChunkDeltaUpdateS2CPacket", "EntitySpawnS2CPacket", "EntitiesDestroyS2CPacket" };
            String[] ids = { "2761", "2626", "2637", "2604", "2716" };
            List<Target> result = new ArrayList<>();
            for (int i = 0; i < names.length; i++) result.add(new Target(named ? names[i] : mapped[i],
                "(L" + (named ? "net/minecraft/network/packet/s2c/play/" + packets[i] : "net/minecraft/class_" + ids[i]) + ";)V", i));
            return result;
        }
        if (owner.equals("net/minecraft/client/MinecraftClient") || owner.equals("net/minecraft/class_310")) {
            return List.of(new Target(named ? "handleInputEvents" : "method_1508", "()V", 5), new Target(named ? "doAttack" : "method_1536", "()Z", 6));
        }
        if (owner.equals("net/minecraft/client/network/ClientPlayerInteractionManager") || owner.equals("net/minecraft/class_636")) {
            return List.of(new Target(named ? "attackEntity" : "method_2918", named
                ? "(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/entity/Entity;)V"
                : "(Lnet/minecraft/class_1657;Lnet/minecraft/class_1297;)V", 7));
        }
        throw reject("unsupported target");
    }
    private static IllegalArgumentException reject(String reason) { return new IllegalArgumentException("Gameplay callback preparation rejected: " + reason); }
}
