"""Mechanically bind one private manual VMProtect review to its exact artifact.

This is deliberately not a generic VMProtect detector. It rechecks an approved
private evidence index, the recorded native instruction paths, and current
source identity. It does not execute code, attest environment tests, approve a
policy, create a publication receipt, or claim successful login/attachment.
Private MAPs, addresses and capture data stay in the supplied review directory.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import xml.etree.ElementTree as ET

import native_protection_gate as gate
import prepare_vmprotect_map as alias

ROOT = Path(__file__).resolve().parents[2]
INPUT_SHA256 = "a16e5bd56aba5f8365f64b9bb11e6ac086497077c220cc680c54203a776a1f0a"
OUTPUT_SHA256 = "ad538688e39f7eb1d811379c81a2bd955300984cbd80669f503ccd2623bf89e4"
REVIEW_INDEX_SHA256 = "b5a2d57da8896200825b30a48177f0154d17cafa0efc1b3755d31af29da81ce7"
MAX_EVIDENCE = 32 * 1024 * 1024
ROLES = {"encoded_read", "stream_advance", "rolling_decode", "rolling_key_update",
         "signed_handler_offset", "dispatch"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(path, maximum=MAX_EVIDENCE):
    path = Path(os.path.abspath(path))
    for part in (path, *path.parents):
        if part.exists() or part.is_symlink():
            info = part.lstat()
            require(not stat.S_ISLNK(info.st_mode) and not getattr(info, "st_file_attributes", 0) & 0x400,
                    "Evidence paths must not traverse links or reparse points")
    with path.open("rb") as stream:
        before = os.fstat(stream.fileno())
        require(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= maximum, "Missing or oversized evidence")
        data = stream.read(maximum + 1)
        after = os.fstat(stream.fileno())
    require(len(data) == before.st_size == after.st_size and before.st_mtime_ns == after.st_mtime_ns,
            "Evidence changed while reading")
    return data


def object_json(data):
    def pairs(values):
        result = {}
        for key, value in values:
            require(key not in result, "Duplicate evidence field")
            result[key] = value
        return result
    def constant(_):
        raise ValueError("Non-finite evidence number")
    result = json.loads(data, object_pairs_hook=pairs, parse_constant=constant)
    require(isinstance(result, dict), "Evidence must be an object")
    return result


def relative_path(root, name):
    require(isinstance(name, str) and name and "\\" not in name and ":" not in name,
            "Invalid private evidence path")
    value = PurePosixPath(name)
    require(not value.is_absolute() and value.as_posix() == name
            and all(part not in ("", ".", "..") for part in value.parts), "Evidence path escapes review directory")
    return Path(root).joinpath(*value.parts)


def load_review(directory):
    index_bytes = read(Path(directory) / "coverage-review-index.json", 1024 * 1024)
    require(sha(index_bytes) == REVIEW_INDEX_SHA256, "This private review index has not been reviewed")
    index = object_json(index_bytes)
    require(index.get("schema") == "celeste-exact-artifact-vmp-review-index-1", "Unsupported review index")
    require(isinstance(index.get("files"), list) and 1 <= len(index["files"]) <= 64, "Invalid review inventory")
    files = {}
    for item in index["files"]:
        require(isinstance(item, dict) and set(item) == {"path", "sha256", "bytes"}, "Invalid review inventory entry")
        name = item["path"]
        require(name not in files and type(item["bytes"]) is int, "Duplicate or mistyped review entry")
        data = read(relative_path(directory, name))
        require(len(data) == item["bytes"] and sha(data) == item["sha256"], "Private reviewed evidence changed")
        files[name] = data
    require(index.get("review") in files, "Private review manifest is missing")
    return object_json(files[index["review"]]), files


def check_project(data, functions):
    require(b"<!DOCTYPE" not in data.upper() and b"<!ENTITY" not in data.upper(), "Project entities are forbidden")
    document = ET.fromstring(data)
    require(document.tag == "Document", "Unexpected protection project")
    protections = list(document.iter("Protection"))
    require(len(protections) == 1 and protections[0] in list(document), "Expected one protection section")
    require(not list(document.iter("Script")), "Reviewed project must contain no script")
    procedures = protections[0].findall("./Procedures/Procedure")
    require(len(procedures) == 7, "Project must contain exactly seven reviewed selections")
    expected = {row["alias"] for row in functions}
    require(len(expected) == 7 and {row.get("MapAddress") for row in procedures} == expected,
            "Project selections differ from reviewed aliases")
    require(all(row.get("IncludedInCompilation") == "true" and row.get("CompilationType") == "0"
                for row in procedures), "Every reviewed selection must use Virtualization")


def check_block(block, base, decoder):
    data = bytes.fromhex(block["hex"])
    address = int(block["address"], 16)
    require(type(block["size"]) is int and 0 < len(data) == block["size"] <= 256,
            "Capture block exceeds reviewed bound")
    require(sha(data) == block["sha256"] and address == base + int(block["rva"], 16), "Capture block binding differs")
    actual = list(decoder.disasm(data, address))
    records = block["instructions"]
    require(isinstance(records, list) and 0 < len(records) <= len(actual), "Recorded instruction extent differs")
    for expected, instruction in zip(records, actual):
        require(expected == {"address": hex(instruction.address), "bytes": instruction.bytes.hex(),
                             "mnemonic": instruction.mnemonic, "operands": instruction.op_str},
                "Recorded native instruction differs from captured bytes")
    return actual[:len(records)]


def connected(block, instructions, destination, base, capstone):
    target = base + destination
    if any(instruction.address == target for instruction in instructions[1:]):
        return True  # same captured linear block, including call-to-next setup
    for instruction in instructions:
        if ((instruction.group(capstone.CS_GRP_JUMP) or instruction.group(capstone.CS_GRP_CALL))
                and instruction.operands and instruction.operands[0].type == capstone.x86_const.X86_OP_IMM
                and instruction.operands[0].imm == target):
            return True
    last = instructions[-1]
    next_address = last.address + last.size
    if (target == next_address and int(block["address"], 16) + block["size"] - next_address < 15
            and not any(last.group(group) for group in (capstone.CS_GRP_JUMP, capstone.CS_GRP_CALL, capstone.CS_GRP_RET))):
        return True
    # The reviewed computed trampoline is CALL next; POP reg; ADD reg, imm;
    # JMP reg. Evaluate that exact local sequence; do not guess indirect edges.
    if len(instructions) >= 4:
        call, pop, add, jump = instructions[-4:]
        if [i.mnemonic for i in (call, pop, add, jump)] == ["call", "pop", "add", "jmp"]:
            op = capstone.x86_const
            if (call.operands[0].type == op.X86_OP_IMM and call.operands[0].imm == pop.address
                    and pop.operands[0].type == op.X86_OP_REG
                    and add.operands[0].type == op.X86_OP_REG and add.operands[1].type == op.X86_OP_IMM
                    and jump.operands[0].type == op.X86_OP_REG
                    and pop.operands[0].reg == add.operands[0].reg == jump.operands[0].reg):
                return call.address + call.size + add.operands[1].imm == target
    return False


def verify(binary, input_binary, review_directory):
    output_bytes, input_bytes = read(binary, 128 * 1024 * 1024), read(input_binary, 128 * 1024 * 1024)
    require(sha(output_bytes) == OUTPUT_SHA256 and sha(input_bytes) == INPUT_SHA256,
            "Only the exact reviewed input and protected artifact are accepted")
    review, files = load_review(review_directory)
    require(review.get("schema") == "celeste-exact-artifact-vmp-review-1"
            and review.get("input_sha256") == INPUT_SHA256 and review.get("protected_sha256") == OUTPUT_SHA256,
            "Private review belongs to another artifact")
    require(gate.source_digest("celeste_plus") == review["source_sha256"], "Current native sources differ from review")
    paths = {name: relative_path(review_directory, review[name]) for name in
             ("mapping", "candidate", "original_map", "derived_map", "project", "compile_evidence")}
    mapping = alias.validate_alias_map(Path(input_binary), paths["original_map"], paths["derived_map"],
                                       paths["mapping"], paths["candidate"])
    require(mapping == object_json(files[review["mapping"]]), "Alias validation differs from evidence snapshot")
    candidate = object_json(files[review["candidate"]])
    for item in candidate["sourceFiles"]:
        require(sha(read(relative_path(ROOT, item["path"]))) == item["sha256"], "Compiler source input changed")
    check_project(files[review["project"]], mapping["functions"])
    compile_record = object_json(files[review["compile_evidence"]])
    require(compile_record.get("schema") == "celeste-vmp-compile-observation-1"
            and compile_record.get("input_sha256") == INPUT_SHA256
            and compile_record.get("original_map_sha256") == mapping["original_map_sha256"]
            and compile_record.get("derived_map_sha256") == mapping["aliased_map_sha256"]
            and compile_record.get("project_sha256") == sha(files[review["project"]])
            and compile_record.get("job_result", {}).get("output_sha256") == OUTPUT_SHA256
            and compile_record["job_result"].get("output_bytes") == len(output_bytes), "Compile evidence differs")
    require(any("VMProtect Lite [registered]" in window.get("title", "")
                for window in compile_record.get("gui_state", {}).get("windows", [])), "Reviewed licensed compiler observation missing")
    try:
        import capstone
    except ImportError as error:
        raise ValueError("Install the reviewed Capstone version to recheck instruction records") from error
    require(capstone.__version__ == review["capstone_version"], "Capstone version differs from the reviewed decoder")
    decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
    decoder.detail = True
    blocks, decoded, instruction_records = {}, {}, {}
    base = None
    for name in review["observations"]:
        observation = object_json(files[name])
        observed_base = int(observation["image_base"], 16)
        require(base is None or base == observed_base, "Review combines inconsistent loaded images")
        base = observed_base
        require(observation.get("schema") == "celeste-bounded-code-observation-1"
                and observation.get("status") == "observed-code-only-not-protection-verdict"
                and observation.get("image_sha256") == OUTPUT_SHA256
                and observation.get("mapping") == mapping
                and observation.get("capstone_version") == capstone.__version__
                and 0 < observation["bytes_read_including_headers"] <= 56 * 1024
                and len(observation["blocks"]) <= 96, "Capture identity or scope differs")
        for block in observation["blocks"]:
            rva = int(block["rva"], 16)
            instructions = check_block(block, base, decoder)
            for row in block["instructions"]:
                location = int(row["address"], 16) - base
                if location in instruction_records:
                    require(instruction_records[location] == row, "Captured instructions changed between observations")
                instruction_records[location] = row
            blocks.setdefault(rva, []).append(block)
            decoded[id(block)] = instructions
    for variant in review["variants"].values():
        signals = variant["signals"]
        require(len(signals) == len(ROLES) and {row["role"] for row in signals} == ROLES, "Incomplete reviewed VM evidence")
        for signal in signals:
            require(instruction_records.get(int(signal["rva"], 16)) == signal["instruction"], "Reviewed VM instruction evidence missing")
            if signal["role"] == "dispatch":
                require(signal["instruction"]["mnemonic"] in ("jmp", "ret"), "Reviewed dispatch is not an indirect transfer")
    reviewed = {row["alias"]: row for row in review["functions"]}
    require(len(reviewed) == 7 and set(reviewed) == {row["alias"] for row in mapping["functions"]}, "Incomplete reviewed alias coverage")
    coverage = []
    for original in mapping["functions"]:
        row = reviewed[original["alias"]]
        chain = [int(value, 16) for value in row["path"]]
        require(row["symbol"] == original["symbol"] and chain[0] == int(original["rva"], 16)
                and 1 <= len(chain) <= 32, "Reviewed path belongs to another original function")
        variant = review["variants"][row["variant"]]
        suffix = [int(value, 16) for value in variant["path"]]
        require(chain[-len(suffix):] == suffix, "Reviewed path does not reach its VM decoder")
        for start, end in zip(chain, chain[1:]):
            require(any(connected(block, decoded[id(block)], end, base, capstone) for block in blocks.get(start, [])),
                    "A reviewed entry-to-dispatch edge is not supported by captured instructions")
        coverage.append({"map_symbol": original["alias"], "original_symbol": original["symbol"],
                         "input_va": int(original["address"], 16), "project_compilation_type": "0",
                         "mode": "virtualization", "transformation_verified": True,
                         "basis": "manually-reviewed-loaded-code-and-mechanical-evidence-binding"})
    # Recheck mutable paths after the path-based alias validator and source reads.
    for name, data in files.items():
        require(read(relative_path(review_directory, name)) == data, "Reviewed evidence changed during verification")
    require(read(binary, 128 * 1024 * 1024) == output_bytes and read(input_binary, 128 * 1024 * 1024) == input_bytes,
            "Artifact changed during verification")
    require(gate.source_digest("celeste_plus") == review["source_sha256"], "Native sources changed during verification")
    return {"schema": "celeste-reviewed-vmp-coverage-1", "status": "coverage-verified-for-exact-reviewed-artifact",
            "product": "celeste_plus", "platform": "windows-x64", "binary_sha256": OUTPUT_SHA256,
            "binary_size": len(output_bytes), "input_sha256": INPUT_SHA256,
            "source_sha256": review["source_sha256"], "review_index_sha256": REVIEW_INDEX_SHA256,
            "protector_sha256": review["protector_sha256"], "configuration_sha256": sha(files[review["project"]]),
            "map_sha256": mapping["original_map_sha256"], "derived_map_sha256": mapping["aliased_map_sha256"],
            "map_alias_record_sha256": sha(files[review["mapping"]]),
            "compile_evidence_sha256": sha(files[review["compile_evidence"]]),
            "protection": "native-virtualization", "virtualized_functions": len(coverage), "function_coverage": coverage,
            "scope": review["scope"], "environment_tests_attested": False, "auth_attach_verified": False,
            "performance_verified": False, "publication_approved": False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("binary", "input-binary", "review-directory", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        result = verify(args.binary, args.input_binary, args.review_directory)
        with args.output.open("x", encoding="utf-8") as destination:
            json.dump(result, destination, indent=2)
            destination.write("\n")
        print(json.dumps({key: result[key] for key in ("status", "binary_sha256", "virtualized_functions", "publication_approved")}))
    except (OSError, ValueError, TypeError, KeyError, ET.ParseError) as error:
        parser.exit(1, f"Reviewed protection verification refused: {error}\n")


if __name__ == "__main__":
    main()
