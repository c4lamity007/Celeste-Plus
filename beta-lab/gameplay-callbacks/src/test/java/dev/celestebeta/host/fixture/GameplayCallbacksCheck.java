package dev.celestebeta.host.fixture;

import dev.celestebeta.host.GameplayCallbackBridge;
import dev.c4lam.celeste.compat.TotemPopTrackerCheck;
import dev.celestebeta.host.instrument.GameplayCallbacksTransformer;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/** Real class-schema checks plus explicitly synthetic executable JVM fixtures. */
public final class GameplayCallbacksCheck {
    private static final String BRIDGE = "dev/celestebeta/host/GameplayCallbackBridge";
    private static final List<String> OWNERS = List.of(
        "net/minecraft/client/network/ClientPlayNetworkHandler", "net/minecraft/class_634",
        "net/minecraft/client/MinecraftClient", "net/minecraft/class_310",
        "net/minecraft/client/network/ClientPlayerInteractionManager", "net/minecraft/class_636");
    private static final List<String> NETWORK_NAMED = List.of(
        "onWorldTimeUpdate", "onBlockUpdate", "onChunkDeltaUpdate", "onEntitySpawn", "onEntitiesDestroy", "onEntityStatus");
    private static final List<String> NETWORK_INTERMEDIARY = List.of(
        "method_11079", "method_11136", "method_11100", "method_11112", "method_11095", "method_11148");
    private static int checks;

