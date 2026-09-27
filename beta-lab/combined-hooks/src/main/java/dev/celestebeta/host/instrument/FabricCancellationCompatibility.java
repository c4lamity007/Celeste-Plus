package dev.celestebeta.host.instrument;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/** Recognizes ordinary Mixin cancellation exits without identifying or rewriting mods. */
final class FabricCancellationCompatibility {
    private static final String CI = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String CIR = CI + "Returnable";
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

    private FabricCancellationCompatibility() { }

    record Cancellation(Set<Integer> returnOrdinals) { }
    record Review(Map<String, Cancellation> cancellations, Set<String> reviewedMethods) { }

    /**
     * Inspect only methods the caller actually changes. Unrelated mod methods
     * cannot veto preparation. Keys are method name followed by its descriptor.
     * Every recognized cancellation exit is reported, including targets without
     * an Interaction RETURN hook. Normal return counts and mod names are not
     * compatibility criteria, and observers without a cancellation guard need
     * no adaptation.
     */
    static Review inspect(ClassNode node, Set<String> methodKeys) {
        Map<String, Cancellation> cancellations = new LinkedHashMap<>();
        Set<String> reviewed = new HashSet<>();
        for (MethodNode method : node.methods) {
            String key = method.name + method.desc;
            if (!methodKeys.contains(key)) continue;
            if (!reviewed.add(key)) throw reject("ambiguous selected method " + key);
            List<MethodInsnNode> checks = new ArrayList<>();
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && namesCancellationCheck(call)) checks.add(call);
            }
            if (checks.isEmpty()) continue;
            Frame<SourceValue>[] frames;
            try { frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, method); }
            catch (AnalyzerException failure) {
                throw reject("unverifiable cancellation local flow in " + key + ": " + failure.getMessage());
            }
            List<AbstractInsnNode> returns = returns(method);
            Set<Integer> skipped = new HashSet<>();
            for (MethodInsnNode check : checks) {
                AbstractInsnNode returned = recognize(node, method, check, frames);
                int ordinal = returns.indexOf(returned);
                if (ordinal < 0 || !skipped.add(ordinal)) throw reject("ambiguous cancellation return in " + key);
            }
            cancellations.put(key, new Cancellation(Set.copyOf(skipped)));
        }
        if (!reviewed.equals(methodKeys)) throw reject("selected cancellation-review target is missing");
        return new Review(Map.copyOf(cancellations), Set.copyOf(reviewed));
    }

    private static AbstractInsnNode recognize(ClassNode node, MethodNode target, MethodInsnNode check,
                                              Frame<SourceValue>[] frames) {
        Type result = Type.getReturnType(target.desc);
        String info = result.getSort() == Type.VOID ? CI : CIR;
        // CIR inherits isCancelled from CI, so either invocation owner is valid
        // for a returnable callback. A void callback must still use CI.
        if (!isCancellationCheck(check) || result.getSort() == Type.VOID && !check.owner.equals(CI)) {
            throw reject("unsupported cancellation check in " + target.name);
        }
        AbstractInsnNode loaded = previous(check);
        if (!(loaded instanceof VarInsnNode ci) || ci.getOpcode() != Opcodes.ALOAD) {
            throw reject("missing cancellation callback-info local in " + target.name);
        }
        int ciLocal = ci.var;
        if (!(previous(loaded) instanceof MethodInsnNode call)) {
            throw reject("cancellation check is not adjacent to its callback in " + target.name);
        }
        validateCallback(node, target, call, info, ciLocal, frames);

        AbstractInsnNode current = next(check);
        if (!(current instanceof JumpInsnNode branch) || branch.getOpcode() != Opcodes.IFEQ) {
            throw reject("unsupported cancellation branch in " + target.name);
        }
        current = next(current);
        if (result.getSort() != Type.VOID) {
            requireLoad(current, Opcodes.ALOAD, ciLocal);
            current = next(current);
            boolean reference = result.getSort() == Type.OBJECT || result.getSort() == Type.ARRAY;
            String getter = reference ? "getReturnValue" : "getReturnValue" + result.getDescriptor();
            String descriptor = reference ? "()Ljava/lang/Object;" : "()" + result.getDescriptor();
            if (!(current instanceof MethodInsnNode value) || value.getOpcode() != Opcodes.INVOKEVIRTUAL || value.itf
                    || !value.owner.equals(CIR) || !value.name.equals(getter) || !value.desc.equals(descriptor)) {
                throw reject("unsupported cancellation result getter in " + target.name);
            }
            current = next(current);
            if (reference) {
                if (current instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
                        && cast.desc.equals(result.getInternalName())) {
                    current = next(current);
                } else if (!result.equals(Type.getType(Object.class))) {
                    throw reject("unsupported cancellation result cast in " + target.name);
                }
            }
        }
        if (current == null || current.getOpcode() != result.getOpcode(Opcodes.IRETURN)
                || next(current) == null || next(current) != next(branch.label)) {
            throw reject("cancellation does not return directly to its adjacent continuation in " + target.name);
        }
        return current;
    }

    private static void validateCallback(ClassNode node, MethodNode target, MethodInsnNode call, String info,
                                         int ciLocal, Frame<SourceValue>[] frames) {
        boolean staticCall = call.getOpcode() == Opcodes.INVOKESTATIC;
        if (!call.owner.equals(node.name) || call.itf || !Type.getReturnType(call.desc).equals(Type.VOID_TYPE)
                || !staticCall && call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKESPECIAL) {
            throw reject("unsupported cancellation callback invocation in " + target.name);
        }
        MethodNode helper = unique(node, call.name, call.desc);
        if ((helper.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || helper.instructions.size() == 0
                || ((helper.access & Opcodes.ACC_STATIC) != 0) != staticCall) {
            throw reject("unsupported cancellation callback helper shape in " + target.name);
        }
        // MixinMerged establishes the ordinary injector shape, not an artifact
        // allowlist or trust boundary. Names and integer priorities are free.
        AnnotationNode annotation = merged(helper);
        Object origin = attribute(annotation, "mixin");
        if (!(origin instanceof String name) || name.isBlank() || !(attribute(annotation, "priority") instanceof Integer)) {
            throw reject("missing or malformed callback merge metadata in " + target.name);
        }
        Type[] arguments = Type.getArgumentTypes(call.desc);
        int callbackIndex = -1;
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i].getSort() != Type.OBJECT || !isCallbackInfo(arguments[i].getInternalName())) continue;
            if (callbackIndex >= 0 || !arguments[i].getInternalName().equals(info)) {
                throw reject("ambiguous callback-info descriptor in " + target.name);
            }
            callbackIndex = i;
        }
        if (callbackIndex < 0) throw reject("missing callback-info parameter in " + target.name);
        Frame<SourceValue> atCall = frames[target.instructions.indexOf(call)];
        // ASM frames count stack values, not local-variable slots; a trailing
        // long/double captured local occupies one argument position here.
        if (atCall == null || atCall.getStackSize() < arguments.length + (staticCall ? 0 : 1)
                || ciLocal >= atCall.getLocals()) {
            throw reject("unreachable or incomplete callback argument flow in " + target.name);
        }
        SourceValue supplied = atCall.getStack(atCall.getStackSize() - arguments.length + callbackIndex);
        if (supplied.insns.size() != 1 || !(supplied.insns.iterator().next() instanceof VarInsnNode source)
                || source.getOpcode() != Opcodes.ALOAD || source.var != ciLocal) {
            throw reject("callback and cancellation guard use different callback-info values in " + target.name);
        }
        Frame<SourceValue> atLoad = frames[target.instructions.indexOf(source)];
        if (atLoad == null || ciLocal >= atLoad.getLocals()
                || !atLoad.getLocal(ciLocal).equals(atCall.getLocal(ciLocal))) {
            throw reject("callback-info local changes while callback arguments are evaluated in " + target.name);
        }
    }

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
    private static boolean isCallbackInfo(String name) { return name.equals(CI) || name.equals(CIR); }
    private static boolean namesCancellationCheck(MethodInsnNode call) {
        return isCallbackInfo(call.owner) && call.name.equals("isCancelled");
    }
    private static boolean isCancellationCheck(MethodInsnNode call) {
        return namesCancellationCheck(call) && call.desc.equals("()Z") && call.getOpcode() == Opcodes.INVOKEVIRTUAL && !call.itf;
    }
    private static AnnotationNode merged(MethodNode method) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        var matches = annotations.stream().filter(annotation -> annotation.desc.equals(MERGED)).toList();
        if (matches.size() != 1) throw reject("missing or ambiguous MixinMerged metadata");
        return matches.getFirst();
    }
    private static Object attribute(AnnotationNode annotation, String key) {
        Object result = null;
        boolean found = false;
        if (annotation.values == null) return null;
        if ((annotation.values.size() & 1) != 0) throw reject("malformed MixinMerged metadata");
        for (int i = 0; i < annotation.values.size(); i += 2) if (key.equals(annotation.values.get(i))) {
            if (found) throw reject("duplicate MixinMerged attribute");
            found = true;
            result = annotation.values.get(i + 1);
        }
        return result;
    }
    private static void requireLoad(AbstractInsnNode instruction, int opcode, int local) {
        if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != opcode || load.var != local) throw reject("unexpected callback local flow");
    }
    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        AbstractInsnNode next = instruction.getNext(); while (next != null && next.getOpcode() < 0) next = next.getNext(); return next;
    }
    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        AbstractInsnNode previous = instruction.getPrevious(); while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious(); return previous;
    }
    private static IllegalArgumentException reject(String detail) { return CombinedHooksTransformer.reject("Mixin cancellation compatibility: " + detail); }
}
