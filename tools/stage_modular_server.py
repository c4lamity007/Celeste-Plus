"""Prepare and seal an exact same-origin Plus server context. Never deploy or sign.

The base inventory is caller-reviewed and pinned. Aggregate approval uses the
existing service response-signing authority; private keys never enter this tool.
The previous image/config remains an explicit inactive emergency rollback.
"""
from __future__ import annotations
import argparse
import ast
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import time
import tomllib

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools/packaging"))
import module_release as modules
import register_native_release as native

SPEC = importlib.util.spec_from_file_location("legacy_stage_helpers", ROOT / "beta-lab/release-package/stage_server.py")
legacy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(legacy)
APP = "qb0f90cf11f4d1d52"
ORIGIN = "https://" + APP + ".fly.dev"
HEADER = {"alg": "EdDSA", "kid": "celeste-release-1", "typ": "CST"}
APP_FILES = tuple("""__init__.py api_responses.py artifact_envelope.py artifact_transfer.py auth_errors.py
auth_lifecycle.py beta_delivery.py config.py database.py dpop.py grant_routes.py license_operator.py
license_reset.py main.py module_delivery.py native_download.py native_manifest.py products.py
release_assignment.py release_cache.py request_limits.py secret_files.py security.py token_revocation.py
transport_policy.py static/admin.css templates/cloud_configs.html templates/dashboard.html
templates/flags.html templates/license_detail.html templates/login.html templates/users.html""".split())
require, sha, parsed, read_file = legacy.require, legacy.sha, legacy.parsed, legacy.read_file


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def pinned(path, digest, maximum=8 * 1024 * 1024):
    path = Path(path).absolute()
    data = read_file(path.parent, path.name, maximum)
    require(re.fullmatch(r"[0-9a-f]{64}", digest or "") and sha(data) == digest, "Reviewed input pin differs")
    return data


def files_from_inventory(root, inventory, *, required=("Dockerfile", "fly.toml", "requirements.txt", "entrypoint.sh"),
                         rollback_evidence=False):
    require(isinstance(inventory, dict) and 1 <= len(inventory) <= 4096, "Invalid base inventory")
    result = {}
    for name, record in inventory.items():
        require(isinstance(record, dict) and set(record) == {"sha256", "size"}, "Invalid inventory record")
        require(not any(part.startswith(".") for part in Path(name).parts)
                or name == ".dockerignore"
                or rollback_evidence and name == "evidence/rollback-context/.dockerignore",
                "Hidden deployment input refused")
        data = read_file(root, name, 256 * 1024 * 1024)
        require(len(data) == record["size"] and sha(data) == record["sha256"], "Base context changed: " + name)
        result[name] = data
    require(set(required) <= result.keys(), "Incomplete base context")
    return result


def backend(project):
    source = project / "auth-service"
    files = {"app/" + name: read_file(source, "app/" + name, 2 * 1024 * 1024) for name in APP_FILES}
    modules_present = {Path(name).stem for name in APP_FILES if name.endswith(".py")}
    for name, data in files.items():
        legacy.public_text(data, name)
        if name.endswith(".py"):
            for node in ast.walk(ast.parse(data)):
                if isinstance(node, ast.ImportFrom) and node.level:
                    require(node.level == 1 and node.module and node.module.split(".")[0] in modules_present,
                            "Unlisted backend dependency: " + name)
    for name in ("requirements.txt", "entrypoint.sh"):
        files[name] = read_file(source, name, 64 * 1024)
        legacy.public_text(files[name], name)
    actual_python = {path.name for path in (source / "app").glob("*.py")}
    require(actual_python == {name for name in APP_FILES if name.endswith(".py")}, "Review changed backend Python file inventory")
    return files


