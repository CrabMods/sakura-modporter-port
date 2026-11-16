from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

from prepare_candidate_import import (
    CandidatePreparationError,
    EXPECTED,
    EXPECTED_INVOCATION,
    PUBLIC_HOOK_APPLY,
    TOOL_ASSET_ROOT,
    TOOL_JAR,
    TOOL_RELEASE_URL,
    TOOL_REPOSITORY,
    TOOL_TAG,
    TOOL_VERSION,
    prepare_candidate,
)


class CandidatePreparationTest(unittest.TestCase):
    official_sha = "a" * 64

    def provenance(self) -> dict[str, object]:
        return {
            "schemaVersion": 1,
            "candidate": {
                "modId": EXPECTED["mod_id"],
                "version": EXPECTED["candidate_version"],
                "repository": EXPECTED["candidate_repository"],
            },
            "upstream": {
                "repository": EXPECTED["upstream_repository"],
                "commit": EXPECTED["upstream_commit"],
                "tree": EXPECTED["upstream_tree"],
                "license": "MIT",
            },
            "generator": {
                "name": "ModPorter",
                "version": TOOL_VERSION,
                "repository": TOOL_REPOSITORY,
                "license": "Apache-2.0",
                "jarFile": TOOL_JAR,
                "jarSha256": self.official_sha,
                "release": {
                    "tag": TOOL_TAG,
                    "url": TOOL_RELEASE_URL,
                    "id": 101,
                    "publishedAt": "2026-11-16T00:00:00Z",
                    "jarAssetId": 102,
                    "jarAssetUrl": f"{TOOL_ASSET_ROOT}/{TOOL_JAR}",
                    "checksumsAssetId": 103,
                    "checksumsUrl": f"{TOOL_ASSET_ROOT}/SHA256SUMS",
                    "checksumsSha256": "b" * 64,
                },
                "invocation": EXPECTED_INVOCATION,
            },
            "policy": {
                "modLogicHandPatches": False,
                "metadataAndPackagingEditsOnly": True,
                "upstreamRelicensed": False,
                "reproducibleArchiveFlags": True,
                "implementationTimestampRemoved": True,
            },
        }

    def make_project(self, parent: Path) -> tuple[Path, Path]:
        root = parent / "candidate"
        (root / "gradle").mkdir(parents=True)
        (root / "gradle" / "modporter-testing.gradle").write_text(
            "// public hook\n", encoding="utf-8"
        )
        (root / "build.gradle").write_text(
            "plugins { id 'java' }\n", encoding="utf-8"
        )
        source = root / "src" / "main" / "java" / "example" / "Example.java"
        source.parent.mkdir(parents=True)
        source.write_bytes(b"final class Example {}\n")
        (root / "MODPORTER-PROVENANCE.json").write_text(
            json.dumps(self.provenance(), indent=2) + "\n", encoding="utf-8"
        )
        return root, source

    def test_applies_hook_without_touching_source_and_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project, source = self.make_project(Path(directory))
            before = source.read_bytes()
            self.assertTrue(prepare_candidate(project, self.official_sha))
            self.assertEqual(before, source.read_bytes())
            self.assertEqual(
                1,
                (project / "build.gradle")
                .read_text(encoding="utf-8")
                .splitlines()
                .count(PUBLIC_HOOK_APPLY),
            )
            self.assertFalse(prepare_candidate(project, self.official_sha))
            self.assertEqual(before, source.read_bytes())

    def test_check_refuses_a_missing_hook(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project, _source = self.make_project(Path(directory))
            with self.assertRaisesRegex(CandidatePreparationError, "has not been applied"):
                prepare_candidate(project, self.official_sha, check_only=True)

    def test_refuses_a_noncanonical_indented_hook(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project, _source = self.make_project(Path(directory))
            (project / "build.gradle").write_text(
                f"    {PUBLIC_HOOK_APPLY}\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(CandidatePreparationError, "noncanonical"):
                prepare_candidate(project, self.official_sha)

    def test_refuses_a_jar_hash_not_independently_confirmed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project, _source = self.make_project(Path(directory))
            with self.assertRaisesRegex(CandidatePreparationError, "independently supplied"):
                prepare_candidate(project, "c" * 64)

    def test_refuses_nonrelease_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project, _source = self.make_project(Path(directory))
            provenance = self.provenance()
            generator = provenance["generator"]
            assert isinstance(generator, dict)
            generator["release"] = None
            (project / "MODPORTER-PROVENANCE.json").write_text(
                json.dumps(provenance) + "\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(CandidatePreparationError, "release"):
                prepare_candidate(project, self.official_sha)

    def test_refuses_wrong_upstream_tree(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project, _source = self.make_project(Path(directory))
            provenance = self.provenance()
            upstream = provenance["upstream"]
            assert isinstance(upstream, dict)
            upstream["tree"] = "0" * 40
            (project / "MODPORTER-PROVENANCE.json").write_text(
                json.dumps(provenance) + "\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(CandidatePreparationError, "upstream.tree"):
                prepare_candidate(project, self.official_sha)


if __name__ == "__main__":
    unittest.main()
