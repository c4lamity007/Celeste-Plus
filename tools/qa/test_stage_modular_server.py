"""Disposable offline staging fixtures, never production protection evidence."""
from pathlib import Path
import hashlib
import importlib.util
import json
import sys
import time
import tomllib
from types import SimpleNamespace

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import stage_modular_server as stage


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return path


def signed(key, claims, header=None):
    first = stage.native.b64(stage.canonical(header or stage.HEADER))
    second = stage.native.b64(stage.canonical(claims))
    value = first + "." + second
    return value + "." + stage.native.b64(key.sign(value.encode()))


def beta(directory, identity, runtime, payload=None):
    parts = {}
    for key, filename in stage.legacy.BETA_FILES.items():
        entries = payload if key == "payload-a" else {"fixture/" + key + ".txt": b"public offline fixture"}
        data = stage.modules.zip_bytes(entries or {"fixture.bin": b"fixture"})
        write(directory / filename, data)
        parts[filename] = data
    value = {"schema": "celeste-beta-release-2", "release_id": identity, "runtime_sha256": runtime,
             "client_version": "fixture-1", "artifacts": [{"id": key, "filename": filename,
             "role": "bootstrap" if key.startswith("bootstrap") else "payload", "size": len(parts[filename]),
             "sha256": stage.sha(parts[filename])} for key, filename in stage.legacy.BETA_FILES.items()]}
    write(directory / "beta-release.json", stage.canonical(value))
    return value