def candidate_snapshot(directory, receipt_path, receipt_sha256):
    receipt_data = pinned(receipt_path, receipt_sha256, 16 * 1024 * 1024)
    receipt = parsed(receipt_data)
    require(receipt.get("schema") == "celeste-plus-java-protection-run-1"
            and receipt.get("status") == "protected-verified-candidate-not-published"
            and "module_delivery" in receipt, "Complete protected modular candidate required")
    beta = parsed(read_file(directory, "beta-release.json", 16384))
    # Reuse the exact four-part verifier; these parts contain resident code only.
    beta, files = legacy.beta_snapshot(directory, {"release": "standard-not-the-plus-release", "protectedDigest": "0" * 64})
    catalog_data = read_file(directory, "module-release.json", 512 * 1024)
    catalog = parsed(catalog_data)
    require(beta["release_id"] == catalog["release_id"] == receipt["release_id"]
            and beta["runtime_sha256"] == catalog["runtime_sha256"], "Candidate host/module contracts differ")
    artifacts = {item["filename"]: read_file(directory, "modules/" + item["filename"], modules.MAX_MODULE)
                 for item in catalog["modules"]}
    resident = {}
    for name in ("payload-a.bin", "payload-b.bin"):
        entries = modules.archive_entries(files[name], max_entry=64 * 1024 * 1024)
        require(not resident.keys() & entries.keys(), "Duplicate resident entries")
        resident.update(entries)
    require(not any(name.endswith(".class") and name.startswith(modules.IMPLEMENTATION) for name in resident),
            "Resident contains module implementations")
    modules.verify_catalog(catalog, artifacts, resident.get(modules.ANCHOR))
    files["module-release.json"] = catalog_data
    files.update({"modules/" + name: data for name, data in artifacts.items()})
    for name, data in files.items():
        record = receipt["artifacts"].get(name)
        require(record == {"sha256": sha(data), "bytes": len(data)}, "Protection receipt/artifact mismatch: " + name)
    require(receipt["module_delivery"]["executable_root_sha256"] == catalog["executable_root_sha256"], "Protection root differs")
    return beta, catalog, files, receipt_data


def update_env(text, updates):
    before = tomllib.loads(text)
    require(before.get("app") == APP and "[env]" in text, "Wrong base application")
    require(not any(name in before.get("env", {}) for name in (
        "CELESTE_SIGNING_PRIVATE_KEY", "CELESTE_KEY_PEPPER", "CELESTE_SESSION_SECRET", "CELESTE_MODULE_KEY_ENCRYPTION_KEY",
        "CELESTE_ADMIN_PASSWORD_HASH")),
        "Private secrets must not be in the staged config")
    lines = text.splitlines()
    start = lines.index("[env]") + 1
    stop = next((i for i in range(start, len(lines)) if lines[i].lstrip().startswith("[")), len(lines))
    block = [line for line in lines[start:stop] if not any(re.match(r"\s*" + re.escape(key) + r"\s*=", line) for key in updates)]
    block = ["  " + key + " = " + json.dumps(value) for key, value in updates.items()] + block
    changed = "\n".join(lines[:start] + block + lines[stop:]) + "\n"
    after = tomllib.loads(changed)
    expected = json.loads(json.dumps(before))
    expected["env"].update(updates)
    require(after == expected, "Unexpected base config changes")
    return changed.encode()


def publication(data, key, expected_digest, expected_size):
    wrapper = parsed(data)
    require(set(wrapper) == {"publication"}, "Signed native publication required")
    claims = native.verify_token(wrapper["publication"], key, {"alg": "EdDSA", "typ": "CST"})
    require(set(claims) == {"aud", "product", "platform", "iat", "nbf", "exp", "sequence", "binaries"}
            and claims.get("aud") == "celeste-native-publication" and claims.get("product") == "celeste_plus"
            and claims.get("platform") == "windows-x64"
            and all(type(claims[name]) is int for name in ("iat", "nbf", "exp", "sequence"))
            and claims["iat"] >= 0 and claims["nbf"] == claims["iat"] and claims["sequence"] > 0
            and claims["iat"] < claims["exp"] <= claims["iat"] + 300
            and isinstance(claims["binaries"], list) and 1 <= len(claims["binaries"]) <= 4,
            "Wrong native publication contract")
    entries = [native.binary_metadata(item) for item in claims.get("binaries", [])]
    selected = [item for item in entries if item["sha256"] == expected_digest]
    require(len(selected) == 1 and selected[0]["size"] == expected_size,
            "Matching loader SHA/size is absent from signed native policy")
    require(len({item["sha256"] for item in entries}) == len(entries), "Duplicate native loader entries")
    return claims, selected[0]


