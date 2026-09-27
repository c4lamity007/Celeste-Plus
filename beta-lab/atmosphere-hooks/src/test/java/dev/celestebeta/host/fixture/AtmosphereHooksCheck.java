package dev.celestebeta.host.fixture;

import dev.celestebeta.host.AtmosphereHookBridge;
import dev.celestebeta.host.instrument.AtmosphereHooksTransformer;
import dev.c4lam.celeste.compat.AtmosphereHooks;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import org.joml.Vector4f;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/** Real named/intermediary schema checks and explicitly synthetic executable GPU-call fixtures. */
public final class AtmosphereHooksCheck {
    private static final String BRIDGE = "dev/celestebeta/host/AtmosphereHookBridge";
    private static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";
    private static final String DEVICE = "com/mojang/blaze3d/systems/GpuDevice";
    private static final String BUILDER = "com/mojang/blaze3d/buffers/Std140Builder";
    private static final String PUT = "(F)L" + BUILDER + ";";
    private static final String UPLOAD = "(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V";
    private static final List<String> OWNERS = List.of("net/minecraft/client/render/fog/FogRenderer", "net/minecraft/class_758",
        "net/minecraft/client/render/LightmapTextureManager", "net/minecraft/class_765");
    private static final String[] NAMED_DISTANCES = {"environmentalStart", "environmentalEnd", "renderDistanceStart", "renderDistanceEnd", "skyEnd", "cloudEnd"};
    private static final String[] INTERMEDIARY_DISTANCES = {"field_60582", "field_60584", "field_60583", "field_60585", "field_60099", "field_60100"};
    private static int checks;