@pytest.fixture
def fixture(tmp_path, monkeypatch):
    key = Ed25519PrivateKey.generate()
    public = stage.native.b64(key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw))
    monkeypatch.setattr(stage.native, "SERVER_PUBLIC_KEY", public)
    project, base, candidate = tmp_path / "project", tmp_path / "base", tmp_path / "candidate"
    for name in stage.APP_FILES:
        write(project / "auth-service/app" / name, (stage.ROOT / "auth-service/app" / name).read_bytes())
    for name in ("requirements.txt", "entrypoint.sh"):
        write(project / "auth-service" / name, (stage.ROOT / "auth-service" / name).read_bytes())
    old_loader, loader = b"offline old loader", b"offline candidate loader"
    old_hash, new_hash = stage.sha(old_loader), stage.sha(loader)
    write(base / "public-native" / (old_hash + ".exe"), old_loader)
    write(base / "app/__init__.py", b"")
    write(base / "requirements.txt", b"fixture")
    write(base / "entrypoint.sh", b"fixture")
    write(base / "Dockerfile", b"FROM fixture\nWORKDIR /srv/auth\nCOPY app ./app\nCOPY legacy /srv/auth/legacy\nCOPY public-native /srv/auth/public-native\nUSER auth\n")
    config = ('app = "' + stage.APP + '"\n[env]\n'
              'CELESTE_PLUS_ENABLED = "true"\nCELESTE_PLUS_AUTH_PROTOCOL = "2"\n'
              'CELESTE_BETA_RELEASE_DIR = "/srv/auth/legacy/old-release"\n'
              'CELESTE_ARTIFACT_DIR = "/srv/auth/standard"\n'
              'CELESTE_NATIVE_RELEASE_PUBLIC_KEY = "' + public + '"\n'
              'CELESTE_PLUS_DOWNLOAD_SHA256 = "' + old_hash + '"\n')
    write(base / "fly.toml", config.encode())
    prior = beta(base / "legacy/old-release", "old-release", "a" * 64)
    public_prior = {**prior, "artifacts": [{"id": item["id"], "role": item["role"], "size": item["size"], "sha256": item["sha256"],
                     "url": "/v1/beta/artifacts/old-release/" + item["id"]} for item in prior["artifacts"]]}
    rollback_manifest = write(tmp_path / "rollback-manifest.json", stage.canonical({"manifest": signed(key, {"aud": "celeste-beta-manifest", "product": "celeste_plus", **public_prior})}))
    module_jar = stage.modules.zip_bytes({"fixture/Module.class": b"not executable, fixture only"})
    record = {"id": "fixture", "version": "new-release", "artifact_id": "1" * 32, "entrypoint": "fixture.Module",
              "filename": "1" * 32 + ".jar", "sha256": stage.sha(module_jar), "size": len(module_jar),
              "entries_sha256": stage.modules.entries_digest(stage.modules.module_entries(module_jar))}
    executable = stage.modules.executable_catalog("new-release", [record])
    catalog = stage.modules.full_catalog(executable, "b" * 64)
    beta(candidate, "new-release", "b" * 64, {stage.modules.ANCHOR: stage.modules.canonical(executable), "fixture/Host.class": b"fixture"})
    write(candidate / "module-release.json", stage.canonical(catalog))
    write(candidate / "modules" / record["filename"], module_jar)
    receipt = {"schema": "celeste-plus-java-protection-run-1", "status": "protected-verified-candidate-not-published",
               "release_id": "new-release", "module_delivery": {"executable_root_sha256": catalog["executable_root_sha256"]},
               "artifacts": {p.relative_to(candidate).as_posix(): {"sha256": stage.sha(p.read_bytes()), "bytes": p.stat().st_size}
                             for p in candidate.rglob("*") if p.is_file()}}
    receipt_path = write(tmp_path / "receipt.json", stage.canonical(receipt))
    native_header = {"alg": "EdDSA", "typ": "CST"}
    def policy(digest, size, name):
        claims = {"aud": "celeste-native-publication", "product": "celeste_plus", "platform": "windows-x64", "iat": 1000, "nbf": 1000, "exp": 1300, "sequence": 1,
                  "binaries": [{"sha256": digest, "size": size, "release_id": name, "protection_receipt_sha256": "f" * 64}]}
        return write(tmp_path / (name + "-native.json"), stage.canonical({"publication": signed(key, claims, native_header)}))
    old_policy, new_policy = policy(old_hash, len(old_loader), "old-release"), policy(new_hash, len(loader), "new-release")
    old_claims = stage.native.verify_token(json.loads(old_policy.read_bytes())["publication"], key.public_key(), native_header)
    new_claims = stage.native.verify_token(json.loads(new_policy.read_bytes())["publication"], key.public_key(), native_header)
    new_claims["sequence"] = 2
    new_claims["binaries"] += old_claims["binaries"]
    new_policy.write_bytes(stage.canonical({"publication": signed(key, new_claims, native_header)}))
    inventory = {p.relative_to(base).as_posix(): {"sha256": stage.sha(p.read_bytes()), "size": p.stat().st_size}
                 for p in base.rglob("*") if p.is_file()}
    inventory_path = write(tmp_path / "inventory.json", stage.canonical(inventory))
    plan = {"schema": "celeste-plus-modular-stage-plan-1", "mode": "qa", "base_context": str(base),
            "base_inventory": str(inventory_path), "base_inventory_sha256": stage.sha(inventory_path.read_bytes()),
            "candidate_dir": str(candidate), "protection_receipt": str(receipt_path), "protection_receipt_sha256": stage.sha(receipt_path.read_bytes()),
            "native_publication": str(new_policy), "native_publication_sha256": stage.sha(new_policy.read_bytes()),
            "rollback_native_publication": str(old_policy), "rollback_native_publication_sha256": stage.sha(old_policy.read_bytes()),
            "rollback_manifest": str(rollback_manifest), "rollback_manifest_sha256": stage.sha(rollback_manifest.read_bytes()),
            "loader_binary": str(write(tmp_path / "loader.exe", loader)), "qa_installations": ["A" * 32],
            "rollback_image": "registry.fly.io/" + stage.APP + "@sha256:" + "a" * 64,
            "rollback_contract": "legacy-full-client", "public_native_context_dir": "public-native"}
    return SimpleNamespace(project=project, base=base, candidate=candidate, plan=plan, key=key, new_hash=new_hash,
                           output=project / "build/module-qa/staged", old_hash=old_hash)


