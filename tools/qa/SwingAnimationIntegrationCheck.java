package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.beta.visual.BetaVisualHooks;
import dev.c4lam.celeste.gui.HandModelsEditorScreen;
import dev.c4lam.celeste.gui.model.CelestePage;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.module.ModuleContext;
import dev.celestebeta.host.VisualHookBridge;
import dev.celestebeta.host.instrument.VisualHooksTransformer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.SwingAnimationType;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Synthetic client environment; real loader hooks, payload handler, GUI settings and MatrixStack. */
public final class SwingAnimationIntegrationCheck {
    private static final String OWNER = "net/minecraft/client/render/item/HeldItemRenderer";
    private static final List<String> STYLES = List.of("Smooth", "Slice", "Sweep", "Thrust", "Spin");
    private static final MinecraftClient CLIENT = MinecraftClient.getInstance();
    private static final AbstractClientPlayerEntity PLAYER = CLIENT.player;
    private static final ItemStack SWORD = new ItemStack();
    private static final SwingAnimationFeature SWING = new SwingAnimationFeature();
    private static final HandModelsFeature HANDS = new HandModelsFeature();
    private static final GuiSetting.Mode STYLE = new GuiSetting.Mode("Style", "", STYLES, "Slice");
    private static final GuiSetting.Toggle POSITION = new GuiSetting.Toggle("Affect position", "", true);
    private static final GuiSetting.Slider STRENGTH = new GuiSetting.Slider("Strength", "", 40, 0, 100, 1);
    private static final ModuleContext SWING_CONTEXT = new ModuleContext(CLIENT,
        new GuiModule("Swing Animations", "", CelestePage.VISUALS, true, STYLE, STRENGTH, POSITION));
    private static final ModuleContext HAND_CONTEXT = new ModuleContext(CLIENT,
        new GuiModule("Hand Models", "", CelestePage.VISUALS, false,
            new GuiSetting.Slider("Right X", "", .66, .08, .92, .01),
            new GuiSetting.Slider("Right Y", "", .69, .08, .92, .01),
            new GuiSetting.Slider("Left X", "", .33, .08, .92, .01),
            new GuiSetting.Slider("Left Y", "", .71, .08, .92, .01),
            new GuiSetting.Slider("Right Size", "", 1.2, .1, 2, .1),
            new GuiSetting.Slider("Left Size", "", .8, .1, 2, .1)));
    private static final Matrix4f CAMERA = new Matrix4f().rotateZ(.04F).translate(.02F, -.01F, .03F);
    private static Object renderer;
    private static Method draw;
    private static int checks;
    private static VisualHookBridge.Registration registration;

