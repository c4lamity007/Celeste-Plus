"""Preview by default; destructive apply requires an exact preview hash and a verified new backup."""
import argparse
import json
import sqlite3
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.license_reset import preview, reset_with_backup

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database",required=True)
    parser.add_argument("--apply",action="store_true")
    parser.add_argument("--confirmation")
    parser.add_argument("--expected-plan-sha256")
    parser.add_argument("--backup")
    args=parser.parse_args()
    if not args.apply:
        source=Path(args.database).resolve(strict=True)
        with sqlite3.connect(source.as_uri()+"?mode=ro",uri=True) as connection:
            print(json.dumps(preview(connection),indent=2))
        return
    if args.confirmation != "DELETE-ALL-LICENSES" or not args.expected_plan_sha256 or not args.backup:
        parser.error("Apply requires --confirmation DELETE-ALL-LICENSES, the reviewed --expected-plan-sha256, and --backup")
    print(json.dumps(reset_with_backup(args.database,args.backup,args.expected_plan_sha256),indent=2))
if __name__ == "__main__": main()
