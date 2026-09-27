package dev.celestebeta.host.fixture;

import dev.celestebeta.host.RotationHookBridge;
import dev.celestebeta.host.instrument.RotationHooksTransformer;
import dev.c4lam.celeste.compat.KeyboardInputAccess;
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
import net.minecraft.client.input.Input;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec2f;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/** Actual class-schema checks plus explicitly synthetic executable JVM fixtures. */
public final class RotationHooksCheck {
    private static final String BRIDGE = "dev/celestebeta/host/RotationHookBridge";
    private static final List<String> OWNERS = List.of(
        "net/minecraft/client/network/ClientPlayerEntity", "net/minecraft/class_746",
        "net/minecraft/client/network/ClientPlayerInteractionManager", "net/minecraft/class_636",
        "net/minecraft/entity/Entity", "net/minecraft/class_1297",
        "net/minecraft/client/input/KeyboardInput", "net/minecraft/class_743",
        "net/minecraft/entity/LivingEntity", "net/minecraft/class_1309",
        "net/minecraft/entity/player/PlayerInventory", "net/minecraft/class_1661",
        "net/minecraft/client/render/WorldRenderer", "net/minecraft/class_761");
    private static int checks;

    private RotationHooksCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected intermediary jar path");
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
            try (InputStream input = jar.getInputStream(jar.getJarEntry("net/minecraft/class_744.class"))) {
                ClassNode intermediaryInput = read(input.readAllBytes());
                var movement = intermediaryInput.fields.stream().filter(field -> field.name.equals("field_55868")).toList();
                check(movement.size() == 1 && movement.getFirst().desc.equals("Lnet/minecraft/class_241;")
                    && (movement.getFirst().access & (Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == Opcodes.ACC_PROTECTED,
                    "actual intermediary movement field is the expected protected mutable Vec2f");
            }
        }
        KeyboardInputAccess.verifyLateMember();
        Input input = new Input(); Vec2f movement = new Vec2f(0.25F, -0.75F);
        KeyboardInputAccess.setMovement(input, movement);
        check(input.getMovementInput() == movement, "actual named Input movement helper writes exact vector identity");
        RotationHookBridge.verifyVanillaAccess(Entity.class);
        checks++;
        bridgeContract();
        System.out.println("PASS late rotation hooks: " + checks
            + " checks; seven real named/intermediary schemas and executable camera, getter, keyboard and inventory fixtures");
    }