def prepare(project, plan, output):
    require(plan.get("schema") == "celeste-plus-modular-stage-plan-1" and plan.get("mode") in {"qa", "cutover"}, "Invalid staging plan")
    allowed = project / "build/module-qa"
    output = output.absolute()
    require(output.resolve().is_relative_to(allowed.resolve()) and output.resolve() != allowed.resolve()
            and not output.exists(), "Output must be a fresh build/module-qa child")
    base = Path(plan["base_context"]).resolve(strict=True)
    inventory = parsed(pinned(plan["base_inventory"], plan["base_inventory_sha256"]))
    image_base = plan.get("base_image")
    files = files_from_inventory(base, inventory, required=("Dockerfile", "fly.toml") if image_base else
                                 ("Dockerfile", "fly.toml", "requirements.txt", "entrypoint.sh"))
    base_files = dict(files)
    legacy.public_text(files["fly.toml"], "base fly.toml")
    config = tomllib.loads(files["fly.toml"].decode())
    require(config.get("app") == APP and config["env"].get("CELESTE_PLUS_AUTH_PROTOCOL", "2") == "2", "Wrong base contract")
    original_config = files["fly.toml"]
    original_backend = {name: data for name, data in files.items() if name.startswith("app/") or name in {"requirements.txt", "entrypoint.sh"}}
    require(re.fullmatch(r"registry\.fly\.io/" + APP + r"@sha256:[0-9a-f]{64}", plan["rollback_image"]), "Immutable rollback image required")
    require(not image_base or image_base == plan["rollback_image"], "Image base must be the exact inactive rollback image")
    require(plan["rollback_contract"] in {"legacy-full-client", "independent-modules"}, "Explicit rollback contract required")
    base_modular = config["env"].get("CELESTE_PLUS_REQUIRE_MODULE_DELIVERY", "false") == "true"
    require(base_modular == (plan["rollback_contract"] == "independent-modules"), "Rollback contract and backend policy differ")
    candidate, catalog, additions, receipt = candidate_snapshot(Path(plan["candidate_dir"]), plan["protection_receipt"], plan["protection_receipt_sha256"])
    require(candidate["release_id"] not in config["env"]["CELESTE_BETA_RELEASE_DIR"].split("/"), "Candidate must be separate from active base")
    native_key = native.Ed25519PublicKey.from_public_bytes(native.decode(config["env"]["CELESTE_NATIVE_RELEASE_PUBLIC_KEY"]))
    loader = read_file(Path(plan["loader_binary"]).parent, Path(plan["loader_binary"]).name, 256 * 1024 * 1024)
    new_policy = pinned(plan["native_publication"], plan["native_publication_sha256"])
    old_policy = pinned(plan["rollback_native_publication"], plan["rollback_native_publication_sha256"])
    prior_digest = config["env"]["CELESTE_PLUS_DOWNLOAD_SHA256"]
    if image_base:
        prior_loader_path = Path(plan["rollback_loader_binary"])
        prior_loader = read_file(prior_loader_path.parent, prior_loader_path.name, 256 * 1024 * 1024)
        require(sha(prior_loader) == prior_digest, "Rollback loader bytes differ from base config")
    else:
        prior_loaders = [data for name, data in files.items() if name.endswith("/" + prior_digest + ".exe") and sha(data) == prior_digest]
        require(len(prior_loaders) == 1, "Base context lacks exact rollback loader bytes")
        prior_loader = prior_loaders[0]
    old_claims, old_binary = publication(old_policy, native_key, prior_digest, len(prior_loader))
    new_claims, _ = publication(new_policy, native_key, sha(loader), len(loader))
    reuse_loader = loader == prior_loader
    current_native_manifest = None
    if reuse_loader:
        require(new_policy == old_policy, "Unchanged loader reuse requires the identical approved native policy")
        current_native_manifest = pinned(plan["current_native_manifest"], plan["current_native_manifest_sha256"])
        native.verify_live_manifest(parsed(current_native_manifest), old_claims, plan["current_native_request_nonce"])
    else:
        require(new_claims["sequence"] > old_claims["sequence"], "Candidate native sequence must advance the retained policy")
        require(all(item in new_claims["binaries"] for item in old_claims["binaries"]),
                "Candidate native policy must retain every previously approved loader")
    rollback_manifest = pinned(plan["rollback_manifest"], plan["rollback_manifest_sha256"])
    signed_prior = native.verify_token(parsed(rollback_manifest)["manifest"],
        native.Ed25519PublicKey.from_public_bytes(native.decode(native.SERVER_PUBLIC_KEY)), HEADER)
    require(signed_prior.get("aud") == "celeste-beta-manifest" and signed_prior.get("product") == "celeste_plus",
            "Historical signed Plus manifest required")
    prior_release = signed_prior["release_id"]
    require(config["env"]["CELESTE_BETA_RELEASE_DIR"].rstrip("/").endswith("/" + prior_release), "Rollback config and signed host differ")
    prior_catalogs = [(name, parsed(data)) for name, data in files.items() if name.endswith("/beta-release.json")
                      and parsed(data).get("release_id") == prior_release]
    require(len(prior_catalogs) == 1, "Exact rollback host bundle required")
    prior_name, prior_catalog = prior_catalogs[0]
    prior_root = base / Path(prior_name).parent
    verified_prior, _ = legacy.beta_snapshot(prior_root, {"release": "standard-not-plus", "protectedDigest": "0" * 64})
    public_prior = {**verified_prior, "artifacts": [{"id": row["id"], "role": row["role"], "sha256": row["sha256"], "size": row["size"],
        "url": "/v1/beta/artifacts/" + prior_release + "/" + row["id"]} for row in verified_prior["artifacts"]]}
    public_prior["artifacts"].sort(key=lambda item: item["id"])
    # beta_snapshot returns disk names; compare the signed server's public representation.
    require(all(signed_prior.get(key) == value for key, value in public_prior.items()), "Rollback bytes differ from signed host manifest")
    if image_base:
        # The reviewed deployed image retains Standard, the prior Plus host, and native files.
        # Build from a new import root so old app files cannot shadow the reviewed backend.
        files = {}
    ids = plan.get("qa_installations", [])
    require(isinstance(ids, list) and len(ids) == len(set(ids)) and len(ids) <= 16
            and all(re.fullmatch(r"[A-Za-z0-9_-]{32,48}", identity) for identity in ids), "Invalid explicit QA installation list")
    require(plan["mode"] != "qa" or ids, "QA staging requires the reviewed installation assignment")
    destination = "/srv/auth/module-candidates/" + candidate["release_id"]
    updates = {"CELESTE_PLUS_ENABLED": "true", "CELESTE_PLUS_AUTH_PROTOCOL": "2"}
    if plan["mode"] == "qa":
        updates.update(CELESTE_PLUS_QA_BETA_RELEASE_DIR=destination, CELESTE_PLUS_QA_MODULE_RELEASE_DIR=destination,
                       CELESTE_PLUS_QA_INSTALLATIONS=",".join(ids))
    else:
        updates.update(CELESTE_BETA_RELEASE_DIR=destination, CELESTE_MODULE_RELEASE_DIR=destination,
                       CELESTE_PLUS_REQUIRE_MODULE_DELIVERY="true", CELESTE_PLUS_QA_INSTALLATIONS="",
                       CELESTE_PLUS_QA_BETA_RELEASE_DIR="", CELESTE_PLUS_QA_MODULE_RELEASE_DIR="",
                       CELESTE_PLUS_DOWNLOAD_SHA256=sha(loader))
        native_folder = "candidate-native" if image_base else plan["public_native_context_dir"]
        require(re.fullmatch(r"[A-Za-z0-9_/-]+", native_folder) and ".." not in native_folder,
                "Invalid public native context folder")
        # The reviewed base Dockerfile must already copy this exact directory.
        require(image_base or re.search(r"(?m)^COPY(?: --[^ ]+)* " + re.escape(native_folder) + r" ", files["Dockerfile"].decode()),
                "Base Dockerfile does not copy the selected public native folder")
        if not reuse_loader:
            files[native_folder + "/" + sha(loader) + ".exe"] = loader
    files = {name: data for name, data in files.items() if not name.startswith("app/")}
    files.update(backend(project))
    files.update({"module-candidate/" + name: data for name, data in additions.items()})
    if image_base:
        docker = ("FROM " + image_base + "\nUSER root\nWORKDIR /srv/auth/module-backend\n"
                  "COPY requirements.txt ./requirements.txt\n"
                  "RUN python -m pip install --no-cache-dir -r requirements.txt\n"
                  "COPY --chown=10001:10001 app ./app\n"
                  "COPY entrypoint.sh /usr/local/bin/celeste-auth-entrypoint\n"
                  "RUN chmod 0755 /usr/local/bin/celeste-auth-entrypoint\n"
                  "COPY --chown=10001:10001 module-candidate " + destination + "\n")
        if plan["mode"] == "cutover" and not reuse_loader:
            require(config["env"].get("CELESTE_PUBLIC_NATIVE_DIR") == "/srv/auth/public-native",
                    "Review changed inherited native directory")
            docker += "COPY --chown=10001:10001 candidate-native /srv/auth/public-native\n"
        docker += 'USER auth\nENTRYPOINT ["/usr/local/bin/celeste-auth-entrypoint"]\n'
        files["Dockerfile"] = docker.encode()
    else:
        docker = files["Dockerfile"].decode().replace("\r\n", "\n")
        require(docker.count("USER auth\n") == 1, "Review changed base Dockerfile")
        files["Dockerfile"] = docker.replace("USER auth\n", "COPY --chown=auth:auth module-candidate " + destination + "\nUSER auth\n").encode()
    files["fly.toml"] = update_env(original_config.decode(), updates)
    # These evidence/rollback files are retained locally and never copied into the image.
    evidence = {"rollback.fly.toml": original_config, "rollback-native-publication.json": old_policy,
                "candidate-native-publication.json": new_policy, "protection-receipt.json": receipt,
                "rollback-manifest.json": rollback_manifest, "candidate-loader.exe": loader,
                "rollback-loader.exe": prior_loader, "base-inventory.json": canonical(inventory)}
    if image_base:
        evidence.update({"rollback-context/" + name: data for name, data in base_files.items()})
    if current_native_manifest is not None:
        evidence["current-native-manifest.json"] = current_native_manifest
        evidence["current-native-request.json"] = canonical({"request_nonce": plan["current_native_request_nonce"]})
    evidence.update({"rollback-backend/" + name: data for name, data in original_backend.items()})
    image_files = sorted(name for name in files if name != ".dockerignore")
    files[".dockerignore"] = ("**\n!.dockerignore\n" + "".join("!" + name + "\n" for name in image_files)).encode()
    files.update({"evidence/" + name: data for name, data in evidence.items()})
    manifest = {name: {"sha256": sha(data), "size": len(data)} for name, data in sorted(files.items())}
    document = {"schema": "celeste-plus-deployment-input-1", "product": "celeste_plus", "app": APP, "origin": ORIGIN,
                "mode": plan["mode"], "candidate_release": candidate["release_id"], "runtime_sha256": candidate["runtime_sha256"],
                "module_catalog_sha256": sha(canonical(catalog)), "rollback_contract": plan["rollback_contract"],
                "rollback_image": plan["rollback_image"], "base_image": image_base,
                "native_policy_action": "reuse-current-approved-policy" if reuse_loader else "register-retaining-prior-loaders",
                "files": manifest}
    for name in files:
        legacy.safe_path(output, name, exists=False)
    output.mkdir(parents=True)
    for name, data in files.items():
        target = legacy.safe_path(output, name, exists=False)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    (output / "deployment-input.json").write_bytes(canonical(document))
    return document


