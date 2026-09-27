"""Explicit local operator action; displays a freshly created key only once."""
import argparse
import json
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.config import Settings
from app.database import Database
from app.products import DURATIONS, issue_license

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--product", required=True, choices=("celeste", "celeste_plus", "both"))
    parser.add_argument("--label", default="")
    parser.add_argument("--duration", choices=tuple(DURATIONS), default="lifetime")
    parser.add_argument("--max-devices", type=int, default=1)
    args=parser.parse_args()
    settings=Settings.from_env()
    database=Database(settings.database_path); database.initialize()
    with database.connect() as connection:
        connection.execute("BEGIN IMMEDIATE")
        uid,key=issue_license(connection,settings.key_pepper,args.product,args.label,args.duration,args.max_devices)
    print(json.dumps({"uid":uid,"product":args.product,"key":key,"display":"one-time"}))
if __name__ == "__main__": main()