    private static void actual(String owner, byte[] original) throws Exception {
        byte[] snapshot = original.clone();
        byte[] prepared = RotationHooksTransformer.prepare(owner, original);
        ClassNode before = read(original), after = read(prepared);
        check(schema(before).equals(schema(after)), "class schema unchanged: " + owner);
        if (player(owner)) {
            MethodNode control = after.methods.stream()
                .filter(method -> method.name.equals(mapped(owner, "isCamera", "method_3134"))
                    && method.desc.equals("()Z"))
                .findFirst().orElseThrow();
            int frames = 0;
            for (var instruction : control.instructions) {
                if (!(instruction instanceof FrameNode frame)) continue;
                frames++;
                check(frame.local != null && !frame.local.isEmpty() && owner.equals(frame.local.getFirst()),
                    "local-control callback retains its receiver across verifier frames: " + owner);
            }
            check(frames > 0, "local-control target has verifier frames: " + owner);
        }
        List<MethodNode> changed = new ArrayList<>();
        for (int i = 0; i < before.methods.size(); i++) {
            MethodNode old = before.methods.get(i), updated = after.methods.get(i);
            if (text(old).equals(text(updated))) continue;
            changed.add(old);
            new Analyzer<>(new BasicVerifier()).analyze(owner, updated);
            checks++;
            Map<String, Integer> expected = expectedHooks(owner, old);
            check(!expected.isEmpty(), "only intended target methods changed: " + old.name);
            Map<String, Integer> actual = new LinkedHashMap<>();
            for (var instruction : updated.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) {
                    actual.merge(call.name, 1, Integer::sum);
                }
            }
            check(expected.equals(actual), "exact bridge kinds and invocation counts: " + owner + "." + old.name
                + " expected=" + expected + " actual=" + actual);
        }
        check(changed.size() == expectedMethods(owner), "exact changed method count: " + owner);
        check(Arrays.equals(snapshot, original), "input bytes preserved");
        expect(IllegalArgumentException.class, () -> RotationHooksTransformer.prepare(owner, prepared), "duplicate transform rejected");
        expect(IllegalArgumentException.class, () -> RotationHooksTransformer.prepare("other/Game", original), "unsupported target rejected");
        expect(IllegalArgumentException.class, () -> RotationHooksTransformer.prepare(OWNERS.get((OWNERS.indexOf(owner) + 1) % OWNERS.size()), original), "name mismatch rejected");
        for (MethodNode target : changed) {
            ClassNode missing = read(original);
            missing.methods.removeIf(method -> method.name.equals(target.name) && method.desc.equals(target.desc));
            expect(IllegalArgumentException.class, () -> RotationHooksTransformer.prepare(owner, write(missing)), "missing target method rejected");
            Map<String, MethodInsnNode> unique = new LinkedHashMap<>();
            if (!expectedHooks(owner, target).containsKey("lookDeltas")) {
                for (var instruction : target.instructions) {
                    if (instruction instanceof MethodInsnNode call && getterHook(owner, target, call) != null) {
                        unique.putIfAbsent(call.owner + "." + call.name + call.desc, call);
                    }
                }
            }
            for (MethodInsnNode site : unique.values()) {
                ClassNode absent = read(original);
                MethodNode method = find(absent, target);
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && sameCall(call, site)) call.name += "$missing";
                }
                expect(IllegalArgumentException.class, () -> RotationHooksTransformer.prepare(owner, write(absent)), "missing getter call rejected");
            }
        }
        ClassNode startup = read(original);
        startup.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "celeste$existing", "()V", null, null));
        expect(IllegalArgumentException.class, () -> RotationHooksTransformer.prepare(owner, write(startup)), "startup mixin rejected");
        execute(owner, changed);
    }

    @SuppressWarnings("try")
    private static void execute(String owner, List<MethodNode> targets) throws Exception {
        Map<String, byte[]> definitions = new HashMap<>();
        String vector = mapped(owner, "net/minecraft/util/math/Vec3d", "net/minecraft/class_243");
        String stack = mapped(owner, "net/minecraft/item/ItemStack", "net/minecraft/class_1799");
        definitions.put(vector.replace('/', '.'), simple(vector));
        definitions.put(stack.replace('/', '.'), simple(stack));
        for (MethodNode target : targets) {
            for (Type type : Type.getArgumentTypes(target.desc)) if (type.getSort() == Type.OBJECT) {
                definitions.putIfAbsent(type.getClassName(), simple(type.getInternalName()));
            }
            Type result = Type.getReturnType(target.desc);
            if (result.getSort() == Type.OBJECT) definitions.putIfAbsent(result.getClassName(), simple(result.getInternalName()));
        }
        String player = mapped(owner, "net/minecraft/entity/player/PlayerEntity", "net/minecraft/class_1657");
        if (interaction(owner)) definitions.put(player.replace('/', '.'), receiver(player, named(owner), vector));
        if (worldRenderer(owner)) {
            String camera = mapped(owner, "net/minecraft/client/render/Camera", "net/minecraft/class_4184");
            String entity = mapped(owner, "net/minecraft/entity/Entity", "net/minecraft/class_1297");
            definitions.put(entity.replace('/', '.'), simple(entity));
            definitions.put(camera.replace('/', '.'), focusCamera(camera, entity));
        }
        definitions.put(owner.replace('/', '.'), RotationHooksTransformer.prepare(owner, fixture(owner, targets, vector)));
        FixtureLoader loader = new FixtureLoader(definitions);
        Class<?> type = loader.loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance();
        Object vanillaVector = loader.loadClass(vector.replace('/', '.')).getConstructor().newInstance();
        Object vanillaStack = loader.loadClass(stack.replace('/', '.')).getConstructor().newInstance();
        type.getField("vector").set(instance, vanillaVector);
        type.getField("vanillaStack").set(instance, vanillaStack);
        for (MethodNode target : targets) {
            Method method = declared(type, target.name);
            Object[] parameters = parameters(method);
            Object receiver = interaction(owner) ? parameters[1] : instance;
            if (interaction(owner)) receiver.getClass().getField("vector").set(receiver, vanillaVector);
            if (keyboard(owner)) { keyboardExecution(instance, method); continue; }
            if (inventory(owner)) { inventoryExecution(instance, method, parameters, vanillaStack); continue; }
            if (player(owner) && target.name.equals(mapped(owner, "isCamera", "method_3134"))) {
                playerControlExecution(instance, method); continue;
            }
            if (worldRenderer(owner)) {
                renderFocusExecution(instance, method, parameters); continue;
            }
            if (entity(owner) && target.name.equals(mapped(owner, "changeLookDirection", "method_5872"))) {
                lookExecution(instance, method, parameters); continue;
            }
            reset(instance, receiver);
            Object dormant = invoke(method, instance, parameters);
            Map<String, Integer> expected = expectedHooks(owner, target);
            int count = expected.values().stream().mapToInt(Integer::intValue).sum();
            check(intField(receiver, "reads") == count && intField(instance, "bodyCalls") == 1,
                "disconnected redirected getters each perform one vanilla read");
            checkObserved(instance, expected, false, vanillaVector);
            if (target.desc.endsWith("L" + vector + ";")) {
                check(dormant == vanillaVector, "dormant gliding vector identity preserved");
            }
            Recorder handler = new Recorder();
            handler.expected = receiver;
            handler.replacementVector = loader.loadClass(vector.replace('/', '.')).getConstructor().newInstance();
            reset(instance, receiver);
            try (var registration = RotationHookBridge.install(handler)) {
                Object active = invoke(method, instance, parameters);
                check(handler.events.equals(expand(expected)), "exact getter dispatch sequence and receiver identity");
                check(intField(receiver, "reads") == 0 && intField(instance, "bodyCalls") == 1,
                    "active getter redirect does not add vanilla reads");
                checkObserved(instance, expected, true, handler.replacementVector);
                if (target.desc.endsWith("L" + vector + ";")) check(active == handler.replacementVector, "replacement vector identity returned");
                RuntimeException originalFailure = new IllegalStateException("fixture body failure");
                type.getField("failure").set(instance, originalFailure);
                int events = handler.events.size();
                check(expect(IllegalStateException.class, () -> invoke(method, instance, parameters), "original body failure") == originalFailure,
                    "body exception identity preserved");
                check(handler.events.size() == events, "no getter invented before original throwing body");
                type.getField("failure").set(instance, null);
            }
            reset(instance, receiver);
            invoke(method, instance, parameters);
            check(intField(receiver, "reads") == count, "close restores exact vanilla getter reads");
        }
    }

    @SuppressWarnings("try")
    private static void lookExecution(Object instance, Method method, Object[] parameters) throws Exception {
        parameters[0] = 6.0D; parameters[1] = -4.0D;
        invoke(method, instance, parameters);
        check(instance.getClass().getField("observedX").getDouble(instance) == 6.0D
            && instance.getClass().getField("observedY").getDouble(instance) == -4.0D,
            "dormant look adapter preserves exact deltas");
        Recorder handler = new Recorder(); handler.expected = instance;
        try (var registration = RotationHookBridge.install(handler)) {
            reset(instance, instance); invoke(method, instance, parameters);
            check(handler.events.equals(List.of("redirectLook")), "look redirect dispatched once");
            check(instance.getClass().getField("observedX").getDouble(instance) == 0.0D
                && instance.getClass().getField("observedY").getDouble(instance) == 0.0D,
                "consumed Freecam look zeros only the real entity deltas");
        }
        invoke(method, instance, parameters);
        check(instance.getClass().getField("observedX").getDouble(instance) == 6.0D
            && instance.getClass().getField("observedY").getDouble(instance) == -4.0D,
            "closing look hook restores vanilla deltas");
    }

    @SuppressWarnings("try")
    private static void playerControlExecution(Object instance, Method method) throws Exception {
        reset(instance, instance);
        check(!(boolean) invoke(method, instance) && intField(instance, "bodyCalls") == 1,
            "disconnected local-control hook preserves vanilla false");
        Recorder handler = new Recorder(); handler.expected = instance;
        try (var registration = RotationHookBridge.install(handler)) {
            reset(instance, instance);
            handler.keepPlayerControl = false;
            check(!(boolean) invoke(method, instance) && intField(instance, "bodyCalls") == 1,
                "inactive Freecam preserves vanilla local-control result");
            check(handler.events.equals(List.of("keepLocalPlayerControl")),
                "local-control hook receives the exact player once");
            handler.events.clear(); reset(instance, instance);
            handler.keepPlayerControl = true;
            check((boolean) invoke(method, instance) && intField(instance, "bodyCalls") == 1,
                "active Freecam keeps vanilla player input and movement-packet gate live");
            check(handler.events.equals(List.of("keepLocalPlayerControl")),
                "active local-control decision dispatches exactly once");
        }
        reset(instance, instance);
        check(!(boolean) invoke(method, instance) && intField(instance, "bodyCalls") == 1,
            "closing Freecam hook restores vanilla local-control cleanup");
    }

    @SuppressWarnings("try")
    private static void renderFocusExecution(Object instance, Method method, Object[] parameters) throws Exception {
        Object camera = parameters[0];
        Method focusGetter = Arrays.stream(camera.getClass().getDeclaredMethods())
            .filter(candidate -> candidate.getParameterCount() == 0 && candidate.getReturnType() != void.class)
            .findFirst().orElseThrow();
        Object focus = focusGetter.getReturnType().getConstructor().newInstance();
        Object body = focusGetter.getReturnType().getConstructor().newInstance();
        camera.getClass().getField("focus").set(camera, focus);
        instance.getClass().getField("comparisonFocus").set(instance, body);
        var observed = instance.getClass().getField("observedMatch");
        camera.getClass().getField("reads").setInt(camera, 0);
        invoke(method, instance, parameters);
        check(!observed.getBoolean(instance) && intField(camera, "reads") == 4,
            "disconnected render-focus hook preserves all four vanilla reads");
        Recorder handler = new Recorder(); handler.expected = focus;
        handler.replacementFocus = body;
        try (var registration = RotationHookBridge.install(handler)) {
            camera.getClass().getField("reads").setInt(camera, 0);
            invoke(method, instance, parameters);
            check(observed.getBoolean(instance) && intField(camera, "reads") == 4,
                "active Freecam replaces only the local-body comparison focus");
            check(handler.events.equals(List.of("renderedCameraFocus")),
                "render-focus hook dispatches once for only the fourth focus read");
        }
        camera.getClass().getField("reads").setInt(camera, 0);
        invoke(method, instance, parameters);
        check(!observed.getBoolean(instance) && intField(camera, "reads") == 4,
            "closing Freecam hook restores vanilla render-focus cleanup");
    }

    @SuppressWarnings("try")
    private static void keyboardExecution(Object instance, Method method) throws Exception {
        Recorder handler = new Recorder(); handler.expected = instance;
        invoke(method, instance);
        check(intField(instance, "bodyCalls") == 1, "disconnected keyboard executes vanilla");
        try (var registration = RotationHookBridge.install(handler)) {
            reset(instance, instance);
            invoke(method, instance);
            check(handler.events.equals(List.of("keyboard:1")), "keyboard callback occurs after final vanilla body");
            instance.getClass().getField("early").setBoolean(instance, true);
            invoke(method, instance);
            check(handler.events.size() == 1, "keyboard TAIL excludes earlier return");
            instance.getClass().getField("early").setBoolean(instance, false);
            RuntimeException failure = new IllegalArgumentException("keyboard body failure");
            instance.getClass().getField("failure").set(instance, failure);
            check(expect(IllegalArgumentException.class, () -> invoke(method, instance), "keyboard body exception") == failure
                && handler.events.size() == 1, "throwing keyboard has no TAIL callback");
            instance.getClass().getField("failure").set(instance, null);
        }
        invoke(method, instance);
        check(handler.events.size() == 1, "closed keyboard hook inert");
    }

    @SuppressWarnings("try")
    private static void inventoryExecution(Object instance, Method method, Object[] parameters, Object original) throws Exception {
        boolean set = parameters.length == 1;
        var vanilla = instance.getClass().getField("vanillaStack");
        vanilla.set(instance, original);
        reset(instance, instance);
        check(invoke(method, instance, parameters) == original && intField(instance, "bodyCalls") == 1,
            "dormant inventory preserves original return");
        if (set) check(vanilla.get(instance) == parameters[0], "dormant setter preserves vanilla mutation");
        Recorder handler = new Recorder(); handler.expected = instance;
        Object chosen = original.getClass().getConstructor().newInstance();
        try (var registration = RotationHookBridge.install(handler)) {
            vanilla.set(instance, original); reset(instance, instance);
            handler.selected = null;
            check(invoke(method, instance, parameters) == original && intField(instance, "bodyCalls") == 1,
                "null hook response falls through to original inventory body");
            check(handler.inventory == instance && handler.set == set
                && handler.replacement == (set ? parameters[0] : null), "inventory HEAD passes exact original arguments");
            vanilla.set(instance, original); reset(instance, instance);
            handler.selected = new Object[] { chosen };
            check(invoke(method, instance, parameters) == chosen && intField(instance, "bodyCalls") == 0,
                "one-element hook response cancels with exact stack identity");
            check(vanilla.get(instance) == original, "cancelled setter does not run vanilla mutation");
            handler.selected = new Object[] { null };
            check(invoke(method, instance, parameters) == null && intField(instance, "bodyCalls") == 0,
                "explicit null cancellation differs from passthrough");
            handler.selected = null;
            RuntimeException failure = new IllegalArgumentException("inventory body failure");
            instance.getClass().getField("failure").set(instance, failure);
            check(expect(IllegalArgumentException.class, () -> invoke(method, instance, parameters), "inventory body exception") == failure,
                "inventory body exception identity preserved");
            instance.getClass().getField("failure").set(instance, null);
        }
        vanilla.set(instance, original); reset(instance, instance);
        check(invoke(method, instance, parameters) == original && intField(instance, "bodyCalls") == 1,
            "closing inventory hook restores original body");
    }

    @SuppressWarnings("try")
    private static void bridgeContract() throws Exception {
        NamedReceiver receiver = new NamedReceiver();
        check(RotationHookBridge.outgoingYaw(receiver) == 11 && RotationHookBridge.outgoingPitch(receiver) == 12,
            "named dormant yaw and pitch getters");
        check(RotationHookBridge.glidingVector(receiver) == receiver.vector, "named dormant vector identity");
        check(!RotationHookBridge.keepLocalPlayerControl(receiver)
            && RotationHookBridge.renderedCameraFocus(receiver) == receiver,
            "dormant Freecam adapters preserve vanilla control and focus");
        IntermediaryReceiver intermediary = new IntermediaryReceiver();
        check(RotationHookBridge.movementYaw(intermediary) == 21 && RotationHookBridge.glidingPitch(intermediary) == 22
            && RotationHookBridge.glidingVector(intermediary) == intermediary.vector, "intermediary dormant getter adapter");
        Recorder handler = new Recorder(); handler.expected = receiver; handler.replacementVector = receiver.vector;
        try (var registration = RotationHookBridge.install(handler)) {
            expect(IllegalStateException.class, () -> RotationHookBridge.install(new Recorder()), "overlapping registration rejected");
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    if (RotationHookBridge.itemYaw(receiver) != 11 || RotationHookBridge.itemPitch(receiver) != 12
                        || RotationHookBridge.glidingVector(receiver) != receiver.vector
                        || RotationHookBridge.selectedStack(receiver, null, false) != null
                        || RotationHookBridge.keepLocalPlayerControl(receiver)
                        || RotationHookBridge.renderedCameraFocus(receiver) != receiver) throw new AssertionError("worker modified vanilla");
                    RotationHookBridge.keyboardTick(receiver);
                    try { registration.close(); throw new AssertionError("worker closed registration"); }
                    catch (IllegalStateException expected) { /* expected confinement */ }
                } catch (Throwable failure) { workerFailure.set(failure); }
            }, "rotation-fixture-worker");
            worker.start(); worker.join(5000);
            check(!worker.isAlive() && workerFailure.get() == null && handler.events.isEmpty(),
                "other threads use vanilla and cannot disconnect runtime: " + workerFailure.get());
            handler.failure = new IllegalStateException("handler failed");
            check(expect(IllegalStateException.class, () -> RotationHookBridge.outgoingYaw(receiver), "handler failure") == handler.failure,
                "handler exception identity propagates without an invented vanilla read");
            handler.failure = null;
            check(RotationHookBridge.outgoingYaw(receiver) == 101, "handler exception unwinds dispatch state cleanly");
            handler.selected = new Object[0];
            expect(IllegalStateException.class, () -> RotationHookBridge.selectedStack(receiver, null, false), "invalid selected-stack response rejected");
        }
        try (var registration = RotationHookBridge.install(new Recorder())) {
            check(RotationHookBridge.outgoingYaw(receiver) == 101, "fresh registration works after close");
        }
        RuntimeException failure = new IllegalArgumentException("vanilla getter failed");
        receiver.failure = failure;
        check(expect(IllegalArgumentException.class, () -> RotationHookBridge.outgoingYaw(receiver), "vanilla getter exception") == failure,
            "vanilla getter exception is not wrapped");
        receiver.failure = null;
    }

    private static byte[] fixture(String owner, List<MethodNode> targets, String vector) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        fields(writer); constructor(writer); getters(writer, owner, named(owner), vector);
        for (MethodNode target : targets) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, target.name, target.desc, null, null);
            method.visitCode();
            if (keyboard(owner)) {
                Label normal = new Label();
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "early", "Z");
                method.visitJumpInsn(Opcodes.IFEQ, normal); method.visitInsn(Opcodes.RETURN); method.visitLabel(normal);
            }
            failIfRequested(method, owner, "failure"); increment(method, owner, "bodyCalls");
            if (worldRenderer(owner)) {
                String camera = mapped(owner, "net/minecraft/client/render/Camera", "net/minecraft/class_4184");
                String entity = mapped(owner, "net/minecraft/entity/Entity", "net/minecraft/class_1297");
                String focus = mapped(owner, "getFocusedEntity", "method_19331");
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitFieldInsn(Opcodes.GETFIELD, owner, "comparisonFocus", "Ljava/lang/Object;");
                method.visitTypeInsn(Opcodes.CHECKCAST, entity); method.visitVarInsn(Opcodes.ASTORE, 5);
                for (int i = 0; i < 3; i++) {
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, camera, focus, "()L" + entity + ";", false);
                    method.visitInsn(Opcodes.POP);
                }
                Label matches = new Label(), finish = new Label();
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, camera, focus, "()L" + entity + ";", false);
                method.visitVarInsn(Opcodes.ALOAD, 5); method.visitJumpInsn(Opcodes.IF_ACMPEQ, matches);
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.ICONST_0);
                method.visitFieldInsn(Opcodes.PUTFIELD, owner, "observedMatch", "Z");
                method.visitJumpInsn(Opcodes.GOTO, finish); method.visitLabel(matches);
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.ICONST_1);
                method.visitFieldInsn(Opcodes.PUTFIELD, owner, "observedMatch", "Z"); method.visitLabel(finish);
                method.visitInsn(Opcodes.RETURN);
            } else if (player(owner) && target.name.equals(mapped(owner, "isCamera", "method_3134"))) {
                method.visitInsn(Opcodes.ICONST_0);
                method.visitInsn(Opcodes.IRETURN);
            } else if (entity(owner) && target.name.equals(mapped(owner, "changeLookDirection", "method_5872"))) {
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.DLOAD, 1);
                method.visitFieldInsn(Opcodes.PUTFIELD, owner, "observedX", "D");
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.DLOAD, 3);
                method.visitFieldInsn(Opcodes.PUTFIELD, owner, "observedY", "D");
                method.visitInsn(Opcodes.RETURN);
            } else if (inventory(owner)) {
                Type result = Type.getReturnType(target.desc);
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "vanillaStack", "Ljava/lang/Object;");
                method.visitTypeInsn(Opcodes.CHECKCAST, result.getInternalName());
                if (Type.getArgumentTypes(target.desc).length != 0) {
                    method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ALOAD, 1);
                    method.visitFieldInsn(Opcodes.PUTFIELD, owner, "vanillaStack", "Ljava/lang/Object;");
                }
                method.visitInsn(Opcodes.ARETURN);
            } else {
                for (var instruction : target.instructions) {
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    String hook = getterHook(owner, target, call);
                    if (hook == null) continue;
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitVarInsn(Opcodes.ALOAD, interaction(owner) ? 2 : 0);
                    method.visitMethodInsn(call.getOpcode(), call.owner, call.name, call.desc, call.itf);
                    method.visitFieldInsn(Opcodes.PUTFIELD, owner, observed(hook), hook.equals("glidingVector") ? "Ljava/lang/Object;" : "F");
                }
                Type result = Type.getReturnType(target.desc);
                if (result.getSort() == Type.VOID) method.visitInsn(Opcodes.RETURN);
                else if (result.getInternalName().equals(vector)) {
                    method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "observedVector", "Ljava/lang/Object;");
                    method.visitTypeInsn(Opcodes.CHECKCAST, vector); method.visitInsn(Opcodes.ARETURN);
                } else {
                    method.visitTypeInsn(Opcodes.NEW, result.getInternalName()); method.visitInsn(Opcodes.DUP);
                    method.visitMethodInsn(Opcodes.INVOKESPECIAL, result.getInternalName(), "<init>", "()V", false);
                    method.visitInsn(Opcodes.ARETURN);
                }
            }
            method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] receiver(String owner, boolean named, String vector) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        fields(writer); constructor(writer); getters(writer, owner, named, vector); writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] focusCamera(String owner, String entity) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "reads", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "focus", "Ljava/lang/Object;", null, null).visitEnd();
        constructor(writer);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
            named(owner) ? "getFocusedEntity" : "method_19331", "()L" + entity + ";", null, null);
        method.visitCode(); increment(method, owner, "reads");
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "focus", "Ljava/lang/Object;");
        method.visitTypeInsn(Opcodes.CHECKCAST, entity); method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static void fields(ClassWriter writer) {
        for (String name : List.of("bodyCalls", "reads")) writer.visitField(Opcodes.ACC_PUBLIC, name, "I", null, null).visitEnd();
        for (String name : List.of("observedYaw", "observedPitch")) writer.visitField(Opcodes.ACC_PUBLIC, name, "F", null, null).visitEnd();
        for (String name : List.of("observedX", "observedY")) writer.visitField(Opcodes.ACC_PUBLIC, name, "D", null, null).visitEnd();
        for (String name : List.of("vector", "observedVector", "vanillaStack", "comparisonFocus")) writer.visitField(Opcodes.ACC_PUBLIC, name, "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "failure", "Ljava/lang/RuntimeException;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "early", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "observedMatch", "Z", null, null).visitEnd();
    }
    private static void getters(ClassWriter writer, String owner, boolean named, String vector) {
        for (String kind : List.of("Yaw", "Pitch", "RotationVector")) {
            String name = named ? "get" + kind : kind.equals("Yaw") ? "method_36454" : kind.equals("Pitch") ? "method_36455" : "method_5720";
            String desc = kind.equals("RotationVector") ? "()L" + vector + ";" : "()F";
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null); method.visitCode();
            increment(method, owner, "reads");
            if (kind.equals("RotationVector")) {
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "vector", "Ljava/lang/Object;");
                method.visitTypeInsn(Opcodes.CHECKCAST, vector); method.visitInsn(Opcodes.ARETURN);
            } else { method.visitLdcInsn(kind.equals("Yaw") ? 11F : 12F); method.visitInsn(Opcodes.FRETURN); }
            method.visitMaxs(0, 0); method.visitEnd();
        }
    }
    private static void failIfRequested(MethodVisitor method, String owner, String field) {
        Label healthy = new Label(); method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, field, "Ljava/lang/RuntimeException;"); method.visitJumpInsn(Opcodes.IFNULL, healthy);
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, field, "Ljava/lang/RuntimeException;");
        method.visitInsn(Opcodes.ATHROW); method.visitLabel(healthy);
    }
    private static void increment(MethodVisitor method, String owner, String field) {
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.DUP); method.visitFieldInsn(Opcodes.GETFIELD, owner, field, "I");
        method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD); method.visitFieldInsn(Opcodes.PUTFIELD, owner, field, "I");
    }
    private static void constructor(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(1, 1); method.visitEnd();
    }
    private static byte[] simple(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        constructor(writer); writer.visitEnd(); return writer.toByteArray();
    }

    private static Map<String, Integer> expectedHooks(String owner, MethodNode method) {
        String name = method.name;
        if (player(owner)) {
            if (name.equals(mapped(owner, "tick", "method_5773"))) return pair("outgoingYaw", "outgoingPitch", 1);
            if (name.equals(mapped(owner, "sendMovementPackets", "method_3136"))) return pair("outgoingYaw", "outgoingPitch", 4);
            if (name.equals(mapped(owner, "isCamera", "method_3134"))) return Map.of("keepLocalPlayerControl", 1);
        } else if (interaction(owner) && name.equals("method_41929")) return pair("itemYaw", "itemPitch", 1);
        else if (entity(owner) && name.equals(mapped(owner, "updateVelocity", "method_5724"))) return Map.of("movementYaw", 1);
        else if (entity(owner) && name.equals(mapped(owner, "changeLookDirection", "method_5872"))) return Map.of("lookDeltas", 1);
        else if (keyboard(owner) && name.equals(mapped(owner, "tick", "method_3129"))) return Map.of("keyboardTick", 1);
        else if (living(owner)) {
            if (name.equals(mapped(owner, "calcGlidingVelocity", "method_61430"))) return pair("glidingVector", "glidingPitch", 1);
            if (name.equals(mapped(owner, "jump", "method_6043"))) return Map.of("sprintJumpYaw", 1);
        } else if (inventory(owner) && (name.equals(mapped(owner, "getSelectedStack", "method_7391"))
            || name.equals(mapped(owner, "setSelectedStack", "method_67531")))) return Map.of("selectedStack", 1);
        else if (worldRenderer(owner) && name.equals(mapped(owner, "fillEntityRenderStates", "method_72917"))) {
            return Map.of("renderedCameraFocus", 1);
        }
        return Map.of();
    }
    private static Map<String, Integer> pair(String first, String second, int count) {
        Map<String, Integer> result = new LinkedHashMap<>(); result.put(first, count); result.put(second, count); return result;
    }
    private static String getterHook(String owner, MethodNode target, MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKEVIRTUAL) return null;
        String expectedOwner = interaction(owner) ? mapped(owner, "net/minecraft/entity/player/PlayerEntity", "net/minecraft/class_1657") : owner;
        if (!call.owner.equals(expectedOwner)) return null;
        boolean yaw = call.name.equals(mapped(owner, "getYaw", "method_36454")) && call.desc.equals("()F");
        boolean pitch = call.name.equals(mapped(owner, "getPitch", "method_36455")) && call.desc.equals("()F");
        boolean vector = call.name.equals(mapped(owner, "getRotationVector", "method_5720"))
            && call.desc.equals("()L" + mapped(owner, "net/minecraft/util/math/Vec3d", "net/minecraft/class_243") + ";");
        if (player(owner)) return yaw ? "outgoingYaw" : pitch ? "outgoingPitch" : null;
        if (interaction(owner)) return yaw ? "itemYaw" : pitch ? "itemPitch" : null;
        if (entity(owner)) return yaw ? "movementYaw" : null;
        if (living(owner)) return vector ? "glidingVector" : pitch ? "glidingPitch" : yaw ? "sprintJumpYaw" : null;
        return null;
    }
    private static List<String> expand(Map<String, Integer> hooks) {
        List<String> result = new ArrayList<>();
        int count = hooks.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (int i = 0; i < count; i++) for (var entry : hooks.entrySet()) if (entry.getValue() > i) result.add(entry.getKey());
        return result;
    }
    private static String observed(String hook) { return hook.equals("glidingVector") ? "observedVector" : hook.endsWith("Pitch") ? "observedPitch" : "observedYaw"; }
    private static float replacement(String hook) {
        return switch (hook) {
            case "outgoingYaw" -> 101F; case "outgoingPitch" -> 102F; case "itemYaw" -> 103F; case "itemPitch" -> 104F;
            case "movementYaw" -> 105F; case "glidingPitch" -> 106F; case "sprintJumpYaw" -> 107F;
            default -> throw new IllegalArgumentException(hook);
        };
    }
    private static void checkObserved(Object instance, Map<String, Integer> hooks, boolean active, Object vector) throws Exception {
        for (String hook : hooks.keySet()) {
            var field = instance.getClass().getField(observed(hook));
            if (hook.equals("glidingVector")) check(field.get(instance) == vector, "exact gliding vector identity");
            else check(field.getFloat(instance) == (active ? replacement(hook) : hook.endsWith("Pitch") ? 12F : 11F), "getter result reaches original consumer: " + hook);
        }
    }
    private static void reset(Object instance, Object receiver) throws Exception {
        instance.getClass().getField("bodyCalls").setInt(instance, 0); receiver.getClass().getField("reads").setInt(receiver, 0);
    }
    private static final class Recorder implements RotationHookBridge.Handler {
        Object expected, replacementVector, replacementFocus, inventory, replacement;
        Object[] selected;
        boolean set;
        boolean keepPlayerControl;
        RuntimeException failure;
        final List<String> events = new ArrayList<>();
        private float scalar(Object receiver, String name) { event(receiver, name); return replacement(name); }
        private void event(Object receiver, String name) {
            if (expected != null && receiver != expected) throw new AssertionError("wrong receiver for " + name);
            events.add(name); if (failure != null) throw failure;
        }
        public float outgoingYaw(Object receiver) { return scalar(receiver, "outgoingYaw"); }
        public float outgoingPitch(Object receiver) { return scalar(receiver, "outgoingPitch"); }
        public float itemYaw(Object receiver) { return scalar(receiver, "itemYaw"); }
        public float itemPitch(Object receiver) { return scalar(receiver, "itemPitch"); }
        public float movementYaw(Object receiver) { return scalar(receiver, "movementYaw"); }
        public float glidingPitch(Object receiver) { return scalar(receiver, "glidingPitch"); }
        public float sprintJumpYaw(Object receiver) { return scalar(receiver, "sprintJumpYaw"); }
        public boolean redirectLook(Object receiver, double cursorDeltaX, double cursorDeltaY) {
            event(receiver, "redirectLook"); return true;
        }
        public boolean keepLocalPlayerControl(Object receiver) {
            event(receiver, "keepLocalPlayerControl"); return keepPlayerControl;
        }
        public Object renderedCameraFocus(Object receiver) {
            event(receiver, "renderedCameraFocus"); return replacementFocus;
        }
        public Object glidingVector(Object receiver) { event(receiver, "glidingVector"); return replacementVector; }
        public void keyboardTick(Object receiver) {
            try { event(receiver, "keyboard:" + intField(receiver, "bodyCalls")); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        public Object[] selectedStack(Object receiver, Object value, boolean setting) {
            event(receiver, "selectedStack"); inventory = receiver; replacement = value; set = setting; return selected;
        }
    }
    public static final class NamedReceiver {
        final Object vector = new Object(); RuntimeException failure;
        public float getYaw() { if (failure != null) throw failure; return 11; }
        public float getPitch() { return 12; }
        public Object getRotationVector() { return vector; }
    }
    public static final class IntermediaryReceiver {
        final Object vector = new Object();
        public float method_36454() { return 21; }
        public float method_36455() { return 22; }
        public Object method_5720() { return vector; }
    }
    private static final class FixtureLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;
        FixtureLoader(Map<String, byte[]> definitions) { super(RotationHooksCheck.class.getClassLoader()); this.definitions = definitions; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name);
            if (bytes == null) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name); if (type == null) type = defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(type); return type;
            }
        }
    }
    private static boolean named(String owner) { return !owner.startsWith("net/minecraft/class_"); }
    private static boolean player(String owner) { return owner.endsWith("/ClientPlayerEntity") || owner.endsWith("/class_746"); }
    private static boolean interaction(String owner) { return owner.endsWith("/ClientPlayerInteractionManager") || owner.endsWith("/class_636"); }
    private static boolean entity(String owner) { return owner.endsWith("/Entity") || owner.endsWith("/class_1297"); }
    private static boolean keyboard(String owner) { return owner.endsWith("/KeyboardInput") || owner.endsWith("/class_743"); }
    private static boolean living(String owner) { return owner.endsWith("/LivingEntity") || owner.endsWith("/class_1309"); }
    private static boolean inventory(String owner) { return owner.endsWith("/PlayerInventory") || owner.endsWith("/class_1661"); }
    private static boolean worldRenderer(String owner) { return owner.endsWith("/WorldRenderer") || owner.endsWith("/class_761"); }
    private static String mapped(String owner, String named, String intermediary) { return named(owner) ? named : intermediary; }
    private static int expectedMethods(String owner) {
        if (player(owner)) return 3;
        return entity(owner) || living(owner) || inventory(owner) ? 2 : 1;
    }
    private static boolean sameCall(MethodInsnNode left, MethodInsnNode right) {
        return left.owner.equals(right.owner) && left.name.equals(right.name) && left.desc.equals(right.desc);
    }
    private static MethodNode find(ClassNode owner, MethodNode target) {
        return owner.methods.stream().filter(method -> method.name.equals(target.name) && method.desc.equals(target.desc)).findFirst().orElseThrow();
    }
    private static Method declared(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods()).filter(method -> method.getName().equals(name)).findFirst().orElseThrow();
    }
    private static Object[] parameters(Method method) throws Exception {
        Object[] values = new Object[method.getParameterCount()]; Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < values.length; i++) {
            if (types[i] == float.class) values[i] = 0.5F;
            else if (types[i] == double.class) values[i] = 0.5D;
            else if (types[i] == int.class) values[i] = 7;
            else values[i] = types[i].getConstructor().newInstance();
        }
        return values;
    }
    private static int intField(Object value, String name) throws ReflectiveOperationException { return value.getClass().getField(name).getInt(value); }
    private static Object invoke(Method method, Object instance, Object... arguments) throws Exception {
        try { return method.invoke(instance, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }
    private static byte[] resource(String path) throws Exception {
        try (InputStream input = RotationHooksCheck.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing " + path); return input.readAllBytes();
        }
    }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node; }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static List<String> schema(ClassNode node) {
        List<String> result = new ArrayList<>();
        result.add(node.version + ":" + node.access + ":" + node.name + ":" + node.superName + ":" + node.signature + ":" + node.interfaces);
        node.fields.forEach(field -> result.add("F:" + field.access + ":" + field.name + ":" + field.desc + ":" + field.signature));
        node.methods.forEach(method -> result.add("M:" + method.access + ":" + method.name + ":" + method.desc + ":" + method.signature + ":" + method.exceptions));
        return result;
    }
    private static String text(MethodNode method) {
        Textifier printer = new Textifier(); method.accept(new TraceMethodVisitor(printer));
        StringWriter text = new StringWriter(); printer.print(new PrintWriter(text)); return text.toString();
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static Throwable expect(Class<? extends Throwable> type, Throwing action, String message) {
        checks++; try { action.run(); }
        catch (Throwable failure) { if (type.isInstance(failure)) return failure; throw new AssertionError(message, failure); }
        throw new AssertionError(message + ": no exception");
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
