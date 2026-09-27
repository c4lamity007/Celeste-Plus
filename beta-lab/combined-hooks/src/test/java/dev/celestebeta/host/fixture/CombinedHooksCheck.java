package dev.celestebeta.host.fixture;

import dev.celestebeta.host.GameplayCallbackBridge;
import dev.celestebeta.host.FakeGambleHookBridge;
import dev.celestebeta.host.InteractionHookBridge;
import dev.celestebeta.host.instrument.CombinedHooksTransformer;
import dev.celestebeta.host.instrument.FakeGambleHooksTransformer;
import dev.celestebeta.host.instrument.GameplayCallbacksTransformer;
import dev.celestebeta.host.instrument.InteractionHooksTransformer;
import dev.celestebeta.host.instrument.ResourceReloadTransformer;
import dev.celestebeta.host.instrument.RotationHooksTransformer;
import dev.celestebeta.host.instrument.VisualHooksTransformer;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/** Real-schema composition and executable standard callback/guard compatibility fixtures. */
public final class CombinedHooksCheck {
    private static final String PREFIX = "dev/celestebeta/host/";
    private static final String SELF = "dev/celestebeta/host/fixture/CombinedHooksCheck";
    private static final String CI = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String CIR = CI + "Returnable";
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    private static final String FABRIC = "net.fabricmc.fabric.mixin.event.interaction.client.";
    private static final String MALILIB_CLIENT = "fi.dy.masa.malilib.mixin.test.MixinMinecraft_testSelector";
    private static final String MALILIB_INTERACTION = "fi.dy.masa.malilib.mixin.test.MixinClientPlayInteractionManager_testSelector";
    private static final List<String> OWNERS = List.of(
        "net/minecraft/client/network/ClientPlayerInteractionManager", "net/minecraft/class_636",
        "net/minecraft/client/MinecraftClient", "net/minecraft/class_310",
        "net/minecraft/client/network/ClientPlayNetworkHandler", "net/minecraft/class_634",
        "net/minecraft/entity/LivingEntity", "net/minecraft/class_1309");
    private static int checks;