    public static void main(String[] args) throws Exception {
        byte[] original = Files.readAllBytes(Path.of(args[1]).resolve(OWNER + ".class"));
        ClassNode fixture = read(original);
        try (JarFile minecraft = new JarFile(args[0])) {
            ClassNode vanilla = read(minecraft.getInputStream(minecraft.getJarEntry(OWNER + ".class")).readAllBytes());
            // Execute Minecraft's real helper bytecode, not a second imitation of vanilla's math.
            for (String name : List.of("applyEquipOffset", "applySwingOffset", "swingArm")) {
                fixture.methods.removeIf(m -> m.name.equals(name));
                fixture.methods.add(vanilla.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow());
            }
            MethodNode realHand = vanilla.methods.stream().filter(m -> m.name.equals("renderFirstPersonItem")).findFirst().orElseThrow();
            List<String> calls = new java.util.ArrayList<>();
            for (var instruction : realHand.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(OWNER)) calls.add(call.name);
            int lastSwing = calls.lastIndexOf("swingArm");
            check(lastSwing > 0 && calls.get(lastSwing - 1).equals("applyEquipOffset") && calls.get(lastSwing + 1).equals("renderItem"),
                "fixture uses the actual 1.21.11 ordinary hand order: equip, swing, model submission");
        }
        ClassWriter writer = new ClassWriter(0);
        fixture.accept(writer);
        byte[] prepared = VisualHooksTransformer.prepare(OWNER, writer.toByteArray());
        ClassLoader loader = new ClassLoader(SwingAnimationIntegrationCheck.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(OWNER.replace('/', '.'))) return super.loadClass(name, resolve);
                Class<?> result = findLoadedClass(name);
                if (result == null) result = defineClass(name, prepared, 0, prepared.length);
                if (resolve) resolveClass(result);
                return result;
            }
        };
        Class<?> type = loader.loadClass(OWNER.replace('/', '.'));
        renderer = type.getConstructor().newInstance();
        draw = java.util.Arrays.stream(type.getMethods()).filter(m -> m.getName().equals("renderFirstPersonItem")).findFirst().orElseThrow();
        try (BetaVisualHooks hooks = BetaVisualHooks.connect(CLIENT)) {
            var field = BetaVisualHooks.class.getDeclaredField("registration");
            field.setAccessible(true);
            registration = (VisualHookBridge.Registration) field.get(hooks);
            poses();
            behavior();
            lifecycle();
            check(!registration.hasFailed(), "real payload registration did not silently fail");
        }
        System.out.println("PASS swing render integration: " + checks + " checks; actual transformed host -> bridge -> BetaVisualHooks -> settings/features -> model submission; synthetic client, no in-game claim");
    }

    private static void poses() throws Exception {
        POSITION.setValue(true);
        for (boolean models : new boolean[] {false, true}) {
            if (models) HANDS.onEnable(HAND_CONTEXT); else HANDS.onDisable(HAND_CONTEXT);
            for (Arm arm : Arm.values()) {
                PLAYER.mainArm = arm;
                for (String style : List.of("Slice", "Sweep", "Thrust", "Smooth", "Spin")) {
                    STYLE.setValue(style);
                    SWING.onEnable(SWING_CONTEXT);
                    for (float progress : new float[] {0, .08F, .25F, .5F, .85F, 1}) {
                        Matrix4f expected = reference(style, arm, progress, models);
                        for (float equip : new float[] {0, .43F, 1}) {
                            Matrix4f actual = render(progress, equip, Hand.MAIN_HAND, SWORD);
                            near(actual, expected, "reference preset replaces vanilla: " + style + ", arm=" + arm + ", models=" + models + ", progress=" + progress + ", equip=" + equip);
                            MatrixStack normal = referenceStack(style, arm, progress, models);
                            near((Matrix3f) renderer.getClass().getField("normal").get(renderer), normal.peek().getNormalMatrix(), "normal matrix follows replaced position pose");
                            for (String axis : List.of("X", "Z")) {
                                Vector3f actualNormal = (Vector3f) renderer.getClass().getField("normal" + axis).get(renderer);
                                Vector3f expectedNormal = normal.peek().transformNormal(axis.equals("X") ? 1 : 0, 0, axis.equals("Z") ? 1 : 0, new Vector3f());
                                check(actualNormal.distance(expectedNormal) < 3e-5F, "normal transform and normalization cache match independent reference rotations");
                            }
                        }
                    }
                    check(PLAYER.lastTickProgress == .375F, "same ignore-freeze render interpolation as GameRenderer");
                }
                for (String style : List.of("Slice", "Sweep", "Thrust")) {
                    STYLE.setValue(style);
                    POSITION.setValue(false);
                    SWING.onStartTick(SWING_CONTEXT);
                    Matrix4f originalIdle = render(0, 0, Hand.MAIN_HAND, SWORD);
                    POSITION.setValue(true);
                    SWING.onStartTick(SWING_CONTEXT);
                    Matrix4f changedIdle = render(0, 0, Hand.MAIN_HAND, SWORD);
                    float motion = swordTip(originalIdle, arm).distance(swordTip(changedIdle, arm));
                    check(motion > .12F, "GUI Affect position changes actual sword tip while idle with Hand Models=" + models + ": " + style);
                    check(!originalIdle.equals(changedIdle, 1e-4F), "idle style reaches final submitted model matrix");
                }
            }
        }
    }

    private static void behavior() throws Exception {
        PLAYER.mainArm = Arm.RIGHT;
        HANDS.onEnable(HAND_CONTEXT);
        POSITION.setValue(true);
        STYLE.setValue("Slice");
        SWING.onEnable(SWING_CONTEXT);
        PLAYER.inventoryMain = ItemStack.EMPTY;
        near(render(.35F, .8F, Hand.MAIN_HAND, SWORD), reference("Slice", Arm.RIGHT, .35F, true), "uses rendered sword while inventory has already swapped");
        PLAYER.inventoryMain = SWORD;
        PLAYER.preferredHand = Hand.OFF_HAND;
        near(render(0, .2F, Hand.MAIN_HAND, SWORD), reference("Slice", Arm.RIGHT, 0, true), "main-hand idle pose persists during offhand swing");
        PLAYER.preferredHand = null;
        near(render(.25F, .2F, Hand.MAIN_HAND, SWORD), reference("Slice", Arm.RIGHT, .25F, true), "null preferred hand uses vanilla main-hand fallback");
        PLAYER.preferredHand = Hand.MAIN_HAND;
        for (int scenario = 0; scenario < 9; scenario++) {
            ItemStack item = new ItemStack();
            Hand hand = Hand.MAIN_HAND;
            if (scenario == 0) PLAYER.using = true;
            if (scenario == 1) PLAYER.spyglass = true;
            if (scenario == 2) PLAYER.riptide = true;
            if (scenario == 3) item.map = true;
            if (scenario == 4) item.item = Items.CROSSBOW;
            if (scenario == 5) item.swing = SwingAnimationType.STAB;
            if (scenario == 6) item.empty = true;
            if (scenario == 7) hand = Hand.OFF_HAND;
            if (scenario == 8) CLIENT.currentScreen = new HandModelsEditorScreen();
            SWING.onDisable(SWING_CONTEXT);
            Matrix4f expected = render(.5F, .43F, hand, item);
            SWING.onEnable(SWING_CONTEXT);
            near(render(.5F, .43F, hand, item), expected, "special action/editor/offhand retains existing vanilla pose: " + scenario);
            PLAYER.using = PLAYER.spyglass = PLAYER.riptide = false;
            CLIENT.currentScreen = null;
        }
        POSITION.setValue(false);
        SWING.onStartTick(SWING_CONTEXT);
        Matrix4f rotationOnly = render(.5F, .43F, Hand.MAIN_HAND, SWORD);
        POSITION.setValue(true);
        SWING.onStartTick(SWING_CONTEXT);
        check(!rotationOnly.equals(render(.5F, .43F, Hand.MAIN_HAND, SWORD), 1e-4F), "live GUI toggle switches real render behavior without reopening client");
    }

    private static void lifecycle() throws Exception {
        HANDS.onDisable(HAND_CONTEXT);
        SWING.onDisable(SWING_CONTEXT);
        Matrix4f vanilla = render(.4F, .35F, Hand.MAIN_HAND, SWORD);
        SWING.onEnable(SWING_CONTEXT);
        check(!vanilla.equals(render(.4F, .35F, Hand.MAIN_HAND, SWORD), 1e-4F), "enable changes submitted sword pose");
        SWING.onWorldChanged(SWING_CONTEXT);
        near(render(.4F, .35F, Hand.MAIN_HAND, SWORD), vanilla, "world reset removes preset");
        SWING.onEnable(SWING_CONTEXT);
        CelesteClient.authorized = false;
        near(render(.4F, .35F, Hand.MAIN_HAND, SWORD), vanilla, "real BetaVisualHooks inactive gate preserves vanilla");
        CelesteClient.authorized = true;

        MatrixStack matrices = new MatrixStack();
        matrices.push();
        VisualHookBridge.transformHand(PLAYER, Hand.MAIN_HAND, SWORD, matrices);
        matrices.translate(.1F, .2F, .3F);
        Matrix4f before = new Matrix4f(matrices.peek().getPositionMatrix());
        VisualHookBridge.handDisplayContext(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, new ItemStack());
        near(matrices.peek().getPositionMatrix(), before, "different item cannot consume captured hand pose");
        VisualHookBridge.handDisplayContext(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, SWORD);
        near(matrices.peek().getPositionMatrix(), before, "mismatched submission discards pending pose");
        matrices.pop();

        matrices.push();
        VisualHookBridge.transformHand(PLAYER, Hand.MAIN_HAND, SWORD, matrices);
        matrices.pop();
        VisualHookBridge.handDisplayContext(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, SWORD);
        near(matrices.peek().getPositionMatrix(), new Matrix4f(), "popped entry cannot contaminate parent matrix");
        matrices.push();
        VisualHookBridge.transformHand(PLAYER, Hand.MAIN_HAND, SWORD, matrices);
        SWING.onDisable(SWING_CONTEXT);
        VisualHookBridge.handDisplayContext(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, SWORD);
        near(matrices.peek().getPositionMatrix(), new Matrix4f(), "disable discards a pending hand pose");
        matrices.pop();
    }

    private static Matrix4f render(float progress, float equip, Hand hand, ItemStack item) throws Exception {
        PLAYER.progress = progress;
        MatrixStack matrices = new MatrixStack();
        matrices.multiplyPositionMatrix(CAMERA);
        draw.invoke(renderer, PLAYER, .375F, 0F, hand, progress, item, equip, matrices, null, 0);
        near(matrices.peek().getPositionMatrix(), CAMERA, "vanilla hand pop restores camera matrix");
        check(matrices.isEmpty(), "balanced per-hand matrix scope");
        check(!registration.hasFailed(), "real payload callback stays registered");
        return new Matrix4f((Matrix4f) renderer.getClass().getField("submitted").get(renderer));
    }

    /** Independent preset oracle written as matrix-stack operations, as in the reference renderer. */
    private static Matrix4f reference(String style, Arm arm, float progress, boolean models) {
        return new Matrix4f(referenceStack(style, arm, progress, models).peek().getPositionMatrix());
    }

    private static MatrixStack referenceStack(String style, Arm arm, float progress, boolean models) {
        boolean right = arm == Arm.RIGHT;
        int side = right ? 1 : -1;
        MatrixStack result = new MatrixStack();
        result.multiplyPositionMatrix(CAMERA);
        if (models) result.multiplyPositionMatrix(HandModelsPose.transform(right, right ? .66 : .33, right ? .69 : .71,
            1920.0 / 1080, 70, right ? 1.2 : .8, new Quaternionf().rotationY((float) (Math.PI * .5)), true));
        result.translate(side * .56F, -.52F, -.72F);
        double quick = Math.sin(Math.sqrt(progress) * Math.PI), arc = Math.sin(progress * Math.PI);
        switch (style) {
            case "Slice" -> { rx(result, 50); ry(result, side * -60); rz(result, side * (110 + 40 * quick)); }
            case "Sweep" -> { rx(result, 50); ry(result, side * (-30 * (1 - quick) - 30 + 20 * quick)); rz(result, side * 110); }
            case "Thrust" -> { ry(result, side * 90); rz(result, side * -30); rx(result, -80 - 40 * arc); }
            case "Spin" -> rx(result, -360 * progress);
            default -> { ry(result, side * (45 - 20 * Math.sin(progress * progress * Math.PI))); rz(result, side * -20 * quick); rx(result, -80 * quick); ry(result, side * -45); }
        }
        return result;
    }

    /** Vanilla handheld.json's authored first-person transform applied to a sword-tip vertex. */
    private static Vector3f swordTip(Matrix4f hand, Arm arm) {
        float side = arm == Arm.RIGHT ? 1 : -1;
        Matrix4f model = new Matrix4f(hand).translate(side * 1.13F / 16, 3.2F / 16, 1.13F / 16)
            .rotate(new Quaternionf().rotationXYZ(0, (float) Math.toRadians(side * -90), (float) Math.toRadians(25)))
            .scale(.68F).translate(-.5F, -.5F, -.5F);
        return model.transformPosition(new Vector3f(.95F, .95F, .5F));
    }

    private static void rx(MatrixStack m, double degrees) { m.multiply(RotationAxis.POSITIVE_X.rotationDegrees((float) degrees)); }
    private static void ry(MatrixStack m, double degrees) { m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((float) degrees)); }
    private static void rz(MatrixStack m, double degrees) { m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) degrees)); }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(Opcodes.ASM9); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node; }
    private static void near(Matrix4f actual, Matrix4f expected, String message) { check(actual.equals(expected, 3e-5F), message + "\nactual=" + actual + "expected=" + expected); }
    private static void near(Matrix3f actual, Matrix3f expected, String message) { check(actual.equals(expected, 3e-5F), message); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
