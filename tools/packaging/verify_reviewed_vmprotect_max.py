"""Mechanically bind the private Windows maximum-settings Ultra fourteen-function review.

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
INPUT_SHA256 = "e83a174e14e4c6631897326fa80e35cad417cf1fc2aa33bbabb66588e63f2b3b"
OUTPUT_SHA256 = "31ce0fd052af152e5fe340847eece882bceeb11dd65414a70ac0452ab2dfd743"
REVIEW_INDEX_SHA256 = "ae5fc6d0410593b79c9206ef45e8e873bf08cdfc5308ad0f91e2b611f3e4b5f9"
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
    require(protections[0].get("Options") == "466688"
            and protections[0].get("VMComplexity") == "100",
            "Project options or complexity differ from the reviewed maximum settings")
    procedures = protections[0].findall("./Procedures/Procedure")
    require(len(procedures) == 14, "Project must contain exactly fourteen reviewed selections")
    expected = {row["alias"] for row in functions}
    require(len(expected) == 14 and {row.get("MapAddress") for row in procedures} == expected,
            "Project selections differ from reviewed aliases")
    require(all(row.get("IncludedInCompilation") == "true" and row.get("CompilationType") == "2"
                for row in procedures), "Every reviewed selection must use Ultra")


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
    # The second reviewed trampoline form uses LEA instead of ADD. Accept only
    # same-register base plus a constant, with no nonzero index register.
    if len(instructions) >= 4:
        call, pop, lea, jump = instructions[-4:]
        if [i.mnemonic for i in (call, pop, lea, jump)] == ["call", "pop", "lea", "jmp"]:
            op = capstone.x86_const
            if (call.operands[0].type == op.X86_OP_IMM and call.operands[0].imm == pop.address
                    and pop.operands[0].type == op.X86_OP_REG
                    and lea.operands[0].type == op.X86_OP_REG and lea.operands[1].type == op.X86_OP_MEM
                    and jump.operands[0].type == op.X86_OP_REG
                    and pop.operands[0].reg == lea.operands[0].reg == jump.operands[0].reg
                    and lea.operands[1].mem.base == pop.operands[0].reg
                    and lea.operands[1].mem.index in (op.X86_REG_INVALID, op.X86_REG_RIZ)):
                return call.address + call.size + lea.operands[1].mem.disp == target
    # Exact reviewed CALL-next trampoline with harmless register-only shifts
    # before POP. These instructions cannot alter the saved return address.
    op = capstone.x86_const
    if len(instructions) >= 4:
        pop, add, jump = instructions[-3:]
        if ([i.mnemonic for i in (pop, add, jump)] == ["pop", "add", "jmp"]
                and pop.operands[0].type == op.X86_OP_REG
                and add.operands[0].type == op.X86_OP_REG and add.operands[1].type == op.X86_OP_IMM
                and jump.operands[0].type == op.X86_OP_REG
                and pop.operands[0].reg == add.operands[0].reg == jump.operands[0].reg):
            for offset in range(max(0, len(instructions) - 12), len(instructions) - 3):
                call = instructions[offset]
                middle = instructions[offset + 1:-3]
                if (call.mnemonic != "call" or call.operands[0].type != op.X86_OP_IMM
                        or call.operands[0].imm != call.address + call.size):
                    continue
                if all(i.mnemonic in ("shl", "sal", "shr", "sar", "rol", "ror")
                       and len(i.operands) == 2 and i.operands[0].type == op.X86_OP_REG
                       and i.operands[1].type == op.X86_OP_IMM
                       and i.reg_name(i.operands[0].reg) not in ("rsp", "esp", "sp", "spl")
                       for i in middle):
                    return call.address + call.size + add.operands[1].imm == target
    return False


def adjusted_return(instructions, callers, destination, base, capstone):
    """Bind only the reviewed saved-return ADD with exact flag/stack restoration."""
    op = capstone.x86_const
    if len(instructions) != 6 or [i.mnemonic for i in instructions] != [
            "pushfq", "add", "push", "popfq", "lea", "ret"]:
        return False
    _, add, push, _, lea, ret = instructions
    def stack(operand, displacement):
        return (operand.type == op.X86_OP_MEM and operand.mem.base == op.X86_REG_RSP
                and operand.mem.index == op.X86_REG_INVALID and operand.mem.disp == displacement)
    if not (len(add.operands) == 2 and stack(add.operands[0], 8) and add.operands[0].size == 8
            and add.operands[1].type == op.X86_OP_IMM and len(push.operands) == 1
            and stack(push.operands[0], 0) and push.operands[0].size == 8
            and len(lea.operands) == 2 and lea.operands[0].type == op.X86_OP_REG
            and lea.operands[0].reg == op.X86_REG_RSP and stack(lea.operands[1], 8)
            and len(ret.operands) == 0):
        return False
    for caller in callers:
        if (caller.mnemonic == "call" and caller.operands[0].type == op.X86_OP_IMM
                and caller.operands[0].imm == instructions[0].address
                and caller.address + caller.size + add.operands[1].imm == base + destination):
            return True
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
                                       paths["mapping"], paths["candidate"], profile=alias.V2_PROFILE)
    require(mapping == object_json(files[review["mapping"]]), "Alias validation differs from evidence snapshot")
    candidate = object_json(files[review["candidate"]])
    for item in candidate["sourceFiles"]:
        require(sha(read(relative_path(ROOT, item["path"]))) == item["sha256"], "Compiler source input changed")
    check_project(files[review["project"]], mapping["functions"])
    compile_record = object_json(files[review["compile_evidence"]])
    job = compile_record.get("job", {})
    output = compile_record.get("output", {})
    require(compile_record.get("schema") == "vmprotect-mcp-compile-evidence-1"
            and compile_record.get("status") == "compiled-pending-independent-review"
            and compile_record.get("protection_verified") is False
            and compile_record.get("execution_performed") is False
            and job.get("input_sha256") == INPUT_SHA256
            and job.get("project_sha256") == sha(files[review["project"]])
            and job.get("procedure_count") == 14 and job.get("procedure_total") == 14
            and any(item.get("sha256") == mapping["aliased_map_sha256"] for item in job.get("maps", []))
            and output.get("output_sha256") == OUTPUT_SHA256
            and output.get("output_bytes") == len(output_bytes)
            and output.get("completion_confirmed") is True,
            "Actual MCP compile evidence differs")
    require(compile_record.get("installation", {}).get("sha256") == review["protector_sha256"]
            and output.get("job_id") == job.get("job_id") and output.get("execution_performed") is False,
            "Actual compiler identity or output job differs")
    decline = compile_record.get("execute_decline", {})
    require(decline.get("status") == "execute_declined" and decline.get("job_id") == job.get("job_id")
            and type(decline.get("pid")) is int and decline["pid"] > 0
            and decline["pid"] == output.get("process", {}).get("pid"),
            "Exact compiler execution refusal is missing")
    stable = [row for row in compile_record.get("observations", [])
              if row.get("phase") == "completed_artifact_read"]
    require(len(stable) >= 2 and stable[-1]["elapsed_seconds"] - stable[-2]["elapsed_seconds"] >= 1
            and all(row.get("output", {}).get("output_sha256") == OUTPUT_SHA256
                    and row["output"].get("output_bytes") == len(output_bytes)
                    and row["output"].get("completion_confirmed") is True for row in stable[-2:]),
            "Two stable completed artifact reads are required")
    require(any("VMProtect Lite [registered]" in window.get("title", "")
                for window in compile_record.get("last_gui", {}).get("windows", [])),
            "Reviewed licensed compiler observation missing")
    try:
        import capstone
    except ImportError as error:
        raise ValueError("Install the reviewed Capstone version to recheck instruction records") from error
    require(capstone.__version__ == review["capstone_version"], "Capstone version differs from the reviewed decoder")
    decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
    decoder.detail = True
    blocks, decoded, instruction_records = {}, {}, {}
    base = None
    process_identity = None
    for name in review["observations"]:
        observation = object_json(files[name])
        observed_base = int(observation["image_base"], 16)
        observed_identity = (observation["pid"], observation["process_created_filetime"])
        require(process_identity is None or process_identity == observed_identity,
                "Review combines different process lifetimes")
        process_identity = observed_identity
        require(base is None or base == observed_base, "Review combines inconsistent loaded images")
        base = observed_base
        require(observation.get("schema") == "celeste-bounded-code-observation-1"
                and observation.get("status") == "observed-code-only-not-protection-verdict"
                and observation.get("image_sha256") == OUTPUT_SHA256
                and observation.get("mapping") == mapping
                and observation.get("capstone_version") == capstone.__version__
                and 0 < observation["bytes_read_including_headers"] <= 56 * 1024
                and len(observation["blocks"]) <= 96, "Capture identity or scope differs")
        require(observation.get("observer_sha256") == sha(files[review["observers"][name]]),
                "Capture observer differs from the reviewed bounded helper")
        excluded = review.get("excluded_blocks", {}).get(name, [])
        require(isinstance(excluded, list) and len(excluded) <= 1
                and all(value in [block["rva"] for block in observation["blocks"]] for value in excluded),
                "Invalid explicitly reviewed exploratory-block exclusion")
        for block in observation["blocks"]:
            rva = int(block["rva"], 16)
            instructions = check_block(block, base, decoder)
            if block["rva"] in excluded:
                continue  # Still rehashed/redecoded, never interpreted as a valid entry/path.
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
    require(len(reviewed) == 14 and set(reviewed) == {row["alias"] for row in mapping["functions"]}, "Incomplete reviewed alias coverage")
    coverage = []
    input_pe = alias._Pe(input_bytes)
    for original in mapping["functions"]:
        row = reviewed[original["alias"]]
        chain = [int(value, 16) for value in row["path"]]
        require(row["symbol"] == original["symbol"] and chain[0] == int(original["rva"], 16)
                and 1 <= len(chain) <= 32, "Reviewed path belongs to another original function")
        offset, _ = input_pe.raw(chain[0], 16)
        require(blocks.get(chain[0]) and all(bytes.fromhex(block["hex"])[:16] != input_bytes[offset:offset + 16]
                for block in blocks[chain[0]]), "Reviewed entry still matches the original native prologue")
        variant = review["variants"][row["variant"]]
        suffix = [int(value, 16) for value in variant["path"]]
        require(chain[-len(suffix):] == suffix, "Reviewed path does not reach its VM decoder")
        for number, (start, end) in enumerate(zip(chain, chain[1:])):
            callers = [instruction for block in blocks.get(chain[number - 1], [])
                       for instruction in decoded[id(block)]] if number else []
            require(any(connected(block, decoded[id(block)], end, base, capstone)
                        or adjusted_return(decoded[id(block)], callers, end, base, capstone)
                        for block in blocks.get(start, [])),
                    "A reviewed entry-to-dispatch edge is not supported by captured instructions")
        coverage.append({"map_symbol": original["alias"], "original_symbol": original["symbol"],
                         "input_va": int(original["address"], 16), "project_compilation_type": "2",
                         "mode": "ultra", "transformation_verified": True,
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
