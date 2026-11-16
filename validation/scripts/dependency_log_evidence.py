"""Classify exact dependency messages using native JAR identity and ZIP facts."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


HEADER = re.compile(r"^\[[^\]\r\n]+\] \[[^\]\r\n]+/(?P<severity>WARN|ERROR)\] ")


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def check_zip_facts(archive: zipfile.ZipFile, facts: dict) -> None:
    names = set(archive.namelist())
    for name in facts["absentEntries"]:
        if name in names:
            raise ValueError(f"expected missing resource is present: {name}")
    for check in facts["jsonValues"]:
        value = json.loads(archive.read(check["entry"]).decode("utf-8-sig"))
        for part in check["path"]:
            value = value[part]
        if value != check["equals"]:
            raise ValueError(f"dependency JSON fact differs: {check['entry']}")
    for check in facts["absentJsonKeys"]:
        value = json.loads(archive.read(check["entry"]).decode("utf-8-sig"))
        if check["key"] in value:
            raise ValueError(f"expected missing JSON key is present: {check['entry']}")


def classify(lines: list[str], receipt: dict, policy: dict) -> dict:
    if policy.get("schemaVersion") != 1:
        raise ValueError("unsupported dependency evidence policy")
    definitions = policy["artifacts"]
    identifiers = [c["id"] for d in definitions for c in d["conditions"]]
    if len(identifiers) != len(set(identifiers)):
        raise ValueError("duplicate dependency condition id")
    for definition in definitions:
        if not re.fullmatch(r"[0-9a-f]{64}", definition["sha256"]):
            raise ValueError("invalid pinned dependency SHA-256")
        for condition in definition["conditions"]:
            if condition["severity"] not in ("WARN", "ERROR"):
                raise ValueError("dependency conditions cannot admit other severities")
            if not condition["message"] or "\n" in condition["message"]:
                raise ValueError("dependency conditions must match one complete message")

    allowed = []
    observations = []
    target = receipt.get("target") or {}
    if not receipt.get("allScenariosPassed") or not target.get("originIsExpectedRegularJar"):
        return {"allowedConditions": allowed, "artifacts": observations}
    target_path = Path(target["loadedJarPath"])
    if not target_path.is_file() or sha256(target_path) != target["jarSha256"]:
        return {"allowedConditions": allowed, "artifacts": observations}
    with zipfile.ZipFile(target_path) as target_zip:
        target_entries = [entry.filename for entry in target_zip.infolist() if not entry.is_dir()]

    inventory = target.get("runtimeModJarInventory", [])
    for definition in definitions:
        observation = {"modId": definition["modId"], "verified": False}
        observations.append(observation)
        matches = [entry for entry in inventory if entry.get("modId") == definition["modId"]]
        if len(matches) != 1:
            observation["reason"] = "native inventory has no unique dependency container"
            continue
        native = matches[0]
        try:
            if native.get("regularJar") is not True or native.get("sha256") != definition["sha256"]:
                raise ValueError("native dependency JAR identity differs from the pinned artifact")
            path = Path(native["path"])
            if not path.is_file() or path.is_symlink() or path.stat().st_size != native["bytes"]:
                raise ValueError("native dependency artifact is missing or changed")
            if sha256(path) != definition["sha256"]:
                raise ValueError("dependency artifact hash changed after the native observation")
            if any(name.startswith(prefix) for name in target_entries
                   for prefix in definition["forbiddenTargetPrefixes"]):
                raise ValueError("target JAR overrides an audited dependency resource")
            with zipfile.ZipFile(path) as archive:
                check_zip_facts(archive, definition["facts"])
        except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
            observation["reason"] = str(error)
            continue
        observation.update(verified=True, sha256=definition["sha256"],
                           provenance=definition["provenance"])
        for number, line in enumerate(lines, 1):
            header = HEADER.match(line)
            if header is None:
                continue
            for condition in definition["conditions"]:
                if (header["severity"] == condition["severity"]
                        and line[header.end():] == condition["message"]):
                    allowed.append(dict(line=number, conditionId=condition["id"], text=line,
                                        dependencyModId=definition["modId"],
                                        dependencyJarSha256=definition["sha256"],
                                        provenance=definition["provenance"]))
    if len({entry["line"] for entry in allowed}) != len(allowed):
        raise ValueError("more than one dependency condition matched a log line")
    return {"allowedConditions": allowed, "artifacts": observations}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--policy", required=True, type=Path)
    args = parser.parse_args()
    receipt = json.loads(args.receipt.read_text(encoding="utf-8-sig")) if args.receipt.is_file() else {}
    result = classify(args.log.read_text(encoding="utf-8-sig").splitlines(), receipt,
                      json.loads(args.policy.read_text(encoding="utf-8-sig")))
    print(json.dumps(result))


if __name__ == "__main__":
    main()
