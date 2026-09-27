"""Verify native publication provenance, not remote executable attestation.

An operator-reviewed policy pins the protector, configuration and verifier.
Receipts bind their evidence to the exact output bytes. No receipt is inferred
from filenames, PE section names, channel IDs or the absence of plain strings.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

POLICY_FILE = Path(__file__).with_name("native-protection-policy.json")
ROOT = Path(__file__).resolve().parents[2]


def sha(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def read(path: Path) -> dict:
    if not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ValueError("Missing or oversized native protection evidence")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate native protection evidence field")
            result[key] = value
        return result
    result = json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique)
    if not isinstance(result, dict):
        raise ValueError("Native protection evidence must be an object")
    return result


def source_digest(product: str, root: Path = ROOT) -> str:
    if product != "celeste_plus":
        raise ValueError("This repository builds only the celeste_plus native product")
    folders = [root / "beta-lab/native-shared/src", root / "shared-auth",
               root / "beta-lab/native-launcher/src"]
    manifests = [root / "beta-lab/native-launcher/Cargo.toml",
                 root / "beta-lab/native-launcher/Cargo.lock",
                 root / "beta-lab/native-launcher/assets/celeste-logo.png", root / "rust-toolchain.toml"]
    files = list(manifests)
    for folder in folders:
        sources = [p for p in folder.rglob("*") if p.is_file() and "__pycache__" not in p.parts]
        if not folder.is_dir() or not sources:
            raise ValueError("Native source inputs are unavailable")
        files.extend(sources)
    if not files or any(not p.is_file() for p in files):
        raise ValueError("Native source inputs are unavailable")
    digest = hashlib.sha256()
    for path in sorted(set(files)):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(bytes.fromhex(sha(path)))
    return digest.hexdigest()


def validate(binary: Path, receipt: Path | None, *, release_id: str,
             product: str, platform: str) -> dict:
    if receipt is None:
        raise ValueError("Native publication requires a verified protection receipt; a Cargo build or channel identity is insufficient")
    try:
        policy = read(POLICY_FILE)
        record = read(receipt)
        if policy["schema"] != "celeste-native-protection-policy-1" or policy["required_protection"] != "native-virtualization":
            raise ValueError("Native publication policy does not require virtualization")
        if policy["environment_policy"] != "deny-vm-and-sandbox-v1":
            raise ValueError("Native publication policy lacks the VM/sandbox gate")
        if record["schema"] != "celeste-native-protection-receipt-1":
            raise ValueError("Unsupported native protection receipt")
        if (record["release_id"], record["product"], record["platform"]) != (release_id, product, platform):
            raise ValueError("Native protection receipt belongs to another release/product/platform")
        binary = binary.resolve(strict=True)
        output_hash = sha(binary)
        if record["binary_sha256"] != output_hash or record["binary_size"] != binary.stat().st_size:
            raise ValueError("Native executable differs from the verified protected output")
        if record["source_sha256"] != source_digest(product):
            raise ValueError("Native sources changed after protection verification")
        evidence = {}
        for name in ("input", "protector", "configuration", "verifier", "report"):
            entry = record[name]
            path = Path(entry["path"])
            if not path.is_absolute():
                path = receipt.resolve().parent / path
            path = path.resolve(strict=True)
            if entry["sha256"] != sha(path):
                raise ValueError(f"Native {name} evidence changed")
            evidence[name] = path
        if record["input"]["sha256"] == output_hash:
            raise ValueError("Native protector output is identical to its unprotected input")
        approved = {
            "protector_sha256": record["protector"]["sha256"],
            "configuration_sha256": record["configuration"]["sha256"],
            "verifier_sha256": record["verifier"]["sha256"],
            "platform": platform,
        }
        if approved not in policy["approved_protectors"]:
            raise ValueError("No reviewed protector/configuration/verifier is approved for this platform; publication blocked")
        report = read(evidence["report"])
        if (report.get("status") != "passed" or report.get("binary_sha256") != output_hash
                or report.get("source_sha256") != record["source_sha256"]
                or report.get("protection") != "native-virtualization"
                or type(report.get("virtualized_functions")) is not int or report["virtualized_functions"] < 1
                or report.get("environment_policy") != policy["environment_policy"]
                or report.get("environment_gate_tests") != "passed"):
            raise ValueError("Native verification does not prove the required protection and tested environment gate")
        return {"status": "verified-publication-provenance", "binary_sha256": output_hash,
                "receipt_sha256": sha(receipt), "policy_sha256": sha(POLICY_FILE),
                "report_sha256": record["report"]["sha256"], "protection": "native-virtualization",
                "environment_policy": policy["environment_policy"]}
    except (OSError, KeyError, TypeError, json.JSONDecodeError) as error:
        raise ValueError("Native protection evidence is missing or malformed") from error


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--product", choices=("celeste_plus",), required=True)
    parser.add_argument("--platform", required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(validate(args.binary, args.receipt, release_id=args.release_id,
                                  product=args.product, platform=args.platform), sort_keys=True))
    except ValueError as error:
        parser.exit(1, str(error) + "\n")
