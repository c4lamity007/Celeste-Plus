package dev.celestebeta.host.instrument;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

/** Existing world-fog and lightmap method bodies only, with exact startup hook timing. */
public final class AtmosphereHooksTransformer {
    private static final String BRIDGE = "dev/celestebeta/host/AtmosphereHookBridge";
    private static final String BUILDER = "com/mojang/blaze3d/buffers/Std140Builder";
    private static final String PUT_FLOAT = "(F)L" + BUILDER + ";";
    private static final String UPLOAD = "(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V";

    private AtmosphereHooksTransformer() { }
    public static byte[] prepare(String owner, byte[] original) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(original, "original");
        boolean fog = owner.equals("net/minecraft/client/render/fog/FogRenderer") || owner.equals("net/minecraft/class_758");
        boolean lightmap = owner.equals("net/minecraft/client/render/LightmapTextureManager") || owner.equals("net/minecraft/class_765");
        if (!fog && !lightmap) throw reject("unsupported class: " + owner);
        boolean named = !owner.startsWith("net/minecraft/class_");
        ClassReader reader = new ClassReader(original);
        if (!reader.getClassName().equals(owner)) throw reject("class name and bytes differ");
        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) {
            // Global host startup-Celeste detection is also required: obfuscated
            // Mixin handlers need not retain this readable prefix.
            if (method.name.startsWith("celeste$")) throw reject("startup Celeste hooks already exist");
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) throw reject("atmosphere hooks already prepared");
            }
        }
        String camera = named ? "net/minecraft/client/render/Camera" : "net/minecraft/class_4184";
        String world = named ? "net/minecraft/client/world/ClientWorld" : "net/minecraft/class_638";
        String counter = named ? "net/minecraft/client/render/RenderTickCounter" : "net/minecraft/class_9779";
        String name = fog ? named ? "applyFog" : "method_3211" : named ? "update" : "method_3313";
        String desc = fog ? "(L" + camera + ";IL" + counter + ";FL" + world + ";)Lorg/joml/Vector4f;" : "(F)V";
        List<MethodNode> matches = node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(desc)).toList();
        if (matches.size() != 1) throw reject("expected one " + name + desc);
        MethodNode edit = matches.getFirst();
        validate(edit, fog);
        String field = fog ? named ? "fogEnabled" : "field_54018" : named ? "dirty" : "field_4135";
        validateField(node, field, fog);
        if (fog) fog(owner, edit, named, field, camera, world);
        else lightmap(owner, edit, field);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
                MethodVisitor output = super.visitMethod(access, methodName, descriptor, signature, exceptions);
                if (!methodName.equals(name) || !descriptor.equals(desc)) return output;
                edit.accept(output);
                return null;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void validateField(ClassNode node, String name, boolean isStatic) {
        var matches = node.fields.stream().filter(field -> field.name.equals(name)).toList();
        if (matches.size() != 1) throw reject("missing or ambiguous existing field " + name);
        var field = matches.getFirst();
        if (!field.desc.equals("Z") || (field.access & Opcodes.ACC_STATIC) != (isStatic ? Opcodes.ACC_STATIC : 0)
                || (field.access & Opcodes.ACC_FINAL) != 0) throw reject("unexpected existing field shape " + name);
    }
    private static void validate(MethodNode method, boolean fog) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0 || method.instructions.size() == 0) {
            throw reject("unsupported target shape");
        }
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.JSR || opcode == Opcodes.RET) throw reject("legacy subroutine");
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                if (opcode != (fog ? Opcodes.ARETURN : Opcodes.RETURN)) throw reject("unexpected return opcode");
                returns++;
            }
            if (fog && (instruction instanceof VarInsnNode variable && (variable.var == 1 || variable.var == 5)
                    && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE
                    || instruction instanceof IincInsnNode increment && (increment.var == 1 || increment.var == 5))) {
                throw reject("fog callback argument overwritten");
            }
        }
        if (returns == 0) throw reject("no normal return");
    }

    private static void lightmap(String owner, MethodNode method, String dirty) {
        List<MethodInsnNode> puts = find(method, BUILDER, "putFloat", PUT_FLOAT, Opcodes.INVOKEVIRTUAL, 7);
        method.instructions.insertBefore(puts.get(3), new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "nightVision", "(F)F", false));
        method.instructions.insertBefore(puts.get(6), new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "gamma", "(F)F", false));
        InsnList head = new InsnList();
        LabelNode originalStart = new LabelNode();
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "refreshLightmap", "()Z", false));
        head.add(new JumpInsnNode(Opcodes.IFEQ, originalStart));
        head.add(new VarInsnNode(Opcodes.ALOAD, 0));
        head.add(new InsnNode(Opcodes.ICONST_1));
        head.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, dirty, "Z"));
        head.add(originalStart);
        head.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {owner, Opcodes.FLOAT}, 0, new Object[0]));
        method.instructions.insert(head);
        method.maxStack = Math.max(method.maxStack, 2);
    }

    private static void fog(String owner, MethodNode method, boolean named, String enabled, String camera, String world) {
        HookArgumentFrames.retain(owner, method, 1, 5);
        MethodInsnNode device = find(method, "com/mojang/blaze3d/systems/RenderSystem", "getDevice",
            "()Lcom/mojang/blaze3d/systems/GpuDevice;", Opcodes.INVOKESTATIC, 1).getFirst();
        MethodInsnNode upload = find(method, owner, named ? "applyFog" : "method_71110", UPLOAD, Opcodes.INVOKEVIRTUAL, 1).getFirst();
        if (method.instructions.indexOf(device) >= method.instructions.indexOf(upload)) throw reject("fog anchors reordered");
        Frame<BasicValue>[] frames;
        try { frames = new Analyzer<>(new ExactReferences()).analyze(owner, method); }
        catch (AnalyzerException failure) { throw reject("unverifiable fog local flow: " + failure.getMessage()); }
        Frame<BasicValue> atDevice = frames[method.instructions.indexOf(device)];
        Frame<BasicValue> atUpload = frames[method.instructions.indexOf(upload)];
        if (atDevice == null || atUpload == null) throw reject("unreachable fog anchor");
        verifyLocal(atDevice, 1, camera); verifyLocal(atDevice, 5, world);
        verifyLocal(atUpload, 1, camera); verifyLocal(atUpload, 5, world);
        int data = uniqueLocal(atDevice, named ? "net/minecraft/client/render/fog/FogData" : "net/minecraft/class_7285");
        int color = uniqueLocal(atDevice, "org/joml/Vector4f");
        InsnList shared = new InsnList();
        shared.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, enabled, "Z"));
        shared.add(new VarInsnNode(Opcodes.ALOAD, 1));
        shared.add(new VarInsnNode(Opcodes.ALOAD, 5));
        shared.add(new VarInsnNode(Opcodes.ALOAD, data));
        shared.add(new VarInsnNode(Opcodes.ALOAD, color));
        shared.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "sharedFog",
            "(ZLjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false));
        method.instructions.insertBefore(device, shared);

        // Preserve the already-evaluated receiver and every argument at the
        // upload boundary. Unlike the Sodium path this never mutates FogData.
        int base = method.maxLocals;
        InsnList args = new InsnList();
        for (int i = 5; i >= 0; i--) args.add(new VarInsnNode(Opcodes.FSTORE, base + 4 + i));
        args.add(new VarInsnNode(Opcodes.ASTORE, base + 3)); // exact color reference
        args.add(new VarInsnNode(Opcodes.ISTORE, base + 2)); // position
        args.add(new VarInsnNode(Opcodes.ASTORE, base + 1)); // buffer
        args.add(new VarInsnNode(Opcodes.ASTORE, base)); // upload receiver
        args.add(new IntInsnNode(Opcodes.BIPUSH, 6));
        args.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_FLOAT));
        args.add(new VarInsnNode(Opcodes.ASTORE, base + 10));
        for (int i = 0; i < 6; i++) {
            args.add(new VarInsnNode(Opcodes.ALOAD, base + 10));
            args.add(new InsnNode(Opcodes.ICONST_0 + i));
            args.add(new VarInsnNode(Opcodes.FLOAD, base + 4 + i));
            args.add(new InsnNode(Opcodes.FASTORE));
        }
        args.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, enabled, "Z"));
        args.add(new VarInsnNode(Opcodes.ALOAD, 1));
        args.add(new VarInsnNode(Opcodes.ALOAD, 5));
        args.add(new VarInsnNode(Opcodes.ALOAD, base + 3));
        args.add(new VarInsnNode(Opcodes.ALOAD, base + 10));
        args.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "vanillaFog",
            "(ZLjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;[F)V", false));
        args.add(new VarInsnNode(Opcodes.ALOAD, base));
        args.add(new VarInsnNode(Opcodes.ALOAD, base + 1));
        args.add(new VarInsnNode(Opcodes.ILOAD, base + 2));
        args.add(new VarInsnNode(Opcodes.ALOAD, base + 3));
        for (int i = 0; i < 6; i++) {
            args.add(new VarInsnNode(Opcodes.ALOAD, base + 10));
            args.add(new InsnNode(Opcodes.ICONST_0 + i));
            args.add(new InsnNode(Opcodes.FALOAD));
        }
        method.instructions.insertBefore(upload, args);
        method.maxLocals = base + 11;
        method.maxStack = Math.max(method.maxStack + 2, atDevice.getStackSize() + 5);
    }

    private static List<MethodInsnNode> find(MethodNode method, String owner, String name, String desc, int opcode, int count) {
        List<MethodInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) {
                if (call.getOpcode() != opcode || call.itf) throw reject("unexpected invocation shape for " + name);
                found.add(call);
            }
        }
        if (found.size() != count) throw reject("expected " + count + " calls to " + owner + "." + name);
        return found;
    }
    private static int uniqueLocal(Frame<BasicValue> frame, String type) {
        int found = -1;
        for (int i = 0; i < frame.getLocals(); i++) {
            if (frame.getLocal(i) != null && Type.getObjectType(type).equals(frame.getLocal(i).getType())) {
                if (found != -1) throw reject("ambiguous fog local " + type);
                found = i;
            }
        }
        if (found < 0) throw reject("missing fog local " + type);
        return found;
    }
    private static void verifyLocal(Frame<BasicValue> frame, int slot, String type) {
        if (frame.getLocals() <= slot || frame.getLocal(slot) == null || !Type.getObjectType(type).equals(frame.getLocal(slot).getType())) {
            throw reject("unexpected fog argument local " + slot);
        }
    }
    /** Tracks exact reference values without resolving or loading a Minecraft class. */
    private static final class ExactReferences extends BasicInterpreter {
        ExactReferences() { super(Opcodes.ASM9); }
        @Override public BasicValue newValue(Type type) {
            if (type != null && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)) return new BasicValue(type);
            return super.newValue(type);
        }
        @Override public BasicValue merge(BasicValue first, BasicValue second) {
            if (first.equals(second)) return first;
            if (first.isReference() && second.isReference()) return BasicValue.REFERENCE_VALUE;
            return BasicValue.UNINITIALIZED_VALUE;
        }
    }
    private static IllegalArgumentException reject(String reason) { return new IllegalArgumentException("Atmosphere hook preparation rejected: " + reason); }
}