def test_qa_context_preserves_default_and_signed_exact_rollback(fixture):
    f = fixture
    document = stage.prepare(f.project, f.plan, f.output)
    config = tomllib.loads((f.output / "fly.toml").read_text())
    assert config["env"]["CELESTE_BETA_RELEASE_DIR"] == "/srv/auth/legacy/old-release"
    assert config["env"]["CELESTE_ARTIFACT_DIR"] == "/srv/auth/standard"
    assert config["env"]["CELESTE_PLUS_DOWNLOAD_SHA256"] == f.old_hash
    assert config["env"]["CELESTE_PLUS_QA_INSTALLATIONS"] == "A" * 32
    assert "!evidence/" not in (f.output / ".dockerignore").read_text()
    assert document == stage.verify(f.output)
    claims = {"aud": "celeste-plus-deployment-approval", "product": "celeste_plus", "app": stage.APP,
              "deployment_sha256": stage.sha(stage.canonical(document)), "iat": int(time.time())}
    approval = stage.canonical({"approval": signed(f.key, claims)})
    assert stage.verify(f.output, approval) == document
    (f.output / "app/module_delivery.py").write_bytes(b"changed")
    with pytest.raises(ValueError): stage.verify(f.output, approval)


def test_cutover_requires_modular_pair_and_matching_new_loader(fixture):
    f = fixture
    stage.prepare(f.project, {**f.plan, "mode": "cutover", "qa_installations": []}, f.output)
    config = tomllib.loads((f.output / "fly.toml").read_text())["env"]
    assert config["CELESTE_PLUS_REQUIRE_MODULE_DELIVERY"] == "true"
    assert config["CELESTE_MODULE_RELEASE_DIR"] == config["CELESTE_BETA_RELEASE_DIR"]
    assert config["CELESTE_PLUS_DOWNLOAD_SHA256"] == f.new_hash
    assert config["CELESTE_PLUS_QA_INSTALLATIONS"] == ""
    assert (f.output / "public-native" / (f.new_hash + ".exe")).exists()


@pytest.mark.parametrize("change", ["base-pin", "receipt-pin", "wrong-contract", "no-qa-id", "wrong-image", "bad-loader", "candidate-bytes", "missing-backend", "wrong-native-signature"])
def test_incomplete_or_changed_inputs_never_stage(fixture, change):
    f = fixture
    if change == "base-pin": f.plan["base_inventory_sha256"] = "0" * 64
    elif change == "receipt-pin": f.plan["protection_receipt_sha256"] = "0" * 64
    elif change == "wrong-contract": f.plan["rollback_contract"] = "independent-modules"
    elif change == "no-qa-id": f.plan["qa_installations"] = []
    elif change == "wrong-image": f.plan["rollback_image"] = "latest"
    elif change == "bad-loader": Path(f.plan["loader_binary"]).write_bytes(b"wrong")
    elif change == "candidate-bytes": (f.candidate / "payload-a.bin").write_bytes(b"wrong")
    elif change == "missing-backend": (f.project / "auth-service/app/release_assignment.py").unlink()
    else:
        data = json.loads(Path(f.plan["native_publication"]).read_bytes())
        data["publication"] = data["publication"][:-8] + "A" * 8
        Path(f.plan["native_publication"]).write_bytes(stage.canonical(data))
        f.plan["native_publication_sha256"] = stage.sha(Path(f.plan["native_publication"]).read_bytes())
    with pytest.raises((ValueError, OSError)): stage.prepare(f.project, f.plan, f.output)
    assert not f.output.exists()


def test_aggregate_approval_from_wrong_authority_rejected(fixture):
    f = fixture
    document = stage.prepare(f.project, f.plan, f.output)
    claims = {"aud": "celeste-plus-deployment-approval", "product": "celeste_plus", "app": stage.APP,
              "deployment_sha256": stage.sha(stage.canonical(document)), "iat": int(time.time())}
    with pytest.raises(ValueError):
        stage.verify(f.output, stage.canonical({"approval": signed(Ed25519PrivateKey.generate(), claims)}))