    private AtmosphereHooksCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected intermediary jar path");
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile())) {
            for (String owner : OWNERS) {
                byte[] bytes;
                if (named(owner)) bytes = resource(owner + ".class");
                else try (InputStream input = jar.getInputStream(jar.getJarEntry(owner + ".class"))) { bytes = input.readAllBytes(); }
                actual(owner, bytes);
            }
        }
        AtmosphereHooks.sharedFog(false, null, null, null, null);
        check(!AtmosphereHooks.vanillaFog(false, null, null, null, null), "actual fog helper disabled path needs no game state");
        check(!AtmosphereHooks.refreshLightmap() && AtmosphereHooks.nightVision(0.25F) == 0.25F && AtmosphereHooks.gamma(0.75F) == 0.75F,
            "actual initial-off fullbright helper preserves vanilla values");
        bridgeContract();
        System.out.println("PASS late atmosphere hooks: " + checks
            + " checks; actual named/intermediary schemas, fog timing/identity and lightmap ordinal fixtures (no GPU/game claim)");
    }

    private static void actual(String owner, byte[] original) throws Exception {
        byte[] snapshot = original.clone();
        byte[] prepared = AtmosphereHooksTransformer.prepare(owner, original);
        ClassNode before = read(original), after = read(prepared);
        check(schema(before).equals(schema(after)), "schema unchanged: " + owner);
        MethodNode target = null;
        int changed = 0;
        for (int i = 0; i < before.methods.size(); i++) {
            MethodNode old = before.methods.get(i), updated = after.methods.get(i);
            if (text(old).equals(text(updated))) continue;
            changed++; target = old;
            check(old.name.equals(targetName(owner)), "only intended public target body changes");
            new Analyzer<>(new BasicVerifier()).analyze(owner, updated); checks++;
            List<String> hooks = new ArrayList<>();
            for (var instruction : updated.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) hooks.add(call.name);
            check(hooks.equals(fog(owner) ? List.of("sharedFog", "vanillaFog") : List.of("refreshLightmap", "nightVision", "gamma")),
                "exact intended hooks and order");
        }
        check(changed == 1 && target != null, "exactly one method transformed; private overloads untouched");
        check(Arrays.equals(snapshot, original), "original bytes preserved");
        expect(IllegalArgumentException.class, () -> AtmosphereHooksTransformer.prepare(owner, prepared), "duplicate transform rejected");
        expect(IllegalArgumentException.class, () -> AtmosphereHooksTransformer.prepare("other/Class", original), "unsupported target rejected");
        expect(IllegalArgumentException.class, () -> AtmosphereHooksTransformer.prepare(OWNERS.get((OWNERS.indexOf(owner) + 1) % OWNERS.size()), original), "class name mismatch rejected");
        ClassNode missing = read(original); missing.methods.removeIf(method -> method.name.equals(targetName(owner)) && method.desc.equals(targetDesc(owner)));
        expect(IllegalArgumentException.class, () -> AtmosphereHooksTransformer.prepare(owner, write(missing)), "missing target rejected");
        ClassNode startup = read(original); startup.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "celeste$existing", "()V", null, null));
        expect(IllegalArgumentException.class, () -> AtmosphereHooksTransformer.prepare(owner, write(startup)), "startup hooks rejected");
        for (String anchor : fog(owner) ? List.of("getDevice", uploadName(owner)) : List.of("putFloat")) {
            ClassNode absent = read(original); MethodNode method = target(absent, owner);
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals(anchor)) { call.name += "$missing"; break; }
            expect(IllegalArgumentException.class, () -> AtmosphereHooksTransformer.prepare(owner, write(absent)), "missing or wrong-count anchor rejected: " + anchor);
        }
        ClassNode noDebug = read(original); noDebug.methods.forEach(method -> method.localVariables = null);
        byte[] withoutDebug = AtmosphereHooksTransformer.prepare(owner, write(noDebug));
        new Analyzer<>(new BasicVerifier()).analyze(owner, target(read(withoutDebug), owner));
        checks++;
        var flag = before.fields.stream().filter(field -> field.name.equals(flag(owner))).toList();
        int expected = fog(owner) ? Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC : Opcodes.ACC_PRIVATE;
        check(flag.size() == 1 && flag.getFirst().desc.equals("Z")
            && (flag.getFirst().access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == expected,
            "actual original mutable flag shape");
        if (fog(owner)) fogExecution(owner); else lightmapExecution(owner);
    }

    @SuppressWarnings("try")
    private static void fogExecution(String owner) throws Exception {
        Map<String, byte[]> definitions = new HashMap<>();
        for (Type argument : Type.getArgumentTypes(targetDesc(owner))) if (argument.getSort() == Type.OBJECT) {
            definitions.put(argument.getClassName(), empty(argument.getInternalName()));
        }
        definitions.put(data(owner).replace('/', '.'), fogData(owner));
        definitions.put(DEVICE.replace('/', '.'), empty(DEVICE));
        definitions.put(RENDER_SYSTEM.replace('/', '.'), renderSystem());
        definitions.put(owner.replace('/', '.'), AtmosphereHooksTransformer.prepare(owner, fogFixture(owner)));
        FixtureLoader loader = new FixtureLoader(definitions);
        Class<?> type = loader.loadClass(owner.replace('/', '.'));
        Class<?> renderSystem = loader.loadClass(RENDER_SYSTEM.replace('/', '.'));
        Object instance = type.getConstructor().newInstance(); Method apply = declared(type, targetName(owner), targetDesc(owner));
        Object[] arguments = parameters(apply); Field enabled = field(type, flag(owner)); enabled.setBoolean(null, true);
        Vector4f dormant = (Vector4f) invoke(apply, instance, arguments);
        check(dormant.x == 1F && Arrays.equals((float[]) field(type, "uploaded").get(instance), vanillaDistances()), "disconnected fog preserves color and all six distances");
        check(field(type, "uploadedColor").get(instance) == dormant, "vanilla returned color matches uploaded object identity");
        Recorder handler = new Recorder(); handler.named = named(owner); handler.instance = instance; handler.renderSystem = renderSystem;
        try (var registration = AtmosphereHookBridge.install(handler)) {
            for (boolean shared : new boolean[] {true, false}) {
                resetFog(instance, renderSystem); handler.shared = shared; handler.events.clear();
                Vector4f result = (Vector4f) invoke(apply, instance, arguments);
                check(handler.events.equals(List.of("shared:1:0", "vanilla:2:1")), "shared callback before device and vanilla callback immediately before upload");
                check(handler.camera == arguments[0] && handler.world == arguments[4] && handler.color == result,
                    "exact camera, world and color identities reach both callbacks");
                check(handler.fogData == field(type, "lastData").get(instance), "shared callback receives exact locally created FogData");
                float[] uploaded = (float[]) field(type, "uploaded").get(instance);
                check(Arrays.equals(uploaded, new float[] {101, 102, 13, 14, 15, 16}) && result.x == 2F,
                    "each routing branch adjusts exactly once without double fog tint or distance adjustment");
                check(field(type, "uploadedColor").get(instance) == result && field(type, "uploadedBuffer").get(instance) == field(type, "lastBuffer").get(instance)
                    && field(type, "uploadedPosition").getInt(instance) == 17 && field(type, "uploadCalls").getInt(instance) == 1,
                    "spill/restore preserves exact upload receiver, buffer, position, color and call count");
            }
            resetFog(instance, renderSystem); handler.events.clear(); enabled.setBoolean(null, false);
            Vector4f disabled = (Vector4f) invoke(apply, instance, arguments);
            check(!handler.enabled && disabled.x == 1F && Arrays.equals((float[]) field(type, "uploaded").get(instance), vanillaDistances()),
                "fog-disabled path keeps vanilla color and all distances");
            enabled.setBoolean(null, true);
            RuntimeException originalFailure = new IllegalArgumentException("fog body failure");
            field(type, "failure").set(instance, originalFailure); handler.events.clear();
            check(expect(IllegalArgumentException.class, () -> invoke(apply, instance, arguments), "fog body failure") == originalFailure
                && handler.events.isEmpty(), "original body exception preserved before hooks");
            field(type, "failure").set(instance, null);
            RuntimeException uploadFailure = new IllegalStateException("upload failed");
            field(type, "uploadFailure").set(instance, uploadFailure);
            check(expect(IllegalStateException.class, () -> invoke(apply, instance, arguments), "upload failure") == uploadFailure,
                "private upload exception identity preserved after argument hooks");
            field(type, "uploadFailure").set(instance, null);
            Method upload = declared(type, uploadName(owner), UPLOAD); upload.setAccessible(true); handler.events.clear();
            ByteBuffer buffer = ByteBuffer.allocate(64); Vector4f color = new Vector4f(3, 4, 5, 6);
            invoke(upload, instance, buffer, 9, color, 1F, 2F, 3F, 4F, 5F, 6F);
            check(handler.events.isEmpty() && Arrays.equals((float[]) field(type, "uploaded").get(instance), new float[] {1, 2, 3, 4, 5, 6})
                && color.x == 3, "private upload/NONE-style callers remain entirely unhooked");
        }
        handler.events.clear(); resetFog(instance, renderSystem);
        Vector4f closed = (Vector4f) invoke(apply, instance, arguments);
        check(closed.x == 1 && handler.events.isEmpty(), "closing atmosphere registration restores vanilla fog");
    }

    @SuppressWarnings("try")
    private static void lightmapExecution(String owner) throws Exception {
        Map<String, byte[]> definitions = new HashMap<>(); definitions.put(BUILDER.replace('/', '.'), builderFixture());
        definitions.put(owner.replace('/', '.'), AtmosphereHooksTransformer.prepare(owner, lightmapFixture(owner)));
        Class<?> type = new FixtureLoader(definitions).loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance(); Method update = declared(type, targetName(owner), "(F)V");
        Field dirty = field(type, flag(owner));
        invoke(update, instance, 0.5F);
        check(field(type, "bodyCalls").getInt(instance) == 0, "disconnected clean lightmap skips vanilla body");
        dirty.setBoolean(instance, true); invoke(update, instance, 0.5F);
        check(Arrays.equals(values(instance), new float[] {10, 11, 12, 13, 14, 15, 16}) && !dirty.getBoolean(instance), "dormant dirty lightmap keeps all scalar writes and resets flag");
        Recorder handler = new Recorder(); handler.instance = instance;
        try (var registration = AtmosphereHookBridge.install(handler)) {
            int previous = field(type, "bodyCalls").getInt(instance); handler.refresh = false;
            invoke(update, instance, 0.5F);
            check(field(type, "bodyCalls").getInt(instance) == previous && handler.events.equals(List.of("refresh")), "settled fullbright preserves clean update budget");
            handler.events.clear(); handler.refresh = true;
            invoke(update, instance, 0.5F);
            check(field(type, "bodyCalls").getInt(instance) == previous + 1 && !dirty.getBoolean(instance), "transition forces existing dirty flag at HEAD before vanilla check");
            check(handler.events.equals(List.of("refresh", "night:13.0", "gamma:16.0"))
                && Arrays.equals(values(instance), new float[] {10, 11, 12, 113, 14, 15, 216}), "only exact putFloat ordinals three and six adjusted");
            RuntimeException failure = new IllegalStateException("lightmap body failure"); field(type, "failure").set(instance, failure);
            check(expect(IllegalStateException.class, () -> invoke(update, instance, 0.5F), "lightmap original body failure") == failure,
                "lightmap exception identity retained"); field(type, "failure").set(instance, null);
        }
        dirty.setBoolean(instance, false); int previous = field(type, "bodyCalls").getInt(instance); invoke(update, instance, 0.5F);
        check(field(type, "bodyCalls").getInt(instance) == previous, "closed lightmap no longer forces refresh");
        dirty.setBoolean(instance, true); invoke(update, instance, 0.5F);
        check(Arrays.equals(values(instance), new float[] {10, 11, 12, 13, 14, 15, 16}), "closed scalar callbacks restore vanilla writes");
    }

    @SuppressWarnings("try")
    private static void bridgeContract() throws Exception {
        check(!AtmosphereHookBridge.refreshLightmap() && AtmosphereHookBridge.nightVision(4) == 4 && AtmosphereHookBridge.gamma(5) == 5, "dormant bridge values");
        expect(IllegalArgumentException.class, () -> AtmosphereHookBridge.vanillaFog(true, null, null, null, new float[5]), "malformed distance array rejected");
        Recorder handler = new Recorder(); handler.refresh = true;
        try (var registration = AtmosphereHookBridge.install(handler)) {
            expect(IllegalStateException.class, () -> AtmosphereHookBridge.install(new Recorder()), "duplicate registration rejected");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    if (AtmosphereHookBridge.refreshLightmap() || AtmosphereHookBridge.nightVision(2) != 2 || AtmosphereHookBridge.gamma(3) != 3) throw new AssertionError("worker scalar changed");
                    AtmosphereHookBridge.sharedFog(true, null, null, null, null); AtmosphereHookBridge.vanillaFog(true, null, null, null, new float[6]);
                    try { registration.close(); throw new AssertionError("worker closed registration"); }
                    catch (IllegalStateException expected) { /* client-thread boundary */ }
                } catch (Throwable problem) { failure.set(problem); }
            }, "atmosphere-fixture-worker");
            worker.start(); worker.join(5000);
            check(!worker.isAlive() && failure.get() == null && handler.events.isEmpty(), "worker remains vanilla and cannot close registration: " + failure.get());
            handler.failure = new IllegalStateException("callback failure");
            check(expect(IllegalStateException.class, () -> AtmosphereHookBridge.nightVision(4), "callback failure") == handler.failure,
                "callback exception identity propagates"); handler.failure = null;
            check(AtmosphereHookBridge.nightVision(4) == 104, "callback failure unwinds dispatch state");
        }
        check(!AtmosphereHookBridge.refreshLightmap(), "closed bridge inert");
    }

    private static byte[] fogFixture(String owner) {
        ClassWriter writer = writer(owner); String fogData = data(owner);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, flag(owner), "Z", null, null).visitEnd();
        for (String name : List.of("stage", "uploadCalls", "uploadedPosition")) writer.visitField(Opcodes.ACC_PUBLIC, name, "I", null, null).visitEnd();
        for (String name : List.of("lastData", "lastBuffer", "uploadedBuffer", "uploadedColor")) writer.visitField(Opcodes.ACC_PUBLIC, name, "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "uploaded", "[F", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "uploadFailure", "Ljava/lang/RuntimeException;", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, targetName(owner), targetDesc(owner), null, null); method.visitCode();
        fail(method, owner, "failure");
        method.visitTypeInsn(Opcodes.NEW, "org/joml/Vector4f"); method.visitInsn(Opcodes.DUP);
        method.visitInsn(Opcodes.FCONST_1); method.visitInsn(Opcodes.FCONST_0); method.visitInsn(Opcodes.FCONST_0); method.visitInsn(Opcodes.FCONST_1);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/joml/Vector4f", "<init>", "(FFFF)V", false); method.visitVarInsn(Opcodes.ASTORE, 7);
        method.visitTypeInsn(Opcodes.NEW, fogData); method.visitInsn(Opcodes.DUP); method.visitMethodInsn(Opcodes.INVOKESPECIAL, fogData, "<init>", "()V", false);
        method.visitVarInsn(Opcodes.ASTORE, 11);
        for (int i = 0; i < 6; i++) { method.visitVarInsn(Opcodes.ALOAD, 11); method.visitLdcInsn(11F + i); method.visitFieldInsn(Opcodes.PUTFIELD, fogData, distances(owner)[i], "F"); }
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ALOAD, 11); method.visitFieldInsn(Opcodes.PUTFIELD, owner, "lastData", "Ljava/lang/Object;");
        setInt(method, owner, "stage", 1);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, RENDER_SYSTEM, "getDevice", "()L" + DEVICE + ";", false); method.visitInsn(Opcodes.POP);
        setInt(method, owner, "stage", 2);
        method.visitIntInsn(Opcodes.BIPUSH, 64); method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/nio/ByteBuffer", "allocate", "(I)Ljava/nio/ByteBuffer;", false);
        method.visitVarInsn(Opcodes.ASTORE, 12); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ALOAD, 12);
        method.visitFieldInsn(Opcodes.PUTFIELD, owner, "lastBuffer", "Ljava/lang/Object;");
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ALOAD, 12); method.visitIntInsn(Opcodes.BIPUSH, 17); method.visitVarInsn(Opcodes.ALOAD, 7);
        for (String distance : distances(owner)) { method.visitVarInsn(Opcodes.ALOAD, 11); method.visitFieldInsn(Opcodes.GETFIELD, fogData, distance, "F"); }
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, uploadName(owner), UPLOAD, false);
        method.visitVarInsn(Opcodes.ALOAD, 7); method.visitInsn(Opcodes.ARETURN); end(method);
        MethodVisitor upload = writer.visitMethod(Opcodes.ACC_PRIVATE, uploadName(owner), UPLOAD, null, null); upload.visitCode();
        fail(upload, owner, "uploadFailure"); increment(upload, owner, "uploadCalls");
        for (int i : new int[] {1, 3}) {
            upload.visitVarInsn(Opcodes.ALOAD, 0); upload.visitVarInsn(Opcodes.ALOAD, i);
            upload.visitFieldInsn(Opcodes.PUTFIELD, owner, i == 1 ? "uploadedBuffer" : "uploadedColor", "Ljava/lang/Object;");
        }
        upload.visitVarInsn(Opcodes.ALOAD, 0); upload.visitVarInsn(Opcodes.ILOAD, 2); upload.visitFieldInsn(Opcodes.PUTFIELD, owner, "uploadedPosition", "I");
        upload.visitVarInsn(Opcodes.ALOAD, 0); upload.visitIntInsn(Opcodes.BIPUSH, 6); upload.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
        for (int i = 0; i < 6; i++) { upload.visitInsn(Opcodes.DUP); upload.visitIntInsn(Opcodes.BIPUSH, i); upload.visitVarInsn(Opcodes.FLOAD, 4 + i); upload.visitInsn(Opcodes.FASTORE); }
        upload.visitFieldInsn(Opcodes.PUTFIELD, owner, "uploaded", "[F"); upload.visitInsn(Opcodes.RETURN); end(upload);
        writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] lightmapFixture(String owner) {
        ClassWriter writer = writer(owner); writer.visitField(Opcodes.ACC_PRIVATE, flag(owner), "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "bodyCalls", "I", null, null).visitEnd(); writer.visitField(Opcodes.ACC_PUBLIC, "builder", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, targetName(owner), "(F)V", null, null); method.visitCode();
        Label dirty = new Label(); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, flag(owner), "Z");
        method.visitJumpInsn(Opcodes.IFNE, dirty); method.visitInsn(Opcodes.RETURN); method.visitLabel(dirty);
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.ICONST_0); method.visitFieldInsn(Opcodes.PUTFIELD, owner, flag(owner), "Z");
        fail(method, owner, "failure"); increment(method, owner, "bodyCalls");
        method.visitTypeInsn(Opcodes.NEW, BUILDER); method.visitInsn(Opcodes.DUP); method.visitMethodInsn(Opcodes.INVOKESPECIAL, BUILDER, "<init>", "()V", false);
        method.visitVarInsn(Opcodes.ASTORE, 2); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitFieldInsn(Opcodes.PUTFIELD, owner, "builder", "Ljava/lang/Object;"); method.visitVarInsn(Opcodes.ALOAD, 2);
        for (int i = 0; i < 7; i++) { method.visitLdcInsn(10F + i); method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "putFloat", PUT, false); }
        method.visitInsn(Opcodes.POP); method.visitInsn(Opcodes.RETURN); end(method); writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] fogData(String owner) {
        ClassWriter writer = writer(data(owner)); for (String name : distances(owner)) writer.visitField(Opcodes.ACC_PUBLIC, name, "F", null, null).visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] renderSystem() {
        ClassWriter writer = writer(RENDER_SYSTEM); writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getDevice", "()L" + DEVICE + ";", null, null); method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, RENDER_SYSTEM, "calls", "I"); method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD);
        method.visitFieldInsn(Opcodes.PUTSTATIC, RENDER_SYSTEM, "calls", "I"); method.visitInsn(Opcodes.ACONST_NULL); method.visitInsn(Opcodes.ARETURN);
        end(method); writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] builderFixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, BUILDER, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "values", "[F", null, null).visitEnd(); writer.visitField(Opcodes.ACC_PUBLIC, "count", "I", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0); init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0); init.visitIntInsn(Opcodes.BIPUSH, 7); init.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
        init.visitFieldInsn(Opcodes.PUTFIELD, BUILDER, "values", "[F"); init.visitInsn(Opcodes.RETURN); end(init);
        MethodVisitor put = writer.visitMethod(Opcodes.ACC_PUBLIC, "putFloat", PUT, null, null); put.visitCode();
        put.visitVarInsn(Opcodes.ALOAD, 0); put.visitFieldInsn(Opcodes.GETFIELD, BUILDER, "values", "[F"); put.visitVarInsn(Opcodes.ALOAD, 0);
        put.visitFieldInsn(Opcodes.GETFIELD, BUILDER, "count", "I"); put.visitVarInsn(Opcodes.FLOAD, 1); put.visitInsn(Opcodes.FASTORE);
        increment(put, BUILDER, "count"); put.visitVarInsn(Opcodes.ALOAD, 0); put.visitInsn(Opcodes.ARETURN); end(put);
        writer.visitEnd(); return writer.toByteArray();
    }
    private static ClassWriter writer(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "failure", "Ljava/lang/RuntimeException;", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); init.visitInsn(Opcodes.RETURN); end(init); return writer;
    }
    private static byte[] empty(String owner) { ClassWriter writer = writer(owner); writer.visitEnd(); return writer.toByteArray(); }
    private static void end(MethodVisitor method) { method.visitMaxs(0, 0); method.visitEnd(); }
    private static void fail(MethodVisitor method, String owner, String field) {
        Label healthy = new Label(); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, field, "Ljava/lang/RuntimeException;");
        method.visitJumpInsn(Opcodes.IFNULL, healthy); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, field, "Ljava/lang/RuntimeException;");
        method.visitInsn(Opcodes.ATHROW); method.visitLabel(healthy);
    }
    private static void increment(MethodVisitor method, String owner, String field) {
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.DUP); method.visitFieldInsn(Opcodes.GETFIELD, owner, field, "I");
        method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD); method.visitFieldInsn(Opcodes.PUTFIELD, owner, field, "I");
    }
    private static void setInt(MethodVisitor method, String owner, String field, int value) {
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitIntInsn(Opcodes.BIPUSH, value); method.visitFieldInsn(Opcodes.PUTFIELD, owner, field, "I");
    }
    private static final class Recorder implements AtmosphereHookBridge.Handler {
        final List<String> events = new ArrayList<>();
        Object instance, camera, world, fogData, color; Class<?> renderSystem;
        boolean named, shared, enabled, refresh; RuntimeException failure;
        public boolean refreshLightmap() { event("refresh"); return refresh; }
        public float nightVision(float vanilla) { event("night:" + vanilla); return vanilla + 100; }
        public float gamma(float vanilla) { event("gamma:" + vanilla); return vanilla + 200; }
        public void sharedFog(boolean active, Object c, Object w, Object data, Object value) {
            try {
                event("shared:" + field(instance.getClass(), "stage").getInt(instance) + ":" + field(renderSystem, "calls").getInt(null));
                enabled = active; camera = c; world = w; fogData = data; color = value;
                if (active && shared) {
                    field(data.getClass(), (named ? NAMED_DISTANCES : INTERMEDIARY_DISTANCES)[0]).setFloat(data, 101);
                    field(data.getClass(), (named ? NAMED_DISTANCES : INTERMEDIARY_DISTANCES)[1]).setFloat(data, 102);
                    ((Vector4f) value).x += 1;
                }
            } catch (ReflectiveOperationException problem) { throw new AssertionError(problem); }
        }
        public void vanillaFog(boolean active, Object c, Object w, Object value, float[] distances) {
            try {
                event("vanilla:" + field(instance.getClass(), "stage").getInt(instance) + ":" + field(renderSystem, "calls").getInt(null));
                if (camera != c || world != w || color != value || enabled != active || distances.length != 6) throw new AssertionError("fog input identity or distance shape changed");
                if (active && !shared) { distances[0] = 101; distances[1] = 102; ((Vector4f) value).x += 1; }
            } catch (ReflectiveOperationException problem) { throw new AssertionError(problem); }
        }
        private void event(String event) { events.add(event); if (failure != null) throw failure; }
    }
    private static final class FixtureLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;
        FixtureLoader(Map<String, byte[]> definitions) { super(AtmosphereHooksCheck.class.getClassLoader()); this.definitions = definitions; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name); if (bytes == null) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name); if (type == null) type = defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(type); return type;
            }
        }
    }
    private static boolean named(String owner) { return !owner.startsWith("net/minecraft/class_"); }
    private static boolean fog(String owner) { return owner.endsWith("/FogRenderer") || owner.endsWith("/class_758"); }
    private static String mapped(String owner, String name, String intermediary) { return named(owner) ? name : intermediary; }
    private static String targetName(String owner) { return fog(owner) ? mapped(owner, "applyFog", "method_3211") : mapped(owner, "update", "method_3313"); }
    private static String targetDesc(String owner) {
        return fog(owner) ? "(L" + mapped(owner, "net/minecraft/client/render/Camera", "net/minecraft/class_4184") + ";IL"
            + mapped(owner, "net/minecraft/client/render/RenderTickCounter", "net/minecraft/class_9779") + ";FL"
            + mapped(owner, "net/minecraft/client/world/ClientWorld", "net/minecraft/class_638") + ";)Lorg/joml/Vector4f;" : "(F)V";
    }
    private static String uploadName(String owner) { return mapped(owner, "applyFog", "method_71110"); }
    private static String flag(String owner) { return fog(owner) ? mapped(owner, "fogEnabled", "field_54018") : mapped(owner, "dirty", "field_4135"); }
    private static String data(String owner) { return mapped(owner, "net/minecraft/client/render/fog/FogData", "net/minecraft/class_7285"); }
    private static String[] distances(String owner) { return named(owner) ? NAMED_DISTANCES : INTERMEDIARY_DISTANCES; }
    private static float[] vanillaDistances() { return new float[] {11, 12, 13, 14, 15, 16}; }
    private static float[] values(Object instance) throws ReflectiveOperationException {
        Object builder = field(instance.getClass(), "builder").get(instance); return (float[]) field(builder.getClass(), "values").get(builder);
    }
    private static void resetFog(Object instance, Class<?> renderSystem) throws ReflectiveOperationException {
        field(instance.getClass(), "stage").setInt(instance, 0); field(instance.getClass(), "uploadCalls").setInt(instance, 0); field(renderSystem, "calls").setInt(null, 0);
    }
    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
    private static Method declared(Class<?> owner, String name, String desc) {
        return Arrays.stream(owner.getDeclaredMethods()).filter(method -> method.getName().equals(name) && Type.getMethodDescriptor(method).equals(desc)).findFirst().orElseThrow();
    }
    private static MethodNode target(ClassNode owner, String name) {
        return owner.methods.stream().filter(method -> method.name.equals(targetName(name)) && method.desc.equals(targetDesc(name))).findFirst().orElseThrow();
    }
    private static Object[] parameters(Method method) throws Exception {
        Object[] arguments = new Object[method.getParameterCount()]; Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == float.class) arguments[i] = 0.5F;
            else if (types[i] == int.class) arguments[i] = 8;
            else arguments[i] = types[i].getConstructor().newInstance();
        }
        return arguments;
    }
    private static Object invoke(Method method, Object instance, Object... arguments) throws Exception {
        try { return method.invoke(instance, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }
    private static byte[] resource(String path) throws Exception {
        try (InputStream input = AtmosphereHooksCheck.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing " + path); return input.readAllBytes();
        }
    }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node; }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static List<String> schema(ClassNode node) {
        List<String> result = new ArrayList<>(); result.add(node.version + ":" + node.access + ":" + node.name + ":" + node.superName + ":" + node.signature + ":" + node.interfaces);
        node.fields.forEach(field -> result.add("F:" + field.access + ":" + field.name + ":" + field.desc + ":" + field.signature));
        node.methods.forEach(method -> result.add("M:" + method.access + ":" + method.name + ":" + method.desc + ":" + method.signature + ":" + method.exceptions)); return result;
    }
    private static String text(MethodNode method) {
        Textifier printer = new Textifier(); method.accept(new TraceMethodVisitor(printer)); StringWriter text = new StringWriter(); printer.print(new PrintWriter(text)); return text.toString();
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static Throwable expect(Class<? extends Throwable> type, Throwing action, String message) {
        checks++; try { action.run(); } catch (Throwable failure) { if (type.isInstance(failure)) return failure; throw new AssertionError(message, failure); }
        throw new AssertionError(message + ": no exception");
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
