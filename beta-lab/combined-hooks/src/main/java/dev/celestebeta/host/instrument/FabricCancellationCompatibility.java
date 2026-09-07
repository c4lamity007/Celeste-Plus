package dev.celestebeta.host.instrument;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Structural validation of Fabric interaction guards before late hook preparation. */
final class FabricCancellationCompatibility {
    private static final String CI = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String CIR = CI + "Returnable";
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    private static final String MULTIPLAYER = "net.fabricmc.fabric.mixin.event.interaction.client.MultiPlayerGameModeMixin";
    private static final String MINECRAFT = "net.fabricmc.fabric.mixin.event.interaction.client.MinecraftMixin";
    private static final String INTERACTION = "dev/celestebeta/host/InteractionHookBridge";

    private FabricCancellationCompatibility() { }

    record Cancellation(String tailOperation, Set<Integer> returnOrdinals) { }
    record Review(Map<String, Cancellation> cancellations, Set<String> reviewedMethods) { }
    private record Scope(String name, String desc, String helperSuffix, String origin, int originalReturns, String tailOperation) { }
    private record Guard(MethodInsnNode check, AbstractInsnNode returned) { }

    static Review inspect(ClassNode node) {
        Map<String, Cancellation> cancellations = new LinkedHashMap<>();
        Set<String> reviewed = new HashSet<>();
        for (Scope scope : scopes(node.name)) {
            MethodNode method = unique(node, scope.name, scope.desc);
            List<AbstractInsnNode> returns = returns(method);
            Set<Integer> skipped = new HashSet<>();
            Set<MethodInsnNode> checks = new HashSet<>();
            int helpers = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !hasCallbackArgument(call.desc)) continue;
                if (++helpers > 1) throw reject("multiple callbacks at the reviewed Fabric site in " + method.name);
                Guard guard = recognize(node, method, call, scope);
                checks.add(guard.check);
                int ordinal = returns.indexOf(guard.returned);
                if (ordinal < 0 || !skipped.add(ordinal)) throw reject("ambiguous Fabric cancellation return");
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && isCancellationCheck(call) && !checks.contains(call)) {
                    throw reject("unrecognized cancellation guard in " + method.name);
                }
            }
            if (returns.size() != scope.originalReturns + skipped.size()) {
                throw reject("unexpected normal/cancellation return count in " + method.name + ": expected "
                    + scope.originalReturns + " original plus " + skipped.size() + " reviewed Fabric returns, got " + returns.size());
            }
            String key = method.name + method.desc;
            reviewed.add(key);
            if (!skipped.isEmpty() && scope.tailOperation != null) cancellations.put(key, new Cancellation(scope.tailOperation, Set.copyOf(skipped)));
        }
        return new Review(Map.copyOf(cancellations), Set.copyOf(reviewed));
    }

    private static Guard recognize(ClassNode node, MethodNode target, MethodInsnNode call, Scope scope) {
        Type result = Type.getReturnType(scope.desc);
        String info = result.getSort() == Type.VOID ? CI : CIR;
        String callbackDesc = scope.desc.substring(0, scope.desc.indexOf(')')) + "L" + info + ";)V";
        if (!call.owner.equals(node.name) || !call.name.endsWith("$" + scope.helperSuffix) || !call.desc.equals(callbackDesc)
                || call.itf || call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKESPECIAL) {
            throw reject("unrecognized Fabric callback provenance/descriptor in " + target.name);
        }
        MethodNode helper = unique(node, call.name, call.desc);
        if ((helper.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || helper.instructions.size() == 0) {
            throw reject("unsupported Fabric callback helper shape");
        }
        AnnotationNode merged = merged(helper);
        if (!scope.origin.equals(attribute(merged, "mixin")) || !Integer.valueOf(1000).equals(attribute(merged, "priority"))) {
            throw reject("unapproved Fabric callback origin/priority in " + target.name);
        }
        AbstractInsnNode previous = previous(call);
        if (!(previous instanceof VarInsnNode ci) || ci.getOpcode() != Opcodes.ALOAD) throw reject("missing callback-info local");
        int ciLocal = ci.var;
        List<int[]> arguments = new ArrayList<>();
        arguments.add(new int[] {Opcodes.ALOAD, 0});
        int slot = 1;
        for (Type argument : Type.getArgumentTypes(scope.desc)) {
            arguments.add(new int[] {argument.getOpcode(Opcodes.ILOAD), slot}); slot += argument.getSize();
        }
        if (ciLocal < slot) throw reject("Fabric callback info overwrites a method argument");
        previous = previous(previous);
        for (int i = arguments.size() - 1; i >= 0; i--) {
            int[] argument = arguments.get(i);
            if (!(previous instanceof VarInsnNode load) || load.getOpcode() != argument[0] || load.var != argument[1]) {
                throw reject("Fabric callback arguments differ from the original method in " + target.name);
            }
            previous = previous(previous);
        }
        AbstractInsnNode current = next(call);
        requireLoad(current, Opcodes.ALOAD, ciLocal);
        current = next(current);
        if (!(current instanceof MethodInsnNode check) || !isCancellationCheck(check) || !check.owner.equals(info)) {
            throw reject("unsupported Fabric cancellation check");
        }
        current = next(current);
        if (!(current instanceof JumpInsnNode branch) || branch.getOpcode() != Opcodes.IFEQ) throw reject("unsupported Fabric cancellation branch");
        current = next(current);
        if (result.getSort() != Type.VOID) {
            requireLoad(current, Opcodes.ALOAD, ciLocal);
            current = next(current);
            String getter = result.getSort() == Type.BOOLEAN ? "getReturnValueZ" : "getReturnValue";
            String descriptor = result.getSort() == Type.BOOLEAN ? "()Z" : "()Ljava/lang/Object;";
            if (!(current instanceof MethodInsnNode value) || value.getOpcode() != Opcodes.INVOKEVIRTUAL || value.itf
                    || !value.owner.equals(CIR) || !value.name.equals(getter) || !value.desc.equals(descriptor)) {
                throw reject("unsupported Fabric cancellation result getter");
            }
            current = next(current);
            if (result.getSort() == Type.OBJECT) {
                if (!(current instanceof TypeInsnNode cast) || cast.getOpcode() != Opcodes.CHECKCAST || !cast.desc.equals(result.getInternalName())) {
                    throw reject("unsupported Fabric cancellation result cast");
                }
                current = next(current);
            } else if (result.getSort() != Type.BOOLEAN) throw reject("unsupported Fabric cancellation return type");
        }
        if (current == null || current.getOpcode() != result.getOpcode(Opcodes.IRETURN)
                || next(current) == null || next(current) != next(branch.label)) {
            throw reject("Fabric cancellation does not return directly to its adjacent vanilla continuation");
        }
        return new Guard(check, current);
    }

    /**
     * Startup Celeste has priority 2000; its RETURN anchors are prepared before
     * Fabric's priority-1000 cancellers add these extra return instructions.
     * Keep those cancellation exits intact but do not invent Celeste RETURN
     * attack/item callbacks on them merely because a late scan can now see the
     * exits. Breaking uses an explicit startup finally wrapper and therefore
     * keeps its matching cleanup on these exits as well as on exceptions.
     */
    static byte[] restoreCancellationTiming(byte[] prepared, Review review) {
        if (review.cancellations.isEmpty()) return prepared;
        ClassReader reader = new ClassReader(prepared);
        ClassNode node = CombinedHooksTransformer.read(prepared);
        Set<String> edited = new HashSet<>();
        for (MethodNode method : node.methods) {
            Cancellation cancellation = review.cancellations.get(method.name + method.desc);
            if (cancellation == null) continue;
            List<AbstractInsnNode> returns = returns(method);
            for (int ordinal : cancellation.returnOrdinals) {
                if (ordinal >= returns.size()) throw reject("cancellation return ordinal changed during composition");
                removeInteractionTail(method, returns.get(ordinal), cancellation.tailOperation);
            }
            edited.add(method.name + method.desc);
        }
        if (edited.size() != review.cancellations.size()) throw reject("reviewed cancellation target disappeared during composition");
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor output = super.visitMethod(access, name, desc, signature, exceptions);
                if (!edited.contains(name + desc)) return output;
                unique(node, name, desc).accept(output); return null;
            }
        }, 0);
        return writer.toByteArray();
    }
    private static void removeInteractionTail(MethodNode method, AbstractInsnNode returned, String operation) {
        if (!operation.equals("afterAttack") && !operation.equals("afterItem")) throw reject("unsupported cancellation-tail operation");
        List<AbstractInsnNode> remove = new ArrayList<>();
        AbstractInsnNode current = previous(returned);
        String descriptor = "(Ljava/lang/Object;Ljava/lang/Object;)V";
        if (!(current instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC || call.itf
                || !call.owner.equals(INTERACTION) || !call.name.equals(operation) || !call.desc.equals(descriptor)) {
            throw reject("expected newly composed interaction tail at Fabric cancellation return");
        }
        remove.add(current); current = previous(current);
        if (operation.equals("afterAttack")) {
            requireLoad(current, Opcodes.ALOAD, 2); remove.add(current); current = previous(current);
        } else if (operation.equals("afterItem")) {
            if (current == null || current.getOpcode() != Opcodes.SWAP) throw reject("unexpected item tail stack shape");
            remove.add(current); current = previous(current);
        }
        requireLoad(current, Opcodes.ALOAD, 0); remove.add(current); current = previous(current);
        if (operation.equals("afterItem")) {
            if (current == null || current.getOpcode() != Opcodes.DUP) throw reject("unexpected item tail duplication");
            remove.add(current);
        }
        remove.forEach(method.instructions::remove);
    }

    static void rejectUnreviewedTargetGuards(ClassNode baseline, ClassNode prepared, Review review, Set<String> bridges) {
        for (MethodNode method : prepared.methods) {
            boolean changed = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && bridges.contains(call.owner)) { changed = true; break; }
            }
            if (!changed || review.reviewedMethods.contains(method.name + method.desc)) continue;
            for (AbstractInsnNode instruction : unique(baseline, method.name, method.desc).instructions) {
                if (instruction instanceof MethodInsnNode call && isCancellationCheck(call)) {
                    throw reject("cancellation-bearing target has no approved Fabric adapter: " + method.name + method.desc);
                }
            }
        }
    }
    private static List<Scope> scopes(String owner) {
        boolean named = !owner.startsWith("net/minecraft/class_");
        if (owner.equals("net/minecraft/client/MinecraftClient") || owner.equals("net/minecraft/class_310")) {
            return List.of(new Scope(named ? "doAttack" : "method_1536", "()Z", "injectDoAttackForCancelling", MINECRAFT, 7, null));
        }
        if (!owner.equals("net/minecraft/client/network/ClientPlayerInteractionManager") && !owner.equals("net/minecraft/class_636")) return List.of();
        String pos = mapped(named, "util/math/BlockPos", "2338"), direction = mapped(named, "util/math/Direction", "2350");
        String player = mapped(named, "entity/player/PlayerEntity", "1657"), entity = mapped(named, "entity/Entity", "1297");
        String hand = mapped(named, "util/Hand", "1268"), result = mapped(named, "util/ActionResult", "1269");
        String clientPlayer = mapped(named, "client/network/ClientPlayerEntity", "746"), hit = mapped(named, "util/hit/BlockHitResult", "3965");
        return List.of(
            new Scope(named ? "attackBlock" : "method_2910", "(L" + pos + ";L" + direction + ";)Z", "attackBlock", MULTIPLAYER, 3, null),
            new Scope(named ? "updateBlockBreakingProgress" : "method_2902", "(L" + pos + ";L" + direction + ";)Z", "method_2902", MULTIPLAYER, 5, null),
            new Scope(named ? "attackEntity" : "method_2918", "(L" + player + ";L" + entity + ";)V", "attackEntity", MULTIPLAYER, 1, "afterAttack"),
            new Scope(named ? "interactItem" : "method_2919", "(L" + player + ";L" + hand + ";)L" + result + ";", "interactItem", MULTIPLAYER, 2, "afterItem"),
            new Scope(named ? "interactBlock" : "method_2896", "(L" + clientPlayer + ";L" + hand + ";L" + hit + ";)L" + result + ";", "interactBlock", MULTIPLAYER, 2, null));
    }
    private static String mapped(boolean named, String name, String id) { return "net/minecraft/" + (named ? name : "class_" + id); }
    private static MethodNode unique(ClassNode node, String name, String desc) {
        var methods = node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(desc)).toList();
        if (methods.size() != 1) throw reject("expected one method " + name + desc);
        return methods.getFirst();
    }
    private static List<AbstractInsnNode> returns(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN) result.add(instruction);
        return result;
    }
    private static boolean hasCallbackArgument(String desc) {
        for (Type type : Type.getArgumentTypes(desc)) if (type.getSort() == Type.OBJECT && (type.getInternalName().equals(CI) || type.getInternalName().equals(CIR))) return true;
        return false;
    }
    private static boolean isCancellationCheck(MethodInsnNode call) {
        return (call.owner.equals(CI) || call.owner.equals(CIR)) && call.name.equals("isCancelled") && call.desc.equals("()Z")
            && call.getOpcode() == Opcodes.INVOKEVIRTUAL && !call.itf;
    }
    private static AnnotationNode merged(MethodNode method) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        var matches = annotations.stream().filter(annotation -> annotation.desc.equals(MERGED)).toList();
        if (matches.size() != 1) throw reject("missing or ambiguous Fabric MixinMerged provenance");
        return matches.getFirst();
    }
    private static Object attribute(AnnotationNode annotation, String key) {
        Object result = null;
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) if (key.equals(annotation.values.get(i))) {
            if (result != null) throw reject("duplicate Fabric provenance attribute");
            result = annotation.values.get(i + 1);
        }
        return result;
    }
    private static void requireLoad(AbstractInsnNode instruction, int opcode, int local) {
        if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != opcode || load.var != local) throw reject("unexpected Fabric callback local flow");
    }
    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        AbstractInsnNode next = instruction.getNext(); while (next != null && next.getOpcode() < 0) next = next.getNext(); return next;
    }
    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        AbstractInsnNode previous = instruction.getPrevious(); while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious(); return previous;
    }
    private static IllegalArgumentException reject(String detail) { return CombinedHooksTransformer.reject("Fabric cancellation compatibility: " + detail); }
}