def image_plan(f):
    (f.base / "Dockerfile").write_text("FROM registry.fly.io/" + stage.APP + "@sha256:" + "b" * 64 + "\nCOPY legacy /srv/auth/legacy\n")
    config = (f.base / "fly.toml").read_text() + 'CELESTE_PUBLIC_NATIVE_DIR = "/srv/auth/public-native"\n'
    (f.base / "fly.toml").write_text(config)
    old_loader = (f.base / "public-native" / (f.old_hash + ".exe")).read_bytes()
    old_loader_file = write(f.project / "old-loader.exe", old_loader)
    # A deployed thin layer has no backend source or native binary in its context.
    write(f.base / ".dockerignore", b"**\n!Dockerfile\n!fly.toml\n!legacy/**\n")
    names = ["Dockerfile", "fly.toml", ".dockerignore"] + [p.relative_to(f.base).as_posix() for p in (f.base / "legacy").rglob("*") if p.is_file()]
    inventory = {name: {"sha256": stage.sha((f.base / name).read_bytes()), "size": (f.base / name).stat().st_size} for name in names}
    inventory_file = write(f.project / "thin-inventory.json", stage.canonical(inventory))
    return {**f.plan, "base_image": f.plan["rollback_image"],
            "base_inventory": str(inventory_file), "base_inventory_sha256": stage.sha(inventory_file.read_bytes()),
            "rollback_loader_binary": str(old_loader_file)}


def test_image_base_keeps_standard_and_old_native_inherited(fixture):
    f = fixture
    plan = image_plan(f)
    result = stage.prepare(f.project, plan, f.output)
    docker = (f.output / "Dockerfile").read_text()
    assert docker.startswith("FROM " + plan["base_image"] + "\n")
    assert "WORKDIR /srv/auth/module-backend\n" in docker
    assert "COPY --chown=10001:10001 app ./app" in docker
    assert 'ENTRYPOINT ["/usr/local/bin/celeste-auth-entrypoint"]' in docker
    assert "COPY legacy" not in docker and "candidate-native" not in docker
    env = tomllib.loads((f.output / "fly.toml").read_text())["env"]
    assert env["CELESTE_ARTIFACT_DIR"] == "/srv/auth/standard"
    assert env["CELESTE_BETA_RELEASE_DIR"] == "/srv/auth/legacy/old-release"
    assert env["CELESTE_PLUS_DOWNLOAD_SHA256"] == f.old_hash
    assert not (f.output / "legacy").exists()
    assert (f.output / "evidence/rollback-context/legacy/old-release/payload-a.bin").exists()
    assert (f.output / "evidence/rollback-context/.dockerignore").read_bytes() == (f.base / ".dockerignore").read_bytes()
    assert "!evidence/" not in (f.output / ".dockerignore").read_text()
    assert stage.verify(f.output) == result
    (f.output / "evidence/rollback-context/.dockerignore").write_bytes(b"changed")
    with pytest.raises(ValueError, match="Base context changed"):
        stage.verify(f.output)


def test_image_cutover_preserves_inherited_native_paths(fixture):
    f = fixture
    stage.prepare(f.project, {**image_plan(f), "mode": "cutover", "qa_installations": []}, f.output)
    docker = (f.output / "Dockerfile").read_text()
    assert "COPY --chown=10001:10001 candidate-native /srv/auth/public-native" in docker
    env = tomllib.loads((f.output / "fly.toml").read_text())["env"]
    assert env["CELESTE_PLUS_REQUIRE_MODULE_DELIVERY"] == "true"
    assert env["CELESTE_ARTIFACT_DIR"] == "/srv/auth/standard"
    assert (f.output / "candidate-native" / (f.new_hash + ".exe")).is_file()