    private CombinedHooksCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected intermediary jar path");
        checks += HookArgumentFramesCheck.run();
        checks += GenericReturnHooksCheck.run(Path.of(args[0]));
        genericReturnParserExecution();
        Map<String, byte[]> originals = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile())) {
            for (String owner : OWNERS) {
                byte[] bytes;
                if (named(owner)) bytes = resource(owner + ".class");
                else try (InputStream input = jar.getInputStream(jar.getJarEntry(owner + ".class"))) { bytes = input.readAllBytes(); }
                actual(owner, bytes);
                originals.put(owner, bytes);
            }
        }
        Map<String, byte[]> prepared = CombinedHooksTransformer.prepareAll(originals);
        check(prepared.keySet().equals(originals.keySet()), "batch preserves all supplied owner keys");
        expect(UnsupportedOperationException.class, () -> prepared.put("other/Class", new byte[0]), "batch map is immutable");
        expect(IllegalArgumentException.class, () -> CombinedHooksTransformer.prepareAll(Map.of()), "empty preparation batch rejected");
        Map<String, byte[]> bad = new LinkedHashMap<>(originals); bad.put("other/Class", originals.values().iterator().next());
        expect(IllegalArgumentException.class, () -> CombinedHooksTransformer.prepareAll(bad), "one invalid class rejects complete batch");
        System.out.println("PASS combined late hooks: " + checks
            + " checks; eight real overlapping schemas, pinned composition and generic executable callback fixtures");
    }

    private static void actual(String owner, byte[] original) throws Exception {
        check(CombinedHooksTransformer.supportedOwners().contains(owner), "supported overlapping owner");
        check(CombinedHooksTransformer.familiesFor(owner).equals(families(owner)), "family order is explicit and pinned");
        byte[] snapshot = original.clone(), combined = CombinedHooksTransformer.prepare(owner, original);
        ClassNode before = read(original), after = read(combined);
        check(schema(before).equals(schema(after)), "combined transform preserves complete member schema: " + owner);
        check(Arrays.equals(snapshot, original), "combined input remains immutable");
        Map<String, Map<String, Integer>> expected = new LinkedHashMap<>();
        byte[] chain = original;
        for (String family : families(owner)) {
            byte[] one = prepare(family, owner, original);
            merge(expected, callbacks(read(one)));
            chain = prepare(family, owner, chain);
            expect(IllegalArgumentException.class, () -> CombinedHooksTransformer.prepare(owner, one), "partially prepared family rejected");
        }
        check(expected.equals(callbacks(after)), "exact union of every family callback count survives composition");
        check(Arrays.equals(chain, combined), "pristine combined output equals the pinned explicit chain");
        List<MethodNode> targets = new ArrayList<>();
        for (int i = 0; i < before.methods.size(); i++) {
            MethodNode old = before.methods.get(i), updated = after.methods.get(i);
            if (text(old).equals(text(updated))) continue;
            targets.add(old); new Analyzer<>(new BasicVerifier()).analyze(owner, updated); checks++;
            check(expected.containsKey(old.name + old.desc), "only independently expected target body changed");
        }
        check(targets.size() == (interaction(owner) ? 14 : client(owner) ? 5 : network(owner) ? 8 : 3), "exact combined target count: " + owner + " = " + targets.size());
        expect(IllegalArgumentException.class, () -> CombinedHooksTransformer.prepare(owner, combined), "duplicate complete transform rejected");
        expect(IllegalArgumentException.class, () -> CombinedHooksTransformer.prepare("other/Class", original), "unsupported class rejected");
        ClassNode startup = read(original); startup.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "celeste$existing", "()V", null, null));
        expect(IllegalArgumentException.class, () -> CombinedHooksTransformer.prepare(owner, write(startup)), "startup hooks rejected");
        if (interaction(owner)) {
            MethodNode attack = method(after, mapped(owner, "attackEntity", "method_2918"));
            check(hookNames(attack).getFirst().equals("FakeGambleHookBridge.attackEntity")
                && hookNames(attack).get(1).equals("GameplayCallbackBridge.entityAttack")
                && hookNames(attack).get(2).equals("InteractionHookBridge.beforeAttack"), "local ownership guard precedes observers and sprint capture in composed attack");
            execution(owner, targets, false);
            execution(owner, targets, true);
            for (CallbackShape shape : CallbackShape.values()) genericInteractionExecution(owner, targets, shape);
            breakingReturnSiteExecution(owner, targets);
            for (boolean malilibFirst : List.of(false, true)) for (boolean legacyName : List.of(false, true)) {
                malilibBreakingExecution(owner, targets, malilibFirst, legacyName);
            }
        } else if (client(owner)) {
            ClassNode guarded = read(original);
            addGuard(guarded, method(guarded, mapped(owner, "doAttack", "method_1536")), "injectDoAttackForCancelling", false);
            byte[] adapted = CombinedHooksTransformer.prepare(owner, write(guarded));
            check(schema(guarded).equals(schema(read(adapted))), "known ClientPreAttack guard accepted without schema alteration");
            MethodNode doAttack = method(read(adapted), mapped(owner, "doAttack", "method_1536"));
            check(returnCount(doAttack) == 8 && hookNames(doAttack).equals(List.of("GameplayCallbackBridge.mainHand")),
                "ClientPreAttack cancellation remains intact before original getter filter");
            rejectedGuardVariants(owner, guarded);
            for (boolean malilibFirst : List.of(false, true)) for (boolean legacyName : List.of(false, true)) {
                malilibClientExecution(owner, original, malilibFirst, legacyName);
            }
            ClassNode inputGuard = read(original);
            addGuard(inputGuard, method(inputGuard, mapped(owner, "handleInputEvents", "method_1508")), "arbitrary$input$Guard", false,
                "third.party.InputMixin", "fabric", "fabricCancel", false);
            byte[] inputBytes = write(inputGuard);
            ClassNode normalizedInput = read(inputBytes);
            byte[] preparedInput = CombinedHooksTransformer.prepare(owner, inputBytes);
            check(schema(inputGuard).equals(schema(read(preparedInput))), "standard canceller accepted in edited input method outside the former six-method list");
            assertHelpersUnchanged(normalizedInput, read(preparedInput));
        } else if (network(owner)) {
            genericNetworkExecution(owner, targets);
        }
        if (interaction(owner) || network(owner)) discardedTailArgumentsExecution(owner, targets);
    }

    /** Original bytecode may explicitly drop locals after its own final argument use. */
    @SuppressWarnings("try")
    private static void discardedTailArgumentsExecution(String owner, List<MethodNode> targets) throws Exception {
        Map<String, byte[]> definitions = fixtureDefinitions(targets);
        String inventory = mapped(owner, "net/minecraft/entity/player/PlayerInventory", "net/minecraft/class_1661");
        if (interaction(owner)) definitions.put(inventory.replace('/', '.'), inventoryFixture(owner, inventory));
        ClassNode original = read(fixture(owner, targets, inventory));
        List<String> selected = interaction(owner)
            ? List.of(mapped(owner, "attackEntity", "method_2918"), mapped(owner, "interactItem", "method_2919"),
                mapped(owner, "attackBlock", "method_2910"), mapped(owner, "updateBlockBreakingProgress", "method_2902"))
            : List.of(mapped(owner, "onWorldTimeUpdate", "method_11079"), mapped(owner, "onBlockUpdate", "method_11136"),
                mapped(owner, "onChunkDeltaUpdate", "method_11100"), mapped(owner, "onEntitySpawn", "method_11112"),
                mapped(owner, "onEntitiesDestroy", "method_11095"), mapped(owner, "onEntityStatus", "method_11148"));
        for (String name : selected) {
            MethodNode method = method(original, name);
            var returned = method.instructions.getLast();
            while (returned.getOpcode() < Opcodes.IRETURN || returned.getOpcode() > Opcodes.RETURN) returned = returned.getPrevious();
            var anchor = returned.getOpcode() == Opcodes.RETURN ? returned : returned.getPrevious();
            InsnList discarded = new InsnList();
            discarded.add(new InsnNode(Opcodes.NOP));
            discarded.add(new LabelNode());
            discarded.add(new FrameNode(Opcodes.F_NEW, 0, new Object[0], 0, new Object[0]));
            discarded.add(new InsnNode(Opcodes.NOP));
            method.instructions.insertBefore(anchor, discarded);
        }
        byte[] baseline = write(original);
        for (boolean prepared : List.of(false, true)) {
            definitions.put(owner.replace('/', '.'), prepared ? CombinedHooksTransformer.prepare(owner, baseline) : baseline);
            Class<?> type = new FixtureLoader(definitions).loadClass(owner.replace('/', '.'));
            Object instance = type.getConstructor().newInstance();
            Recorder recorder = new Recorder(instance);
            try (var interactions = InteractionHookBridge.install(recorder); var gameplay = GameplayCallbackBridge.install(recorder)) {
                for (String name : selected) {
                    Method invoked = declared(type, name); Object[] arguments = parameters(invoked);
                    recorder.player = arguments[0]; recorder.target = network(owner) ? arguments[0] : arguments[1];
                    reset(instance); Object result = invoke(invoked, instance, arguments);
                    boolean breaking = name.equals(mapped(owner, "attackBlock", "method_2910")) || name.equals(mapped(owner, "updateBlockBreakingProgress", "method_2902"));
                    List<String> expected = !prepared ? List.of("body") : network(owner) ? List.of("body", "serverUpdate")
                        : breaking ? List.of("beginBreaking", "body", "endBreaking")
                        : name.equals(mapped(owner, "attackEntity", "method_2918")) ? List.of("observer", "capture", "body", "afterAttack")
                        : List.of("beforeItem", "body", "afterItem");
                    check((breaking ? Boolean.FALSE.equals(result) : result == null) && events(instance).equals(expected) && type.getField("bodyCalls").getInt(instance) == 1,
                        "JVM verifies and executes " + (prepared ? "prepared" : "original") + " discarded-argument tail: " + name);
                }
            }
        }
    }

    private enum CallbackShape { ORIGINAL_ARGUMENTS, CALLBACK_ONLY, CI_FIRST, CAPTURED_LOCALS, STATIC_CAPTURED }

    @SuppressWarnings("try")
    private static void genericInteractionExecution(String owner, List<MethodNode> targets, CallbackShape shape) throws Exception {
        Map<String, byte[]> definitions = fixtureDefinitions(targets);
        String inventory = mapped(owner, "net/minecraft/entity/player/PlayerInventory", "net/minecraft/class_1661");
        definitions.put(inventory.replace('/', '.'), inventoryFixture(owner, inventory));
        ClassNode baseline = read(fixture(owner, targets, inventory));
        for (MethodNode target : List.copyOf(baseline.methods)) {
            if (fabricSuffix(owner, target.name) == null) continue;
            boolean litematica = target.name.equals(mapped(owner, "interactBlock", "method_2896"));
            String helper = litematica ? "handler$bco000$litematica$onInteractBlock" : "arbitraryThirdPartyCallback" + target.name;
            String origin = litematica ? "fi.dy.masa.litematica.mixin.easyplace.MixinClientPlayerInteractionManager_easyPlace" : "third.party.ArbitraryMixin";
            for (int site = 0; site < 2; site++) addGenericGuard(baseline, target, helper, origin, litematica ? 980 : 5000, shape);
            addObserver(baseline, target);
        }
        byte[] input = framed(baseline), snapshot = input.clone();
        ClassNode prepared = read(CombinedHooksTransformer.prepare(owner, input));
        check(schema(read(input)).equals(schema(prepared)) && Arrays.equals(input, snapshot), "generic callback composition preserves schema and input: " + shape);
        assertHelpersUnchanged(read(input), prepared);
        for (MethodNode target : targets) { new Analyzer<>(new BasicVerifier()).analyze(owner, method(prepared, target.name)); checks++; }
        definitions.put(owner.replace('/', '.'), write(prepared));
        Class<?> type = new FixtureLoader(definitions).loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance(); Recorder recorder = new Recorder(instance);
        try (var interactions = InteractionHookBridge.install(recorder); var gameplay = GameplayCallbackBridge.install(recorder)) {
            for (MethodNode target : targets) {
                String suffix = fabricSuffix(owner, target.name); if (suffix == null) continue;
                Method invoked = declared(type, target.name); Object[] args = parameters(invoked);
                recorder.player = args[0]; recorder.target = args.length > 1 ? args[1] : null;
                Object cancelled = invoked.getReturnType() == void.class ? null : invoked.getReturnType() == boolean.class
                    ? Boolean.TRUE : invoked.getReturnType().getConstructor().newInstance();
                type.getField("cancelResult").set(instance, cancelled);
                for (int cancelAt = 0; cancelAt <= 2; cancelAt++) {
                    resetGeneric(instance, cancelAt); int previousEnds = recorder.endCalls;
                    Object actual = invoke(invoked, instance, args);
                    check(cancelAt == 0 ? actual == null || Boolean.FALSE.equals(actual) : actual == cancelled,
                        "generic cancellation retains exact boolean/object/void result at either independent site");
                    List<String> expected = genericEvents(suffix, cancelAt, true);
                    check(events(instance).equals(expected), "generic helper ordering and ordinary-return timing: " + shape + "/" + target.name + "/" + cancelAt);
                    check(type.getField("bodyCalls").getInt(instance) == (cancelAt == 0 ? 1 : 0), "generic cancellation does not execute original body");
                    check(recorder.breakingDepth == 0 && recorder.endCalls == previousEnds + (suffix.equals("attackBlock") || suffix.equals("method_2902") ? 1 : 0),
                        "repeated generic sites retain once-only breaking cleanup");
                }
                for (int branch = 0; branch < originalReturns(owner, target.name) - 1; branch++) {
                    resetGeneric(instance, 0); type.getField("branch").setInt(instance, branch);
                    invoke(invoked, instance, args);
                    check(events(instance).equals(genericEvents(suffix, 0, false)), "every early ordinary return keeps startup callbacks");
                }
                type.getField("branch").setInt(instance, -1);
            }
        }
        Method attack = declared(type, mapped(owner, "attackEntity", "method_2918"));
        resetGeneric(instance, 0); invoke(attack, instance, parameters(attack));
        check(events(instance).equals(List.of("noncancellable", "generic", "generic", "body")), "retired bridges preserve all independent mod callbacks");
    }

    private static List<String> genericEvents(String suffix, int cancelAt, boolean body) {
        List<String> expected = new ArrayList<>();
        if (suffix.equals("attackEntity")) expected.addAll(List.of("observer", "capture"));
        else if (suffix.equals("interactItem")) expected.add("beforeItem");
        else if (suffix.equals("attackBlock") || suffix.equals("method_2902")) expected.add("beginBreaking");
        expected.add("noncancellable"); expected.add("generic"); if (cancelAt != 1) expected.add("generic");
        if (cancelAt == 0 && body) expected.add("body");
        if (suffix.equals("attackBlock") || suffix.equals("method_2902")) expected.add("endBreaking");
        else if (cancelAt == 0 && suffix.equals("attackEntity")) expected.add("afterAttack");
        else if (cancelAt == 0 && suffix.equals("interactItem")) expected.add("afterItem");
        return expected;
    }

    @SuppressWarnings("try")
    private static void genericNetworkExecution(String owner, List<MethodNode> targets) throws Exception {
        Map<String, byte[]> definitions = fixtureDefinitions(targets);
        ClassNode baseline = read(fixture(owner, targets, "unused/Inventory"));
        MethodNode target = method(baseline, mapped(owner, "onBlockUpdate", "method_11136"));
        addBranchReturn(owner, target, true);
        addGenericGuard(baseline, target, "packetCallbackWithoutHandlerPrefix", "third.party.NetworkMixin", Integer.MIN_VALUE, CallbackShape.STATIC_CAPTURED);
        addObserver(baseline, target);
        byte[] input = framed(baseline), output = CombinedHooksTransformer.prepare(owner, input);
        check(schema(read(input)).equals(schema(read(output))), "non-reviewed network target accepts a standard static captured callback");
        assertHelpersUnchanged(read(input), read(output));
        new Analyzer<>(new BasicVerifier()).analyze(owner, method(read(output), target.name)); checks++;
        definitions.put(owner.replace('/', '.'), output);
        Class<?> type = new FixtureLoader(definitions).loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance(); Recorder recorder = new Recorder(instance);
        Method invoked = declared(type, target.name); Object[] args = parameters(invoked); recorder.target = args[0];
        try (var gameplay = GameplayCallbackBridge.install(recorder)) {
            resetGeneric(instance, 1); invoke(invoked, instance, args);
            check(events(instance).equals(List.of("noncancellable", "generic")), "network cancellation skips original body and startup TAIL observer");
            resetGeneric(instance, 0); invoke(invoked, instance, args);
            check(events(instance).equals(List.of("noncancellable", "generic", "body", "serverUpdate")), "network final ordinary return retains TAIL observer");
            resetGeneric(instance, 0); type.getField("branch").setInt(instance, 99); invoke(invoked, instance, args);
            check(events(instance).equals(List.of("noncancellable", "generic")), "network early ordinary return does not invent a TAIL observer");
        }
    }

    private static Map<String, byte[]> fixtureDefinitions(List<MethodNode> targets) {
        Map<String, byte[]> definitions = new HashMap<>();
        for (MethodNode target : targets) {
            for (Type argument : Type.getArgumentTypes(target.desc)) if (argument.getSort() == Type.OBJECT) definitions.put(argument.getClassName(), empty(argument.getInternalName()));
            Type result = Type.getReturnType(target.desc); if (result.getSort() == Type.OBJECT) definitions.put(result.getClassName(), empty(result.getInternalName()));
        }
        return definitions;
    }

    /** Mixin RETURN injection runs after the vanilla boolean was pushed. */
    @SuppressWarnings("try")
    private static void breakingReturnSiteExecution(String owner, List<MethodNode> targets) throws Exception {
        Map<String, byte[]> definitions = fixtureDefinitions(targets);
        String inventory = mapped(owner, "net/minecraft/entity/player/PlayerInventory", "net/minecraft/class_1661");
        definitions.put(inventory.replace('/', '.'), inventoryFixture(owner, inventory));
        ClassNode baseline = read(fixture(owner, targets, inventory));
        for (String name : List.of(mapped(owner, "attackBlock", "method_2910"), mapped(owner, "updateBlockBreakingProgress", "method_2902"))) {
            MethodNode target = method(baseline, name); var originalFirst = target.instructions.getFirst();
            var returned = target.instructions.getLast(); while (returned.getOpcode() != Opcodes.IRETURN) returned = returned.getPrevious();
            check(returned.getPrevious().getOpcode() == Opcodes.ICONST_0, "live-result fixture identifies original boolean value");
            target.instructions.set(returned.getPrevious(), new InsnNode(Opcodes.ICONST_1));
            addGenericGuard(baseline, target, "returnSiteCallback" + name, "third.party.ReturnMixin", 2001, CallbackShape.CALLBACK_ONLY);
            InsnList guard = new InsnList();
            while (target.instructions.getFirst() != originalFirst) { var instruction = target.instructions.getFirst(); target.instructions.remove(instruction); guard.add(instruction); }
            target.instructions.insertBefore(returned, guard);
        }
        byte[] input = framed(baseline), prepared = CombinedHooksTransformer.prepare(owner, input);
        check(schema(read(input)).equals(schema(read(prepared))), "live-result RETURN cancellation preserves complete schema");
        assertHelpersUnchanged(read(input), read(prepared)); definitions.put(owner.replace('/', '.'), prepared);
        Class<?> type = new FixtureLoader(definitions).loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance(); Recorder recorder = new Recorder(instance);
        try (var registration = InteractionHookBridge.install(recorder)) {
            for (String name : List.of(mapped(owner, "attackBlock", "method_2910"), mapped(owner, "updateBlockBreakingProgress", "method_2902"))) {
                new Analyzer<>(new BasicVerifier()).analyze(owner, method(read(prepared), name)); checks++;
                Method invoked = declared(type, name); Object[] args = parameters(invoked);
                for (boolean cancel : List.of(false, true)) for (boolean value : List.of(false, true)) {
                    resetGeneric(instance, cancel ? 1 : 0); type.getField("cancelResult").set(instance, value); int ends = recorder.endCalls;
                    check(Boolean.valueOf(cancel ? value : true).equals(invoke(invoked, instance, args)), "RETURN-site cancellation retains exact result above live original boolean");
                    check(events(instance).equals(List.of("beginBreaking", "body", "generic", "endBreaking"))
                        && recorder.endCalls == ends + 1 && recorder.breakingDepth == 0, "live operand-stack cancellation reaches shared cleanup exactly once");
                }
            }
        }
    }

    /** Execute every JVM result category after structural inspection, without loading game classes. */
    private static void genericReturnParserExecution() throws Exception {
        String[] descriptors = {"V", "Z", "B", "C", "S", "I", "J", "F", "D", "Ljava/lang/String;", "[Ljava/lang/String;", "[[I"};
        Object[] values = {null, true, (byte) 7, 'x', (short) 300, 12345, 1234567890123L, 1.25F, 8.5D, "identity", new String[] {"array"}, new int[][] {{1, 2}}};
        Method inspect = Class.forName("dev.celestebeta.host.instrument.FabricCancellationCompatibility").getDeclaredMethod("inspect", ClassNode.class, Set.class);
        inspect.setAccessible(true);
        for (int i = 0; i < descriptors.length; i++) for (CallbackShape shape : CallbackShape.values()) {
            String owner = "fixture/GenericReturns" + i + shape.ordinal();
            MethodNode template = new MethodNode(Opcodes.ACC_PUBLIC, "result", "()" + descriptors[i], null, null);
            ClassNode baseline = read(fixture(owner, List.of(template), "unused/Inventory"));
            MethodNode target = method(baseline, "result");
            addExtraNormalReturn(owner, target);
            for (int site = 0; site < 2; site++) addGenericGuard(baseline, target, "unprefixedCallback", "third.party.PrimitiveMixin", Integer.MAX_VALUE, shape);
            addObserver(baseline, target); byte[] bytes = framed(baseline); ClassNode normalized = read(bytes);
            Object review = invoke(inspect, null, normalized, Set.of(target.name + target.desc));
            Method cancellations = review.getClass().getDeclaredMethod("cancellations"); cancellations.setAccessible(true);
            Map<?, ?> found = (Map<?, ?>) invoke(cancellations, review);
            check(found.size() == 1, "generic parser identifies arbitrary target result category " + descriptors[i] + "/" + shape);
            Object cancellation = found.values().iterator().next();
            Method ordinals = cancellation.getClass().getDeclaredMethod("returnOrdinals"); ordinals.setAccessible(true);
            check(((Set<?>) invoke(ordinals, cancellation)).size() == 2, "same helper at two independent sites identifies two distinct cancellation exits");
            new Analyzer<>(new BasicVerifier()).analyze(owner, method(normalized, "result")); checks++;
            Class<?> type = new FixtureLoader(Map.of(owner.replace('/', '.'), bytes)).loadClass(owner.replace('/', '.'));
            Object instance = type.getConstructor().newInstance(); Method result = declared(type, "result");
            type.getField("cancelResult").set(instance, values[i]);
            for (int site = 1; site <= 2; site++) {
                resetGeneric(instance, site); Object actual = invoke(result, instance);
                check(values[i] == null ? actual == null : values[i].getClass().isArray() ? values[i] == actual : values[i].equals(actual), "typed cancellation return executes without conversion or value loss");
                check(events(instance).equals(site == 1 ? List.of("noncancellable", "generic") : List.of("noncancellable", "generic", "generic")), "typed cancellation short-circuits at the selected independent site");
            }
            resetGeneric(instance, 0); invoke(result, instance);
            check(events(instance).equals(List.of("noncancellable", "generic", "generic", "body")), "noncancellable observer and ordinary typed body remain executable");
            if (i == 0 && shape == CallbackShape.CALLBACK_ONLY) {
                ClassNode mismatched = read(bytes); MethodNode selected = method(mismatched, "result");
                int otherLocal = selected.maxLocals++; MethodNode initialization = new MethodNode();
                initialization.visitTypeInsn(Opcodes.NEW, CI); initialization.visitInsn(Opcodes.DUP);
                initialization.visitLdcInsn("independent"); initialization.visitInsn(Opcodes.ICONST_1);
                initialization.visitMethodInsn(Opcodes.INVOKESPECIAL, CI, "<init>", "(Ljava/lang/String;Z)V", false);
                initialization.visitVarInsn(Opcodes.ASTORE, otherLocal); selected.instructions.insert(initialization.instructions);
                for (var instruction : selected.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals("unprefixedCallback")) {
                    ((VarInsnNode) call.getPrevious()).var = otherLocal; break;
                }
                ClassNode validBytecode = read(framed(mismatched));
                new Analyzer<>(new BasicVerifier()).analyze(owner, method(validBytecode, "result")); checks++;
                expect(IllegalArgumentException.class, () -> invoke(inspect, null, validBytecode, Set.of(target.name + target.desc)),
                    "distinct initialized callback-info objects remain rejected despite valid bytecode");
            }
        }
    }

    private static void resetGeneric(Object instance, int cancelAt) throws Exception {
        reset(instance); instance.getClass().getField("genericCalls").setInt(instance, 0);
        instance.getClass().getField("genericCancelAt").setInt(instance, cancelAt);
    }

    public static void genericCallback(Object instance, Object callbackInfo, String captured, long wideCaptured) {
        check("captured".equals(captured) && wideCaptured == 42L, "callback captured locals retain exact reference/wide values");
        record(instance, "generic");
        try {
            Class<?> type = instance.getClass(); int count = type.getField("genericCalls").getInt(instance) + 1;
            type.getField("genericCalls").setInt(instance, count);
            if (count == type.getField("genericCancelAt").getInt(instance)) {
                if (callbackInfo instanceof org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<?> raw) {
                    @SuppressWarnings("unchecked") var info = (org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Object>) raw;
                    info.setReturnValue(type.getField("cancelResult").get(instance));
                } else ((org.spongepowered.asm.mixin.injection.callback.CallbackInfo) callbackInfo).cancel();
            }
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    /** The installed MaLiLib 0.27.20 selector is a real canceller, not an observer. */
    @SuppressWarnings("try")
    private static void malilibBreakingExecution(String owner, List<MethodNode> targets, boolean malilibFirst, boolean legacyName) throws Exception {
        Map<String, byte[]> definitions = new HashMap<>();
        for (MethodNode target : targets) {
            for (Type arg : Type.getArgumentTypes(target.desc)) if (arg.getSort() == Type.OBJECT) definitions.put(arg.getClassName(), empty(arg.getInternalName()));
            Type result = Type.getReturnType(target.desc);
            if (result.getSort() == Type.OBJECT) definitions.put(result.getClassName(), empty(result.getInternalName()));
        }
        String inventory = mapped(owner, "net/minecraft/entity/player/PlayerInventory", "net/minecraft/class_1661");
        definitions.put(inventory.replace('/', '.'), inventoryFixture(owner, inventory));
        ClassNode baseline = read(fixture(owner, targets, inventory));
        MethodNode attack = method(baseline, mapped(owner, "attackBlock", "method_2910"));
        addCompanionGuards(baseline, attack, "attackBlock", malilibFirst, legacyName);
        rejectedCompanionVariants(owner, baseline, attack.name, legacyName);
        byte[] input = write(baseline), snapshot = input.clone();
        ClassNode prepared = read(CombinedHooksTransformer.prepare(owner, input));
        check(Arrays.equals(input, snapshot) && schema(baseline).equals(schema(prepared)), "MaLiLib breaking composition preserves input and complete schema");
        assertHelpersUnchanged(read(input), prepared);
        check(returnCount(method(prepared, attack.name)) == 2, "vanilla breaking exits share an epilogue and local ghost result returns before vanilla begins");
        check(countHook(method(prepared, attack.name), "InteractionHookBridge.endBreaking") == 2,
            "normal and exceptional breaking epilogues each contain one cleanup");
        new Analyzer<>(new BasicVerifier()).analyze(owner, method(prepared, attack.name)); checks++;
        definitions.put(owner.replace('/', '.'), write(prepared));
        Class<?> type = new FixtureLoader(definitions).loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance(); Recorder recorder = new Recorder(instance);
        Method invoked = declared(type, attack.name); Object[] args = parameters(invoked);
        try (var registration = InteractionHookBridge.install(recorder)) {
            for (boolean fabricCancel : List.of(false, true)) for (boolean malilibCancel : List.of(false, true)) {
                reset(instance); type.getField("fabricCancel").setBoolean(instance, fabricCancel);
                type.getField("malilibCancel").setBoolean(instance, malilibCancel); type.getField("cancelResult").set(instance, Boolean.TRUE);
                int ends = recorder.endCalls;
                boolean expected = fabricCancel && (!malilibFirst || !malilibCancel);
                check(Boolean.valueOf(expected).equals(invoke(invoked, instance, args)), "independent breaking cancellers retain their exact false/true result");
                List<String> order = new ArrayList<>(List.of("beginBreaking"));
                order.addAll(companionEvents(malilibFirst, fabricCancel, malilibCancel));
                if (!fabricCancel && !malilibCancel) order.add("body"); order.add("endBreaking");
                check(events(instance).equals(order) && recorder.breakingDepth == 0 && recorder.endCalls == ends + 1,
                    "MaLiLib/Fabric short-circuit order and once-only projection cleanup are preserved");
                check(type.getField("bodyCalls").getInt(instance) == (fabricCancel || malilibCancel ? 0 : 1), "cancellation never reaches original breaking body");
            }
            type.getField("fabricCancel").setBoolean(instance, false); type.getField("malilibCancel").setBoolean(instance, false);
            for (int branch = 0; branch < 3; branch++) {
                reset(instance); type.getField("branch").setInt(instance, branch); int ends = recorder.endCalls;
                check(Boolean.FALSE.equals(invoke(invoked, instance, args)) && events(instance).getLast().equals("endBreaking")
                    && recorder.breakingDepth == 0 && recorder.endCalls == ends + 1, "each original breaking branch retains exactly one cleanup with both mods present");
            }
            RuntimeException failure = new IllegalArgumentException("MaLiLib coexistence body failure");
            type.getField("failure").set(instance, failure); reset(instance); int ends = recorder.endCalls;
            check(expect(IllegalArgumentException.class, () -> invoke(invoked, instance, args), "coexisting breaking exception") == failure
                && recorder.breakingDepth == 0 && recorder.endCalls == ends + 1, "coexisting breaking body throw retains identity and cleans exactly once");
            type.getField("failure").set(instance, null); type.getField("branch").setInt(instance, -1);
        }
        reset(instance); invoke(invoked, instance, args);
        List<String> dormant = new ArrayList<>(companionEvents(malilibFirst, false, false)); dormant.add("body");
        check(events(instance).equals(dormant), "retired breaking bridge restores both original mod callbacks without synthetic cleanup");
    }

    @SuppressWarnings("try")
    private static void malilibClientExecution(String owner, byte[] original, boolean malilibFirst, boolean legacyName) throws Exception {
        String attackName = mapped(owner, "doAttack", "method_1536");
        ClassNode executable = read(clientFixture(owner));
        ClassNode baseline = read(original); MethodNode old = method(baseline, attackName);
        baseline.methods.set(baseline.methods.indexOf(old), method(executable, attackName));
        baseline.fields.addAll(executable.fields);
        addCompanionGuards(baseline, method(baseline, attackName), "injectDoAttackForCancelling", malilibFirst, legacyName);
        rejectedCompanionVariants(owner, baseline, attackName, legacyName);
        byte[] input = write(baseline), snapshot = input.clone();
        ClassNode prepared = read(CombinedHooksTransformer.prepare(owner, input));
        check(Arrays.equals(input, snapshot) && schema(baseline).equals(schema(prepared)), "MaLiLib client composition preserves input and complete real client schema");
        assertHelpersUnchanged(read(input), prepared);
        MethodNode attack = method(prepared, attackName);
        check(returnCount(attack) == 9 && hookNames(attack).equals(List.of("GameplayCallbackBridge.mainHand")),
            "seven vanilla and both cancellation returns survive with only the original main-hand filter");
        new Analyzer<>(new BasicVerifier()).analyze(owner, attack); checks++;
        // Execute the exact prepared attack body/helpers in a minimal same-owner class;
        // the other real client methods have already participated in full composition above.
        executable.methods.removeIf(method -> method.name.equals(attackName)); executable.methods.add(attack);
        for (MethodNode helper : prepared.methods) if (fixtureHelper(helper)) executable.methods.add(helper);
        String player = mapped(owner, "net/minecraft/client/network/ClientPlayerEntity", "net/minecraft/class_746");
        String hand = mapped(owner, "net/minecraft/util/Hand", "net/minecraft/class_1268");
        String stack = mapped(owner, "net/minecraft/item/ItemStack", "net/minecraft/class_1799");
        Map<String, byte[]> definitions = Map.of(owner.replace('/', '.'), write(executable), player.replace('/', '.'), clientPlayerFixture(owner, player, hand, stack),
            hand.replace('/', '.'), empty(hand), stack.replace('/', '.'), empty(stack));
        Class<?> type = new FixtureLoader(definitions).loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance(); Recorder recorder = new Recorder(instance); Method invoked = declared(type, attackName);
        try (var registration = GameplayCallbackBridge.install(recorder)) {
            for (boolean fabricCancel : List.of(false, true)) for (boolean malilibCancel : List.of(false, true)) {
                reset(instance); type.getField("fabricCancel").setBoolean(instance, fabricCancel);
                type.getField("malilibCancel").setBoolean(instance, malilibCancel); type.getField("cancelResult").set(instance, Boolean.FALSE);
                List<String> order = new ArrayList<>(companionEvents(malilibFirst, fabricCancel, malilibCancel));
                if (!fabricCancel && !malilibCancel) order.addAll(List.of("getter", "mainHand", "body"));
                check(Boolean.valueOf(!fabricCancel && !malilibCancel).equals(invoke(invoked, instance)), "both client cancellers return false while uncancelled body retains true");
                check(events(instance).equals(order) && type.getField("bodyCalls").getInt(instance) == (fabricCancel || malilibCancel ? 0 : 1),
                    "both client cancellation paths skip the vanilla getter, main-hand filter and body in original callback order");
            }
            type.getField("fabricCancel").setBoolean(instance, false); type.getField("malilibCancel").setBoolean(instance, false);
            for (int branch = 0; branch < 7; branch++) {
                reset(instance); type.getField("branch").setInt(instance, branch);
                check(Boolean.valueOf(branch == 6 || (branch & 1) != 0).equals(invoke(invoked, instance)), "every original client boolean branch remains exact with both mods");
                check(events(instance).contains("mainHand") == (branch == 6), "main-hand filter runs only when original branch reaches its getter");
            }
            RuntimeException failure = new IllegalArgumentException("MaLiLib coexistence client failure");
            type.getField("failure").set(instance, failure); reset(instance);
            check(expect(IllegalArgumentException.class, () -> invoke(invoked, instance), "client body failure") == failure
                && events(instance).equals(companionEvents(malilibFirst, false, false)), "client exception identity and pre-body callback timing survive coexistence");
            type.getField("failure").set(instance, null); type.getField("branch").setInt(instance, -1);
        }
        reset(instance); invoke(invoked, instance);
        List<String> dormant = new ArrayList<>(companionEvents(malilibFirst, false, false)); dormant.addAll(List.of("getter", "body"));
        check(events(instance).equals(dormant), "retired client bridge restores both original mod callbacks and getter");
    }

    private static List<String> companionEvents(boolean malilibFirst, boolean fabricCancel, boolean malilibCancel) {
        if (malilibFirst) return malilibCancel ? List.of("malilib") : List.of("malilib", "fabric");
        return fabricCancel ? List.of("fabric") : List.of("fabric", "malilib");
    }

    private static void addCompanionGuards(ClassNode baseline, MethodNode target, String fabricSuffix, boolean malilibFirst, boolean legacyName) {
        if (!malilibFirst) addMalilibGuard(baseline, target, legacyName);
        addGuard(baseline, target, fabricSuffix, true);
        if (malilibFirst) addMalilibGuard(baseline, target, legacyName);
    }

    private static void addMalilibGuard(ClassNode baseline, MethodNode target, boolean legacyName) {
        boolean minecraft = client(baseline.name);
        addGuard(baseline, target, malilibSuffix(baseline.name, legacyName), true,
            minecraft ? MALILIB_CLIENT : MALILIB_INTERACTION, "malilib", "malilibCancel", minecraft);
    }

    /** Active MethodMapper bytecode evidence: src/test/fixtures/malilib-handler-names.md. */
    private static String malilibSuffix(String owner, boolean legacyName) {
        return "malilib" + (legacyName ? "_" : "$") + (client(owner) ? "onLeftClickMouse" : "handleBreakingRestriction1");
    }

    private static boolean fixtureHelper(MethodNode method) {
        return method.name.startsWith("handler$fixture$") || method.name.startsWith("handler$bfk000$")
            || method.name.equals("nonCancellableObserver") || method.visibleAnnotations != null
            && method.visibleAnnotations.stream().anyMatch(annotation -> annotation.desc.equals(MERGED));
    }

    private static void assertHelpersUnchanged(ClassNode before, ClassNode after) {
        for (MethodNode helper : before.methods) if (fixtureHelper(helper)) {
            check(text(helper).equals(text(method(after, helper.name))), "reviewed mod helper bytecode is never rewritten: " + helper.name);
        }
    }

    private static void rejectedCompanionVariants(String owner, ClassNode baseline, String targetName, boolean legacyName) {
        for (String mutation : List.of("origin", "priority", "name", "namespace", "guard", "extraSite", "mixedAliases")) {
            ClassNode malformed = read(write(baseline)); MethodNode helper = method(malformed, "handler$bfk000$" + malilibSuffix(owner, legacyName));
            if (mutation.equals("origin")) helper.visibleAnnotations.getFirst().values.set(1, "other.mod.UnknownMixin");
            else if (mutation.equals("priority")) helper.visibleAnnotations.getFirst().values.set(3, 999);
            else if (mutation.equals("name") || mutation.equals("namespace")) {
                String previous = helper.name;
                helper.name = mutation.equals("namespace") ? helper.name.replace("$malilib", "$unknownmod") : helper.name + "Unknown";
                for (var instruction : method(malformed, targetName).instructions) if (instruction instanceof MethodInsnNode call && call.name.equals(previous)) call.name = helper.name;
            } else if (mutation.equals("guard")) {
                boolean following = false;
                for (var instruction : method(malformed, targetName).instructions) {
                    if (instruction instanceof MethodInsnNode call && call.name.equals(helper.name)) following = true;
                    if (following && instruction instanceof JumpInsnNode branch) { branch.setOpcode(Opcodes.IFNE); break; }
                }
            } else {
                int helpers = malformed.methods.size();
                addMalilibGuard(malformed, method(malformed, targetName), mutation.equals("mixedAliases") ? !legacyName : legacyName);
                // Independent injection sites may invoke the same helper or
                // separate aliases. Neither helper names nor sites are allowlisted.
                if (mutation.equals("extraSite")) malformed.methods.remove(helpers);
            }
            if (mutation.equals("guard")) {
                expect(IllegalArgumentException.class, () -> CombinedHooksTransformer.prepare(owner, write(malformed)), "malformed callback guard remains fail-closed");
            } else {
                ClassNode prepared = read(CombinedHooksTransformer.prepare(owner, write(malformed)));
                check(schema(malformed).equals(schema(prepared)), "generic callback accepts valid " + mutation + " without mod-name/priority/site allowlists");
                assertHelpersUnchanged(read(write(malformed)), prepared);
            }
        }
    }

    @SuppressWarnings("try")
    private static void execution(String owner, List<MethodNode> targets, boolean fabric) throws Exception {
        Map<String, byte[]> definitions = new HashMap<>();
        for (MethodNode target : targets) {
            for (Type arg : Type.getArgumentTypes(target.desc)) if (arg.getSort() == Type.OBJECT) definitions.put(arg.getClassName(), empty(arg.getInternalName()));
            Type result = Type.getReturnType(target.desc);
            if (result.getSort() == Type.OBJECT) definitions.put(result.getClassName(), empty(result.getInternalName()));
        }
        String inventory = mapped(owner, "net/minecraft/entity/player/PlayerInventory", "net/minecraft/class_1661");
        definitions.put(inventory.replace('/', '.'), inventoryFixture(owner, inventory));
        ClassNode synthetic = read(fixture(owner, targets, inventory));
        if (fabric) {
            for (MethodNode target : List.copyOf(synthetic.methods)) {
                String suffix = fabricSuffix(owner, target.name);
                if (suffix != null) addGuard(synthetic, target, suffix, true);
            }
            rejectedGuardVariants(owner, synthetic);
        }
        byte[] prepared = CombinedHooksTransformer.prepare(owner, write(synthetic));
        check(schema(synthetic).equals(schema(read(prepared))), "synthetic composition preserves schema");
        if (fabric) for (MethodNode target : targets) {
            String tail = interactionTail(owner, target.name);
            if (tail != null) check(countHook(method(read(prepared), target.name), "InteractionHookBridge." + tail)
                == (tail.equals("endBreaking") ? 2 : originalReturns(owner, target.name)),
                "Fabric cancellation retains breaking finally cleanup but excludes attack/item normal-return observers: " + target.name);
        }
        definitions.put(owner.replace('/', '.'), prepared);
        Class<?> type = new FixtureLoader(definitions).loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance();
        Recorder recorder = new Recorder(instance);
        try (var interactions = InteractionHookBridge.install(recorder); var gameplay = GameplayCallbackBridge.install(recorder)) {
            Method attack = declared(type, mapped(owner, "attackEntity", "method_2918")); Object[] args = parameters(attack);
            recorder.player = args[0]; recorder.target = args[1];
            reset(instance); invoke(attack, instance, args);
            check(events(instance).equals(fabric ? List.of("observer", "capture", "fabric", "body", "afterAttack")
                : List.of("observer", "capture", "body", "afterAttack")), "combined attack execution order is stable");
            check(type.getField("bodyCalls").getInt(instance) == 1, "composed attack executes original exactly once");
            reset(instance); RuntimeException failure = new IllegalArgumentException("combined original body"); type.getField("failure").set(instance, failure);
            check(expect(IllegalArgumentException.class, () -> invoke(attack, instance, args), "body exception") == failure,
                "composed body exception identity retained");
            check(events(instance).equals(fabric ? List.of("observer", "capture", "fabric") : List.of("observer", "capture")),
                "throwing body does not invent cleanup callback"); type.getField("failure").set(instance, null);
            reset(instance); recorder.observerFailure = failure;
            check(expect(IllegalArgumentException.class, () -> invoke(attack, instance, args), "observer exception") == failure
                && events(instance).equals(List.of("observer")), "throwing observer precedes and suppresses later sprint capture"); recorder.observerFailure = null;
            breakingExecution(owner, type, instance, recorder, fabric);
            localGhostExecution(owner, type, instance, recorder);
            if (fabric) {
                for (MethodNode target : targets) {
                    if (fabricSuffix(owner, target.name) == null) continue;
                    Method selected = declared(type, target.name); Object[] inputs = parameters(selected);
                    recorder.player = inputs[0]; recorder.target = inputs.length > 1 ? inputs[1] : null;
                    Object cancelled = selected.getReturnType() == void.class ? null : selected.getReturnType() == boolean.class
                        ? Boolean.TRUE : selected.getReturnType().getConstructor().newInstance();
                    type.getField("cancelResult").set(instance, cancelled); type.getField("fabricCancel").setBoolean(instance, true); reset(instance);
                    Object actual = invoke(selected, instance, inputs);
                    check(actual == cancelled || Boolean.TRUE.equals(actual) && Boolean.TRUE.equals(cancelled), "Fabric cancellation keeps exact return value");
                    List<String> expected = target.name.equals(mapped(owner, "attackEntity", "method_2918")) ? List.of("observer", "capture", "fabric")
                        : target.name.equals(mapped(owner, "interactItem", "method_2919")) ? List.of("beforeItem", "fabric")
                        : target.name.equals(mapped(owner, "interactBlock", "method_2896")) ? List.of("fabric") : List.of("beginBreaking", "fabric", "endBreaking");
                    check(events(instance).equals(expected) && type.getField("bodyCalls").getInt(instance) == 0,
                        "known Fabric cancel retains HEAD observers and breaking cleanup without original body: " + target.name);
                    check(recorder.breakingDepth == 0, "Fabric cancellation does not retain a breaking projection");
                    type.getField("fabricCancel").setBoolean(instance, false); reset(instance);
                    for (int branch = 0; branch < originalReturns(owner, target.name); branch++) {
                        type.getField("branch").setInt(instance, branch); events(instance).clear();
                        invoke(selected, instance, inputs);
                        String tail = interactionTail(owner, target.name);
                        if (tail != null) check(events(instance).getLast().equals(tail), "all original return branches keep cleanup: " + target.name);
                        check(recorder.breakingDepth == 0, "original return branch does not retain a breaking projection");
                    }
                    type.getField("branch").setInt(instance, -1);
                }
            }
        }
        Method attack = declared(type, mapped(owner, "attackEntity", "method_2918")); reset(instance); invoke(attack, instance, parameters(attack));
        check(events(instance).equals(fabric ? List.of("fabric", "body") : List.of("body")), "retiring both bridges restores reviewed baseline only");
    }

    @SuppressWarnings("try")
    private static void localGhostExecution(String owner, Class<?> type, Object instance, Recorder recorder) throws Exception {
        Object[] handled = {null};
        FakeGambleHookBridge.Handler handler = (FakeGambleHookBridge.Handler) java.lang.reflect.Proxy.newProxyInstance(
            FakeGambleHookBridge.Handler.class.getClassLoader(), new Class<?>[] {FakeGambleHookBridge.Handler.class},
            (proxy, method, arguments) -> method.getReturnType() == boolean.class ? Boolean.TRUE : handled[0]);
        try (var local = FakeGambleHookBridge.install(handler)) {
            for (String name : List.of(mapped(owner, "attackEntity", "method_2918"), mapped(owner, "attackBlock", "method_2910"),
                    mapped(owner, "updateBlockBreakingProgress", "method_2902"), mapped(owner, "interactItem", "method_2919"),
                    mapped(owner, "interactBlock", "method_2896"))) {
                Method method = declared(type, name);
                handled[0] = method.getReturnType() == void.class ? null : method.getReturnType() == boolean.class
                    ? Boolean.FALSE : method.getReturnType().getConstructor().newInstance();
                reset(instance);
                Object result = invoke(method, instance, parameters(method));
                check(result == handled[0] && events(instance).isEmpty() && recorder.breakingDepth == 0
                        && type.getField("bodyCalls").getInt(instance) == 0,
                    "owned local action suppresses vanilla body, gameplay observers and sprint/breaking projections: " + name);
            }
        }
        // Later real actions are not held behind the swallowed local operation.
        Method attack = declared(type, mapped(owner, "attackEntity", "method_2918"));
        Object[] arguments = parameters(attack); recorder.player = arguments[0]; recorder.target = arguments[1];
        reset(instance); invoke(attack, instance, arguments);
        check(type.getField("bodyCalls").getInt(instance) == 1 && events(instance).contains("observer"),
            "detaching local action bridge immediately restores composed real attack");
    }

    private static void breakingExecution(String owner, Class<?> type, Object instance, Recorder recorder, boolean fabric) throws Exception {
        for (String name : List.of(mapped(owner, "attackBlock", "method_2910"), mapped(owner, "updateBlockBreakingProgress", "method_2902"))) {
            Method method = declared(type, name); Object[] inputs = parameters(method);
            List<String> normalEvents = fabric ? List.of("beginBreaking", "fabric", "body", "endBreaking")
                : List.of("beginBreaking", "body", "endBreaking");
            List<String> exitEvents = fabric ? List.of("beginBreaking", "fabric", "endBreaking") : List.of("beginBreaking", "endBreaking");
            type.getField("fabricCancel").setBoolean(instance, false); type.getField("branch").setInt(instance, -1);
            reset(instance); int previous = recorder.endCalls;
            check(Boolean.FALSE.equals(invoke(method, instance, inputs)), "normal breaking boolean remains unchanged");
            check(events(instance).equals(normalEvents) && recorder.breakingDepth == 0 && recorder.endCalls == previous + 1,
                "composed normal breaking exit cleans exactly once");
            RuntimeException bodyFailure = new IllegalArgumentException("composed breaking body");
            type.getField("failure").set(instance, bodyFailure); reset(instance); previous = recorder.endCalls;
            check(expect(IllegalArgumentException.class, () -> invoke(method, instance, inputs), "breaking body exception") == bodyFailure,
                "breaking body exception identity survives cleanup");
            check(events(instance).equals(exitEvents) && recorder.breakingDepth == 0 && recorder.endCalls == previous + 1,
                "composed breaking body throw cleans exactly once");
            type.getField("failure").set(instance, null);
            for (int attempt = 0; attempt < 3; attempt++) {
                type.getField("fabricCancel").setBoolean(instance, fabric); type.getField("cancelResult").set(instance, Boolean.TRUE);
                type.getField("branch").setInt(instance, 0); reset(instance); previous = recorder.endCalls;
                check(Boolean.valueOf(fabric).equals(invoke(method, instance, inputs)), "cancellation return remains exact across repeated calls");
                check(events(instance).equals(exitEvents) && type.getField("bodyCalls").getInt(instance) == 0
                    && recorder.breakingDepth == 0 && recorder.endCalls == previous + 1,
                    "repeated breaking cancellation clears projection exactly once");
                type.getField("fabricCancel").setBoolean(instance, false); type.getField("branch").setInt(instance, -1);
                reset(instance); previous = recorder.endCalls; invoke(method, instance, inputs);
                check(events(instance).equals(normalEvents) && recorder.breakingDepth == 0 && recorder.endCalls == previous + 1,
                    "normal call after cancellation starts and ends with zero projection depth");
            }
            RuntimeException cleanupFailure = new IllegalStateException("composed breaking cleanup");
            for (int exit = 0; exit < 3; exit++) {
                type.getField("fabricCancel").setBoolean(instance, exit == 1 && fabric);
                type.getField("branch").setInt(instance, exit == 1 ? 0 : -1);
                type.getField("failure").set(instance, exit == 2 ? bodyFailure : null);
                reset(instance); recorder.endFailure = cleanupFailure; previous = recorder.endCalls;
                check(expect(IllegalStateException.class, () -> invoke(method, instance, inputs), "breaking cleanup exception") == cleanupFailure,
                    "breaking cleanup exception retains Java finally behavior");
                check(events(instance).equals(exit == 0 ? normalEvents : exitEvents)
                    && recorder.breakingDepth == 0 && recorder.endCalls == previous + 1,
                    "throwing cleanup is never called twice on normal, cancelled or exceptional exit");
                recorder.endFailure = null;
            }
            type.getField("failure").set(instance, null); type.getField("fabricCancel").setBoolean(instance, false);
            type.getField("branch").setInt(instance, -1); reset(instance);
            RuntimeException beginFailure = new IllegalArgumentException("composed breaking begin");
            recorder.beginFailure = beginFailure; previous = recorder.endCalls;
            check(expect(IllegalArgumentException.class, () -> invoke(method, instance, inputs), "breaking begin exception") == beginFailure,
                "begin callback exception remains exact");
            check(events(instance).equals(List.of("beginBreaking")) && recorder.breakingDepth == 0 && recorder.endCalls == previous,
                "throwing begin does not invoke unpaired cleanup or original body");
            recorder.beginFailure = null;
        }
    }

    private static void rejectedGuardVariants(String owner, ClassNode guarded) {
        ClassNode unknown = read(write(guarded));
        MethodNode helper = unknown.methods.stream().filter(method -> method.name.startsWith("handler$fixture$")).findFirst().orElseThrow();
        helper.visibleAnnotations.getFirst().values.set(1, "other.mod.UnknownMixin");
        check(schema(unknown).equals(schema(read(CombinedHooksTransformer.prepare(owner, write(unknown))))), "arbitrary nonempty callback origin accepted structurally");
        ClassNode priority = read(write(guarded));
        MethodNode wrongPriority = priority.methods.stream().filter(method -> method.name.startsWith("handler$fixture$")).findFirst().orElseThrow();
        wrongPriority.visibleAnnotations.getFirst().values.set(3, 900);
        check(schema(priority).equals(schema(read(CombinedHooksTransformer.prepare(owner, write(priority))))), "callback priority is metadata, not a compatibility allowlist");
        for (String mutation : List.of("missing", "duplicate", "duplicateKey", "emptyOrigin", "blankOrigin", "nonIntegerPriority")) {
            ClassNode metadata = read(write(guarded));
            MethodNode selected = metadata.methods.stream().filter(method -> method.name.startsWith("handler$fixture$")).findFirst().orElseThrow();
            if (mutation.equals("missing")) selected.visibleAnnotations.clear();
            else if (mutation.equals("duplicate")) selected.visibleAnnotations.add(selected.visibleAnnotations.getFirst());
            else if (mutation.equals("duplicateKey")) selected.visibleAnnotations.getFirst().values.addAll(List.of("priority", 1000));
            else if (mutation.equals("nonIntegerPriority")) selected.visibleAnnotations.getFirst().values.set(3, "1000");
            else selected.visibleAnnotations.getFirst().values.set(1, mutation.equals("emptyOrigin") ? "" : "  ");
            expect(IllegalArgumentException.class, () -> CombinedHooksTransformer.prepare(owner, write(metadata)), "invalid callback metadata rejected: " + mutation);
        }
        ClassNode malformed = read(write(guarded));
        boolean changed = false;
        for (MethodNode target : malformed.methods) {
            for (var instruction : target.instructions.toArray()) if (!changed && instruction instanceof MethodInsnNode call && call.name.equals("isCancelled")) {
                var next = instruction.getNext(); while (next != null && next.getOpcode() < 0) next = next.getNext();
                if (!(next instanceof JumpInsnNode jump)) throw new AssertionError("missing fixture guard");
                jump.setOpcode(Opcodes.IFNE); changed = true;
            }
        }
        check(changed, "fixture guard mutation applied");
        expect(IllegalArgumentException.class, () -> CombinedHooksTransformer.prepare(owner, write(malformed)), "malformed cancellation guard rejected");
        ClassNode wrongLocal = read(write(guarded));
        changed = false;
        for (MethodNode target : wrongLocal.methods) for (var instruction : target.instructions) {
            if (!changed && instruction instanceof MethodInsnNode call && call.name.equals("isCancelled")) {
                var previous = instruction.getPrevious(); while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
                if (!(previous instanceof VarInsnNode local)) throw new AssertionError("missing CI load");
                local.var++; target.maxLocals++; changed = true;
            }
        }
        expect(IllegalArgumentException.class, () -> CombinedHooksTransformer.prepare(owner, write(wrongLocal)), "wrong cancellation-info local rejected");
        ClassNode duplicate = read(write(guarded));
        duplicate.methods.add(duplicate.methods.stream().filter(method -> method.name.startsWith("handler$fixture$")).findFirst().orElseThrow());
        expect(IllegalArgumentException.class, () -> CombinedHooksTransformer.prepare(owner, write(duplicate)), "duplicate callback helper rejected");
        ClassNode extraReturn = read(write(guarded));
        MethodNode extraTarget = method(extraReturn, client(owner) ? mapped(owner, "doAttack", "method_1536") : mapped(owner, "attackEntity", "method_2918"));
        addExtraNormalReturn(extraReturn.name, extraTarget);
        ClassNode extended = read(CombinedHooksTransformer.prepare(owner, write(extraReturn)));
        check(returnCount(method(extended, extraTarget.name)) == returnCount(extraTarget) + (interaction(owner) ? 1 : 0),
            "additional ordinary returns retained along with the local ownership guard return");
    }

    /** Standard merged guard with CI anywhere among arguments, including wide captured locals. */
    private static void addGenericGuard(ClassNode owner, MethodNode target, String name, String origin, int priority, CallbackShape shape) {
        Type result = Type.getReturnType(target.desc); String info = result.getSort() == Type.VOID ? CI : CIR;
        boolean captured = shape == CallbackShape.CAPTURED_LOCALS || shape == CallbackShape.STATIC_CAPTURED;
        boolean statik = shape == CallbackShape.STATIC_CAPTURED;
        boolean originals = shape == CallbackShape.ORIGINAL_ARGUMENTS || shape == CallbackShape.CAPTURED_LOCALS || shape == CallbackShape.CI_FIRST;
        String originalArguments = target.desc.substring(1, target.desc.indexOf(')'));
        String descriptor = "(" + (statik ? "Ljava/lang/Object;" : "")
            + (shape == CallbackShape.CI_FIRST ? "L" + info + ";" : "")
            + (originals ? originalArguments : "")
            + (shape != CallbackShape.CI_FIRST ? "L" + info + ";" : "")
            + (captured ? "Ljava/lang/String;J" : "") + ")V";
        if (owner.methods.stream().noneMatch(method -> method.name.equals(name))) {
            MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE | (statik ? Opcodes.ACC_STATIC : 0), name, descriptor, null, null);
            AnnotationNode annotation = new AnnotationNode(MERGED); annotation.values = new ArrayList<>(List.of("mixin", origin, "priority", priority));
            helper.visibleAnnotations = new ArrayList<>(List.of(annotation));
            int slot = statik ? 0 : 1, ciSlot = -1, captureSlot = -1;
            for (Type argument : Type.getArgumentTypes(descriptor)) {
                if (argument.getDescriptor().equals("L" + info + ";")) ciSlot = slot;
                slot += argument.getSize();
            }
            if (captured) captureSlot = slot - 3;
            helper.visitCode(); helper.visitVarInsn(Opcodes.ALOAD, 0); helper.visitVarInsn(Opcodes.ALOAD, ciSlot);
            if (captured) { helper.visitVarInsn(Opcodes.ALOAD, captureSlot); helper.visitVarInsn(Opcodes.LLOAD, captureSlot + 1); }
            else { helper.visitLdcInsn("captured"); helper.visitLdcInsn(42L); }
            helper.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "genericCallback", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;J)V", false);
            helper.visitInsn(Opcodes.RETURN); helper.visitMaxs(5, slot); helper.visitEnd(); owner.methods.add(helper);
        }
        int ciLocal = target.maxLocals++, capturedLocal = target.maxLocals;
        if (captured) target.maxLocals += 3;
        MethodNode guard = new MethodNode();
        if (captured) { guard.visitLdcInsn("captured"); guard.visitVarInsn(Opcodes.ASTORE, capturedLocal); guard.visitLdcInsn(42L); guard.visitVarInsn(Opcodes.LSTORE, capturedLocal + 1); }
        guard.visitTypeInsn(Opcodes.NEW, info); guard.visitInsn(Opcodes.DUP); guard.visitLdcInsn(target.name); guard.visitInsn(Opcodes.ICONST_1);
        guard.visitMethodInsn(Opcodes.INVOKESPECIAL, info, "<init>", "(Ljava/lang/String;Z)V", false); guard.visitVarInsn(Opcodes.ASTORE, ciLocal);
        guard.visitVarInsn(Opcodes.ALOAD, 0);
        if (shape == CallbackShape.CI_FIRST) guard.visitVarInsn(Opcodes.ALOAD, ciLocal);
        if (originals) {
            int slot = 1;
            for (Type argument : Type.getArgumentTypes(target.desc)) { guard.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot); slot += argument.getSize(); }
        }
        if (shape != CallbackShape.CI_FIRST) guard.visitVarInsn(Opcodes.ALOAD, ciLocal);
        if (captured) { guard.visitVarInsn(Opcodes.ALOAD, capturedLocal); guard.visitVarInsn(Opcodes.LLOAD, capturedLocal + 1); }
        guard.visitMethodInsn(statik ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, owner.name, name, descriptor, false);
        guard.visitVarInsn(Opcodes.ALOAD, ciLocal); guard.visitMethodInsn(Opcodes.INVOKEVIRTUAL, info, "isCancelled", "()Z", false);
        Label resume = new Label(); guard.visitJumpInsn(Opcodes.IFEQ, resume);
        if (result.getSort() != Type.VOID) {
            guard.visitVarInsn(Opcodes.ALOAD, ciLocal);
            boolean reference = result.getSort() == Type.OBJECT || result.getSort() == Type.ARRAY;
            guard.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CIR, reference ? "getReturnValue" : "getReturnValue" + result.getDescriptor(),
                reference ? "()Ljava/lang/Object;" : "()" + result.getDescriptor(), false);
            if (reference) guard.visitTypeInsn(Opcodes.CHECKCAST, result.getInternalName());
        }
        guard.visitInsn(result.getOpcode(Opcodes.IRETURN)); guard.visitLabel(resume);
        target.instructions.insert(guard.instructions); target.maxStack = Math.max(target.maxStack, 16);
    }

    /** A noncancellable callback has no guard and needs no MixinMerged metadata. */
    private static void addObserver(ClassNode owner, MethodNode target) {
        String descriptor = "(L" + CI + ";)V";
        if (owner.methods.stream().noneMatch(method -> method.name.equals("nonCancellableObserver"))) {
            MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE, "nonCancellableObserver", descriptor, null, null);
            helper.visitCode(); helper.visitVarInsn(Opcodes.ALOAD, 0); helper.visitLdcInsn("noncancellable");
            helper.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "record", "(Ljava/lang/Object;Ljava/lang/String;)V", false);
            helper.visitInsn(Opcodes.RETURN); helper.visitMaxs(2, 2); helper.visitEnd(); owner.methods.add(helper);
        }
        MethodNode observer = new MethodNode(); observer.visitVarInsn(Opcodes.ALOAD, 0);
        observer.visitTypeInsn(Opcodes.NEW, CI); observer.visitInsn(Opcodes.DUP); observer.visitLdcInsn(target.name); observer.visitInsn(Opcodes.ICONST_0);
        observer.visitMethodInsn(Opcodes.INVOKESPECIAL, CI, "<init>", "(Ljava/lang/String;Z)V", false);
        observer.visitMethodInsn(Opcodes.INVOKESPECIAL, owner.name, "nonCancellableObserver", descriptor, false);
        target.instructions.insert(observer.instructions); target.maxStack = Math.max(target.maxStack, 6);
    }

    private static void addExtraNormalReturn(String owner, MethodNode target) { addBranchReturn(owner, target, false); }

    private static void addBranchReturn(String owner, MethodNode target, boolean executable) {
        MethodNode branch = new MethodNode(); Label resume = new Label();
        if (executable) {
            branch.visitVarInsn(Opcodes.ALOAD, 0); branch.visitFieldInsn(Opcodes.GETFIELD, owner, "branch", "I");
            branch.visitIntInsn(Opcodes.BIPUSH, 99); branch.visitJumpInsn(Opcodes.IF_ICMPNE, resume);
        } else { branch.visitInsn(Opcodes.ICONST_0); branch.visitJumpInsn(Opcodes.IFEQ, resume); }
        result(branch, target.desc); branch.visitLabel(resume);
        List<Object> locals = arguments(owner, target.desc); branch.visitFrame(Opcodes.F_NEW, locals.size(), locals.toArray(), 0, new Object[0]);
        target.instructions.insert(branch.instructions); target.maxStack = Math.max(target.maxStack, 2);
    }

    /** Recompute frames only for these isolated synthetic classes; never resolves game hierarchy. */
    private static byte[] framed(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String first, String second) { return first.equals(second) ? first : "java/lang/Object"; }
        };
        node.accept(writer); return writer.toByteArray();
    }

    /** Emits the standard Mixin-generated helper/CI guard shape, without loading game callbacks. */
    private static void addGuard(ClassNode owner, MethodNode target, String suffix, boolean executable) {
        addGuard(owner, target, suffix, executable, FABRIC + (client(owner.name) ? "MinecraftMixin" : "MultiPlayerGameModeMixin"),
            "fabric", "fabricCancel", false);
    }

    private static void addGuard(ClassNode owner, MethodNode target, String suffix, boolean executable,
                                 String origin, String event, String cancelField, boolean cancelOnly) {
        Type result = Type.getReturnType(target.desc); String ci = result.getSort() == Type.VOID ? CI : CIR;
        String descriptor = target.desc.substring(0, target.desc.indexOf(')')) + "L" + ci + ";)V";
        String helperName = (event.equals("malilib") ? "handler$bfk000$" : "handler$fixture$") + suffix;
        MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE, helperName, descriptor, null, null);
        AnnotationNode annotation = new AnnotationNode(MERGED);
        annotation.values = new ArrayList<>(List.of("mixin", origin, "priority", 1000));
        helper.visibleAnnotations = new ArrayList<>(List.of(annotation));
        helper.visitCode();
        if (executable) {
            helper.visitVarInsn(Opcodes.ALOAD, 0); helper.visitLdcInsn(event); helper.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "record", "(Ljava/lang/Object;Ljava/lang/String;)V", false);
            Label skip = new Label(); helper.visitVarInsn(Opcodes.ALOAD, 0); helper.visitFieldInsn(Opcodes.GETFIELD, owner.name, cancelField, "Z"); helper.visitJumpInsn(Opcodes.IFEQ, skip);
            int ciLocal = 1; for (Type arg : Type.getArgumentTypes(target.desc)) ciLocal += arg.getSize();
            helper.visitVarInsn(Opcodes.ALOAD, ciLocal);
            if (result.getSort() == Type.VOID || cancelOnly) helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ci, "cancel", "()V", false);
            else {
                if (event.equals("malilib")) helper.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");
                else { helper.visitVarInsn(Opcodes.ALOAD, 0); helper.visitFieldInsn(Opcodes.GETFIELD, owner.name, "cancelResult", "Ljava/lang/Object;"); }
                helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CIR, "setReturnValue", "(Ljava/lang/Object;)V", false);
            }
            helper.visitLabel(skip);
            List<Object> helperLocals = arguments(owner.name, target.desc); helperLocals.add(ci);
            helper.visitFrame(Opcodes.F_NEW, helperLocals.size(), helperLocals.toArray(), 0, new Object[0]);
        }
        helper.visitInsn(Opcodes.RETURN); helper.visitMaxs(3, 2 + Type.getArgumentTypes(target.desc).length); helper.visitEnd(); owner.methods.add(helper);
        int local = target.maxLocals++;
        InsnList guard = new InsnList(); guard.add(new TypeInsnNode(Opcodes.NEW, ci)); guard.add(new InsnNode(Opcodes.DUP));
        guard.add(new LdcInsnNode(target.name)); guard.add(new InsnNode(Opcodes.ICONST_1));
        guard.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ci, "<init>", "(Ljava/lang/String;Z)V", false)); guard.add(new VarInsnNode(Opcodes.ASTORE, local));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0)); int slot = 1;
        for (Type arg : Type.getArgumentTypes(target.desc)) { guard.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot)); slot += arg.getSize(); }
        guard.add(new VarInsnNode(Opcodes.ALOAD, local)); guard.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.name, helperName, descriptor, false));
        guard.add(new VarInsnNode(Opcodes.ALOAD, local)); guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ci, "isCancelled", "()Z", false));
        LabelNode resume = new LabelNode(); guard.add(new JumpInsnNode(Opcodes.IFEQ, resume));
        if (result.getSort() == Type.VOID) guard.add(new InsnNode(Opcodes.RETURN));
        else {
            guard.add(new VarInsnNode(Opcodes.ALOAD, local));
            guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ci, result.getSort() == Type.BOOLEAN ? "getReturnValueZ" : "getReturnValue",
                result.getSort() == Type.BOOLEAN ? "()Z" : "()Ljava/lang/Object;", false));
            if (result.getSort() == Type.OBJECT) guard.add(new TypeInsnNode(Opcodes.CHECKCAST, result.getInternalName()));
            guard.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        }
        guard.add(resume);
        List<Object> locals = arguments(owner.name, target.desc);
        while (locals.size() < local) locals.add(Opcodes.TOP);
        locals.add(ci);
        // A real loop-entry method can already begin with an explicit frame.
        // Keep that original frame at the shared resume offset (the temporary
        // CI is dead beyond the guard). Emitting two different frames there
        // serializes a negative offset_delta as 65535; recomputing the real game
        // hierarchy as Object would be an inappropriate fixture workaround.
        boolean originalEntryFrame = false;
        for (var instruction : target.instructions) {
            if (instruction.getOpcode() >= 0) break;
            if (instruction instanceof FrameNode) { originalEntryFrame = true; break; }
        }
        if (!originalEntryFrame) guard.add(new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 0, new Object[0]));
        target.instructions.insert(guard); target.maxStack = Math.max(target.maxStack, 8);
    }

    private static List<Object> arguments(String owner, String desc) {
        List<Object> locals = new ArrayList<>(); locals.add(owner);
        for (Type arg : Type.getArgumentTypes(desc)) locals.add(switch (arg.getSort()) {
            case Type.OBJECT, Type.ARRAY -> arg.getInternalName(); case Type.FLOAT -> Opcodes.FLOAT;
            case Type.DOUBLE -> Opcodes.DOUBLE; case Type.LONG -> Opcodes.LONG; default -> Opcodes.INTEGER;
        });
        return locals;
    }

    private static byte[] clientFixture(String owner) {
        String player = mapped(owner, "net/minecraft/client/network/ClientPlayerEntity", "net/minecraft/class_746");
        String hand = mapped(owner, "net/minecraft/util/Hand", "net/minecraft/class_1268");
        String stack = mapped(owner, "net/minecraft/item/ItemStack", "net/minecraft/class_1799");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "events", "Ljava/util/List;", null, null).visitEnd();
        for (String name : List.of("bodyCalls", "branch")) writer.visitField(Opcodes.ACC_PUBLIC, name, "I", null, null).visitEnd();
        for (String name : List.of("fabricCancel", "malilibCancel")) writer.visitField(Opcodes.ACC_PUBLIC, name, "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "cancelResult", "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "failure", "Ljava/lang/RuntimeException;", null, null).visitEnd(); constructor(writer, owner, true);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, mapped(owner, "doAttack", "method_1536"), "()Z", null, null); method.visitCode();
        Label healthy = new Label(); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "failure", "Ljava/lang/RuntimeException;");
        method.visitJumpInsn(Opcodes.IFNULL, healthy); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "failure", "Ljava/lang/RuntimeException;");
        method.visitInsn(Opcodes.ATHROW); method.visitLabel(healthy);
        for (int branch = 0; branch < 6; branch++) {
            Label next = new Label(); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "branch", "I");
            method.visitLdcInsn(branch); method.visitJumpInsn(Opcodes.IF_ICMPNE, next);
            method.visitInsn((branch & 1) == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1); method.visitInsn(Opcodes.IRETURN); method.visitLabel(next);
        }
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitLdcInsn("getter");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "record", "(Ljava/lang/Object;Ljava/lang/String;)V", false);
        method.visitTypeInsn(Opcodes.NEW, player); method.visitInsn(Opcodes.DUP); method.visitMethodInsn(Opcodes.INVOKESPECIAL, player, "<init>", "()V", false);
        method.visitTypeInsn(Opcodes.NEW, hand); method.visitInsn(Opcodes.DUP); method.visitMethodInsn(Opcodes.INVOKESPECIAL, hand, "<init>", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, player, mapped(owner, "getStackInHand", "method_5998"), "(L" + hand + ";)L" + stack + ";", false); method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.DUP); method.visitFieldInsn(Opcodes.GETFIELD, owner, "bodyCalls", "I");
        method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD); method.visitFieldInsn(Opcodes.PUTFIELD, owner, "bodyCalls", "I");
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitLdcInsn("body"); method.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "record", "(Ljava/lang/Object;Ljava/lang/String;)V", false);
        method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IRETURN); method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] clientPlayerFixture(String namespace, String player, String hand, String stack) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, player, null, "java/lang/Object", null);
        constructor(writer, player, false);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, mapped(namespace, "getStackInHand", "method_5998"), "(L" + hand + ";)L" + stack + ";", null, null);
        method.visitCode(); method.visitTypeInsn(Opcodes.NEW, stack); method.visitInsn(Opcodes.DUP); method.visitMethodInsn(Opcodes.INVOKESPECIAL, stack, "<init>", "()V", false);
        method.visitInsn(Opcodes.ARETURN); method.visitMaxs(2, 2); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] fixture(String owner, List<MethodNode> targets, String inventory) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "events", "Ljava/util/List;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "bodyCalls", "I", null, null).visitEnd(); writer.visitField(Opcodes.ACC_PUBLIC, "branch", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "fabricCancel", "Z", null, null).visitEnd(); writer.visitField(Opcodes.ACC_PUBLIC, "cancelResult", "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "malilibCancel", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "genericCalls", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "genericCancelAt", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "failure", "Ljava/lang/RuntimeException;", null, null).visitEnd(); constructor(writer, owner, true);
        for (MethodNode target : targets) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, target.name, target.desc, null, null); method.visitCode();
            Label healthy = new Label(); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "failure", "Ljava/lang/RuntimeException;");
            method.visitJumpInsn(Opcodes.IFNULL, healthy); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "failure", "Ljava/lang/RuntimeException;"); method.visitInsn(Opcodes.ATHROW); method.visitLabel(healthy);
            for (int i = 0; i < originalReturns(owner, target.name) - 1; i++) {
                Label next = new Label(); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "branch", "I");
                method.visitLdcInsn(i); method.visitJumpInsn(Opcodes.IF_ICMPNE, next); result(method, target.desc); method.visitLabel(next);
            }
            if (target.name.equals(mapped(owner, "syncSelectedSlot", "method_2911"))) {
                method.visitTypeInsn(Opcodes.NEW, inventory); method.visitInsn(Opcodes.DUP); method.visitMethodInsn(Opcodes.INVOKESPECIAL, inventory, "<init>", "()V", false);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, inventory, mapped(owner, "getSelectedSlot", "method_67532"), "()I", false); method.visitInsn(Opcodes.POP);
            } else if (target.name.equals("method_41929")) {
                String player = mapped(owner, "net/minecraft/entity/player/PlayerEntity", "net/minecraft/class_1657");
                for (String getter : List.of(mapped(owner, "getYaw", "method_36454"), mapped(owner, "getPitch", "method_36455"))) {
                    method.visitVarInsn(Opcodes.ALOAD, 2); method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, player, getter, "()F", false); method.visitInsn(Opcodes.POP);
                }
            }
            method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.DUP); method.visitFieldInsn(Opcodes.GETFIELD, owner, "bodyCalls", "I"); method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.IADD); method.visitFieldInsn(Opcodes.PUTFIELD, owner, "bodyCalls", "I"); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitLdcInsn("body");
            method.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "record", "(Ljava/lang/Object;Ljava/lang/String;)V", false); result(method, target.desc); method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }
    private static void result(MethodVisitor method, String desc) {
        Type result = Type.getReturnType(desc);
        if (result.getSort() == Type.VOID) method.visitInsn(Opcodes.RETURN);
        else {
            method.visitInsn(switch (result.getSort()) {
                case Type.OBJECT, Type.ARRAY -> Opcodes.ACONST_NULL;
                case Type.LONG -> Opcodes.LCONST_0; case Type.FLOAT -> Opcodes.FCONST_0; case Type.DOUBLE -> Opcodes.DCONST_0;
                default -> Opcodes.ICONST_0;
            });
            method.visitInsn(result.getOpcode(Opcodes.IRETURN));
        }
    }
    private static byte[] empty(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        constructor(writer, owner, false);
        if (owner.endsWith("/PlayerEntity") || owner.endsWith("/class_1657")) {
            for (String name : List.of(mapped(owner, "getYaw", "method_36454"), mapped(owner, "getPitch", "method_36455"))) {
                MethodVisitor getter = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "()F", null, null); getter.visitCode();
                getter.visitInsn(Opcodes.FCONST_0); getter.visitInsn(Opcodes.FRETURN); getter.visitMaxs(1, 1); getter.visitEnd();
            }
        }
        writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] inventoryFixture(String owner, String inventory) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, inventory, null, "java/lang/Object", null); constructor(writer, inventory, false);
        MethodVisitor getter = writer.visitMethod(Opcodes.ACC_PUBLIC, mapped(owner, "getSelectedSlot", "method_67532"), "()I", null, null); getter.visitCode(); getter.visitInsn(Opcodes.ICONST_4);
        getter.visitInsn(Opcodes.IRETURN); getter.visitMaxs(1, 1); getter.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static void constructor(ClassWriter writer, String owner, boolean events) {
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        if (events) {
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList"); init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false); init.visitFieldInsn(Opcodes.PUTFIELD, owner, "events", "Ljava/util/List;");
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitInsn(Opcodes.ICONST_M1); init.visitFieldInsn(Opcodes.PUTFIELD, owner, "branch", "I");
        }
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(3, 1); init.visitEnd();
    }
    private static final class Recorder implements InteractionHookBridge.Handler, GameplayCallbackBridge.Handler {
        final Object instance; Object player, target; RuntimeException observerFailure, beginFailure, endFailure;
        int breakingDepth, endCalls;
        Recorder(Object instance) { this.instance = instance; }
        public void entityAttack(Object value) { check(value == target, "exact observer target"); record(instance, "observer"); if (observerFailure != null) throw observerFailure; }
        public void beforeAttack(Object manager, Object value) { check(manager == instance && value == player, "exact sprint capture arguments"); record(instance, "capture"); }
        public void afterAttack(Object manager, Object value) { check(manager == instance && value == target, "exact cleanup arguments"); record(instance, "afterAttack"); }
        public int selectedSlot(Object inventory, int vanilla) { return vanilla; }
        public void beginBreaking(Object manager) {
            check(manager == instance, "exact breaking manager"); record(manager, "beginBreaking");
            if (beginFailure != null) throw beginFailure; breakingDepth++;
        }
        public void endBreaking(Object manager) {
            check(manager == instance && breakingDepth > 0, "cleanup matches active breaking projection");
            breakingDepth--; endCalls++; record(manager, "endBreaking"); if (endFailure != null) throw endFailure;
        }
        public void beforeItem(Object manager, Object value, Object hand) { record(manager, "beforeItem"); }
        public void afterItem(Object manager, Object value) { record(manager, "afterItem"); }
        public void serverUpdate(int kind, Object packet) {
            check(network(instance.getClass().getName().replace('.', '/')) && packet == target, "exact network TAIL packet"); record(instance, "serverUpdate");
        }
        public void useItem(Object client) { check(client == instance, "exact use receiver"); record(instance, "useItem"); }
        public void blockBreaking(Object client, boolean breaking) {
            check(client == instance, "exact breaking receiver"); record(instance, "blockBreaking");
        }
        public boolean attack(Object client) { throw new AssertionError("unexpected click redirect"); }
        public Object mainHand(Object vanilla, Object hand) { if (client(instance.getClass().getName().replace('.', '/'))) record(instance, "mainHand"); return vanilla; }
    }
    public static void record(Object instance, String event) { events(instance).add(event); }
    @SuppressWarnings("unchecked")
    private static List<String> events(Object instance) {
        try { return (List<String>) instance.getClass().getField("events").get(instance); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static final class FixtureLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;
        FixtureLoader(Map<String, byte[]> definitions) { super(CombinedHooksCheck.class.getClassLoader()); this.definitions = definitions; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name); if (bytes == null) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) { Class<?> type = findLoadedClass(name); if (type == null) type = defineClass(name, bytes, 0, bytes.length); if (resolve) resolveClass(type); return type; }
        }
    }
    private static List<String> families(String owner) {
        if (interaction(owner)) return List.of("interaction", "rotation", "gameplay", "fake-gamble");
        if (client(owner)) return List.of("reload", "fake-gamble", "gameplay");
        if (network(owner)) return List.of("fake-gamble", "gameplay"); return List.of("visual", "rotation");
    }
    private static byte[] prepare(String family, String owner, byte[] bytes) {
        return switch (family) {
            case "interaction" -> InteractionHooksTransformer.prepare(owner, bytes); case "rotation" -> RotationHooksTransformer.prepare(owner, bytes);
            case "fake-gamble" -> FakeGambleHooksTransformer.prepare(owner, bytes); case "gameplay" -> GameplayCallbacksTransformer.prepare(owner, bytes);
            case "reload" -> ResourceReloadTransformer.prepare(owner, bytes); case "visual" -> VisualHooksTransformer.prepare(owner, bytes);
            default -> throw new IllegalArgumentException(family);
        };
    }
    private static Map<String, Map<String, Integer>> callbacks(ClassNode type) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (MethodNode method : type.methods) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.startsWith(PREFIX) && !call.owner.contains("/fixture/")) counts.merge(call.owner + "." + call.name + call.desc, 1, Integer::sum);
            if (!counts.isEmpty()) result.put(method.name + method.desc, counts);
        }
        return result;
    }
    private static void merge(Map<String, Map<String, Integer>> into, Map<String, Map<String, Integer>> from) {
        from.forEach((method, values) -> values.forEach((hook, count) -> into.computeIfAbsent(method, ignored -> new LinkedHashMap<>()).merge(hook, count, Integer::sum)));
    }
    private static List<String> hookNames(MethodNode method) {
        List<String> result = new ArrayList<>(); for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.startsWith(PREFIX) && !call.owner.contains("/fixture/")) result.add(call.owner.substring(PREFIX.length()) + "." + call.name); return result;
    }
    private static int countHook(MethodNode method, String hook) { return (int) hookNames(method).stream().filter(hook::equals).count(); }
    private static int returnCount(MethodNode method) { int count = 0; for (var instruction : method.instructions) if (instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN) count++; return count; }
    private static String fabricSuffix(String owner, String name) {
        if (name.equals(mapped(owner, "attackBlock", "method_2910"))) return "attackBlock";
        if (name.equals(mapped(owner, "updateBlockBreakingProgress", "method_2902"))) return "method_2902";
        if (name.equals(mapped(owner, "attackEntity", "method_2918"))) return "attackEntity";
        if (name.equals(mapped(owner, "interactItem", "method_2919"))) return "interactItem";
        if (name.equals(mapped(owner, "interactBlock", "method_2896"))) return "interactBlock"; return null;
    }
    private static String interactionTail(String owner, String name) {
        String suffix = fabricSuffix(owner, name); if (suffix == null) return null;
        return switch (suffix) { case "attackBlock", "method_2902" -> "endBreaking"; case "attackEntity" -> "afterAttack"; case "interactItem" -> "afterItem"; default -> null; };
    }
    private static int originalReturns(String owner, String name) {
        String suffix = fabricSuffix(owner, name); if (suffix == null) return 1;
        return switch (suffix) { case "attackBlock" -> 3; case "method_2902" -> 5; case "interactItem", "interactBlock" -> 2; default -> 1; };
    }
    private static boolean named(String owner) { return !owner.startsWith("net/minecraft/class_"); }
    private static boolean interaction(String owner) { return owner.endsWith("/ClientPlayerInteractionManager") || owner.endsWith("/class_636"); }
    private static boolean client(String owner) { return owner.endsWith("/MinecraftClient") || owner.endsWith("/class_310"); }
    private static boolean network(String owner) { return owner.endsWith("/ClientPlayNetworkHandler") || owner.endsWith("/class_634"); }
    private static String mapped(String owner, String name, String intermediary) { return named(owner) ? name : intermediary; }
    private static MethodNode method(ClassNode owner, String name) { return owner.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow(); }
    private static Method declared(Class<?> owner, String name) { return Arrays.stream(owner.getDeclaredMethods()).filter(method -> method.getName().equals(name)).findFirst().orElseThrow(); }
    private static Object[] parameters(Method method) throws Exception {
        Object[] result = new Object[method.getParameterCount()]; Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < result.length; i++) { if (types[i] == int.class) result[i] = 3 + i; else if (types[i] == float.class) result[i] = 0.5F; else result[i] = types[i].getConstructor().newInstance(); } return result;
    }
    private static void reset(Object instance) throws Exception { events(instance).clear(); instance.getClass().getField("bodyCalls").setInt(instance, 0); }
    private static Object invoke(Method method, Object instance, Object... arguments) throws Exception {
        try { return method.invoke(instance, arguments); } catch (InvocationTargetException failure) { if (failure.getCause() instanceof Exception exception) throw exception; if (failure.getCause() instanceof Error error) throw error; throw new AssertionError(failure.getCause()); }
    }
    private static byte[] resource(String path) throws Exception { try (InputStream input = CombinedHooksCheck.class.getClassLoader().getResourceAsStream(path)) { if (input == null) throw new IllegalStateException("Missing " + path); return input.readAllBytes(); } }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node; }
    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private static List<String> schema(ClassNode node) {
        List<String> result = new ArrayList<>(); result.add(node.version + ":" + node.access + ":" + node.name + ":" + node.superName + ":" + node.signature + ":" + node.interfaces);
        node.fields.forEach(field -> result.add("F:" + field.access + ":" + field.name + ":" + field.desc + ":" + field.signature)); node.methods.forEach(method -> result.add("M:" + method.access + ":" + method.name + ":" + method.desc + ":" + method.signature + ":" + method.exceptions)); return result;
    }
    private static String text(MethodNode method) { Textifier printer = new Textifier(); method.accept(new TraceMethodVisitor(printer)); StringWriter text = new StringWriter(); printer.print(new PrintWriter(text)); return text.toString(); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static Throwable expect(Class<? extends Throwable> type, Throwing action, String message) { checks++; try { action.run(); } catch (Throwable failure) { if (type.isInstance(failure)) return failure; throw new AssertionError(message, failure); } throw new AssertionError(message + ": no exception"); }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
