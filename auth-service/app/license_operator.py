"""Operator-only creation and reviewed clean-slate reset; never runs on app startup."""
from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path

from .config import Settings
from .database import Database
from .license_reset import preview, reset_with_backup
from .products import issue_license


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create")
    create.add_argument("--product", choices=("celeste", "celeste_plus", "both"), required=True)
    create.add_argument("--label", default="")
    create.add_argument("--duration", default="lifetime")
    create.add_argument("--max-devices", type=int, default=1)
    for name in ("preview-reset", "reset"):
        command = commands.add_parser(name)
        command.add_argument("--database", required=True)
        if name == "reset":
            command.add_argument("--confirmation", required=True, choices=("DELETE-ALL-LICENSES",))
            command.add_argument("--expected-plan-sha256", required=True)
            command.add_argument("--backup", required=True)
    args = parser.parse_args(argv)
    if args.command == "create":
        config = Settings.from_env()
        database = Database(config.database_path)
        database.initialize()
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            uid, key = issue_license(connection, config.key_pepper, args.product, args.label, args.duration, args.max_devices)
        result = {"uid": uid, "product": args.product, "key": key, "display": "one-time"}
    elif args.command == "preview-reset":
        source = Path(args.database).resolve(strict=True)
        with sqlite3.connect(source.as_uri() + "?mode=ro", uri=True) as connection:
            result = preview(connection)
    else:
        result = reset_with_backup(args.database, args.backup, args.expected_plan_sha256)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
