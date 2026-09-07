"""Prepare the current Celeste+ runtime source from an exact Celeste commit and verified overlay.

Only a fresh build/runtime-core directory is written. Existing outputs are never
deleted or reused. This produces development source, not a published protected payload.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tarfile

REPOSITORY = "https://github.com/c4lamity007/Celeste.git"
LOCK_SCHEMA = "celeste-plus-core-lock-1"
OVERLAY_SCHEMA = "celeste-plus-runtime-overlay-1"
HASH = re.compile(r"[0-9a-f]{64}\Z")
COMMIT = re.compile(r"[0-9a-f]{40}\Z")
TEXT_EXTENSIONS = {".java", ".json", ".glsl", ".vsh", ".fsh"}
RESERVED = {"con", "prn", "aux", "nul", *(f"com{i}" for i in range(1, 10)), *(f"lpt{i}" for i in range(1, 10))}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def relative_path(name: str) -> PurePosixPath:
    require(isinstance(name, str) and name and "\\" not in name and ":" not in name and "\x00" not in name,
            "Invalid relative source path")
    path = PurePosixPath(name)
    require(not path.is_absolute() and path.as_posix() == name and all(part not in {"", ".", ".."} for part in path.parts),
            "Path escapes its source root")
    require(all(not part.endswith((" ", ".")) and part.split(".")[0].lower() not in RESERVED for part in path.parts),
            "Nonportable source path")
    return path


def plain_path(root: Path, relative: str, *, exists: bool = True) -> Path:
    path = relative_path(relative)
    target = root.joinpath(*path.parts)
    require(target.is_relative_to(root), "Path is outside the owned directory")
    current = root
    for part in (None, *path.parts):
        if part is not None:
            current = current / part
        try:
            metadata = current.lstat()
        except FileNotFoundError:
            continue
        require(not stat.S_ISLNK(metadata.st_mode) and not getattr(current, "is_junction", lambda: False)()
                and not getattr(metadata, "st_file_attributes", 0) & 0x400,
                "Links and reparse points are not allowed in runtime preparation")
    require(not exists or target.is_file(), "Required source file is missing")
    return target


def load_json(data: bytes) -> dict:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate JSON field")
            result[key] = value
        return result
    value = json.loads(data, object_pairs_hook=unique)
    require(isinstance(value, dict), "Expected an object")
    return value


def read_contract(repo: Path) -> tuple[dict, dict, dict[str, bytes]]:
    lock = load_json(plain_path(repo, "core.lock.json").read_bytes())
    require(set(lock) == {"schema", "repository", "commit", "overlayManifestSha256"}, "Unknown core lock fields")
    require(lock["schema"] == LOCK_SCHEMA and lock["repository"] == REPOSITORY, "Unrecognized core repository lock")
    require(isinstance(lock["commit"], str) and COMMIT.fullmatch(lock["commit"]), "Pin an exact reviewed Celeste commit in core.lock.json first")
    require(isinstance(lock["overlayManifestSha256"], str) and HASH.fullmatch(lock["overlayManifestSha256"]), "Invalid overlay manifest hash")
    encoded = plain_path(repo, "runtime-overlay/manifest.json").read_bytes()
    require(sha(encoded) == lock["overlayManifestSha256"], "Overlay manifest hash differs from core lock")
    manifest = load_json(encoded)
    require(set(manifest) == {"schema", "sourceState", "files"} and manifest["schema"] == OVERLAY_SCHEMA,
            "Unrecognized overlay manifest")
    require(manifest["sourceState"] == "current-development-source-not-published-payload", "Unknown runtime source state")
    require(isinstance(manifest["files"], list) and 0 < len(manifest["files"]) <= 4096, "Invalid overlay file count")
    content = {}
    folded = set()
    for entry in manifest["files"]:
        require(isinstance(entry, dict) and set(entry) == {"path", "sha256", "baseSha256"}, "Unknown overlay entry fields")
        name = entry["path"]
        path = relative_path(name)
        require(path.parts[:2] == ("src", "main") and path.suffix in TEXT_EXTENSIONS, "Overlay may contain only runtime source text")
        require(name.casefold() not in folded, "Duplicate or case-colliding overlay path")
        folded.add(name.casefold())
        require(isinstance(entry["sha256"], str) and HASH.fullmatch(entry["sha256"]), "Invalid overlay source hash")
        require(entry["baseSha256"] is None or isinstance(entry["baseSha256"], str) and HASH.fullmatch(entry["baseSha256"]), "Invalid base source hash")
        encoded = plain_path(repo, "runtime-overlay/" + name).read_bytes()
        require(len(encoded) <= 4 * 1024 * 1024 and b"\x00" not in encoded, "Overlay is not bounded source text")
        encoded.decode("utf-8")
        require(sha(encoded) == entry["sha256"], "Overlay source hash mismatch: " + name)
        content[name] = encoded
    return lock, manifest, content


def git(*args: str, cwd: Path | None = None, stdout=None) -> bytes | None:
    environment = os.environ.copy()
    environment["GIT_TERMINAL_PROMPT"] = "0"
    completed = subprocess.run(["git", *args], cwd=cwd, env=environment, check=True,
                               stdout=subprocess.PIPE if stdout is None else stdout, stderr=subprocess.PIPE)
    return completed.stdout


def validate_archive(archive: tarfile.TarFile, manifest: dict) -> dict[str, tarfile.TarInfo]:
    members = {}
    folded = set()
    total = 0
    for member in archive.getmembers():
        name = member.name.rstrip("/")
        path = relative_path(name)
        require(path.parts[0] not in {".prepare", ".celeste-plus-runtime.json", ".git"}, "Core archive collides with preparation metadata")
        require(member.isfile() or member.isdir(), "Core archive contains a link or special file")
        require(name.casefold() not in folded, "Core archive contains colliding paths")
        folded.add(name.casefold())
        require(0 <= member.size <= 128 * 1024 * 1024, "Core archive file exceeds bounds")
        total += member.size
        require(total <= 1024 * 1024 * 1024 and len(folded) <= 100000, "Core archive exceeds bounds")
        members[name] = member
    core_spelling = {name.casefold(): name for name in members}
    for entry in manifest["files"]:
        require(core_spelling.get(entry["path"].casefold(), entry["path"]) == entry["path"],
                "Overlay path case differs from the locked core")
        original = members.get(entry["path"])
        if entry["baseSha256"] is None:
            require(original is None, "Overlay addition already exists in locked core: " + entry["path"])
        else:
            require(original is not None and original.isfile(), "Overlay base source is absent: " + entry["path"])
            with archive.extractfile(original) as stream:
                require(sha(stream.read()) == entry["baseSha256"], "Base source hash mismatch: " + entry["path"])
    return members


def extract_archive(archive: tarfile.TarFile, members: dict, destination: Path) -> None:
    for name, member in members.items():
        target = plain_path(destination, name, exists=False)
        if member.isdir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target = plain_path(destination, name, exists=False)
        with archive.extractfile(member) as source, target.open("xb") as output:
            shutil.copyfileobj(source, output)
        if os.name != "nt":
            target.chmod(0o755 if member.mode & 0o111 else 0o644)


def prepare(repo: Path, core_checkout: Path | None = None) -> Path:
    repo = repo.resolve(strict=True)
    lock, manifest, overlay = read_contract(repo)
    if core_checkout is not None:
        core_checkout = core_checkout.resolve(strict=True)
        resolved = git("rev-parse", "--verify", lock["commit"] + "^{commit}", cwd=core_checkout).decode().strip()
        require(resolved == lock["commit"], "Local core checkout lacks the exact locked commit")
    build = plain_path(repo, "build", exists=False)
    build.mkdir(exist_ok=True)
    destination = plain_path(repo, "build/runtime-core", exists=False)
    require(not destination.exists(), "build/runtime-core already exists; use a fresh repository or move that output aside explicitly")
    destination.mkdir(exist_ok=False)
    work = destination / ".prepare"
    work.mkdir()
    if core_checkout is None:
        core_checkout = work / "core-source"
        git("clone", "--no-checkout", "--filter=blob:none", "--no-tags", REPOSITORY, str(core_checkout))
        resolved = git("rev-parse", "--verify", lock["commit"] + "^{commit}", cwd=core_checkout).decode().strip()
        require(resolved == lock["commit"], "Remote core repository lacks the exact locked commit")
    archive_path = work / "core.tar"
    with archive_path.open("xb") as stream:
        # Git for Windows can apply global core.autocrlf during archive creation.
        # Bind source to portable Git bytes rather than the operator's checkout policy.
        git("-c", "core.autocrlf=false", "-c", "core.eol=lf", "archive", "--format=tar",
            lock["commit"], cwd=core_checkout, stdout=stream)
    with tarfile.open(archive_path, "r:") as archive:
        members = validate_archive(archive, manifest)  # Every base hash checked before any overlay is applied.
        extract_archive(archive, members, destination)
    for entry in manifest["files"]:
        target = plain_path(destination, entry["path"], exists=entry["baseSha256"] is not None)
        if entry["baseSha256"] is not None:
            require(sha(target.read_bytes()) == entry["baseSha256"], "Extracted base changed before overlay")
        else:
            require(not target.exists(), "Overlay addition would overwrite a file")
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".overlay-new")
        with temporary.open("xb") as stream:
            stream.write(overlay[entry["path"]])
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    report = {"schema": "celeste-plus-prepared-runtime-1", "coreCommit": lock["commit"],
              "overlayManifestSha256": lock["overlayManifestSha256"], "overlayFiles": len(overlay),
              "sourceState": manifest["sourceState"], "complete": True}
    with (destination / ".celeste-plus-runtime.json").open("x", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2)
        stream.write("\n")
    return destination


def gradle_command(destination: Path, *, windows: bool) -> list[str]:
    wrapper = destination / ("gradlew.bat" if windows else "gradlew")
    return ([str(wrapper)] if windows else ["sh", str(wrapper)]) + ["--no-daemon", "build"]


def main(argv=None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-checkout", type=Path, help="Read the exact commit from a local Celeste Git repository; no checkout edits")
    parser.add_argument("--build", action="store_true", help="Run the prepared core's Gradle build after verification")
    parser.add_argument("--media-dll", type=Path, help="External MediaPlayerInfo.dll supplied to the optional runtime build")
    args = parser.parse_args(argv)
    if args.media_dll is not None and not args.build:
        parser.error("--media-dll requires --build")
    try:
        media = args.media_dll.resolve(strict=True) if args.media_dll is not None else None
        require(media is None or media.is_file() and not media.is_symlink(), "Media DLL input is not a regular file")
        destination = prepare(Path(__file__).resolve().parents[1], args.core_checkout)
        if args.build:
            environment = os.environ.copy()
            if media is not None:
                environment["MEDIA_PLAYER_INFO_DLL"] = str(media)
            subprocess.run(gradle_command(destination, windows=os.name == "nt"),
                           cwd=destination, env=environment, check=True)
        print(json.dumps({"status": "built" if args.build else "prepared", "runtime_core": str(destination),
                          "source_state": "development source; not a published protected payload"}))
    except (OSError, ValueError, UnicodeError, subprocess.CalledProcessError, tarfile.TarError) as error:
        # Git stderr can include credential helper output; never echo it.
        message = "Git or Gradle command failed" if isinstance(error, subprocess.CalledProcessError) else str(error)
        parser.exit(1, "Runtime preparation refused: " + message + "\n")


if __name__ == "__main__":
    main()