    private GameplayCallbacksCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected intermediary jar path");
        checks += TotemPopTrackerCheck.run();
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile())) {
            for (String owner : OWNERS) {
                byte[] original;
                if (named(owner)) original = resource(owner + ".class");
                else {
                    var entry = jar.getJarEntry(owner + ".class");
                    if (entry == null) throw new IllegalStateException("Missing intermediary class " + owner);
                    try (InputStream input = jar.getInputStream(entry)) { original = input.readAllBytes(); }
                }
                actual(owner, original);
            }
        }
        GameplayCallbackBridge.assertVisibleFrom(GameplayCallbacksCheck.class.getClassLoader());
        checks++;
        System.out.println("PASS gameplay callbacks: " + checks
            + " checks; three real named/intermediary schemas and executable TAIL, HEAD, attack and stack fixtures");
    }

    private static void actual(String owner, byte[] original) throws Exception {
        byte[] snapshot = original.clone();
        byte[] prepared = GameplayCallbacksTransformer.prepare(owner, original);
        ClassNode before = read(original), after = read(prepared);
        check(schema(before).equals(schema(after)), "class schema unchanged: " + owner);
        List<MethodNode> targets = new ArrayList<>();
        for (int index = 0; index < before.methods.size(); index++) {
            MethodNode old = before.methods.get(index), updated = after.methods.get(index);
            if (text(old).equals(text(updated))) continue;
            targets.add(old);
            new Analyzer<>(new BasicVerifier()).analyze(owner, updated);
            checks++;
            Map<String, Integer> expected = expectedHooks(owner, old);
            check(!expected.isEmpty(), "only intended method changed: " + owner + "." + old.name);
            Map<String, Integer> hooks = new LinkedHashMap<>();
            for (var instruction : updated.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) {
                    hooks.merge(call.name, 1, Integer::sum);
                }
            }
            check(expected.equals(hooks), "exact hook kinds/counts: " + old.name + " expected=" + expected + " actual=" + hooks);
        }
        check(targets.size() == (network(owner) ? 6 : client(owner) ? 2 : 1), "exact changed method count: " + owner);
        check(Arrays.equals(snapshot, original), "caller bytes unchanged");
        expect(IllegalArgumentException.class, () -> GameplayCallbacksTransformer.prepare(owner, prepared), "duplicate preparation rejected");
        expect(IllegalArgumentException.class, () -> GameplayCallbacksTransformer.prepare("other/Game", original), "unsupported class rejected");
        expect(IllegalArgumentException.class,
            () -> GameplayCallbacksTransformer.prepare(OWNERS.get((OWNERS.indexOf(owner) + 1) % OWNERS.size()), original), "wrong class identity rejected");
        for (MethodNode target : targets) {
            ClassNode missing = read(original);
            missing.methods.removeIf(method -> method.name.equals(target.name) && method.desc.equals(target.desc));
            expect(IllegalArgumentException.class, () -> GameplayCallbacksTransformer.prepare(owner, write(missing)), "missing target rejected");
            for (int modifier : List.of(Opcodes.ACC_STATIC, Opcodes.ACC_ABSTRACT, Opcodes.ACC_NATIVE)) {
                ClassNode incompatible = read(original); find(incompatible, target).access |= modifier;
                expect(IllegalArgumentException.class, () -> GameplayCallbacksTransformer.prepare(owner, write(incompatible)), "incompatible target access rejected");
            }
            ClassNode wrongReturn = read(original);
            for (var instruction : find(wrongReturn, target).instructions.toArray()) {
                int opcode = instruction.getOpcode();
                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                    find(wrongReturn, target).instructions.set(instruction, new InsnNode(Opcodes.ARETURN));
                    break;
                }
            }
            expect(IllegalArgumentException.class, () -> GameplayCallbacksTransformer.prepare(owner, write(wrongReturn)), "incompatible return opcode rejected");
            if (network(owner)) {
                ClassNode overwritten = read(original);
                find(overwritten, target).instructions.insert(new VarInsnNode(Opcodes.ASTORE, 1));
                find(overwritten, target).instructions.insert(new InsnNode(Opcodes.ACONST_NULL));
                expect(IllegalArgumentException.class, () -> GameplayCallbacksTransformer.prepare(owner, write(overwritten)), "overwritten packet argument rejected");
            }
            if (client(owner)) {
                ClassNode missingCall = read(original);
                for (var instruction : find(missingCall, target).instructions) {
                    if (instruction instanceof MethodInsnNode call && redirected(owner, target, call)) call.name += "$missing";
                }
                expect(IllegalArgumentException.class, () -> GameplayCallbacksTransformer.prepare(owner, write(missingCall)), "missing redirect site rejected");
            }
        }
        ClassNode startup = read(original);
        startup.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "celeste$existing", "()V", null, null));
        expect(IllegalArgumentException.class, () -> GameplayCallbacksTransformer.prepare(owner, write(startup)), "startup mixin rejected");
        execute(owner, targets);
    }

    private static void execute(String owner, List<MethodNode> targets) throws Exception {
        Map<String, byte[]> definitions = new HashMap<>();
        for (MethodNode target : targets) {
            for (Type argument : Type.getArgumentTypes(target.desc)) {
                if (argument.getSort() == Type.OBJECT) definitions.put(argument.getClassName(), simple(argument.getInternalName()));
            }
        }
        if (client(owner)) {
            definitions.put(mapped(owner, "net/minecraft/item/ItemStack", "net/minecraft/class_1799").replace('/', '.'), simple(stack(owner)));
            definitions.put(hand(owner).replace('/', '.'), simple(hand(owner)));
            definitions.put(player(owner).replace('/', '.'), playerFixture(owner));
        }
        byte[] stub = fixture(owner, targets);
        byte[] prepared = GameplayCallbacksTransformer.prepare(owner, stub);
        check(schema(read(stub)).equals(schema(read(prepared))), "fixture schema preserved");
        definitions.put(owner.replace('/', '.'), prepared);
        FixtureLoader loader = new FixtureLoader(definitions);
        Class<?> type = loader.loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance();
        if (client(owner)) clientExecution(owner, instance, targets, loader);
        else observerExecution(owner, instance, targets);
    }

    @SuppressWarnings("try")
    private static void observerExecution(String owner, Object instance, List<MethodNode> targets) throws Exception {
        for (MethodNode target : targets) {
            Method method = declared(instance.getClass(), target.name);
            Object[] arguments = parameters(method);
            Recorder recorder = new Recorder(instance);
            reset(instance);
            invoke(method, instance, arguments);
            check(intField(instance, "bodyCalls") == 1, "disconnected observer executes original body");
            try (var registration = GameplayCallbackBridge.install(recorder)) {
                expect(IllegalStateException.class, () -> GameplayCallbackBridge.install(recorder), "overlapping bridge registration rejected");
                reset(instance);
                invoke(method, instance, arguments);
                String event = network(owner) ? "server:" + networkKind(owner, target.name) + ":1" : "entity:0";
                check(recorder.events.equals(List.of(event)), "original TAIL/HEAD timing: " + target.name);
                check(recorder.observed == arguments[network(owner) ? 0 : 1], "exact original packet/target identity");
                check(intField(instance, "bodyCalls") == 1, "active observer keeps one original body");
                recorder.events.clear(); reset(instance); set(instance, "early", true);
                invoke(method, instance, arguments);
                check(recorder.events.equals(network(owner) ? List.of() : List.of("entity:0")), "TAIL excludes early return; HEAD still observes it");
                check(intField(instance, "bodyCalls") == 0, "early return remains early");
                set(instance, "early", false); recorder.events.clear();
                RuntimeException bodyFailure = new IllegalArgumentException("original observer body");
                set(instance, "failure", bodyFailure);
                check(expect(IllegalArgumentException.class, () -> invoke(method, instance, arguments), "original observer exception") == bodyFailure,
                    "original observer exception identity preserved");
                check(recorder.events.equals(network(owner) ? List.of() : List.of("entity:0")), "no synthetic TAIL on throwing body");
                set(instance, "failure", null); reset(instance); recorder.events.clear();
                RuntimeException handlerFailure = new IllegalStateException("observer callback"); recorder.failure = handlerFailure;
                check(expect(IllegalStateException.class, () -> invoke(method, instance, arguments), "observer callback exception") == handlerFailure,
                    "observer callback exception identity preserved");
                check(intField(instance, "bodyCalls") == (network(owner) ? 1 : 0), "throwing observer retains TAIL/HEAD timing");
                recorder.failure = null; recorder.events.clear(); reset(instance);
                AtomicReference<Throwable> workerFailure = new AtomicReference<>();
                Thread worker = new Thread(() -> {
                    try {
                        invoke(method, instance, arguments);
                        try { registration.close(); throw new AssertionError("worker closed bridge"); }
                        catch (IllegalStateException expected) { /* client-thread confinement */ }
                    } catch (Throwable failure) { workerFailure.set(failure); }
                }, "gameplay-observer-worker");
                worker.start(); worker.join(5000);
                check(!worker.isAlive() && workerFailure.get() == null && recorder.events.isEmpty()
                    && intField(instance, "bodyCalls") == 1, "worker keeps original body without observing or closing client bridge");
                recorder.during = () -> expect(IllegalStateException.class, registration::close, "close during callback rejected");
                invoke(method, instance, arguments); recorder.during = null;
            }
            int events = recorder.events.size(); reset(instance);
            invoke(method, instance, arguments);
            check(recorder.events.size() == events && intField(instance, "bodyCalls") == 1, "closed observer restores original behavior");
        }
    }

    @SuppressWarnings("try")
    private static void clientExecution(String owner, Object instance, List<MethodNode> targets, ClassLoader loader) throws Exception {
        Method input = declared(instance.getClass(), mapped(owner, "handleInputEvents", "method_1508"));
        Method attack = declared(instance.getClass(), mapped(owner, "doAttack", "method_1536"));
        MethodNode inputTarget = targets.stream().filter(method -> method.name.equals(input.getName())).findFirst().orElseThrow();
        int sites = redirectCount(owner, inputTarget);
        Object player = loader.loadClass(player(owner).replace('/', '.')).getConstructor().newInstance();
        Object hand = loader.loadClass(hand(owner).replace('/', '.')).getConstructor().newInstance();
        Object original = loader.loadClass(stack(owner).replace('/', '.')).getConstructor().newInstance();
        Object replacement = original.getClass().getConstructor().newInstance();
        set(instance, "player", player); set(instance, "hand", hand); set(player, "vanillaStack", original);
        GameplayCallbackBridge.preflight(instance.getClass());
        for (boolean value : List.of(false, true)) {
            reset(instance); set(player, "reads", 0); set(instance, "attackResult", value);
            invoke(input, instance);
            check(intField(instance, "bodyCalls") == 1 && intField(instance, "attackCalls") == sites
                && intField(instance, "afterCalls") == sites && intField(player, "reads") == sites,
                "dormant input retains exact attack/read counts");
            check(field(instance, "inputResult").equals(value) && field(instance, "observedStack") == original
                && field(player, "lastHand") == hand, "dormant boolean, stack, and hand identities preserved");
        }
        Recorder recorder = new Recorder(instance); recorder.replacement = replacement;
        try (var registration = GameplayCallbackBridge.install(recorder)) {
            for (boolean result : List.of(false, true)) {
                reset(instance); set(player, "reads", 0); recorder.events.clear(); recorder.attackResult = result;
                invoke(input, instance);
                check(recorder.events.equals(inputEvents(owner, inputTarget, result)),
                    "active action wrappers retain original input call ordering");
                check(intField(instance, "attackCalls") == 0 && intField(player, "reads") == 0
                    && intField(instance, "afterCalls") == sites && field(instance, "inputResult").equals(result),
                    "active redirect returns exact boolean without extra vanilla attack/getter calls");
            }
            reset(instance); set(player, "reads", 0); recorder.events.clear(); set(instance, "attackResult", true);
            check(Boolean.TRUE.equals(invoke(attack, instance)), "direct original attack boolean unchanged");
            check(recorder.events.equals(List.of("main:1")) && recorder.observed == original && recorder.hand == hand,
                "stack filter receives exact getter result and hand after exactly one getter read");
            check(field(instance, "observedStack") == replacement && intField(player, "reads") == 1,
                "filtered stack identity reaches original consumer");
            recorder.replacement = null; recorder.events.clear(); set(player, "reads", 0);
            invoke(attack, instance);
            check(field(instance, "observedStack") == null && intField(player, "reads") == 1, "explicit null filter result preserved");
            recorder.replacement = replacement;
            RuntimeException getterFailure = new IllegalArgumentException("original getter");
            set(player, "failure", getterFailure); recorder.events.clear(); set(player, "reads", 0);
            check(expect(IllegalArgumentException.class, () -> invoke(attack, instance), "original getter exception") == getterFailure,
                "getter exception identity preserved");
            check(recorder.events.isEmpty() && intField(player, "reads") == 1, "throwing getter does not run result filter");
            set(player, "failure", null);
            RuntimeException callbackFailure = new IllegalStateException("gameplay handler"); recorder.failure = callbackFailure;
            reset(instance); set(player, "reads", 0);
            check(expect(IllegalStateException.class, () -> invoke(input, instance), "attack handler exception") == callbackFailure
                && intField(instance, "afterCalls") == 0 && intField(instance, "attackCalls") == 0,
                "attack handler exception reaches original caller without extra body/consumer calls");
            check(expect(IllegalStateException.class, () -> invoke(attack, instance), "stack handler exception") == callbackFailure
                && intField(player, "reads") == 1, "stack handler exception follows exactly one original read");
            recorder.failure = null; recorder.events.clear(); reset(instance);
            RuntimeException inputFailure = new IllegalArgumentException("original input body"); set(instance, "failure", inputFailure);
            check(expect(IllegalArgumentException.class, () -> invoke(input, instance), "input exception") == inputFailure
                && recorder.events.isEmpty(), "throw before original call site starts no action callback");
            set(instance, "failure", null); reset(instance); set(player, "reads", 0);
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { invoke(input, instance); }
                catch (Throwable failure) { workerFailure.set(failure); }
            }, "gameplay-attack-worker");
            worker.start(); worker.join(5000);
            check(!worker.isAlive() && workerFailure.get() == null && recorder.events.isEmpty()
                && intField(instance, "attackCalls") == sites && intField(player, "reads") == sites
                && field(instance, "observedStack") == original, "worker redirects retain original attack and stack without client handler");
            recorder.during = () -> expect(IllegalStateException.class, registration::close, "close during attack rejected");
            invoke(input, instance); recorder.during = null;
        }
        int events = recorder.events.size(); reset(instance); set(player, "reads", 0);
        invoke(input, instance);
        check(recorder.events.size() == events && intField(instance, "attackCalls") == sites
            && intField(player, "reads") == sites && field(instance, "observedStack") == original, "close restores exact vanilla call path");
        RuntimeException originalFailure = new IllegalArgumentException("private vanilla attack"); set(instance, "attackFailure", originalFailure);
        check(expect(IllegalArgumentException.class, () -> invoke(input, instance), "fallback attack failure") == originalFailure,
            "private original attack exception identity survives fallback");
        AssertionError originalError = new AssertionError("private vanilla attack error"); set(instance, "attackFailure", originalError);
        check(expect(AssertionError.class, () -> invoke(input, instance), "fallback attack error") == originalError,
            "private original attack Error identity survives fallback");
        set(instance, "attackFailure", null);
    }

    private static byte[] fixture(String owner, List<MethodNode> targets) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        for (String name : List.of("bodyCalls", "attackCalls", "afterCalls", "useCalls", "breakingCalls")) addField(writer, name, "I");
        for (String name : List.of("early", "attackResult", "inputResult")) addField(writer, name, "Z");
        for (String name : List.of("failure", "attackFailure")) addField(writer, name, "Ljava/lang/Throwable;");
        addField(writer, "observedStack", "Ljava/lang/Object;");
        if (client(owner)) { addField(writer, "player", "L" + player(owner) + ";"); addField(writer, "hand", "L" + hand(owner) + ";"); }
        constructor(writer);
        for (MethodNode target : targets) {
            boolean input = target.name.equals(mapped(owner, "handleInputEvents", "method_1508"));
            MethodVisitor method = writer.visitMethod(client(owner) ? Opcodes.ACC_PRIVATE : Opcodes.ACC_PUBLIC, target.name, target.desc, null, null);
            method.visitCode();
            if (!client(owner)) {
                Label normal = new Label(); method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitFieldInsn(Opcodes.GETFIELD, owner, "early", "Z"); method.visitJumpInsn(Opcodes.IFEQ, normal);
                method.visitInsn(Opcodes.RETURN); method.visitLabel(normal);
                failIfRequested(method, owner, "failure"); increment(method, owner, "bodyCalls"); method.visitInsn(Opcodes.RETURN);
            } else if (input) {
                increment(method, owner, "bodyCalls"); failIfRequested(method, owner, "failure");
                for (var instruction : target.instructions) {
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    if (redirected(owner, target, call)) {
                        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ALOAD, 0);
                        method.visitMethodInsn(call.getOpcode(), call.owner, call.name, call.desc, call.itf);
                        method.visitFieldInsn(Opcodes.PUTFIELD, owner, "inputResult", "Z"); increment(method, owner, "afterCalls");
                    } else if (inputCall(owner, call, "doItemUse", "method_1583", "()V")) {
                        method.visitVarInsn(Opcodes.ALOAD, 0);
                        method.visitMethodInsn(call.getOpcode(), call.owner, call.name, call.desc, call.itf);
                    } else if (inputCall(owner, call, "handleBlockBreaking", "method_1590", "(Z)V")) {
                        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.ICONST_1);
                        method.visitMethodInsn(call.getOpcode(), call.owner, call.name, call.desc, call.itf);
                    }
                }
                method.visitInsn(Opcodes.RETURN);
            } else {
                increment(method, owner, "attackCalls"); failIfRequested(method, owner, "attackFailure");
                for (var instruction : target.instructions) {
                    if (!(instruction instanceof MethodInsnNode call) || !redirected(owner, target, call)) continue;
                    method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitFieldInsn(Opcodes.GETFIELD, owner, "player", "L" + player(owner) + ";");
                    method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "hand", "L" + hand(owner) + ";");
                    method.visitMethodInsn(call.getOpcode(), call.owner, call.name, call.desc, call.itf);
                    method.visitFieldInsn(Opcodes.PUTFIELD, owner, "observedStack", "Ljava/lang/Object;");
                }
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "attackResult", "Z"); method.visitInsn(Opcodes.IRETURN);
            }
            method.visitMaxs(0, 0); method.visitEnd();
        }
        if (client(owner)) {
            MethodVisitor use = writer.visitMethod(Opcodes.ACC_PRIVATE, mapped(owner, "doItemUse", "method_1583"), "()V", null, null);
            use.visitCode(); increment(use, owner, "useCalls"); use.visitInsn(Opcodes.RETURN); use.visitMaxs(0, 0); use.visitEnd();
            MethodVisitor breaking = writer.visitMethod(Opcodes.ACC_PRIVATE, mapped(owner, "handleBlockBreaking", "method_1590"), "(Z)V", null, null);
            breaking.visitCode(); increment(breaking, owner, "breakingCalls"); breaking.visitInsn(Opcodes.RETURN); breaking.visitMaxs(0, 0); breaking.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] playerFixture(String namespace) {
        String owner = player(namespace);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        addField(writer, "reads", "I"); addField(writer, "vanillaStack", "Ljava/lang/Object;");
        addField(writer, "lastHand", "Ljava/lang/Object;"); addField(writer, "failure", "Ljava/lang/Throwable;"); constructor(writer);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, mapped(namespace, "getStackInHand", "method_5998"),
            "(L" + hand(namespace) + ";)L" + stack(namespace) + ";", null, null);
        method.visitCode(); increment(method, owner, "reads");
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitFieldInsn(Opcodes.PUTFIELD, owner, "lastHand", "Ljava/lang/Object;");
        failIfRequested(method, owner, "failure"); method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, "vanillaStack", "Ljava/lang/Object;");
        method.visitTypeInsn(Opcodes.CHECKCAST, stack(namespace)); method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static final class Recorder implements GameplayCallbackBridge.Handler {
        final Object instance;
        final List<String> events = new ArrayList<>();
        Object observed, hand, replacement;
        boolean attackResult;
        RuntimeException failure;
        Runnable during;
        Recorder(Object instance) { this.instance = instance; }
        private void event(String value) { events.add(value); if (during != null) during.run(); if (failure != null) throw failure; }
        private int body() { try { return intField(instance, "bodyCalls"); } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); } }
        public void serverUpdate(int kind, Object packet) { observed = packet; event("server:" + kind + ":" + body()); }
        public void useItem(Object client) { if (client != instance) throw new AssertionError("wrong use receiver"); event("use:" + body()); }
        public void blockBreaking(Object client, boolean breaking) {
            if (client != instance) throw new AssertionError("wrong breaking receiver");
            event("breaking:" + breaking + ":" + body());
        }
        public void entityAttack(Object target) { observed = target; event("entity:" + body()); }
        public boolean attack(Object client) {
            if (client != instance) throw new AssertionError("wrong client receiver"); event("attack:" + body()); return attackResult;
        }
        public Object mainHand(Object vanilla, Object value) {
            observed = vanilla; hand = value;
            try { event("main:" + intField(field(instance, "player"), "reads")); }
            catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
            return replacement;
        }
    }

    private static Map<String, Integer> expectedHooks(String owner, MethodNode target) {
        if (network(owner) && networkKind(owner, target.name) >= 0) return Map.of("serverUpdate", 1);
        if (client(owner)) {
            if (target.name.equals(mapped(owner, "handleInputEvents", "method_1508"))) return Map.of(
                "attack", redirectCount(owner, target),
                "useItem", inputCallCount(owner, target, "doItemUse", "method_1583", "()V"),
                "blockBreaking", inputCallCount(owner, target, "handleBlockBreaking", "method_1590", "(Z)V"));
            if (target.name.equals(mapped(owner, "doAttack", "method_1536"))) return Map.of("mainHand", redirectCount(owner, target));
        }
        if (!network(owner) && !client(owner) && target.name.equals(mapped(owner, "attackEntity", "method_2918"))) return Map.of("entityAttack", 1);
        return Map.of();
    }
    private static int redirectCount(String owner, MethodNode target) {
        int count = 0;
        for (var instruction : target.instructions) if (instruction instanceof MethodInsnNode call && redirected(owner, target, call)) count++;
        return count;
    }
    private static int inputCallCount(String owner, MethodNode target, String named, String intermediary, String descriptor) {
        int count = 0;
        for (var instruction : target.instructions) if (instruction instanceof MethodInsnNode call
            && inputCall(owner, call, named, intermediary, descriptor)) count++;
        return count;
    }
    private static boolean inputCall(String owner, MethodInsnNode call, String named, String intermediary, String descriptor) {
        return call.owner.equals(owner) && call.name.equals(mapped(owner, named, intermediary)) && call.desc.equals(descriptor);
    }
    private static List<String> inputEvents(String owner, MethodNode target, boolean attackResult) {
        List<String> result = new ArrayList<>();
        for (var instruction : target.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (redirected(owner, target, call)) result.add("attack:1");
            else if (inputCall(owner, call, "doItemUse", "method_1583", "()V")) result.add("use:1");
            else if (inputCall(owner, call, "handleBlockBreaking", "method_1590", "(Z)V")) result.add("breaking:true:1");
        }
        return result;
    }
    private static boolean redirected(String owner, MethodNode target, MethodInsnNode call) {
        if (target.name.equals(mapped(owner, "handleInputEvents", "method_1508"))) {
            return call.owner.equals(owner) && call.name.equals(mapped(owner, "doAttack", "method_1536")) && call.desc.equals("()Z");
        }
        return target.name.equals(mapped(owner, "doAttack", "method_1536")) && call.owner.equals(player(owner))
            && call.name.equals(mapped(owner, "getStackInHand", "method_5998"))
            && call.desc.equals("(L" + hand(owner) + ";)L" + stack(owner) + ";");
    }
    private static boolean named(String owner) { return !owner.startsWith("net/minecraft/class_"); }
    private static boolean network(String owner) { return owner.endsWith("/ClientPlayNetworkHandler") || owner.endsWith("/class_634"); }
    private static boolean client(String owner) { return owner.endsWith("/MinecraftClient") || owner.endsWith("/class_310"); }
    private static int networkKind(String owner, String method) {
        int index = (named(owner) ? NETWORK_NAMED : NETWORK_INTERMEDIARY).indexOf(method);
        return index == 5 ? 8 : index;
    }
    private static String mapped(String owner, String named, String intermediary) { return named(owner) ? named : intermediary; }
    private static String player(String owner) { return mapped(owner, "net/minecraft/client/network/ClientPlayerEntity", "net/minecraft/class_746"); }
    private static String hand(String owner) { return mapped(owner, "net/minecraft/util/Hand", "net/minecraft/class_1268"); }
    private static String stack(String owner) { return mapped(owner, "net/minecraft/item/ItemStack", "net/minecraft/class_1799"); }
    private static void addField(ClassWriter writer, String name, String desc) { writer.visitField(Opcodes.ACC_PUBLIC, name, desc, null, null).visitEnd(); }
    private static void increment(MethodVisitor method, String owner, String name) {
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.DUP); method.visitFieldInsn(Opcodes.GETFIELD, owner, name, "I");
        method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD); method.visitFieldInsn(Opcodes.PUTFIELD, owner, name, "I");
    }
    private static void failIfRequested(MethodVisitor method, String owner, String name) {
        Label healthy = new Label(); method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, name, "Ljava/lang/Throwable;"); method.visitJumpInsn(Opcodes.IFNULL, healthy);
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, name, "Ljava/lang/Throwable;");
        method.visitInsn(Opcodes.ATHROW); method.visitLabel(healthy);
    }
    private static void constructor(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(1, 1); method.visitEnd();
    }
    private static byte[] simple(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        constructor(writer); writer.visitEnd(); return writer.toByteArray();
    }
    private static final class FixtureLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;
        FixtureLoader(Map<String, byte[]> definitions) { super(GameplayCallbacksCheck.class.getClassLoader()); this.definitions = definitions; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name); if (bytes == null) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name); if (type == null) type = defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(type); return type;
            }
        }
    }
    private static MethodNode find(ClassNode node, MethodNode target) {
        return node.methods.stream().filter(method -> method.name.equals(target.name) && method.desc.equals(target.desc)).findFirst().orElseThrow();
    }
    private static Method declared(Class<?> type, String name) {
        Method method = Arrays.stream(type.getDeclaredMethods()).filter(value -> value.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true); return method;
    }
    private static Object[] parameters(Method method) throws Exception {
        Object[] values = new Object[method.getParameterCount()];
        for (int index = 0; index < values.length; index++) values[index] = method.getParameterTypes()[index].getConstructor().newInstance();
        return values;
    }
    private static void reset(Object instance) throws ReflectiveOperationException {
        for (String name : List.of("bodyCalls", "attackCalls", "afterCalls", "useCalls", "breakingCalls")) set(instance, name, 0);
    }
    private static Object field(Object instance, String name) throws ReflectiveOperationException { return instance.getClass().getField(name).get(instance); }
    private static int intField(Object instance, String name) throws ReflectiveOperationException { return instance.getClass().getField(name).getInt(instance); }
    private static void set(Object instance, String name, Object value) throws ReflectiveOperationException { instance.getClass().getField(name).set(instance, value); }
    private static Object invoke(Method method, Object instance, Object... arguments) throws Exception {
        try { return method.invoke(instance, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }
    private static byte[] resource(String path) throws Exception {
        try (InputStream input = GameplayCallbacksCheck.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing " + path); return input.readAllBytes();
        }
    }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node; }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static List<String> schema(ClassNode node) {
        List<String> values = new ArrayList<>();
        values.add(node.version + ":" + node.access + ":" + node.name + ":" + node.superName + ":" + node.signature + ":" + node.interfaces);
        node.fields.forEach(field -> values.add("F:" + field.access + ":" + field.name + ":" + field.desc + ":" + field.signature));
        node.methods.forEach(method -> values.add("M:" + method.access + ":" + method.name + ":" + method.desc + ":" + method.signature + ":" + method.exceptions));
        return values;
    }
    private static String text(MethodNode method) {
        Textifier printer = new Textifier(); method.accept(new TraceMethodVisitor(printer)); StringWriter text = new StringWriter();
        printer.print(new PrintWriter(text)); return text.toString();
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static Throwable expect(Class<? extends Throwable> type, Throwing action, String message) {
        checks++; try { action.run(); }
        catch (Throwable failure) { if (type.isInstance(failure)) return failure; throw new AssertionError(message, failure); }
        throw new AssertionError(message + ": no exception");
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
