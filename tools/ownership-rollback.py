#!/usr/bin/env python3
"""Preview one verified table ownership restore; apply only after a clean plugin stop."""
import argparse
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import tempfile
import uuid


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON field")
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=unique, parse_int=Decimal, parse_float=Decimal)


def same(left, right):
    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(same(value, right[key]) for key, value in left.items())
    if isinstance(left, list):
        return len(left) == len(right) and all(same(a, b) for a, b in zip(left, right))
    return left == right


def identity(value, nullable=False):
    if nullable and value is None:
        return None
    if not isinstance(value, str) or str(uuid.UUID(value)) != value.lower():
        raise ValueError("Invalid frozen UUID")
    return str(uuid.UUID(value))


def publish(path, data):
    descriptor, temporary = tempfile.mkstemp(prefix=".rollback-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        current = path.parent
        while True:
            descriptor = os.open(current, os.O_RDONLY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
            if current.parent == current:
                break
            current = current.parent
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data", type=Path, help="Stopped Games plugin data directory")
    parser.add_argument("table", help="Original filename beneath Data/tables")
    parser.add_argument("before", help="64-character SHA256 naming the migration plan")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if Path(args.table).name != args.table or not args.table.endswith(".json"):
        parser.error("Use a table filename, without a directory")
    if len(args.before) != 64 or any(char not in "0123456789abcdef" for char in args.before):
        parser.error("Use the migration plan's full original SHA256")
    root = args.data.resolve()
    directory = root / "migrations/table-ownership" / digest(args.table.encode())
    plan = load((directory / (args.before + ".json")).read_bytes())
    archive = (directory / (args.before + ".original")).read_bytes()
    source = root / "Data/tables" / args.table
    current = source.read_bytes()
    if plan.get("complete") is not True or "character" not in plan or "account" not in plan or plan.get("source") != args.table or plan.get("before") != args.before or digest(archive) != args.before:
        raise ValueError("Migration intent/archive requires review")
    original = load(archive)
    table_id = identity(original.get("id"))
    account = identity(original.get("ownerPlayer"), nullable=True)
    character = identity(plan.get("character"), nullable=True)
    owner_change = plan.get("ownerChange", True)
    if type(owner_change) is not bool:
        raise ValueError("Invalid ownership migration marker")
    expected = dict(original)
    if owner_change:
        if account is None or original.get("ownerCharacterId") is not None:
            raise ValueError("Owner mapping lacks its account")
        expected["ownerIdentityVersion"] = Decimal(2)
        expected["ownerCharacterId"] = plan.get("character")
    elif character != identity(original.get("ownerCharacterId"), nullable=True):
        raise ValueError("Mode-only plan changed its owner")
    if "staffMint" in plan:
        if original.get("staffMint") is not None or type(plan["staffMint"]) is not bool:
            raise ValueError("Invalid frozen legacy mode")
        expected["staffMint"] = plan["staffMint"]
    elif not owner_change:
        raise ValueError("Empty migration plan")
    if identity(plan.get("account"), nullable=True) != account or not same(expected, plan.get("planned")):
        raise ValueError("Frozen mapping/ownership output requires review")
    if digest(current) == args.before:
        print("Already restored; no changes")
        return
    if digest(current) != plan.get("after") or not same(load(current), plan["planned"]):
        raise ValueError("CONFLICT: current table contains intervening progress or ownership changes")
    for journal in (root / "estates/tables").glob("*.json"):
        candidates = load(journal.read_bytes()).get("candidates")
        if not isinstance(candidates, list):
            raise ValueError("Estate intent/receipt requires review")
        candidates = [identity(candidate) for candidate in candidates]
        if len(set(candidates)) != len(candidates):
            raise ValueError("Duplicate estate intent/receipt IDs")
        if table_id in candidates:
            raise ValueError("CONFLICT: this table has estate intent/receipt history")
    print("No conflicts; verified immutable source and exact migrated table")
    if not args.apply:
        print("Preview only; stop Games cleanly before applying")
        return
    if (root / "ownership-active").exists():
        raise ValueError("Games has not stopped cleanly; retain the marker and review recovery")
    preserved = source.with_name(source.name + ".pre-ownership-rollback")
    if preserved.exists() and preserved.read_bytes() != current:
        raise ValueError("Existing pre-rollback preservation file differs")
    if not preserved.exists():
        publish(preserved, current)
    publish(source, archive)
    print("Restored exact original; current file preserved beside it")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise SystemExit(str(error))
