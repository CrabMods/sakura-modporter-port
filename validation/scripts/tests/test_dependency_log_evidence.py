from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dependency_log_evidence import classify


class DependencyLogEvidenceTest(unittest.TestCase):
    def fixture(self, root: Path):
        library = root / "library.jar"
        with zipfile.ZipFile(library, "w") as archive:
            archive.writestr("assets/library/sounds.json", json.dumps({
                "welcome": {"subtitle": "library.missing.subtitle"}
            }))
            archive.writestr("assets/library/lang/en_us.json", "{}")
        target = root / "target.jar"
        with zipfile.ZipFile(target, "w") as archive:
            archive.writestr("assets/target/lang/en_us.json", "{}")
        digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
        policy = {"schemaVersion": 1, "artifacts": [{
            "modId": "library", "sha256": digest(library),
            "provenance": "https://example.com/pinned-source",
            "forbiddenTargetPrefixes": ["assets/library/"],
            "facts": {
                "absentEntries": ["assets/library/sounds/welcome.ogg"],
                "jsonValues": [{"entry": "assets/library/sounds.json",
                    "path": ["welcome", "subtitle"],
                    "equals": "library.missing.subtitle"}],
                "absentJsonKeys": [{"entry": "assets/library/lang/en_us.json",
                    "key": "library.missing.subtitle"}]
            },
            "conditions": [{"id": "library-subtitle", "severity": "ERROR",
                "message": "[minecraft/SoundManager]: missing library subtitle"}]
        }]}
        receipt = {"allScenariosPassed": True, "target": {
            "originIsExpectedRegularJar": True, "loadedJarPath": str(target),
            "jarSha256": digest(target), "runtimeModJarInventory": [{
                "modId": "library", "regularJar": True, "path": str(library),
                "sha256": digest(library), "bytes": library.stat().st_size
            }]
        }}
        line = "[12:00:00] [Render thread/ERROR] [minecraft/SoundManager]: missing library subtitle"
        return policy, receipt, line, library, target

    def test_exact_native_artifact_and_resource_facts_allow_only_exact_message(self):
        with tempfile.TemporaryDirectory() as directory:
            policy, receipt, line, _, _ = self.fixture(Path(directory))
            result = classify([line, line + " extra", line.replace("ERROR", "FATAL")], receipt, policy)
            self.assertEqual([1], [entry["line"] for entry in result["allowedConditions"]])
            self.assertTrue(result["artifacts"][0]["verified"])

    def test_missing_or_duplicate_native_dependency_is_not_admitted(self):
        with tempfile.TemporaryDirectory() as directory:
            policy, receipt, line, _, _ = self.fixture(Path(directory))
            for inventory in [[], receipt["target"]["runtimeModJarInventory"] * 2]:
                changed = copy.deepcopy(receipt)
                changed["target"]["runtimeModJarInventory"] = inventory
                self.assertEqual([], classify([line], changed, policy)["allowedConditions"])

    def test_changed_dependency_bytes_are_not_admitted(self):
        with tempfile.TemporaryDirectory() as directory:
            policy, receipt, line, library, _ = self.fixture(Path(directory))
            with library.open("ab") as stream:
                stream.write(b"changed")
            self.assertEqual([], classify([line], receipt, policy)["allowedConditions"])

    def test_wrong_native_hash_is_not_admitted(self):
        with tempfile.TemporaryDirectory() as directory:
            policy, receipt, line, _, _ = self.fixture(Path(directory))
            receipt["target"]["runtimeModJarInventory"][0]["sha256"] = "0" * 64
            self.assertEqual([], classify([line], receipt, policy)["allowedConditions"])

    def test_target_resource_override_is_not_admitted(self):
        with tempfile.TemporaryDirectory() as directory:
            policy, receipt, line, _, target = self.fixture(Path(directory))
            with zipfile.ZipFile(target, "a") as archive:
                archive.writestr("assets/library/sounds.json", "{}")
            receipt["target"]["jarSha256"] = hashlib.sha256(target.read_bytes()).hexdigest()
            self.assertEqual([], classify([line], receipt, policy)["allowedConditions"])

    def test_resource_facts_must_still_match(self):
        with tempfile.TemporaryDirectory() as directory:
            policy, receipt, line, _, _ = self.fixture(Path(directory))
            policy["artifacts"][0]["facts"]["jsonValues"][0]["equals"] = "different"
            self.assertEqual([], classify([line], receipt, policy)["allowedConditions"])

    def test_unrelated_models_do_not_override_audited_sound_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            policy, receipt, line, _, target = self.fixture(Path(directory))
            policy["artifacts"][0]["forbiddenTargetPrefixes"] = [
                "assets/library/sounds.json", "assets/library/lang/"
            ]
            with zipfile.ZipFile(target, "a") as archive:
                archive.writestr("assets/library/models/block/example.json", "{}")
            receipt["target"]["jarSha256"] = hashlib.sha256(target.read_bytes()).hexdigest()
            self.assertEqual(1, len(classify([line], receipt, policy)["allowedConditions"]))

    def test_empty_zip_directories_are_not_resource_overrides(self):
        with tempfile.TemporaryDirectory() as directory:
            policy, receipt, line, _, target = self.fixture(Path(directory))
            with zipfile.ZipFile(target, "a") as archive:
                archive.writestr("assets/library/", "")
            receipt["target"]["jarSha256"] = hashlib.sha256(target.read_bytes()).hexdigest()
            self.assertEqual(1, len(classify([line], receipt, policy)["allowedConditions"]))

    def test_failed_native_session_cannot_supply_exception_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            policy, receipt, line, _, _ = self.fixture(Path(directory))
            receipt["allScenariosPassed"] = False
            self.assertEqual([], classify([line], receipt, policy)["allowedConditions"])

    def test_policy_cannot_admit_fatal_severity(self):
        with tempfile.TemporaryDirectory() as directory:
            policy, receipt, line, _, _ = self.fixture(Path(directory))
            policy["artifacts"][0]["conditions"][0]["severity"] = "FATAL"
            with self.assertRaises(ValueError):
                classify([line], receipt, policy)


if __name__ == "__main__":
    unittest.main()
