package dev.celestebeta.host.instrument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
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
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Prepares existing 1.21.11 presentation method bodies without adding class members. */
public final class VisualHooksTransformer {
    private static final String BRIDGE = "dev/celestebeta/host/VisualHookBridge";

    private VisualHooksTransformer() { }

    public static byte[] prepare(String owner, byte[] original) {
        return prepare(owner, original, (method, returned) -> false);
    }

    public static byte[] prepare(String owner, byte[] original, BiPredicate<MethodNode, AbstractInsnNode> cancellationReturn) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(original, "original");
        List<Target> targets = targets(owner);
        ClassReader reader = new ClassReader(original);
        if (!reader.getClassName().equals(owner)) throw reject("class name and bytes differ");
        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("celeste$")) throw reject("startup Celeste hooks are already present");
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && BRIDGE.equals(call.owner)) {
                    throw reject("visual hooks are already prepared");
                }
            }
        }
        Map<String, MethodNode> edits = new LinkedHashMap<>();
        for (Target target : targets) {
            List<MethodNode> matches = node.methods.stream()
                .filter(method -> method.name.equals(target.name) && method.desc.equals(target.descriptor)).toList();
            if (matches.size() != 1) throw reject("expected one method: " + target.name + target.descriptor);
            MethodNode method = matches.getFirst();
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0
                    || method.instructions.size() == 0) throw reject("unsupported method shape: " + target.name);
            validate(target, method);
            if (target.kind == Kind.FLUSH) findGuiRender(owner, method);
            if (target.kind == Kind.HAND) findHandPush(owner, method);
            edits.put(target.name + target.descriptor, method);
        }
        for (Target target : targets) {
            MethodNode method = edits.get(target.name + target.descriptor);
            if (target.kind == Kind.EXTRACT) {
                HookArgumentFrames.retain(owner, method, 0, 1, 3, 4);
                addExtraction(method, cancellationReturn);
            } else if (target.kind == Kind.SWING) {
                HookArgumentFrames.retain(owner, method, 0);
                addSwing(method, cancellationReturn);
            } else if (target.kind == Kind.ROTATIONS) addRotations(owner, method, cancellationReturn);
            else if (target.kind == Kind.ITEM_HIGHLIGHT) {
                HookArgumentFrames.retain(owner, method, 1, 2);
                addItemHighlight(method, cancellationReturn);
            }
            else if (target.kind == Kind.HAND) addHandTransform(owner, method);
            else if (target.kind == Kind.HAND_PASS) addHandPass(method);
            else if (target.kind == Kind.HAND_CONTEXT) addHandContext(method);
            else if (target.kind == Kind.FLUSH) {
                method.instructions.insert(findGuiRender(owner, method), new MethodInsnNode(
                    Opcodes.INVOKESTATIC, BRIDGE, "flushGui", "()V", false));
            }
            else if (target.kind == Kind.HUD || target.kind == Kind.MEDIA || target.kind == Kind.MODEL) {
                addPresentationHead(owner, method, target.kind);
            }
            else addCancellation(owner, method, target.kind == Kind.DRAW ? "drawSky" : "ownsSky");
        }
        // Copy untouched method bytes and preserve existing frames. This never
        // resolves Minecraft classes or uses COMPUTE_FRAMES/classloader guessing.
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                MethodVisitor downstream = super.visitMethod(access, name, descriptor, signature, exceptions);
                MethodNode edited = edits.get(name + descriptor);
                if (edited == null) return downstream;
                edited.accept(downstream);
                return null;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void validate(Target target, MethodNode method) {
        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                if (opcode != (target.kind == Kind.SWING || target.kind == Kind.MEDIA ? Opcodes.IRETURN : Opcodes.RETURN)) {
                    throw reject("unexpected return in " + method.name);
                }
                returns++;
            }
            if (opcode == Opcodes.JSR || opcode == Opcodes.RET) throw reject("legacy subroutine");
            if (instruction instanceof VarInsnNode variable && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                int width = opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE ? 2 : 1;
                for (int local = variable.var; local < variable.var + width; local++) {
                    if (target.kind != Kind.ROTATIONS && readsAtReturn(target.kind, local)) {
                        throw reject("hook argument overwritten in " + method.name);
                    }
                }
            } else if (instruction instanceof IincInsnNode increment && target.kind != Kind.ROTATIONS
                    && readsAtReturn(target.kind, increment.var)) {
                throw reject("hook argument overwritten in " + method.name);
            }
        }
        if (returns == 0) throw reject("no normal return in " + method.name);
    }

    // Only locals consumed by a return hook must retain their original value.
    // Fabric may adjust tick delta in-place; neither extraction hook reads it.
    private static boolean readsAtReturn(Kind kind, int local) {
        return switch (kind) {
            case EXTRACT -> local == 0 || local == 1 || local == 3 || local == 4;
            case SWING -> local == 0;
            case ROTATIONS -> local == 1 || local == 2;
            case ITEM_HIGHLIGHT -> local == 1 || local == 2;
            default -> false;
        };
    }

    private static void addExtraction(MethodNode method, BiPredicate<MethodNode, AbstractInsnNode> cancellationReturn) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN || cancellationReturn.test(method, instruction)) continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 3));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 4));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "extractSky",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false));
            method.instructions.insertBefore(instruction, hook);
        }
        method.maxStack = Math.max(method.maxStack, 4);
    }

    private static void addSwing(MethodNode method, BiPredicate<MethodNode, AbstractInsnNode> cancellationReturn) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.IRETURN || cancellationReturn.test(method, instruction)) continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new InsnNode(Opcodes.SWAP));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "swingDuration", "(Ljava/lang/Object;I)I", false));
            method.instructions.insertBefore(instruction, hook);
        }
        method.maxStack = Math.max(method.maxStack, 2);
    }

    private static void addCancellation(String owner, MethodNode method, String operation) {
        InsnList hook = new InsnList();
        LabelNode originalStart = new LabelNode();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, operation, "(Ljava/lang/Object;)Z", false));
        hook.add(new JumpInsnNode(Opcodes.IFEQ, originalStart));
        hook.add(new InsnNode(Opcodes.RETURN));
        hook.add(originalStart);
        hook.add(argumentFrame(owner, method));
        method.instructions.insert(hook);
        method.maxStack = Math.max(method.maxStack, 1);
    }

    private static FrameNode argumentFrame(String owner, MethodNode method) {
        List<Object> locals = new ArrayList<>();
        locals.add(owner);
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

    private static void addPresentationHead(String owner, MethodNode method, Kind kind) {
        InsnList hook = new InsnList();
        LabelNode originalStart = new LabelNode();
        if (kind == Kind.HUD) {
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "hideHud",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        } else {
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                kind == Kind.MODEL ? "replacePlayerModel" : "mediaClick", "(Ljava/lang/Object;)Z", false));
        }
        hook.add(new JumpInsnNode(Opcodes.IFEQ, originalStart));
        if (kind == Kind.MEDIA) { hook.add(new InsnNode(Opcodes.ICONST_1)); hook.add(new InsnNode(Opcodes.IRETURN)); }
        else hook.add(new InsnNode(Opcodes.RETURN));
        hook.add(originalStart);
        hook.add(argumentFrame(owner, method));
        method.instructions.insert(hook);
        method.maxStack = Math.max(method.maxStack, kind == Kind.HUD ? 3 : 1);
    }

    private static void addRotations(String owner, MethodNode method, BiPredicate<MethodNode, AbstractInsnNode> cancellationReturn) {
        int entity = 1, state = 2;
        if (rewritesRotationArguments(method)) {
            entity = preserveRotationArguments(method);
            state = entity + 1;
        } else {
            HookArgumentFrames.retain(owner, method, entity, state);
        }
        AbstractInsnNode tail = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN && !cancellationReturn.test(method, instruction)) tail = instruction;
        }
        if (tail == null) throw reject("missing normal rotation TAIL return");
        {
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, entity));
            hook.add(new VarInsnNode(Opcodes.ALOAD, state));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "applyRotations",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
            method.instructions.insertBefore(tail, hook);
        }
        method.maxStack = Math.max(method.maxStack, 2);
    }

    private static boolean rewritesRotationArguments(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (instruction instanceof VarInsnNode variable && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                int width = opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE ? 2 : 1;
                for (int local = variable.var; local < variable.var + width; local++) {
                    if (readsAtReturn(Kind.ROTATIONS, local)) return true;
                }
            } else if (instruction instanceof IincInsnNode increment && readsAtReturn(Kind.ROTATIONS, increment.var)) {
                return true;
            }
        }
        return false;
    }

    /** Retain original callback arguments when Fabric/MixinExtras writes their local slots. */
    private static int preserveRotationArguments(MethodNode method) {
        int firstSaved = method.maxLocals;
        if (firstSaved < 4 || firstSaved > 65533) throw reject("no space for rotation arguments");
        Type[] arguments = Type.getArgumentTypes(method.desc);
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof FrameNode frame)) continue;
            if (frame.type != Opcodes.F_NEW) throw reject("rotation frame was not expanded");
            List<Object> locals = new ArrayList<>(frame.local == null ? List.of() : frame.local);
            int slots = 0;
            for (Object local : locals) {
                slots += Opcodes.LONG.equals(local) || Opcodes.DOUBLE.equals(local) ? 2 : 1;
            }
            if (slots > firstSaved) throw reject("rotation frame exceeds original locals");
            for (; slots < firstSaved; slots++) locals.add(Opcodes.TOP);
            locals.add(arguments[0].getInternalName());
            locals.add(arguments[1].getInternalName());
            frame.local = locals;
        }
        InsnList capture = new InsnList();
        capture.add(new VarInsnNode(Opcodes.ALOAD, 1));
        capture.add(new VarInsnNode(Opcodes.ASTORE, firstSaved));
        capture.add(new VarInsnNode(Opcodes.ALOAD, 2));
        capture.add(new VarInsnNode(Opcodes.ASTORE, firstSaved + 1));
        // Before the original first label, so every branch and exception handler
        // sees initialized snapshots. No fields, allocations or class loading.
        method.instructions.insert(capture);
        method.maxLocals = firstSaved + 2;
        return firstSaved;
    }

    private static MethodInsnNode findGuiRender(String owner, MethodNode method) {
        boolean named = owner.equals("net/minecraft/client/render/GameRenderer");
        String targetOwner = named ? "net/minecraft/client/gui/render/GuiRenderer" : "net/minecraft/class_11228";
        String targetMethod = named ? "render" : "method_70890";
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(targetOwner)
                    && call.name.equals(targetMethod) && call.desc.equals("(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V")) {
                if (found != null || call.getOpcode() != Opcodes.INVOKEVIRTUAL || call.itf) throw reject("ambiguous GUI render anchor");
                found = call;
            }
        }
        if (found == null) throw reject("missing GUI render anchor");
        return found;
    }

    /** Match the per-hand scope, never the shared renderItem matrix outside that scope. */
    private static MethodInsnNode findHandPush(String owner, MethodNode method) {
        boolean named = owner.equals("net/minecraft/client/render/item/HeldItemRenderer");
        String matrix = named ? "net/minecraft/client/util/math/MatrixStack" : "net/minecraft/class_4587";
        String push = named ? "push" : "method_22903";
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(matrix)
                    && call.name.equals(push) && call.desc.equals("()V")) {
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || call.itf) throw reject("invalid hand matrix push");
                AbstractInsnNode receiver = call.getPrevious();
                while (receiver != null && receiver.getOpcode() < 0) receiver = receiver.getPrevious();
                if (!(receiver instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 8) {
                    throw reject("hand push no longer uses the per-hand matrix argument");
                }
                return call;
            }
        }
        throw reject("missing hand matrix push");
    }

    private static void addHandTransform(String owner, MethodNode method) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 4));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 6));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 8));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "transformHand",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false));
        method.instructions.insert(findHandPush(owner, method), hook);
        method.maxStack = Math.max(method.maxStack, 4);
    }

    /** Exception-safe scope spanning submission AND vanilla's deferred hand-pass flush. */
    private static void addHandPass(MethodNode method) {
        String lease = BRIDGE + "$HandPass";
        int saved = method.maxLocals;
        if (saved < 6 || saved >= 65535) throw reject("no room for hand capture lease");
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof FrameNode frame)) continue;
            if (frame.type != Opcodes.F_NEW) throw reject("hand frame was not expanded");
            List<Object> locals = new ArrayList<>(frame.local == null ? List.of() : frame.local);
            int slots = 0;
            for (Object local : locals) slots += Opcodes.LONG.equals(local) || Opcodes.DOUBLE.equals(local) ? 2 : 1;
            if (slots > saved) throw reject("hand frame exceeds original locals");
            for (; slots < saved; slots++) locals.add(Opcodes.TOP);
            locals.add(lease);
            frame.local = locals;
        }
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        InsnList begin = new InsnList();
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "beginHands", "()L" + lease + ";", false));
        begin.add(new VarInsnNode(Opcodes.ASTORE, saved));
        begin.add(start);
        method.instructions.insert(begin);
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            InsnList finish = new InsnList();
            finish.add(new VarInsnNode(Opcodes.ALOAD, saved));
            finish.add(new InsnNode(Opcodes.ICONST_1));
            finish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "finishHands", "(L" + lease + ";Z)V", false));
            method.instructions.insertBefore(instruction, finish);
        }
        method.instructions.add(end);
        method.instructions.add(handler);
        Object[] locals = new Object[saved + 1];
        java.util.Arrays.fill(locals, Opcodes.TOP);
        locals[saved] = lease;
        method.instructions.add(new FrameNode(Opcodes.F_NEW, locals.length, locals, 1, new Object[] {"java/lang/Throwable"}));
        // The original Throwable stays on the stack across nonthrowing cleanup.
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, saved));
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "finishHands", "(L" + lease + ";Z)V", false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        method.maxLocals = saved + 1;
        method.maxStack = Math.max(method.maxStack, 3);
    }

    private static void addHandContext(MethodNode method) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 3));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "handDisplayContext", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
        hook.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getArgumentTypes(method.desc)[2].getInternalName()));
        hook.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.insert(hook);
        method.maxStack = Math.max(method.maxStack, 2);
    }

    /** Dispatch after each normally completed item-state extraction, never after a Fabric cancellation return. */
    private static void addItemHighlight(MethodNode method,
                                         BiPredicate<MethodNode, AbstractInsnNode> cancellationReturn) {
        int hooks = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.RETURN || cancellationReturn.test(method, instruction)) continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "highlightItem",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
            method.instructions.insertBefore(instruction, hook);
            hooks++;
        }
        if (hooks == 0) throw reject("missing normal item-state TAIL return");
        method.maxStack = Math.max(method.maxStack, 2);
    }

    /** Fixed mapping pairs read from this project's 1.21.11 Yarn build 6. */
    private static List<Target> targets(String owner) {
        String draw = owner.startsWith("net/minecraft/class_") ? "net/minecraft/class_332" : "net/minecraft/client/gui/DrawContext";
        String counter = owner.startsWith("net/minecraft/class_") ? "net/minecraft/class_9779" : "net/minecraft/client/render/RenderTickCounter";
        switch (owner) {
            case "net/minecraft/client/render/item/HeldItemRenderer": return List.of(new Target("renderFirstPersonItem",
                "(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V", Kind.HAND),
                new Target("renderItem", "(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V", Kind.HAND_PASS),
                new Target("renderItem", "(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V", Kind.HAND_CONTEXT));
            case "net/minecraft/class_759": return List.of(new Target("method_3228",
                "(Lnet/minecraft/class_742;FFLnet/minecraft/class_1268;FLnet/minecraft/class_1799;FLnet/minecraft/class_4587;Lnet/minecraft/class_11659;I)V", Kind.HAND),
                new Target("method_22976", "(FLnet/minecraft/class_4587;Lnet/minecraft/class_11659;Lnet/minecraft/class_746;I)V", Kind.HAND_PASS),
                new Target("method_3233", "(Lnet/minecraft/class_1309;Lnet/minecraft/class_1799;Lnet/minecraft/class_811;Lnet/minecraft/class_4587;Lnet/minecraft/class_11659;I)V", Kind.HAND_CONTEXT));
            case "net/minecraft/client/render/GameRenderer": return List.of(new Target("render", "(L" + counter + ";Z)V", Kind.FLUSH));
            case "net/minecraft/class_757": return List.of(new Target("method_3192", "(L" + counter + ";Z)V", Kind.FLUSH));
            case "net/minecraft/client/gui/hud/InGameHud": return List.of(new Target("render", "(L" + draw + ";L" + counter + ";)V", Kind.HUD));
            case "net/minecraft/class_329": return List.of(new Target("method_1753", "(L" + draw + ";L" + counter + ";)V", Kind.HUD));
            case "net/minecraft/client/gui/screen/ChatScreen": return List.of(new Target("mouseClicked", "(Lnet/minecraft/client/gui/Click;Z)Z", Kind.MEDIA));
            case "net/minecraft/class_408": return List.of(new Target("method_25402", "(Lnet/minecraft/class_11909;Z)Z", Kind.MEDIA));
            case "net/minecraft/client/render/entity/LivingEntityRenderer": return List.of(
                new Target("updateRenderState", "(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V", Kind.ROTATIONS),
                new Target("render", "(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V", Kind.MODEL));
            case "net/minecraft/class_922": return List.of(
                new Target("method_62355", "(Lnet/minecraft/class_1309;Lnet/minecraft/class_10042;F)V", Kind.ROTATIONS),
                new Target("method_4054", "(Lnet/minecraft/class_10042;Lnet/minecraft/class_4587;Lnet/minecraft/class_11659;Lnet/minecraft/class_12075;)V", Kind.MODEL));
            case "net/minecraft/client/render/entity/ItemEntityRenderer": return List.of(
                new Target("updateRenderState", "(Lnet/minecraft/entity/ItemEntity;Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;F)V", Kind.ITEM_HIGHLIGHT));
            case "net/minecraft/class_916": return List.of(
                new Target("method_62470", "(Lnet/minecraft/class_1542;Lnet/minecraft/class_10039;F)V", Kind.ITEM_HIGHLIGHT));
            default: break;
        }
        boolean named = owner.equals("net/minecraft/client/render/SkyRendering")
            || owner.equals("net/minecraft/entity/LivingEntity");
        if (owner.equals("net/minecraft/entity/LivingEntity") || owner.equals("net/minecraft/class_1309")) {
            return List.of(new Target(named ? "getHandSwingDuration" : "method_6028", "()I", Kind.SWING));
        }
        if (!owner.equals("net/minecraft/client/render/SkyRendering") && !owner.equals("net/minecraft/class_9975")) {
            throw reject("unsupported class: " + owner);
        }
        String world = named ? "net/minecraft/client/world/ClientWorld" : "net/minecraft/class_638";
        String camera = named ? "net/minecraft/client/render/Camera" : "net/minecraft/class_4184";
        String state = named ? "net/minecraft/client/render/state/SkyRenderState" : "net/minecraft/class_12076";
        String matrix = named ? "net/minecraft/client/util/math/MatrixStack" : "net/minecraft/class_4587";
        String moon = named ? "net/minecraft/world/MoonPhase" : "net/minecraft/class_12131";
        return List.of(
            new Target(named ? "updateRenderState" : "method_74926", "(L" + world + ";FL" + camera + ";L" + state + ";)V", Kind.EXTRACT),
            new Target(named ? "renderTopSky" : "method_62302", "(I)V", Kind.DRAW),
            new Target(named ? "renderGlowingSky" : "method_62306", "(L" + matrix + ";FI)V", Kind.OWN),
            new Target(named ? "renderCelestialBodies" : "method_62307", "(L" + matrix + ";FFFL" + moon + ";FF)V", Kind.OWN),
            new Target(named ? "renderSkyDark" : "method_62305", "()V", Kind.OWN));
    }

    private enum Kind { EXTRACT, DRAW, OWN, SWING, FLUSH, HUD, MEDIA, ROTATIONS, ITEM_HIGHLIGHT, MODEL, HAND, HAND_PASS, HAND_CONTEXT }
    private record Target(String name, String descriptor, Kind kind) { }
    private static IllegalArgumentException reject(String reason) {
        return new IllegalArgumentException("Visual hook preparation rejected: " + reason);
    }
}
