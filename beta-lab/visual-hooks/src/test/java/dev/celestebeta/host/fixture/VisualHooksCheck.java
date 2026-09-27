package dev.celestebeta.host.fixture;

import dev.celestebeta.host.VisualHookBridge;
import dev.celestebeta.host.instrument.VisualHooksTransformer;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/** Actual mapped-class schema checks plus executable, explicitly synthetic JVM fixtures. */
public final class VisualHooksCheck {
    private static int checks;
    private static final String BRIDGE = "dev/celestebeta/host/VisualHookBridge";
    private static final List<String> OWNERS = List.of("net/minecraft/client/render/SkyRendering",
        "net/minecraft/class_9975", "net/minecraft/entity/LivingEntity", "net/minecraft/class_1309",
        "net/minecraft/client/render/GameRenderer", "net/minecraft/class_757",
        "net/minecraft/client/gui/hud/InGameHud", "net/minecraft/class_329",
        "net/minecraft/client/gui/screen/ChatScreen", "net/minecraft/class_408",
        "net/minecraft/client/render/entity/LivingEntityRenderer", "net/minecraft/class_922",
        "net/minecraft/client/render/entity/ItemEntityRenderer", "net/minecraft/class_916",
        "net/minecraft/client/render/item/HeldItemRenderer", "net/minecraft/class_759");