@pytest.mark.parametrize("change", ["image-mismatch", "mutable-image", "rollback-loader", "drop-old-loader", "wrong-native-size", "old-sequence"])
def test_image_incoherent_rollback_never_stages(fixture, change):
    f = fixture
    plan = image_plan(f)
    if change == "image-mismatch": plan["base_image"] = "registry.fly.io/" + stage.APP + "@sha256:" + "c" * 64
    elif change == "mutable-image": plan["base_image"] = plan["rollback_image"] = "latest"
    elif change == "rollback-loader": Path(plan["rollback_loader_binary"]).write_bytes(b"wrong")
    else:
        target = Path(plan["native_publication"])
        claims = stage.native.verify_token(json.loads(target.read_bytes())["publication"], f.key.public_key(), {"alg": "EdDSA", "typ": "CST"})
        if change == "drop-old-loader": claims["binaries"] = claims["binaries"][:1]
        elif change == "wrong-native-size": claims["binaries"][0]["size"] += 1
        else: claims["sequence"] = 1
        target.write_bytes(stage.canonical({"publication": signed(f.key, claims, {"alg": "EdDSA", "typ": "CST"})}))
        plan["native_publication_sha256"] = stage.sha(target.read_bytes())
    with pytest.raises(ValueError): stage.prepare(f.project, plan, f.output)
    assert not f.output.exists()


def test_private_admin_hash_cannot_enter_context(fixture):
    f = fixture
    plan = image_plan(f)
    config = f.base / "fly.toml"
    config.write_text(config.read_text() + 'CELESTE_ADMIN_PASSWORD_HASH = "private-hash-must-not-export"\n')
    inventory_path = Path(plan["base_inventory"])
    inventory = json.loads(inventory_path.read_bytes())
    inventory["fly.toml"] = {"sha256": stage.sha(config.read_bytes()), "size": config.stat().st_size}
    inventory_path.write_bytes(stage.canonical(inventory))
    plan["base_inventory_sha256"] = stage.sha(inventory_path.read_bytes())
    with pytest.raises(ValueError): stage.prepare(f.project, plan, f.output)
    assert not f.output.exists()


@pytest.mark.parametrize("retain_all", [False, True])
def test_native_retention_preserves_every_existing_loader(fixture, retain_all):
    f = fixture
    header = {"alg": "EdDSA", "typ": "CST"}
    old_path, new_path = Path(f.plan["rollback_native_publication"]), Path(f.plan["native_publication"])
    old = stage.native.verify_token(json.loads(old_path.read_bytes())["publication"], f.key.public_key(), header)
    new = stage.native.verify_token(json.loads(new_path.read_bytes())["publication"], f.key.public_key(), header)
    additional = [{"sha256": digit * 64, "size": 123, "release_id": "retained-" + digit, "protection_receipt_sha256": "f" * 64}
                  for digit in ("3", "4")]
    old["binaries"] += additional
    if retain_all: new["binaries"] += additional
    for path, claims, pin in ((old_path, old, "rollback_native_publication_sha256"), (new_path, new, "native_publication_sha256")):
        path.write_bytes(stage.canonical({"publication": signed(f.key, claims, header)}))
        f.plan[pin] = stage.sha(path.read_bytes())
    if retain_all:
        stage.prepare(f.project, f.plan, f.output)
        assert stage.verify(f.output)["mode"] == "qa"
    else:
        with pytest.raises(ValueError): stage.prepare(f.project, f.plan, f.output)
        assert not f.output.exists()


