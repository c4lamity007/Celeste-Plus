"""Offline namespace regression: actual helper, pass, memory loader and boundary checks.

Dependencies must already exist locally. Uses only generated keys and fresh
output files. Does not initialize the game, contact auth, or claim native protection.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OWNER = "dev/c4lam/celeste/auth/CredentialMaterial"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def archive(path, entries):
    with zipfile.ZipFile(path, "x", compression=zipfile.ZIP_DEFLATED) as target:
        for name, data in sorted(entries.items()):
            target.writestr(name, data)


def contents(path):
    with zipfile.ZipFile(path) as source:
        return {name: source.read(name) for name in source.namelist()}


MEMORY_CHECK = r'''
import dev.c4lam.celeste.beta.memory.TwoPartMemoryClassLoader;
import java.nio.file.*;
import java.security.*;
import java.util.*;
public final class MemoryNamespaceCheck {
  static String sha(byte[] a)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(a));}
  public static void main(String[] args)throws Exception{
    byte[] a=Files.readAllBytes(Path.of(args[0])),b=Files.readAllBytes(Path.of(args[1]));
    ClassLoader parent=new ClassLoader(MemoryNamespaceCheck.class.getClassLoader()){
      @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
        if(name.startsWith("org.w3c.dom.utility.misc.")||name.startsWith("dev.c4lam.celeste.auth.")||name.startsWith("fixture."))
          throw new AssertionError("Payload-owned name escaped to parent: "+name);
        return super.loadClass(name,resolve);
      }
    };
    var names=new TwoPartMemoryClassLoader.Namespaces(Set.of("org.w3c.dom.utility.misc.","dev.c4lam.celeste.auth.","fixture."),Set.of("fixture/"));
    var limits=new TwoPartMemoryClassLoader.Limits(8*1024*1024,128,2*1024*1024,16*1024*1024L);
    try(var loader=TwoPartMemoryClassLoader.open(a,sha(a),b,sha(b),names,limits,parent)){
      Class<?> alias=Class.forName(args[2],true,loader);
      if(alias.getClassLoader()!=loader)throw new AssertionError("Alias not defined by actual memory loader");
      Class<?> node=Class.forName("org.w3c.dom.Node",false,loader);
      if(!node.getModule().getName().equals("java.xml")||node.getClassLoader()==loader)
        throw new AssertionError("JDK XML class was shadowed");
      Class.forName("fixture.AuthNamespaceCheck",true,loader).getMethod("main",String[].class).invoke(null,(Object)new String[0]);
      Class.forName("dev.c4lam.celeste.auth.WrapperNamespaceCheck",true,loader).getMethod("main",String[].class).invoke(null,(Object)new String[0]);
    }
    System.out.println("PASS actual TwoPartMemoryClassLoader: child-only aliases and original JDK XML delegation");
  }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--dependency-directory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository", type=Path, default=ROOT)
    parser.add_argument("--pass-source", type=Path)
    parser.add_argument("--check-source", type=Path)
    parser.add_argument("--shared-auth-root", type=Path)
    parser.add_argument("--runtime-auth-root", type=Path)
    parser.add_argument("--obfuscation-source", type=Path)
    args = parser.parse_args()
    root = args.repository.resolve(strict=True)
    tool_source = (args.pass_source or root / "tools/packaging/AuthNamespacePass.java").resolve(strict=True)
    check_source = (args.check_source or root / "tools/qa/AuthNamespaceCheck.java").resolve(strict=True)
    shared = (args.shared_auth_root or root / "beta-lab/shared-auth/src/main/java/dev/c4lam/celeste/auth").resolve(strict=True)
    runtime = (args.runtime_auth_root or root / "runtime-overlay/src/main/java/dev/c4lam/celeste/auth").resolve(strict=True)
    preparation = (args.obfuscation_source or root / "beta-lab/obfuscation/prepare_obfuscation.py").resolve(strict=True)
    memory = root / "beta-lab/memory-runtime/src/main/java/dev/c4lam/celeste/beta/memory"
    java = args.java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    javac = args.java_home / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    jars = sorted(args.dependency_directory.resolve(strict=True).glob("*.jar"))
    assert jars and java.is_file() and javac.is_file(), "Local JDK/dependency bundle is missing"
    dependencies = os.pathsep.join(str(p) for p in jars)
    output = args.output.resolve()
    assert not output.exists(), "Use a fresh output directory"
    output.mkdir(parents=True)
    report = {"schema": "celeste-auth-namespace-qa-1", "steps": [], "inputs": {},
              "limits": ["Synthetic keys only; no game/auth/network/native protection", "Boundary fixture is not a protected production release"]}
    for p in [Path(__file__).resolve(), tool_source, check_source, preparation, preparation.parents[2] / "tools/native_auth_resources.py",
              shared / "CredentialMaterial.java", shared / "DpopProofSigner.java",
              runtime / "InstallCredential.java", memory / "TwoPartMemoryClassLoader.java", memory / "NativeResourceBudget.java", *jars]:
        report["inputs"][str(p)] = digest(p.read_bytes())

    def run(name, command, expected=None, rejection=False):
        result = subprocess.run(list(map(str, command)), capture_output=True, timeout=90)
        data = result.stdout + result.stderr
        (output / (name + ".log")).write_bytes(data)
        text = data.decode("utf-8", errors="replace")
        assert (result.returncode != 0 if rejection else result.returncode == 0), name + ": " + text[-3000:]
        if expected:
            assert expected in text, name + " did not reach its intended assertion"
        report["steps"].append({"name": name, "exit_code": result.returncode, "expected_rejection": rejection, "log_sha256": digest(data)})
        print("PASS " + name, flush=True)

    tool_classes, fixture_classes, harness_classes = [output / name for name in ("tool", "fixture", "harness")]
    for p in (tool_classes, fixture_classes, harness_classes):
        p.mkdir()
    run("compile-pass", [javac, "--release", "21", "-encoding", "UTF-8", "-cp", dependencies, "-d", tool_classes, tool_source])
    generated = output / "generated"
    def source(name, text):
        p = generated / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
        return p
    scenario = source("fixture/AuthNamespaceCheck.java", "package fixture;\n" + check_source.read_text(encoding="utf-8"))
    identity = source("dev/c4lam/celeste/auth/GameIdentity.java", "package dev.c4lam.celeste.auth; record GameIdentity(String uuid,String username){}")
    bridge = source("dev/celestebeta/host/NamespaceFixture.java", "package dev.celestebeta.host; public final class NamespaceFixture {}")
    media = source("dev/redstones/mediaplayerinfo/NamespaceFixture.java", "package dev.redstones.mediaplayerinfo; public final class NamespaceFixture {}")
    wrapper = source("dev/c4lam/celeste/auth/WrapperNamespaceCheck.java", r'''
package dev.c4lam.celeste.auth;
import java.security.*;
import java.util.Arrays;
public final class WrapperNamespaceCheck {
  public static void main(String[] args)throws Exception{
    var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var enc=KeyPairGenerator.getInstance("X25519").generateKeyPair();
    byte[] message={4,2},encoded;
    try(var credential=new InstallCredential("a".repeat(32),1,key.getPrivate().getEncoded(),
        enc.getPrivate().getEncoded(),new byte[32],"public-certificate","uuid","Renée")){
      credential.validate();encoded=credential.encode();
      try(var decoded=InstallCredential.decode(encoded)){
        decoded.validate();if(!decoded.gameName().equals("Renée"))throw new AssertionError("Wrapper codec changed");
        var check=Signature.getInstance("Ed25519");check.initVerify(key.getPublic());check.update(message);
        if(!check.verify(decoded.signInstallation(message)))throw new AssertionError("Wrapper lost alias signing linkage");
      } finally {Arrays.fill(encoded,(byte)0);}
      credential.close();if(!credential.isDestroyed())throw new AssertionError("Wrapper did not close alias");
    }
    System.out.println("PASS actual stable InstallCredential wrapper invokes aliases through memory loader");
  }
}
''')
    run("compile-real-helper", [javac, "--release", "21", "-encoding", "UTF-8", "-cp", dependencies, "-d", fixture_classes,
        shared / "CredentialMaterial.java", shared / "DpopProofSigner.java", runtime / "InstallCredential.java", scenario, identity, bridge, media, wrapper])
    raw_entries = {p.relative_to(fixture_classes).as_posix(): p.read_bytes() for p in fixture_classes.rglob("*.class")}
    raw, renamed, mapping_path = output / "raw.jar", output / "aliases.jar", output / "aliases.json"
    archive(raw, raw_entries)
    tool_cp = str(tool_classes) + os.pathsep + dependencies
    run("actual-namespace-pass", [java, "-Xverify:all", "-cp", tool_cp, "AuthNamespacePass", raw, renamed, mapping_path], "PASS auth namespace aliases:")
    mapping = json.loads(mapping_path.read_text(encoding="utf-8"))
    assert mapping["input_sha256"] == digest(raw.read_bytes()) and mapping["output_sha256"] == digest(renamed.read_bytes())
    assert mapping["inverse_mapping_verified"] is True and mapping["public_descriptors_preserved"] is True
    run("actual-helper-functional", [java, "-ea", "-Xverify:all", "-cp", str(renamed) + os.pathsep + dependencies,
        "fixture.AuthNamespaceCheck"], "PASS auth namespace:")
    aliases = contents(renamed)
    for name in mapping["classes"]:
        assert name + ".class" not in aliases
    payload = {k: v for k, v in aliases.items() if not k.startswith(("dev/celestebeta/host/", "dev/redstones/mediaplayerinfo/"))}
    a, b = output / "payload-a.jar", output / "payload-b.jar"
    archive(a, payload)
    archive(b, {"fixture/memory-marker.txt": b"nonempty second authenticated part"})
    harness = source("MemoryNamespaceCheck.java", MEMORY_CHECK)
    run("compile-real-memory-loader", [javac, "--release", "21", "-encoding", "UTF-8", "-d", harness_classes,
        memory / "TwoPartMemoryClassLoader.java", memory / "NativeResourceBudget.java", harness])
    run("actual-memory-loader", [java, "-ea", "-Xverify:all", "-cp", str(harness_classes) + os.pathsep + dependencies,
        "MemoryNamespaceCheck", a, b, mapping["classes"][OWNER].replace("/", ".")], "PASS actual TwoPartMemoryClassLoader:")

    exposed = source("negative/Exposed.java", "package negative; public class Exposed { public dev.c4lam.celeste.auth.CredentialMaterial value; }")
    negative_classes = output / "negative"
    negative_classes.mkdir()
    run("compile-exposed-fixture", [javac, "--release", "21", "-cp", str(fixture_classes), "-d", negative_classes, exposed])
    native = source("native/dev/c4lam/celeste/auth/CredentialMaterial.java", "package dev.c4lam.celeste.auth; public final class CredentialMaterial { public native void value(); }")
    native_classes = output / "native"
    native_classes.mkdir()
    run("compile-native-fixture", [javac, "--release", "21", "-d", native_classes, native])
    cases = [
        ("exposed-descriptor", raw_entries | {"negative/Exposed.class": (negative_classes / "negative/Exposed.class").read_bytes()}, "Public field exposes"),
        ("native-helper", raw_entries | {OWNER + ".class": (native_classes / (OWNER + ".class")).read_bytes()}, "Native helper requires"),
        ("native-budget", raw_entries | {"META-INF/celeste-beta/native-auth-resources.txt": b"already native"}, "Run before native protection"),
    ]
    for name, entries, expected in cases:
        bad = output / (name + ".jar")
        archive(bad, entries)
        run("reject-" + name, [java, "-cp", tool_cp, "AuthNamespacePass", bad, output / (name + "-out.jar"), output / (name + ".json")], expected, rejection=True)
        assert not (output / (name + "-out.jar")).exists()

    sys.path.insert(0, str(preparation.parents[2] / "tools"))
    spec = importlib.util.spec_from_file_location("namespace_boundary_fixture", preparation)
    boundary = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(boundary)
    changed = [name for name in raw_entries if boundary.payload_boundary(name) and raw_entries[name] != aliases.get(name)]
    assert changed == ["dev/c4lam/celeste/auth/InstallCredential.class"], changed
    # Verify the exact alias snapshot forms the new boundary baseline. The raw
    # comparison MUST still reject; never weaken it to accommodate the renamer.
    try:
        boundary.check_payload_boundaries(raw, renamed)
        raise AssertionError("Raw boundary comparison incorrectly accepted relocated wrapper bytes")
    except ValueError as error:
        assert "boundary bytes" in str(error)
    candidate = output / "boundary-candidate.jar"
    archive(candidate, aliases | {"fixture/protection-resource.txt": b"synthetic implementation resource"})
    boundary_report = boundary.check_payload_boundaries(renamed, candidate)
    assert boundary_report["verified_unchanged_entries"] == 3
    tampered = output / "tampered-boundary.jar"
    archive(tampered, aliases | {"dev/c4lam/celeste/auth/InstallCredential.class": b"tampered"})
    try:
        boundary.check_payload_boundaries(renamed, tampered)
        raise AssertionError("Alias boundary comparison accepted tampering")
    except ValueError as error:
        assert "boundary bytes" in str(error)
    report["boundary"] = {"raw_changed_stable_entries": changed, "raw_comparison_rejected": True,
                          "alias_comparison": boundary_report, "stable_tamper_rejected": True}
    report["mapping_sha256"] = digest(mapping_path.read_bytes())
    assert all(digest(Path(path).read_bytes()) == expected for path, expected in report["inputs"].items()), "QA inputs changed during execution"
    report["status"] = "passed"
    (output / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("PASS raw-to-alias inverse proof, exact alias boundary and tamper rejection", flush=True)


if __name__ == "__main__":
    main()