    private VisualHooksCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected intermediary jar path");
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile())) {
            for (String owner : OWNERS) {
                byte[] bytes;
                if (owner.startsWith("net/minecraft/class_")) {
                    try (InputStream input = jar.getInputStream(jar.getJarEntry(owner + ".class"))) { bytes = input.readAllBytes(); }
                } else bytes = resource(owner + ".class");
                actual(owner, bytes);
            }
        }
        bridgeContract();
        System.out.println("PASS late visual hooks: " + checks + " checks; real named/intermediary schemas and executable fixtures");
    }

    private static void actual(String owner, byte[] bytes) throws Exception {
        byte[] snapshot = bytes.clone();
        byte[] prepared = VisualHooksTransformer.prepare(owner, bytes);
        ClassNode before = read(bytes), after = read(prepared);
        check(schema(before).equals(schema(after)), "target schema unchanged: " + owner);
        List<MethodNode> changed = new ArrayList<>();
        for (int i = 0; i < before.methods.size(); i++) {
            MethodNode old = before.methods.get(i), updated = after.methods.get(i);
            if (!text(old).equals(text(updated))) {
                changed.add(old);
                new Analyzer<>(new BasicVerifier()).analyze(owner, updated);
                checks++;
                check(hasBridge(updated), "each changed body has bridge call");
            }
        }
        check(changed.size() == (sky(owner) ? 5 : heldRenderer(owner) ? 3 : livingRenderer(owner) ? 2 : 1), "only required method bodies changed");
        check(java.util.Arrays.equals(snapshot, bytes), "input bytes untouched");
        expect(IllegalArgumentException.class, () -> VisualHooksTransformer.prepare(owner, prepared), "duplicate rejected");
        expect(IllegalArgumentException.class, () -> VisualHooksTransformer.prepare("other/Game", bytes), "unsupported class rejected");
        expect(IllegalArgumentException.class, () -> VisualHooksTransformer.prepare(OWNERS.get((OWNERS.indexOf(owner) + 1) % 4), bytes), "name mismatch rejected");
        ClassNode missing = read(bytes);
        missing.methods.removeIf(method -> method.name.equals(changed.getFirst().name));
        expect(IllegalArgumentException.class, () -> VisualHooksTransformer.prepare(owner, write(missing)), "missing method rejected");
        ClassNode startup = read(bytes);
        startup.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "celeste$existing", "()V", null, null));
        expect(IllegalArgumentException.class, () -> VisualHooksTransformer.prepare(owner, write(startup)), "startup hooks rejected");
        if (sky(owner)) {
            ClassNode modifiedArg = read(bytes);
            MethodNode extract = modifiedArg.methods.stream().filter(method -> method.name.equals(extractName(owner))).findFirst().orElseThrow();
            extract.instructions.insert(new VarInsnNode(Opcodes.ASTORE, 1));
            expect(IllegalArgumentException.class, () -> VisualHooksTransformer.prepare(owner, write(modifiedArg)), "overwritten extract arguments rejected");
        }
        execution(owner, changed);
        if (sky(owner) || livingRenderer(owner)) unusedFloatExecution(owner, changed);
        if (sky(owner) || livingRenderer(owner) || owner.endsWith("LivingEntity") || owner.endsWith("class_1309")) {
            consumedLocalStores(owner, changed);
        }
    }

    @SuppressWarnings("try")
    private static void execution(String owner, List<MethodNode> changed) throws Exception {
        Map<String, byte[]> definitions = new HashMap<>();
        for (MethodNode method : changed) for (Type type : Type.getArgumentTypes(method.desc)) {
            if (type.getSort() == Type.OBJECT) definitions.put(type.getClassName(), empty(type.getInternalName()));
        }
        if (gameRenderer(owner)) definitions.put(guiRenderer(owner).replace('/', '.'), guiFixture(guiRenderer(owner), guiMethod(owner)));
        if (heldRenderer(owner)) definitions.put(matrixOwner(owner).replace('/', '.'), matrixFixture(owner));
        definitions.put(owner.replace('/', '.'), VisualHooksTransformer.prepare(owner, fixture(owner, changed)));
        FixtureLoader loader = new FixtureLoader(definitions);
        Class<?> type = loader.loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance();
        if (sky(owner)) {
            Method extract = declared(type, extractName(owner));
            Object[] inputs = parameters(extract);
            invoke(extract, instance, inputs);
            check(calls(instance) == 1, "dormant extraction runs vanilla");
            Recorder handler = new Recorder();
            try (var registration = VisualHookBridge.install(handler)) {
                invoke(extract, instance, inputs);
                check(handler.extracts == 1 && handler.renderer == instance
                    && handler.world == inputs[0] && handler.camera == inputs[2] && handler.state == inputs[3],
                    "extraction receives exact receiver and original arguments after vanilla");
                int previous = calls(instance);
                handler.replace = true;
                for (MethodNode method : changed) {
                    if (method.name.equals(extractName(owner))) continue;
                    Method draw = declared(type, method.name);
                    invoke(draw, instance, parameters(draw));
                }
                check(calls(instance) == previous && handler.draws == 1 && handler.owns == 3, "successful sky replacement cancels four vanilla draws");
                handler.replace = false;
                for (MethodNode method : changed) {
                    if (method.name.equals(extractName(owner))) continue;
                    Method draw = declared(type, method.name);
                    invoke(draw, instance, parameters(draw));
                }
                check(calls(instance) == previous + 4, "unsuccessful replacement preserves all vanilla draws");
                RuntimeException expected = new IllegalStateException("original extraction failure");
                type.getField("failure").set(instance, expected);
                check(expect(IllegalStateException.class, () -> invoke(extract, instance, inputs), "original body failure") == expected,
                    "original exception identity preserved");
                check(handler.extracts == 1, "no extraction callback after throwing vanilla body");
                type.getField("failure").set(instance, null);
            }
            int previous = calls(instance);
            Method top = declared(type, owner.endsWith("SkyRendering") ? "renderTopSky" : "method_62302");
            invoke(top, instance, parameters(top));
            check(calls(instance) == previous + 1, "closing registration restores original drawing");
        } else if (owner.endsWith("LivingEntity") || owner.endsWith("class_1309")) {
            Method swing = declared(type, changed.getFirst().name);
            type.getField("duration").setInt(instance, 6);
            check(invoke(swing, instance).equals(6), "dormant swing unchanged");
            Recorder handler = new Recorder();
            try (var registration = VisualHookBridge.install(handler)) {
                check(invoke(swing, instance).equals(9) && handler.vanilla == 6 && handler.entity == instance,
                    "swing hook receives exact vanilla value and entity");
                type.getField("duration").setInt(instance, -1);
                check(invoke(swing, instance).equals(6) && handler.vanilla == 3, "every swing return path hooked");
            }
            check(invoke(swing, instance).equals(3), "unloaded swing returns original branch value");
        } else if (livingRenderer(owner)) {
            String extractionName = owner.endsWith("LivingEntityRenderer") ? "updateRenderState" : "method_62355";
            MethodNode extraction = changed.stream().filter(method -> method.name.equals(extractionName)).findFirst().orElseThrow();
            presentationExecution(owner, extraction, instance);
            playerModelExecution(owner, instance);
        } else if (heldRenderer(owner)) {
            handExecution(owner, changed.stream().filter(method -> Type.getArgumentTypes(method.desc).length == 10).findFirst().orElseThrow(), instance);
            handPassExecution(changed.stream().filter(method -> method.desc.startsWith("(F")).findFirst().orElseThrow(), instance);
            handContextExecution(changed.stream().filter(method -> Type.getArgumentTypes(method.desc).length == 6).findFirst().orElseThrow(), instance);
        } else if (itemRenderer(owner)) {
            itemHighlightExecution(changed.getFirst(), instance);
        } else {
            presentationExecution(owner, changed.getFirst(), instance);
        }
    }

    @SuppressWarnings("try")
    private static void itemHighlightExecution(MethodNode target, Object instance) throws Exception {
        Method method = declared(instance.getClass(), target.name);
        Object[] arguments = parameters(method);
        invoke(method, instance, arguments);
        check(calls(instance) == 1, "dormant item-state extraction remains vanilla");
        Recorder handler = new Recorder();
        handler.bodySource = instance;
        try (var registration = VisualHookBridge.install(handler)) {
            invoke(method, instance, arguments);
            check(calls(instance) == 2 && handler.itemHighlights == 1
                && handler.entity == arguments[0] && handler.itemState == arguments[1]
                && handler.callbackBodyCalls == 2,
                "item highlight receives exact entity/state after vanilla extraction");
            RuntimeException failure = new IllegalStateException("fixture item extraction failure");
            instance.getClass().getField("failure").set(instance, failure);
            check(expect(IllegalStateException.class, () -> invoke(method, instance, arguments),
                "item extraction throws") == failure && handler.itemHighlights == 1,
                "throwing item extraction preserves error without publishing partial state");
            instance.getClass().getField("failure").set(instance, null);
        }
        int callbacks = handler.itemHighlights;
        invoke(method, instance, arguments);
        check(calls(instance) == 3 && handler.itemHighlights == callbacks,
            "closed item highlight hook performs only vanilla extraction");
    }

    @SuppressWarnings("try")
    private static void handPassExecution(MethodNode target, Object instance) throws Exception {
        Method method = java.util.Arrays.stream(instance.getClass().getDeclaredMethods())
            .filter(value -> value.getName().equals(target.name) && Type.getMethodDescriptor(value).equals(target.desc)).findFirst().orElseThrow();
        Object[] arguments = parameters(method);
        int before = calls(instance);
        invoke(method, instance, arguments);
        check(calls(instance) == before + 1, "dormant capture leaves complete original hand pass");
        Recorder handler = new Recorder(); handler.bodySource = instance;
        try (var registration = VisualHookBridge.install(handler)) {
            before = calls(instance);
            invoke(method, instance, arguments);
            check(handler.handBegins == 1 && handler.handEnds == 1 && handler.handSuccess
                && handler.handBeginCalls == before && handler.handEndCalls == before + 1,
                "capture begins before original pass and finishes after its final drawing instruction");
            RuntimeException original = new IllegalArgumentException("fixture original held-pass failure");
            instance.getClass().getField("failure").set(instance, original);
            check(expect(IllegalArgumentException.class, () -> invoke(method, instance, arguments), "hand pass original exception") == original,
                "capture cleanup preserves exact original Throwable");
            check(handler.handBegins == 2 && handler.handEnds == 2 && !handler.handSuccess,
                "throwing vanilla pass always finishes lease without compositing partial output");
            handler.failFinish = true;
            check(expect(IllegalArgumentException.class, () -> invoke(method, instance, arguments), "cleanup exception isolation") == original,
                "a failing cleanup cannot replace the original rendering exception");
            instance.getClass().getField("failure").set(instance, null);
            invoke(method, instance, arguments);
            check(handler.handEnds == 4, "cleanup failure cannot prevent subsequent vanilla return");
            handler.failFinish = false;
            VisualHookBridge.HandPass lease = VisualHookBridge.beginHands();
            handler.failure = new IllegalStateException("another visual callback fails mid-pass");
            VisualHookBridge.drawSky(instance);
            check(registration.hasFailed(), "mid-pass unrelated visual failure disconnects registration");
            VisualHookBridge.finishHands(lease, true);
            check(handler.handEnds == 5, "pending capture cleanup survives failed active registration");
        }
        handler.failure = null;
        var registration = VisualHookBridge.install(handler);
        VisualHookBridge.HandPass lease = VisualHookBridge.beginHands();
        registration.close();
        VisualHookBridge.finishHands(lease, false);
        check(handler.handEnds == 6, "pending capture cleanup survives registration close");
    }

    @SuppressWarnings("try")
    private static void handContextExecution(MethodNode target, Object instance) throws Exception {
        Method method = java.util.Arrays.stream(instance.getClass().getDeclaredMethods())
            .filter(value -> value.getName().equals(target.name) && Type.getMethodDescriptor(value).equals(target.desc)).findFirst().orElseThrow();
        Object[] inputs = parameters(method);
        var seen = instance.getClass().getField("contextSeen");
        invoke(method, instance, inputs);
        check(seen.get(instance) == inputs[2], "disabled display-context hook keeps vanilla pose");
        ItemContextRecorder handler = new ItemContextRecorder();
        handler.contextReplacement = method.getParameterTypes()[2].getConstructor().newInstance();
        try (var registration = VisualHookBridge.install(handler)) {
            invoke(method, instance, inputs);
            check(handler.originalContext == inputs[2] && handler.item == inputs[1] && seen.get(instance) == handler.contextReplacement,
                "first-person context selection receives exact visible stack before original item-render body");
            Object otherItem = method.getParameterTypes()[1].getConstructor().newInstance();
            inputs[1] = otherItem;
            invoke(method, instance, inputs);
            check(handler.item == otherItem && handler.originalContext == inputs[2],
                "context selection follows visible item changes rather than next inventory selection");
            check(VisualHookBridge.handDisplayContext(inputs[2]) == handler.contextReplacement,
                "old one-argument context calls remain compatible with the updated handler");
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { invoke(method, instance, inputs); }
                catch (Throwable failure) { workerFailure.set(failure); }
            }, "hand-context-fixture-worker");
            worker.start(); worker.join(5000);
            check(!worker.isAlive() && workerFailure.get() == null && seen.get(instance) == inputs[2],
                "off-thread item rendering retains original display context");
            handler.contextReplacement = new Object();
            invoke(method, instance, inputs);
            check(registration.hasFailed() && seen.get(instance) == inputs[2],
                "invalid replacement type fails open before Minecraft CHECKCAST");
        }
        invoke(method, instance, inputs);
        check(seen.get(instance) == inputs[2], "closed display-context hook restores vanilla");
        Recorder legacy = new Recorder();
        legacy.contextReplacement = method.getParameterTypes()[2].getConstructor().newInstance();
        try (var registration = VisualHookBridge.install(legacy)) {
            invoke(method, instance, inputs);
            check(legacy.originalContext == inputs[2] && seen.get(instance) == legacy.contextReplacement,
                "new context-plus-item calls delegate to an existing one-argument handler");
        }
    }

    @SuppressWarnings("try")
    private static void handExecution(String owner, MethodNode target, Object instance) throws Exception {
        Method method = declared(instance.getClass(), target.name);
        Object[] arguments = parameters(method);
        Object matrix = arguments[7];
        invoke(method, instance, arguments);
        check(calls(instance) == 2 && matrix.getClass().getField("depth").getInt(matrix) == 0,
            "dormant held-item transform leaves vanilla body and balanced matrices");
        ItemHandRecorder handler = new ItemHandRecorder(); handler.bodySource = instance;
        try (var registration = VisualHookBridge.install(handler)) {
            invoke(method, instance, arguments);
            check(handler.hands == 1 && handler.entity == arguments[0] && handler.hand == arguments[3]
                && handler.item == arguments[5] && handler.matrix == matrix && handler.handDepth == 1 && handler.callbackBodyCalls == 3,
                "hand callback receives exact visible item, player, logical hand and matrix inside per-hand push");
            check(calls(instance) == 4 && matrix.getClass().getField("depth").getInt(matrix) == 0,
                "hand callback retains subsequent vanilla body and restoring pop");
            Object otherHand = method.getParameterTypes()[3].getConstructor().newInstance();
            Object otherItem = method.getParameterTypes()[5].getConstructor().newInstance();
            arguments[3] = otherHand;
            arguments[5] = otherItem;
            invoke(method, instance, arguments);
            check(handler.hands == 2 && handler.hand == otherHand && handler.item == otherItem,
                "successive hands and rendered items keep separate identity without affecting original pose flow");
            Object equipSwapItem = method.getParameterTypes()[5].getConstructor().newInstance();
            arguments[5] = equipSwapItem;
            invoke(method, instance, arguments);
            check(handler.item == equipSwapItem && handler.hand == otherHand,
                "same logical hand changing its visible equip item forwards that exact stack");
            VisualHookBridge.transformHand(arguments[0], arguments[3], matrix);
            check(handler.hands == 4 && handler.entity == arguments[0] && handler.matrix == matrix,
                "existing three-argument prepared calls remain compatible with the updated handler");
            int callbacks = handler.hands;
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { invoke(method, instance, arguments); }
                catch (Throwable failure) { workerFailure.set(failure); }
            }, "held-item-fixture-worker");
            worker.start(); worker.join(5000);
            check(!worker.isAlive() && workerFailure.get() == null && handler.hands == callbacks,
                "held-item callback does not dispatch from another thread");
            handler.failure = new IllegalArgumentException("fixture hand callback failure");
            invoke(method, instance, arguments);
            check(registration.hasFailed() && matrix.getClass().getField("depth").getInt(matrix) == 0,
                "failed hand callback still allows vanilla restoring pop");
        }
        int callbacks = handler.hands;
        invoke(method, instance, arguments);
        check(handler.hands == callbacks && matrix.getClass().getField("depth").getInt(matrix) == 0,
            "closed hand registration restores dormant vanilla rendering");
        Recorder legacy = new Recorder(); legacy.bodySource = instance;
        try (var registration = VisualHookBridge.install(legacy)) {
            invoke(method, instance, arguments);
            check(legacy.hands == 1 && legacy.entity == arguments[0] && legacy.hand == arguments[3]
                && legacy.matrix == matrix && legacy.handDepth == 1,
                "new four-argument prepared calls delegate to an existing three-argument handler");
        }
    }

    @SuppressWarnings("try")
    private static void unusedFloatExecution(String owner, List<MethodNode> changed) throws Exception {
        Map<String, byte[]> definitions = new HashMap<>();
        for (MethodNode method : changed) for (Type type : Type.getArgumentTypes(method.desc)) {
            if (type.getSort() == Type.OBJECT) definitions.put(type.getClassName(), empty(type.getInternalName()));
        }
        byte[] original = fixture(owner, changed, true);
        byte[] prepared = VisualHooksTransformer.prepare(owner, original);
        check(schema(read(original)).equals(schema(read(prepared))), "unused float rewrite retains the complete target schema");
        definitions.put(owner.replace('/', '.'), prepared);
        Class<?> type = new FixtureLoader(definitions).loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance();
        Method method = declared(type, sky(owner) ? extractName(owner) : rotationName(owner));
        Object[] inputs = parameters(method);
        invoke(method, instance, inputs);
        check(calls(instance) == 1 && type.getField("rewrittenFloat").getFloat(instance) == 1.0F,
            "dormant extraction executes a real float-argument rewrite and vanilla body");
        Recorder handler = new Recorder(); handler.bodySource = instance;
        try (var registration = VisualHookBridge.install(handler)) {
            invoke(method, instance, inputs);
            if (sky(owner)) {
                check(handler.extracts == 1 && handler.renderer == instance && handler.world == inputs[0]
                    && handler.camera == inputs[2] && handler.state == inputs[3],
                    "rewritten sky tick delta does not alter any consumed reference argument");
            } else {
                check(handler.rotations == 1 && handler.entity == inputs[0] && handler.state == inputs[1],
                    "rewritten rotation tick delta does not alter entity or render-state identity");
            }
            check(calls(instance) == 2 && handler.callbackBodyCalls == 2,
                "tail callback runs after the original body despite the unused float rewrite");
            RuntimeException failure = new IllegalStateException("rewritten float fixture body failure");
            type.getField("failure").set(instance, failure);
            check(expect(IllegalStateException.class, () -> invoke(method, instance, inputs), "rewritten body throws") == failure,
                "rewritten body preserves original exception identity");
            check(calls(instance) == 2 && handler.extracts + handler.rotations == 1,
                "throwing rewritten extraction does not invent a tail callback");
            type.getField("failure").set(instance, null);
        }
        invoke(method, instance, inputs);
        check(calls(instance) == 3 && handler.extracts + handler.rotations == 1
            && type.getField("rewrittenFloat").getFloat(instance) == 1.0F,
            "closing the handler retains rewritten vanilla behavior without dispatch");
    }

    private static void consumedLocalStores(String owner, List<MethodNode> changed) throws Exception {
        if (livingRenderer(owner)) {
            rotationSnapshotsExecution(owner, changed);
            return;
        }
        byte[] original = fixture(owner, changed);
        String method = sky(owner) ? extractName(owner) : livingRenderer(owner) ? rotationName(owner) : changed.getFirst().name;
        int[] references = sky(owner) ? new int[] { 0, 1, 3, 4 } : new int[] { 0 };
        for (int local : references) {
            byte[] overwritten = tailStore(original, method, Opcodes.ASTORE, local);
            expect(IllegalArgumentException.class, () -> VisualHooksTransformer.prepare(owner, overwritten),
                "consumed tail-hook reference local remains protected: " + local);
        }
        if (sky(owner)) {
            int unusedStart = 2;
            for (int opcode : new int[] { Opcodes.LSTORE, Opcodes.DSTORE }) {
                byte[] overlapping = tailStore(original, method, opcode, unusedStart);
                expect(IllegalArgumentException.class, () -> VisualHooksTransformer.prepare(owner, overlapping),
                    "wide store beginning in unused local cannot overwrite an adjacent consumed reference");
            }
        }
    }

    @SuppressWarnings("try")
    private static void rotationSnapshotsExecution(String owner, List<MethodNode> changed) throws Exception {
        byte[] ordinary = fixture(owner, changed);
        ClassNode ordinaryBefore = read(ordinary), ordinaryAfter = read(VisualHooksTransformer.prepare(owner, ordinary));
        int beforeLocals = ordinaryBefore.methods.stream().filter(m -> m.name.equals(rotationName(owner))).findFirst().orElseThrow().maxLocals;
        int afterLocals = ordinaryAfter.methods.stream().filter(m -> m.name.equals(rotationName(owner))).findFirst().orElseThrow().maxLocals;
        check(beforeLocals == afterLocals, "unchanged rotation references retain the no-extra-locals fast path");
        byte[] original = rotationRewriteFixture(owner, changed), snapshot = original.clone();
        byte[] prepared = VisualHooksTransformer.prepare(owner, original);
        check(java.util.Arrays.equals(original, snapshot) && schema(read(original)).equals(schema(read(prepared))),
            "rotation snapshots preserve input bytes and complete class schema");
        Object instance = preparedInstance(owner, changed, prepared);
        Class<?> type = instance.getClass();
        Method method = declared(type, rotationName(owner)); Object[] inputs = parameters(method);
        invoke(method, instance, inputs);
        check(calls(instance) == 1 && type.getField("rewrittenEntity").get(instance) == null
            && type.getField("rewrittenState").get(instance) == null, "dormant null-reference rewrite executes vanilla branch");
        Recorder handler = new Recorder(); handler.bodySource = instance;
        try (var registration = VisualHookBridge.install(handler)) {
            for (int branch = 0; branch < 2; branch++) {
                type.getField("duration").setInt(instance, branch);
                int previous = calls(instance), callbacks = handler.rotations;
                invoke(method, instance, inputs);
                check(calls(instance) == previous + 1 && handler.rotations == callbacks + (branch == 0 ? 1 : 0)
                    && (branch != 0 || handler.callbackBodyCalls == previous + 1 && handler.entity == inputs[0] && handler.state == inputs[1]),
                    "only final TAIL dispatches original entity/state; earlier normal return skips it");
                Object rewrittenEntity = type.getField("rewrittenEntity").get(instance);
                Object rewrittenState = type.getField("rewrittenState").get(instance);
                check(branch == 0 ? rewrittenEntity == null && rewrittenState == null
                    : rewrittenEntity != null && rewrittenEntity != inputs[0] && rewrittenState != null && rewrittenState != inputs[1],
                    "fixture really overwrites both argument locals with null or different objects");
                RuntimeException failure = new IllegalStateException("rotation rewrite catch/rethrow fixture");
                type.getField("failure").set(instance, failure);
                int caught = type.getField("caughtCalls").getInt(instance);
                previous = calls(instance); callbacks = handler.rotations;
                check(expect(IllegalStateException.class, () -> invoke(method, instance, inputs), "rewritten handler rethrows") == failure,
                    "existing handler preserves the exact original exception");
                check(type.getField("caughtCalls").getInt(instance) == caught + 1 && calls(instance) == previous
                    && handler.rotations == callbacks, "throwing branch executes original handler without tail callback");
                type.getField("failure").set(instance, null);
            }
        }
        int previous = calls(instance), callbacks = handler.rotations;
        invoke(method, instance, inputs);
        check(calls(instance) == previous + 1 && handler.rotations == callbacks,
            "closed rotation snapshots retain vanilla rewrite without dispatch");
        for (int opcode : new int[] { Opcodes.LSTORE, Opcodes.DSTORE }) {
            byte[] wide = tailStore(ordinary, rotationName(owner), opcode, 0);
            Object wideInstance = preparedInstance(owner, changed, VisualHooksTransformer.prepare(owner, wide));
            Method wideMethod = declared(wideInstance.getClass(), rotationName(owner)); Object[] wideInputs = parameters(wideMethod);
            Recorder wideHandler = new Recorder(); wideHandler.bodySource = wideInstance;
            try (var registration = VisualHookBridge.install(wideHandler)) {
                invoke(wideMethod, wideInstance, wideInputs);
                check(calls(wideInstance) == 1 && wideHandler.rotations == 1 && wideHandler.callbackBodyCalls == 1
                    && wideHandler.entity == wideInputs[0] && wideHandler.state == wideInputs[1],
                    "wide store overlapping rotation entity is accepted with original argument snapshots");
            }
        }
    }

    private static Object preparedInstance(String owner, List<MethodNode> changed, byte[] prepared) throws Exception {
        Map<String, byte[]> definitions = new HashMap<>();
        for (MethodNode method : changed) for (Type type : Type.getArgumentTypes(method.desc)) {
            if (type.getSort() == Type.OBJECT) definitions.put(type.getClassName(), empty(type.getInternalName()));
        }
        definitions.put(owner.replace('/', '.'), prepared);
        return new FixtureLoader(definitions).loadClass(owner.replace('/', '.')).getConstructor().newInstance();
    }

    /** Multiple terminal frames and a handler keep LONG/DOUBLE locals live while references are rewritten. */
    private static byte[] rotationRewriteFixture(String owner, List<MethodNode> changed) {
        ClassNode node = read(fixture(owner, changed));
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "rewrittenEntity", "Ljava/lang/Object;", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "rewrittenState", "Ljava/lang/Object;", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "caughtCalls", "I", null, null));
        MethodNode old = node.methods.stream().filter(m -> m.name.equals(rotationName(owner))).findFirst().orElseThrow();
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, old.name, old.desc, null, null);
        Type[] arguments = Type.getArgumentTypes(old.desc);
        Label nullReferences = new Label(), rewritten = new Label(), start = new Label(), healthy = new Label();
        Label firstReturn = new Label(), end = new Label(), handler = new Label();
        method.visitCode();
        method.visitInsn(Opcodes.LCONST_1); method.visitVarInsn(Opcodes.LSTORE, 4);
        method.visitInsn(Opcodes.DCONST_1); method.visitVarInsn(Opcodes.DSTORE, 6);
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "duration", "I");
        method.visitJumpInsn(Opcodes.IFEQ, nullReferences);
        for (int index = 0; index < 2; index++) {
            String argument = arguments[index].getInternalName();
            method.visitTypeInsn(Opcodes.NEW, argument); method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, argument, "<init>", "()V", false);
            method.visitVarInsn(Opcodes.ASTORE, index + 1);
        }
        method.visitJumpInsn(Opcodes.GOTO, rewritten);
        method.visitLabel(nullReferences);
        method.visitInsn(Opcodes.ACONST_NULL); method.visitVarInsn(Opcodes.ASTORE, 1);
        method.visitInsn(Opcodes.ACONST_NULL); method.visitVarInsn(Opcodes.ASTORE, 2);
        method.visitLabel(rewritten);
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitFieldInsn(Opcodes.PUTFIELD, owner, "rewrittenEntity", "Ljava/lang/Object;");
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitFieldInsn(Opcodes.PUTFIELD, owner, "rewrittenState", "Ljava/lang/Object;");
        method.visitLabel(start);
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "failure", "Ljava/lang/RuntimeException;");
        method.visitJumpInsn(Opcodes.IFNULL, healthy);
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "failure", "Ljava/lang/RuntimeException;");
        method.visitInsn(Opcodes.ATHROW);
        method.visitLabel(healthy);
        method.visitVarInsn(Opcodes.LLOAD, 4); method.visitInsn(Opcodes.POP2);
        method.visitVarInsn(Opcodes.DLOAD, 6); method.visitInsn(Opcodes.POP2);
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.DUP);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, "bodyCalls", "I"); method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IADD); method.visitFieldInsn(Opcodes.PUTFIELD, owner, "bodyCalls", "I");
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "duration", "I");
        method.visitJumpInsn(Opcodes.IFEQ, firstReturn); method.visitInsn(Opcodes.RETURN);
        method.visitLabel(firstReturn); method.visitInsn(Opcodes.RETURN);
        method.visitLabel(end); method.visitLabel(handler); method.visitVarInsn(Opcodes.ASTORE, 8);
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.DUP);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, "caughtCalls", "I"); method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IADD); method.visitFieldInsn(Opcodes.PUTFIELD, owner, "caughtCalls", "I");
        method.visitVarInsn(Opcodes.ALOAD, 8); method.visitInsn(Opcodes.ATHROW);
        method.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
        method.visitMaxs(0, 0); method.visitEnd();
        node.methods.set(node.methods.indexOf(old), method);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer); return writer.toByteArray();
    }

    private static byte[] tailStore(byte[] original, String name, int opcode, int local) throws Exception {
        ClassNode node = read(original);
        MethodNode method = node.methods.stream().filter(value -> value.name.equals(name)).findFirst().orElseThrow();
        for (var instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() < Opcodes.IRETURN || instruction.getOpcode() > Opcodes.RETURN) continue;
            InsnList store = new InsnList();
            store.add(new InsnNode(opcode == Opcodes.LSTORE ? Opcodes.LCONST_0
                : opcode == Opcodes.DSTORE ? Opcodes.DCONST_0 : Opcodes.ACONST_NULL));
            store.add(new VarInsnNode(opcode, local));
            method.instructions.insertBefore(instruction, store);
        }
        method.maxStack += 2;
        method.maxLocals = Math.max(method.maxLocals, local + (opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE ? 2 : 1));
        new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        return write(node);
    }

    @SuppressWarnings("try")
    private static void playerModelExecution(String owner, Object instance) throws Exception {
        Method render = declared(instance.getClass(), owner.endsWith("LivingEntityRenderer") ? "render" : "method_4054");
        Object[] arguments = parameters(render);
        int previous = calls(instance);
        invoke(render, instance, arguments);
        check(calls(instance) == previous + 1, "dormant player model keeps vanilla living render");
        Recorder handler = new Recorder();
        handler.eligibleModelState = arguments[0];
        try (var registration = VisualHookBridge.install(handler)) {
            previous = calls(instance);
            invoke(render, instance, arguments);
            check(calls(instance) == previous + 1 && handler.models == 1 && handler.modelState == arguments[0],
                "unready model passes exact render state and retains vanilla");
            handler.replace = true;
            previous = calls(instance);
            invoke(render, instance, arguments);
            check(calls(instance) == previous && handler.models == 2,
                "ready eligible model suppresses vanilla at render HEAD");
            invoke(render, instance, parameters(render));
            check(calls(instance) == previous + 1 && handler.models == 3,
                "ready model cannot suppress an ineligible render state");
            RuntimeException originalFailure = new IllegalStateException("fixture original render failure");
            instance.getClass().getField("failure").set(instance, originalFailure);
            invoke(render, instance, arguments);
            check(calls(instance) == previous + 1 && handler.models == 4,
                "replacement cancels before any original render instruction");
            handler.replace = false;
            check(expect(IllegalStateException.class, () -> invoke(render, instance, arguments), "original render failure") == originalFailure,
                "fallthrough preserves exact original render exception");
            instance.getClass().getField("failure").set(instance, null);
            handler.replace = true;
            int callbacks = handler.models;
            previous = calls(instance);
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { invoke(render, instance, arguments); }
                catch (Throwable failure) { workerFailure.set(failure); }
            }, "player-model-fixture-worker");
            worker.start(); worker.join(5000);
            check(!worker.isAlive() && workerFailure.get() == null && calls(instance) == previous + 1
                && handler.models == callbacks, "other-thread living render preserves vanilla without dispatch");
            handler.failure = new IllegalArgumentException("fixture player model callback failure");
            previous = calls(instance);
            invoke(render, instance, arguments);
            check(calls(instance) == previous + 1 && registration.hasFailed(),
                "failing replacement callback restores vanilla instead of hiding the player");
            callbacks = handler.models;
            handler.failure = null;
            invoke(render, instance, arguments);
            check(calls(instance) == previous + 2 && handler.models == callbacks,
                "failed player-model handler stays disconnected");
        }
        previous = calls(instance);
        int callbacks = handler.models;
        invoke(render, instance, arguments);
        check(calls(instance) == previous + 1 && handler.models == callbacks,
            "closed model registration renders vanilla and releases dispatch");
    }

    @SuppressWarnings("try")
    private static void presentationExecution(String owner, MethodNode target, Object instance) throws Exception {
        Method method = declared(instance.getClass(), target.name);
        Object[] arguments = parameters(method);
        Recorder handler = new Recorder();
        handler.bodySource = instance;
        try (var registration = VisualHookBridge.install(handler)) {
            handler.replace = true;
            Object result = invoke(method, instance, arguments);
            if (gameRenderer(owner)) {
                check(handler.flushes == 1 && handler.callbackBodyCalls == 1 && calls(instance) == 2,
                    "GUI flush occurs immediately after vanilla GUI draw and before next original instruction");
            } else if (owner.endsWith("LivingEntityRenderer") || owner.endsWith("class_922")) {
                check(handler.rotations == 1 && handler.entity == arguments[0] && handler.state == arguments[1]
                    && handler.callbackBodyCalls == 1, "rotation state applied after vanilla extraction");
                RuntimeException failure = new IllegalStateException("fixture extraction failure");
                instance.getClass().getField("failure").set(instance, failure);
                check(expect(IllegalStateException.class, () -> invoke(method, instance, arguments), "vanilla extraction throws") == failure
                    && handler.rotations == 1, "throwing extraction retains exact error without applying state");
                instance.getClass().getField("failure").set(instance, null);
            } else if (target.desc.endsWith("Z")) {
                check(Boolean.TRUE.equals(result) && calls(instance) == 0 && handler.clicked == arguments[0], "consumed media click returns true before vanilla");
                handler.replace = false;
                check(Boolean.FALSE.equals(invoke(method, instance, arguments)) && calls(instance) == 1, "unconsumed media click keeps vanilla result");
            } else {
                check(handler.hides == 1 && handler.renderer == instance && handler.context == arguments[0]
                    && handler.counter == arguments[1] && calls(instance) == 0, "HUD cancellation passes exact rendering inputs");
                handler.replace = false;
                invoke(method, instance, arguments);
                check(calls(instance) == 1, "HUD fallthrough renders original body");
            }
        }
        int previous = calls(instance);
        int observed = handler.flushes + handler.rotations + handler.hides + handler.clicks;
        invoke(method, instance, arguments);
        check(calls(instance) > previous && observed == handler.flushes + handler.rotations + handler.hides + handler.clicks,
            "disconnected presentation performs only vanilla work");
    }

    @SuppressWarnings("try")
    private static void bridgeContract() throws Exception {
        Object renderer = new Object();
        check(!VisualHookBridge.drawSky(renderer) && !VisualHookBridge.ownsSky(renderer)
            && !VisualHookBridge.replacePlayerModel(renderer)
            && VisualHookBridge.swingDuration(renderer, 6) == 6, "dormant defaults");
        Recorder handler = new Recorder();
        handler.replace = true;
        try (var registration = VisualHookBridge.install(handler)) {
            expect(IllegalStateException.class, () -> VisualHookBridge.install(new Recorder()), "overlapping runtime rejected");
            AtomicInteger result = new AtomicInteger();
            Thread worker = new Thread(() -> {
                if (!VisualHookBridge.drawSky(renderer) && VisualHookBridge.swingDuration(renderer, 6) == 6) result.incrementAndGet();
                try { registration.close(); } catch (IllegalStateException expected) { result.incrementAndGet(); }
            }, "visual-fixture-worker");
            worker.start(); worker.join(5000);
            check(!worker.isAlive() && result.get() == 2 && handler.draws == 0, "other threads keep vanilla and cannot disconnect runtime");
            handler.failure = new IllegalArgumentException("fixture callback failure");
            check(!VisualHookBridge.drawSky(renderer) && registration.hasFailed(), "callback failure restores vanilla");
            handler.failure = null;
            check(!VisualHookBridge.drawSky(renderer) && handler.draws == 1, "failed handler stays disconnected");
        }
        try (var registration = VisualHookBridge.install(new Recorder() {
            @Override public int swingDuration(Object entity, int vanilla) { return 0; }
        })) {
            check(VisualHookBridge.swingDuration(renderer, 6) == 6 && registration.hasFailed(), "invalid duration rejected");
        }
        try (var registration = VisualHookBridge.install(new Recorder() {
            @Override public int swingDuration(Object entity, int vanilla) { return vanilla; }
        })) {
            check(VisualHookBridge.swingDuration(renderer, 0) == 0
                && VisualHookBridge.swingDuration(renderer, -1) == -1 && !registration.hasFailed(),
                "unchanged vanilla haste durations do not disable visuals");
        }
        try (var registration = VisualHookBridge.install(new Recorder() {
            @Override public boolean replacePlayerModel(Object state) { throw new LinkageError("fixture missing model class"); }
        })) {
            check(!VisualHookBridge.replacePlayerModel(renderer) && registration.hasFailed(),
                "model linkage failure falls back to vanilla presentation");
        }
        final VisualHookBridge.Registration[] active = new VisualHookBridge.Registration[1];
        try (var registration = VisualHookBridge.install(new Recorder() {
            @Override public boolean drawSky(Object value) { active[0].close(); return true; }
        })) {
            active[0] = registration;
            check(!VisualHookBridge.drawSky(renderer) && registration.hasFailed(), "disconnect during dispatch rejected");
        }
        VisualHookBridge.assertVisibleFrom(VisualHooksCheck.class.getClassLoader());
        expect(IllegalStateException.class, () -> VisualHookBridge.assertVisibleFrom(null), "invisible bridge rejected");
        Map<String, byte[]> split = Map.of(VisualHookBridge.class.getName(), resource(BRIDGE + ".class"));
        expect(IllegalStateException.class, () -> VisualHookBridge.assertVisibleFrom(new FixtureLoader(split)), "separate bridge definition rejected");
        try (var registration = VisualHookBridge.install(new Recorder())) {
            check(VisualHookBridge.swingDuration(renderer, 6) == 9, "fresh registration works after close");
        }
    }

    private static byte[] fixture(String owner, List<MethodNode> targets) {
        return fixture(owner, targets, false);
    }

    private static byte[] fixture(String owner, List<MethodNode> targets, boolean rewriteUnusedFloat) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "bodyCalls", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "contextSeen", "Ljava/lang/Object;", null, null).visitEnd();
        if (rewriteUnusedFloat) writer.visitField(Opcodes.ACC_PUBLIC, "rewrittenFloat", "F", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "duration", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "failure", "Ljava/lang/RuntimeException;", null, null).visitEnd();
        constructor(writer);
        for (MethodNode target : targets) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, target.name, target.desc, null, null);
            method.visitCode();
            if (rewriteUnusedFloat && target.name.equals(sky(owner) ? extractName(owner) : rotationName(owner))) {
                int local = sky(owner) ? 2 : 3;
                method.visitInsn(Opcodes.FCONST_1); method.visitVarInsn(Opcodes.FSTORE, local);
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.FLOAD, local);
                method.visitFieldInsn(Opcodes.PUTFIELD, owner, "rewrittenFloat", "F");
            }
            Label healthy = new Label();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, owner, "failure", "Ljava/lang/RuntimeException;");
            method.visitJumpInsn(Opcodes.IFNULL, healthy);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, owner, "failure", "Ljava/lang/RuntimeException;");
            method.visitInsn(Opcodes.ATHROW);
            method.visitLabel(healthy);
            method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.DUP);
            method.visitFieldInsn(Opcodes.GETFIELD, owner, "bodyCalls", "I"); method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.IADD); method.visitFieldInsn(Opcodes.PUTFIELD, owner, "bodyCalls", "I");
            if (gameRenderer(owner)) {
                String gui = guiRenderer(owner);
                method.visitTypeInsn(Opcodes.NEW, gui); method.visitInsn(Opcodes.DUP);
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, gui, "<init>", "()V", false);
                method.visitInsn(Opcodes.ACONST_NULL);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, gui, guiMethod(owner), "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", false);
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.DUP);
                method.visitFieldInsn(Opcodes.GETFIELD, owner, "bodyCalls", "I"); method.visitInsn(Opcodes.ICONST_1);
                method.visitInsn(Opcodes.IADD); method.visitFieldInsn(Opcodes.PUTFIELD, owner, "bodyCalls", "I");
            }
            if (heldRenderer(owner) && Type.getArgumentTypes(target.desc).length == 10) {
                method.visitVarInsn(Opcodes.ALOAD, 8);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, matrixOwner(owner), namedHand(owner) ? "push" : "method_22903", "()V", false);
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.DUP);
                method.visitFieldInsn(Opcodes.GETFIELD, owner, "bodyCalls", "I"); method.visitInsn(Opcodes.ICONST_1);
                method.visitInsn(Opcodes.IADD); method.visitFieldInsn(Opcodes.PUTFIELD, owner, "bodyCalls", "I");
                method.visitVarInsn(Opcodes.ALOAD, 8);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, matrixOwner(owner), namedHand(owner) ? "pop" : "method_22909", "()V", false);
            }
            if (heldRenderer(owner) && Type.getArgumentTypes(target.desc).length == 6) {
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ALOAD, 3);
                method.visitFieldInsn(Opcodes.PUTFIELD, owner, "contextSeen", "Ljava/lang/Object;");
            }
            if (target.desc.endsWith("I")) {
                Label positive = new Label();
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "duration", "I");
                method.visitJumpInsn(Opcodes.IFGE, positive); method.visitInsn(Opcodes.ICONST_3); method.visitInsn(Opcodes.IRETURN);
                method.visitLabel(positive); method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitFieldInsn(Opcodes.GETFIELD, owner, "duration", "I"); method.visitInsn(Opcodes.IRETURN);
            } else if (target.desc.endsWith("Z")) { method.visitInsn(Opcodes.ICONST_0); method.visitInsn(Opcodes.IRETURN); }
            else method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] empty(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        constructor(writer); writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] matrixFixture(String heldOwner) {
        String owner = matrixOwner(heldOwner);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "depth", "I", null, null).visitEnd();
        constructor(writer);
        for (boolean push : List.of(true, false)) {
            String name = push ? (namedHand(heldOwner) ? "push" : "method_22903") : (namedHand(heldOwner) ? "pop" : "method_22909");
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null);
            method.visitCode(); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.DUP);
            method.visitFieldInsn(Opcodes.GETFIELD, owner, "depth", "I");
            method.visitInsn(push ? Opcodes.ICONST_1 : Opcodes.ICONST_M1); method.visitInsn(Opcodes.IADD);
            method.visitFieldInsn(Opcodes.PUTFIELD, owner, "depth", "I"); method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }

    private static boolean heldRenderer(String owner) { return owner.endsWith("HeldItemRenderer") || owner.endsWith("class_759"); }
    private static boolean namedHand(String owner) { return owner.endsWith("HeldItemRenderer"); }
    private static String matrixOwner(String owner) { return namedHand(owner) ? "net/minecraft/client/util/math/MatrixStack" : "net/minecraft/class_4587"; }
    private static byte[] guiFixture(String owner, String renderName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        constructor(writer);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, renderName, "(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", null, null);
        method.visitCode(); method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    private static void constructor(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode(); method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(1, 1); method.visitEnd();
    }
    private static Object[] parameters(Method method) throws Exception {
        Object[] values = new Object[method.getParameterCount()];
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < values.length; i++) {
            if (types[i] == float.class) values[i] = 0.5F;
            else if (types[i] == int.class) values[i] = 7;
            else if (types[i] == boolean.class) values[i] = false;
            else values[i] = types[i].getConstructor().newInstance();
        }
        return values;
    }
    private static Method declared(Class<?> owner, String name) {
        return java.util.Arrays.stream(owner.getDeclaredMethods()).filter(method -> method.getName().equals(name)).findFirst().orElseThrow();
    }
    private static int calls(Object value) throws Exception { return value.getClass().getField("bodyCalls").getInt(value); }
    private static Object invoke(Method method, Object instance, Object... arguments) throws Exception {
        try { return method.invoke(instance, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }
    private static class Recorder implements VisualHookBridge.Handler {
        int extracts, draws, owns, vanilla;
        Object renderer, world, camera, state, entity;
        int flushes, hides, clicks, rotations, itemHighlights, models, hands, handDepth, callbackBodyCalls;
        int handBegins, handEnds, handBeginCalls, handEndCalls;
        boolean handSuccess, failFinish;
        Object hand, matrix;
        Object originalContext, contextReplacement;
        Object clicked, context, counter, bodySource, itemState, modelState, eligibleModelState;
        boolean replace;
        RuntimeException failure;
        public void extractSky(Object r, Object w, Object c, Object s) { extracts++; renderer = r; world = w; camera = c; state = s; if (bodySource != null) recordBody(); }
        public boolean drawSky(Object r) { draws++; if (failure != null) throw failure; return replace; }
        public boolean ownsSky(Object r) { owns++; return replace; }
        public int swingDuration(Object value, int original) { entity = value; vanilla = original; return original + 3; }
        public void flushGui() { flushes++; recordBody(); }
        public boolean hideHud(Object r, Object c, Object t) { hides++; renderer = r; context = c; counter = t; return replace; }
        public boolean mediaClick(Object click) { clicks++; clicked = click; return replace; }
        public void applyRotations(Object e, Object s) { rotations++; entity = e; state = s; recordBody(); }
        public void highlightItem(Object e, Object s) {
            itemHighlights++; entity = e; itemState = s; recordBody();
        }
        public boolean replacePlayerModel(Object s) {
            models++; modelState = s;
            if (failure != null) throw failure;
            return replace && s == eligibleModelState;
        }
        public void transformHand(Object player, Object logicalHand, Object matrices) {
            hands++; entity = player; hand = logicalHand; matrix = matrices;
            if (failure != null) throw failure;
            try { handDepth = matrices.getClass().getField("depth").getInt(matrices); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            recordBody();
        }
        public VisualHookBridge.HandPass beginHands() {
            handBegins++;
            try { handBeginCalls = calls(bodySource); }
            catch (Exception failure) { throw new AssertionError(failure); }
            return success -> {
                handEnds++; handSuccess = success;
                try { handEndCalls = calls(bodySource); }
                catch (Exception failure) { throw new AssertionError(failure); }
                if (failFinish) throw new AssertionError("fixture cleanup failed");
            };
        }
        public Object handDisplayContext(Object original) {
            originalContext = original;
            return contextReplacement == null ? original : contextReplacement;
        }
        private void recordBody() {
            try { callbackBodyCalls = calls(bodySource); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }
    }
    private static final class ItemHandRecorder extends Recorder {
        Object item;
        @Override public void transformHand(Object player, Object logicalHand, Object renderedItem, Object matrices) {
            item = renderedItem;
            super.transformHand(player, logicalHand, matrices);
        }
    }
    private static final class ItemContextRecorder extends Recorder {
        Object item;
        @Override public Object handDisplayContext(Object original, Object renderedItem) {
            item = renderedItem;
            return super.handDisplayContext(original);
        }
    }
    private static final class FixtureLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;
        FixtureLoader(Map<String, byte[]> definitions) { super(VisualHooksCheck.class.getClassLoader()); this.definitions = definitions; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name);
            if (bytes == null) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
    private static boolean sky(String owner) { return owner.endsWith("SkyRendering") || owner.endsWith("class_9975"); }
    private static boolean livingRenderer(String owner) { return owner.endsWith("LivingEntityRenderer") || owner.endsWith("class_922"); }
    private static boolean itemRenderer(String owner) { return owner.endsWith("ItemEntityRenderer") || owner.endsWith("class_916"); }
    private static boolean gameRenderer(String owner) { return owner.endsWith("/GameRenderer") || owner.endsWith("class_757"); }
    private static String guiRenderer(String owner) { return owner.endsWith("/GameRenderer") ? "net/minecraft/client/gui/render/GuiRenderer" : "net/minecraft/class_11228"; }
    private static String guiMethod(String owner) { return owner.endsWith("/GameRenderer") ? "render" : "method_70890"; }
    private static String rotationName(String owner) { return owner.endsWith("LivingEntityRenderer") ? "updateRenderState" : "method_62355"; }
    private static String extractName(String owner) { return owner.endsWith("SkyRendering") ? "updateRenderState" : "method_74926"; }
    private static boolean hasBridge(MethodNode method) {
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) return true;
        return false;
    }
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
    private static byte[] resource(String path) throws Exception {
        try (InputStream input = VisualHooksCheck.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing " + path);
            return input.readAllBytes();
        }
    }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node; }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static void check(boolean condition, String text) { checks++; if (!condition) throw new AssertionError(text); }
    private static Throwable expect(Class<? extends Throwable> type, Throwing action, String text) {
        checks++;
        try { action.run(); }
        catch (Throwable failure) { if (type.isInstance(failure)) return failure; throw new AssertionError(text, failure); }
        throw new AssertionError(text + ": no exception");
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