def verify(context, approval=None, public_key=None):
    document = parsed(read_file(context, "deployment-input.json", 2 * 1024 * 1024))
    require(document.get("schema") == "celeste-plus-deployment-input-1" and document.get("product") == "celeste_plus"
            and document.get("app") == APP and document.get("origin") == ORIGIN, "Wrong deployment identity")
    # Image-based preparation retains this exact base file as local rollback evidence.
    # Base inputs and every other hidden path still use the original rejection.
    files_from_inventory(context, document["files"], rollback_evidence=bool(document.get("base_image")))
    expected = set(document["files"]) | {"deployment-input.json"}
    if (context / "deployment-approval.json").exists(): expected.add("deployment-approval.json")
    actual = {path.relative_to(context).as_posix() for path in context.rglob("*") if path.is_file()}
    require(actual == expected, "Context has unreviewed files")
    if approval is not None:
        key = public_key or native.Ed25519PublicKey.from_public_bytes(native.decode(native.SERVER_PUBLIC_KEY))
        claims = native.verify_token(parsed(approval)["approval"], key, HEADER)
        require(set(claims) == {"aud", "product", "app", "deployment_sha256", "iat"}
                and claims["aud"] == "celeste-plus-deployment-approval" and claims["product"] == "celeste_plus"
                and claims["app"] == APP and claims["deployment_sha256"] == sha(canonical(document))
                and type(claims["iat"]) is int and 0 <= claims["iat"] <= int(time.time()) + 5,
                "Aggregate approval differs from exact staged deployment")
    return document


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare")
    prep.add_argument("--plan", type=Path, required=True)
    prep.add_argument("--plan-sha256", required=True)
    prep.add_argument("--output", type=Path, required=True)
    seal = sub.add_parser("seal")
    seal.add_argument("--context", type=Path, required=True)
    seal.add_argument("--approval", type=Path, required=True)
    check = sub.add_parser("verify")
    check.add_argument("--context", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            document = prepare(ROOT, parsed(pinned(args.plan, args.plan_sha256)), args.output)
            claims = {"aud": "celeste-plus-deployment-approval", "product": "celeste_plus", "app": APP,
                      "deployment_sha256": sha(canonical(document)), "iat": int(time.time())}
            print(json.dumps({"status": "prepared-unsigned-not-deployable", "claims_to_existing_signing_workflow": claims}))
        else:
            approval = read_file(args.approval.parent, args.approval.name, 16384) if args.command == "seal" else read_file(args.context, "deployment-approval.json", 16384)
            document = verify(args.context, approval)
            if args.command == "seal":
                target = args.context / "deployment-approval.json"
                require(not target.exists(), "Already sealed")
                target.write_bytes(approval)
            print(json.dumps({"status": "signed-context-verified-not-deployed", "mode": document["mode"],
                              "candidate_release": document["candidate_release"], "rollback_contract": document["rollback_contract"]}))
    except (OSError, ValueError, KeyError, TypeError) as failure:
        parser.exit(1, "Staging refused: " + str(failure) + "\n")


if __name__ == "__main__":
    main()
