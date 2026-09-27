"""Stage an isolated product-scoped server build context; never deploy or read credentials."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import stat
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = "qb0f90cf11f4d1d52"
# Healthy v66 Owner-beta baseline before adding the creator's Sahur pack.
ROLLBACK_IMAGE = "registry.fly.io/qb0f90cf11f4d1d52:deployment-01M1Y262F14D60201MFKS8P3EG"
APP_FILES = (
    "__init__.py", "beta_delivery.py", "config.py", "database.py", "main.py",
    "request_limits.py", "security.py", "products.py", "license_reset.py", "license_operator.py",
    "api_responses.py", "dpop.py", "native_manifest.py", "token_revocation.py", "static/admin.css",
    "templates/cloud_configs.html", "templates/dashboard.html", "templates/flags.html",
    "templates/license_detail.html", "templates/login.html", "templates/users.html",
)
PRODUCTION_FILES = (
    "celeste-client-1.0.0-configured.jar", "CelesteLoader.jar", "chunk-a.bin", "chunk-b.bin",
    "mixinextras-0.5.4.jar", "stage-two.bin", "stage-three.bin", "stage-four.bin",
    "release-manifest.json",
)
BETA_FILES = {"bootstrap-a": "bootstrap-a.jar", "bootstrap-b": "bootstrap-b.bin",
              "payload-a": "payload-a.bin", "payload-b": "payload-b.bin"}
HEX = re.compile(r"[0-9a-f]{64}\Z")
IDENTITY = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}\Z")
SECRET_PATTERNS = (
    r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----",
    r"(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16})",
    r"(?:sk-(?:proj-)?[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,})",
    r"https?://[^/\s:@]+:[^/\s@]+@",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_path(root: Path, name: str, *, exists: bool = True) -> Path:
    relative = Path(name)
    require(not relative.is_absolute() and name and "\\" not in name
            and ":" not in name and all(p not in {".", ".."} for p in relative.parts),
            "Unsafe staged relative path")
    path = root / relative
    for part in (root, *path.parents, path):
        require(not part.is_symlink() and not getattr(part, "is_junction", lambda: False)(),
                "Links/junctions are not permitted in staging paths")
    require(path.resolve().is_relative_to(root.resolve()), "Staging path escaped its root")
    if exists:
        info = path.stat()
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, f"Not a single regular file: {name}")
    return path


def read_file(root: Path, name: str, maximum: int = 160 * 1024 * 1024) -> bytes:
    path = safe_path(root, name)
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    require(len(data) <= maximum, f"File exceeds staging limit: {name}")
    return data


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key")
        result[key] = value
    return result


def parsed(data: bytes) -> dict:
    result = json.loads(data, object_pairs_hook=strict_object)
    require(isinstance(result, dict), "Expected JSON object")
    return result


def public_text(data: bytes, name: str) -> None:
    text = data.decode("utf-8")
    require(not any(re.search(pattern, text) for pattern in SECRET_PATTERNS),
            f"Secret-pattern check failed (value redacted): {name}")


def checked(data: bytes, digest: str, size: int | None, name: str) -> bytes:
    require(isinstance(digest, str) and HEX.fullmatch(digest) is not None,
            f"Invalid digest: {name}")
    require(sha(data) == digest and (size is None or type(size) is int and len(data) == size),
            f"Hash/size mismatch: {name}")
    return data


def production_snapshot(root: Path, manifest_digest: str, legacy_digest: str) -> tuple[dict, dict]:
    data = read_file(root, "release-manifest.json", 16_384)
    checked(data, manifest_digest, None, "production manifest pin")
    public_text(data, "release-manifest.json")
    manifest = parsed(data)
    require(manifest.get("protocol") == "cst-auth-1" and IDENTITY.fullmatch(manifest.get("release", ""))
            and HEX.fullmatch(manifest.get("protectedDigest", "")), "Invalid production identity")
    expected = {"celeste-client-1.0.0-configured.jar": (manifest["jarSha256"], manifest["jarSize"])}
    for key, filename in (("bootstrap", "mixinextras-0.5.4.jar"), ("loader", "stage-two.bin"),
                          ("loaderThree", "stage-three.bin"), ("loaderFour", "stage-four.bin")):
        entry = manifest[key]
        require(entry["file"] == filename, f"Unexpected production filename: {key}")
        expected[filename] = (entry["sha256"], entry["size"])
    require(isinstance(manifest["chunks"], list) and len(manifest["chunks"]) == 2,
            "Production requires exactly two chunks")
    for entry, filename in zip(manifest["chunks"], ("chunk-a.bin", "chunk-b.bin")):
        require(entry["file"] == filename, "Unexpected production chunk filename")
        expected[filename] = (entry["sha256"], entry["size"])
    expected["CelesteLoader.jar"] = (legacy_digest, None)
    files = {"release-manifest.json": data}
    for filename, (digest, size) in expected.items():
        files[filename] = checked(read_file(root, filename), digest, size, filename)
    require(set(files) == set(PRODUCTION_FILES), "Production allowlist mismatch")
    return manifest, files


def beta_snapshot(root: Path, production: dict) -> tuple[dict, dict]:
    data = read_file(root, "beta-release.json", 16_384)
    public_text(data, "beta-release.json")
    catalog = parsed(data)
    require(set(catalog) == {"schema", "release_id", "client_version", "runtime_sha256", "artifacts"}
            and catalog["schema"] == "celeste-beta-release-2", "Invalid beta catalog schema")
    identity, version, digest = catalog["release_id"], catalog["client_version"], catalog["runtime_sha256"]
    require(isinstance(identity, str) and IDENTITY.fullmatch(identity)
            and identity != production["release"], "Beta requires a distinct release identity")
    require(isinstance(version, str) and 1 <= len(version) <= 40 and version.isascii()
            and all(33 <= ord(c) <= 126 for c in version), "Invalid beta client version")
    require(isinstance(digest, str) and HEX.fullmatch(digest)
            and digest != production["protectedDigest"], "Beta requires a distinct canonical runtime digest")
    require(isinstance(catalog["artifacts"], list) and len(catalog["artifacts"]) == 4,
            "Beta requires exactly four artifacts")
    files, seen, total = {"beta-release.json": data}, set(), 0
    for entry in catalog["artifacts"]:
        require(isinstance(entry, dict) and set(entry) == {"id", "role", "filename", "sha256", "size"},
                "Invalid beta artifact fields")
        identity = entry["id"]
        require(isinstance(identity, str) and identity in BETA_FILES and identity not in seen,
                "Unexpected/duplicate beta artifact")
        filename = BETA_FILES[identity]
        require(entry["filename"] == filename
                and entry["role"] == ("bootstrap" if identity.startswith("bootstrap") else "payload")
                and type(entry["size"]) is int and 0 < entry["size"] <= 64 * 1024 * 1024,
                "Invalid beta artifact metadata")
        files[filename] = checked(read_file(root, filename, 64 * 1024 * 1024),
                                  entry["sha256"], entry["size"], filename)
        total += entry["size"]
        seen.add(identity)
    require(total <= 128 * 1024 * 1024 and seen == set(BETA_FILES), "Beta exceeds release limits")
    return catalog, files


def deployment_files(server: Path, release_id: str) -> dict[str, bytes]:
    files = {f"app/{name}": read_file(server, f"app/{name}", 2 * 1024 * 1024) for name in APP_FILES}
    for name in ("requirements.txt", "entrypoint.sh"):
        files[name] = read_file(server, name, 64 * 1024)
    original_docker = read_file(server, "Dockerfile", 64 * 1024).decode("utf-8")
    docker = original_docker.replace("\r\n", "\n")
    anchor = "COPY releases ./releases\n"
    require(docker.count(anchor) == 1 and "WORKDIR /srv/auth\n" in docker
            and "USER auth\n" in docker and "owner-beta" not in docker,
            "Production Dockerfile changed; review staging derivation")
    addition = "COPY --chown=auth:auth owner-beta ./owner-beta\nRUN chmod -R a=rX ./owner-beta\n"
    files["Dockerfile"] = docker.replace(anchor, anchor + addition, 1).encode("utf-8")
    original_fly = read_file(server, "fly.toml", 64 * 1024).decode("utf-8").replace("\r\n", "\n")
    before = tomllib.loads(original_fly)
    require(before.get("app") == APP and before.get("env", {}).get("CELESTE_ARTIFACT_DIR") == "/srv/auth/releases",
            "Unexpected production app/artifact directory")
    require(all(name not in before["env"] for name in ("CELESTE_PLUS_ENABLED", "CELESTE_BETA_RELEASE_DIR", "CELESTE_BETA_OWNER_UID"))
            and original_fly.count("[env]\n") == 1, "Production config already contains beta settings")
    beta_path = f"/srv/auth/owner-beta/{release_id}"
    addition = f'  CELESTE_PLUS_ENABLED = "true"\n  CELESTE_BETA_RELEASE_DIR = "{beta_path}"\n'
    fly = original_fly.replace("[env]\n", "[env]\n" + addition, 1)
    after = tomllib.loads(fly)
    del after["env"]["CELESTE_PLUS_ENABLED"], after["env"]["CELESTE_BETA_RELEASE_DIR"]
    require(before == after, "Staged Fly config changed production settings")
    files["fly.toml"] = fly.encode("utf-8")
    # Default deny: even a later accidentally added credential file is not sent to the builder.
    # Docker traverses excluded parents when an exact descendant exception exists.
    # Do not negate directories: that would reopen all their unlisted descendants.
    rules = ["**", "!Dockerfile", "!.dockerignore", "!requirements.txt", "!entrypoint.sh"]
    rules += [f"!app/{name}" for name in APP_FILES]
    rules += [f"!releases/{name}" for name in PRODUCTION_FILES]
    rules += [f"!owner-beta/{release_id}/{name}" for name in ("beta-release.json", *BETA_FILES.values())]
    files[".dockerignore"] = ("\n".join(rules) + "\n").encode("utf-8")
    for name, data in files.items():
        public_text(data, name)
    return files


def stage(project: Path, beta: Path, output: Path, manifest_digest: str, legacy_digest: str) -> dict:
    project, beta = project.resolve(strict=True), beta.absolute()
    output = output.absolute()
    allowed_root = project / "build/module-qa"
    require(output.resolve().is_relative_to(allowed_root.resolve()) and output.resolve() != allowed_root.resolve(),
            "Output must be a fresh child directory under build/module-qa")
    safe_path(allowed_root, output.relative_to(allowed_root).as_posix(), exists=False)
    require(not output.exists(), "Output already exists; use a fresh directory")
    require(not output.resolve().is_relative_to(beta.resolve())
            and not beta.resolve().is_relative_to(output.resolve()), "Input and output cannot overlap")
    production, prod_files = production_snapshot(project / "auth-service/releases", manifest_digest, legacy_digest)
    catalog, beta_files = beta_snapshot(beta, production)
    files = deployment_files(project / "auth-service", catalog["release_id"])
    files.update({f"releases/{name}": data for name, data in prod_files.items()})
    files.update({f"owner-beta/{catalog['release_id']}/{name}": data for name, data in beta_files.items()})
    output.mkdir(parents=True, exist_ok=False)
    for name, data in files.items():
        target = safe_path(output, name, exists=False)
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as stream:
            stream.write(data)
    receipt = {"schema": "celeste-plus-server-stage-1", "status": "staged-not-deployed-or-game-tested",
               "app": APP, "product": "celeste_plus", "beta_release": catalog["release_id"],
               "production_release": production["release"], "production_manifest_sha256": manifest_digest,
               "legacy_loader_sha256": legacy_digest, "rollback_image_at_audit": ROLLBACK_IMAGE,
               "files": {name: {"sha256": sha(data), "size": len(data)} for name, data in sorted(files.items())}}
    with (output / "staging-report.json").open("x", encoding="utf-8") as stream:
        json.dump(receipt, stream, indent=2, sort_keys=True)
        stream.write("\n")
    verify(output)
    return receipt


def verify(context: Path) -> dict:
    context = context.absolute()
    receipt = parsed(read_file(context, "staging-report.json", 128 * 1024))
    require(receipt.get("schema") == "celeste-plus-server-stage-1" and receipt.get("product") == "celeste_plus"
            and isinstance(receipt.get("beta_release"), str) and IDENTITY.fullmatch(receipt["beta_release"]),
            "Invalid staging receipt")
    allowlist = {"Dockerfile", "fly.toml", ".dockerignore", "requirements.txt", "entrypoint.sh"}
    allowlist.update(f"app/{name}" for name in APP_FILES)
    allowlist.update(f"releases/{name}" for name in PRODUCTION_FILES)
    allowlist.update(f"owner-beta/{receipt['beta_release']}/{name}"
                     for name in ("beta-release.json", *BETA_FILES.values()))
    require(set(receipt["files"]) == allowlist, "Staging receipt differs from exact allowlist")
    expected = set(receipt["files"]) | {"staging-report.json"}
    actual = set()
    for path in context.rglob("*"):
        require(not path.is_symlink() and not getattr(path, "is_junction", lambda: False)(),
                "Links/junctions are not permitted in the context")
        if path.is_file():
            actual.add(path.relative_to(context).as_posix())
    require(actual == expected, "Context gained/lost files; do not deploy")
    for name, item in receipt["files"].items():
        checked(read_file(context, name), item["sha256"], item["size"], name)
    production_snapshot(context / "releases", receipt["production_manifest_sha256"], receipt["legacy_loader_sha256"])
    production = parsed(read_file(context / "releases", "release-manifest.json", 16_384))
    catalog, _ = beta_snapshot(context / "owner-beta" / receipt["beta_release"], production)
    require(catalog["release_id"] == receipt["beta_release"], "Staged catalog/receipt identity differs")
    config = tomllib.loads(read_file(context, "fly.toml", 64 * 1024).decode("utf-8"))
    require(config["app"] == APP and config["env"]["CELESTE_PLUS_ENABLED"] == "true"
            and "CELESTE_BETA_OWNER_UID" not in config["env"]
            and config["env"]["CELESTE_BETA_RELEASE_DIR"] == f"/srv/auth/owner-beta/{receipt['beta_release']}"
            and config["env"]["CELESTE_ARTIFACT_DIR"] == "/srv/auth/releases", "Staged routing changed")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("stage")
    prep.add_argument("--beta-release", type=Path, required=True)
    prep.add_argument("--output", type=Path, required=True)
    prep.add_argument("--production-manifest-sha256", required=True)
    prep.add_argument("--legacy-loader-sha256", required=True)
    check = sub.add_parser("verify")
    check.add_argument("--context", type=Path, required=True)
    args = parser.parse_args()
    try:
        receipt = (stage(ROOT, args.beta_release, args.output, args.production_manifest_sha256,
                         args.legacy_loader_sha256) if args.command == "stage" else verify(args.context))
    except (OSError, ValueError, KeyError, TypeError) as exc:
        parser.exit(1, f"Staging refused: {exc}\n")
    print(json.dumps({"status": receipt["status"], "product": "celeste_plus", "beta_release": receipt["beta_release"],
                      "production_release": receipt["production_release"], "files": len(receipt["files"])}))


if __name__ == "__main__":
    main()