def reused_loader_plan(f):
    plan = image_plan(f)
    Path(plan["loader_binary"]).write_bytes(Path(plan["rollback_loader_binary"]).read_bytes())
    plan["native_publication"] = plan["rollback_native_publication"]
    plan["native_publication_sha256"] = plan["rollback_native_publication_sha256"]
    native_claims = stage.native.verify_token(json.loads(Path(plan["native_publication"]).read_bytes())["publication"],
                                              f.key.public_key(), {"alg": "EdDSA", "typ": "CST"})
    nonce = stage.native.b64(b"n" * 24)
    now = int(time.time())
    live = {"aud": "celeste-native-manifest", "product": "celeste_plus", "platform": "windows-x64",
            "iat": now, "nbf": now, "exp": now + 120, "sequence": native_claims["sequence"],
            "request_nonce": nonce, "sha256": [item["sha256"] for item in native_claims["binaries"]]}
    manifest = write(f.project / "current-native-manifest.json", stage.canonical({"manifest": signed(f.key, live)}))
    plan.update(current_native_manifest=str(manifest), current_native_manifest_sha256=stage.sha(manifest.read_bytes()),
                current_native_request_nonce=nonce)
    return plan


def test_identical_current_loader_and_policy_reuse_has_no_native_copy(fixture):
    f = fixture
    plan = {**reused_loader_plan(f), "mode": "cutover", "qa_installations": []}
    result = stage.prepare(f.project, plan, f.output)
    assert result["native_policy_action"] == "reuse-current-approved-policy"
    assert "candidate-native" not in (f.output / "Dockerfile").read_text()
    assert not (f.output / "candidate-native").exists()
    assert tomllib.loads((f.output / "fly.toml").read_text())["env"]["CELESTE_PLUS_DOWNLOAD_SHA256"] == f.old_hash
    assert stage.verify(f.output) == result


@pytest.mark.parametrize("change", ["stale-sequence", "expired", "nonce", "different-policy"])
def test_reuse_requires_current_identical_approved_policy(fixture, change):
    f = fixture
    plan = reused_loader_plan(f)
    if change == "different-policy":
        plan["native_publication"] = f.plan["native_publication"]
        plan["native_publication_sha256"] = f.plan["native_publication_sha256"]
    else:
        path = Path(plan["current_native_manifest"])
        claims = stage.native.verify_token(json.loads(path.read_bytes())["manifest"], f.key.public_key(), stage.HEADER)
        if change == "stale-sequence": claims["sequence"] += 1
        elif change == "expired": claims.update(iat=1000, nbf=1000, exp=1120)
        else: claims["request_nonce"] = stage.native.b64(b"x" * 24)
        path.write_bytes(stage.canonical({"manifest": signed(f.key, claims)}))
        plan["current_native_manifest_sha256"] = stage.sha(path.read_bytes())
    with pytest.raises(ValueError): stage.prepare(f.project, plan, f.output)
    assert not f.output.exists()


@pytest.mark.parametrize("name", [
    "evidence/rollback-context/.env", "evidence/rollback-context/.git/config",
    "evidence/rollback-context/nested/.dockerignore", "evidence/.dockerignore",
    "app/.dockerignore", ".hidden", ".hidden/.dockerignore",
])
def test_hidden_evidence_exception_is_exact(tmp_path, name):
    data = b"fixture only"
    write(tmp_path / name, data)
    inventory = {name: {"sha256": stage.sha(data), "size": len(data)}}
    with pytest.raises(ValueError, match="Hidden deployment input refused"):
        stage.files_from_inventory(tmp_path, inventory, required=(), rollback_evidence=True)


def test_rollback_dockerignore_is_not_allowed_as_base_input(tmp_path):
    name = "evidence/rollback-context/.dockerignore"
    data = b"fixture only"
    write(tmp_path / name, data)
    inventory = {name: {"sha256": stage.sha(data), "size": len(data)}}
    with pytest.raises(ValueError, match="Hidden deployment input refused"):
        stage.files_from_inventory(tmp_path, inventory, required=())
    assert stage.files_from_inventory(tmp_path, inventory, required=(), rollback_evidence=True) == {name: data}


def test_backend_inventory_covers_committed_incident_handler():
    files = stage.backend(ROOT)
    assert files["app/security_incidents.py"] == (ROOT / "auth-service/app/security_incidents.py").read_bytes()
    assert {Path(name).name for name in files if name.endswith(".py")} == {
        path.name for path in (ROOT / "auth-service/app").glob("*.py")}
