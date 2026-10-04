"""Compile the real runtime-overlay MoveFix against tiny Minecraft API fixtures."""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[2]

VEC2F = r'''package net.minecraft.util.math;
public final class Vec2f {
    public static final Vec2f ZERO = new Vec2f(0.0F, 0.0F);
    public final float x;
    public final float y;
    public Vec2f(float x, float y) { this.x = x; this.y = y; }
    public float lengthSquared() { return x * x + y * y; }
    public float length() { return (float) Math.sqrt(lengthSquared()); }
    public Vec2f normalize() {
        float length = length();
        return length == 0.0F ? ZERO : new Vec2f(x / length, y / length);
    }
    public Vec2f multiply(float scalar) { return new Vec2f(x * scalar, y * scalar); }
}
'''

PLAYER_INPUT = r'''package net.minecraft.util;
public record PlayerInput(boolean forward, boolean backward, boolean left, boolean right,
                          boolean jump, boolean sneak, boolean sprint) {
    public static final PlayerInput DEFAULT = new PlayerInput(false, false, false, false, false, false, false);
}
'''

CHECK = r'''package qa;
import dev.c4lam.celeste.rotation.MoveFix;
import dev.c4lam.celeste.rotation.MoveFixMode;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

public final class MoveFixCheck {
    private static final float EPSILON = 1.0E-4F;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static boolean near(float a, float b) { return Math.abs(a - b) < EPSILON; }
    private static void sameVector(Vec2f actual, Vec2f expected, String message) {
        check(near(actual.x, expected.x) && near(actual.y, expected.y), message);
    }
    private static void legalEightWay(MoveFix.Correction correction, float magnitude) {
        Vec2f movement = correction.movement();
        check(near(movement.length(), magnitude), "strict move-fix preserves input magnitude");
        float x = Math.abs(movement.x), y = Math.abs(movement.y);
        check(x > EPSILON || y > EPSILON, "nonzero input remains nonzero");
        check(x < EPSILON || y < EPSILON || near(x, y), "strict direction is one of vanilla's eight directions");
        PlayerInput keys = correction.keys();
        check(keys.forward() == (movement.y > EPSILON), "forward key matches projected vector");
        check(keys.backward() == (movement.y < -EPSILON), "back key matches projected vector");
        check(keys.left() == (movement.x > EPSILON), "left key matches projected vector");
        check(keys.right() == (movement.x < -EPSILON), "right key matches projected vector");
        check(!(keys.forward() && keys.backward()) && !(keys.left() && keys.right()), "no opposing keys");
    }

    public static void main(String[] args) {
        PlayerInput keys = new PlayerInput(true, false, false, true, true, true, true);
        Vec2f raw = new Vec2f(0.25F, 0.75F);
        for (int cameraYaw = -180; cameraYaw <= 180; cameraYaw += 15) {
            for (int serverYaw = -180; serverYaw <= 180; serverYaw += 30) {
                MoveFix.Correction anarchy = MoveFix.correct(raw, keys, cameraYaw, serverYaw, MoveFixMode.ANARCHY);
                sameVector(anarchy.movement(), raw, "Anarchy leaves camera-space movement unchanged");
                check(anarchy.keys() == keys, "Anarchy leaves the original directional keys unchanged");
                MoveFix.Correction off = MoveFix.correct(raw, keys, cameraYaw, serverYaw, MoveFixMode.OFF);
                sameVector(off.movement(), raw, "Off leaves movement unchanged");
                check(off.keys() == keys, "Off leaves keys unchanged");

                float strictYaw = MoveFix.movementYaw(cameraYaw, serverYaw, MoveFixMode.STRICT);
                check(near(strictYaw, serverYaw), "Default/Strict uses server yaw for corrected movement");
                check(near(MoveFix.sprintJumpYaw(cameraYaw, serverYaw, MoveFixMode.STRICT), serverYaw),
                    "Default/Strict uses server yaw for sprint-jump movement");
                check(near(MoveFix.movementYaw(cameraYaw, serverYaw, MoveFixMode.NORMAL), serverYaw),
                    "legacy Normal resolves to Strict");
                check(near(MoveFix.movementYaw(cameraYaw, serverYaw, MoveFixMode.ANARCHY), cameraYaw),
                    "Anarchy keeps camera-space movement yaw");
                check(near(MoveFix.sprintJumpYaw(cameraYaw, serverYaw, MoveFixMode.ANARCHY), cameraYaw),
                    "Anarchy keeps camera-space sprint-jump yaw");
                check(near(MoveFix.movementYaw(cameraYaw, serverYaw, MoveFixMode.OFF), cameraYaw),
                    "Off keeps camera-space movement yaw");

                MoveFix.Correction strict = MoveFix.correct(raw, keys, cameraYaw, serverYaw, MoveFixMode.STRICT);
                legalEightWay(strict, raw.length());
                MoveFix.Correction legacy = MoveFix.correct(raw, keys, cameraYaw, serverYaw, MoveFixMode.NORMAL);
                sameVector(legacy.movement(), strict.movement(), "legacy Normal matches Strict vector");
                check(legacy.keys().equals(strict.keys()), "legacy Normal matches Strict keys");
                check(strict.keys().jump() && strict.keys().sneak() && strict.keys().sprint(),
                    "strict correction preserves non-directional input");
            }
        }
        System.out.println("MoveFix checks passed: Anarchy/Off camera-space preservation and Strict eight-way projection.");
    }
}
'''


def main() -> int:
    java_home = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None
    javac = java_home / "bin" / ("javac.exe" if os.name == "nt" else "javac") if java_home else None
    java = java_home / "bin" / ("java.exe" if os.name == "nt" else "java") if java_home else None
    javac = str(javac) if javac and javac.is_file() else shutil.which("javac")
    java = str(java) if java and java.is_file() else shutil.which("java")
    if not javac or not java:
        print("MoveFix check requires a JDK (set JAVA_HOME or put javac/java on PATH).", file=sys.stderr)
        return 2

    sources = {
        "net/minecraft/util/math/Vec2f.java": VEC2F,
        "net/minecraft/util/PlayerInput.java": PLAYER_INPUT,
        "qa/MoveFixCheck.java": CHECK,
    }
    production = [
        ROOT / "runtime-overlay/src/main/java/dev/c4lam/celeste/rotation/MoveFix.java",
        ROOT / "runtime-overlay/src/main/java/dev/c4lam/celeste/rotation/MoveFixMode.java",
    ]
    with tempfile.TemporaryDirectory(prefix="celeste-movefix-") as temporary:
        directory = Path(temporary)
        classes = directory / "classes"
        classes.mkdir()
        fixture_paths = []
        for relative, content in sources.items():
            path = directory / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8", newline="\n")
            fixture_paths.append(str(path))
        commands = [
            [javac, "-encoding", "UTF-8", "-d", str(classes), *fixture_paths, *(str(path) for path in production)],
            [java, "-cp", str(classes), "qa.MoveFixCheck"],
        ]
        for command in commands:
            result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
            if result.returncode:
                if result.stdout:
                    print(result.stdout, end="")
                if result.stderr:
                    print(result.stderr, end="", file=sys.stderr)
                return result.returncode
            if result.stdout:
                print(result.stdout, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
