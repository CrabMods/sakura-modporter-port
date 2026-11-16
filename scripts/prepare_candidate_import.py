"""Validate an official ModPorter export and attach the public build hook."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from urllib.parse import urlsplit

PUBLIC_HOOK = Path("gradle/modporter-testing.gradle")
PUBLIC_HOOK_APPLY = "apply from: 'gradle/modporter-testing.gradle'"
PROVENANCE_FILE = Path("MODPORTER-PROVENANCE.json")
TOOL_REPOSITORY = "https://github.com/CrabMods/modporter"
TOOL_VERSION = "0.4.0"
TOOL_TAG = "v0.4.0"
TOOL_JAR = "modporter-0.4.0-all.jar"
TOOL_RELEASE_URL = f"{TOOL_REPOSITORY}/releases/tag/{TOOL_TAG}"
TOOL_ASSET_ROOT = f"{TOOL_REPOSITORY}/releases/download/{TOOL_TAG}"
EXPECTED_INVOCATION = [
    "java",
    "-jar",
    TOOL_JAR,
    "port",
    "--src",
    "<clean-git-archive>",
    "--out",
    "<new-empty-output>",
    "--add-tool-credit",
]
EXPECTED = {'mod_id': 'sakura', 'candidate_version': '1.0.10-1.21.1-modporter.1', 'candidate_repository': 'https://github.com/crabsatellite/sakura-modporter-port', 'upstream_repository': 'https://github.com/0999312/Sakura_mod.git', 'upstream_commit': '0201301e5aa371d7f0816b1d786ca89e99936912', 'upstream_tree': 'ef9f417b0ff116654add3b45c5b632df60cbabdd'}
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")


class CandidatePreparationError(RuntimeError):
    """Raised when a candidate cannot be prepared without weakening provenance."""


def _strict_object(pairs: Sequence[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise CandidatePreparationError(f"duplicate JSON key in provenance: {key}")
        result[key] = value
    return result


def _mapping(value: object, label: str) -> Mapping[str, object]:
    if not isinstance(value, dict):
        raise CandidatePreparationError(f"{label} must be a JSON object")
    return value


def _exact(mapping: Mapping[str, object], key: str, expected: object, label: str) -> None:
    if mapping.get(key) != expected:
        raise CandidatePreparationError(
            f"{label}.{key} must be {expected!r}, found {mapping.get(key)!r}"
        )


def _positive_int(mapping: Mapping[str, object], key: str, label: str) -> None:
    value = mapping.get(key)
    if type(value) is not int or value <= 0:
        raise CandidatePreparationError(f"{label}.{key} must be a positive integer")


def _normalize_github_repository(value: object, label: str) -> str:
    if not isinstance(value, str):
        raise CandidatePreparationError(f"{label} must be a GitHub repository URL")
    parsed = urlsplit(value)
    path = parsed.path.rstrip("/")
    if path.casefold().endswith(".git"):
        path = path[:-4]
    segments = [segment for segment in path.split("/") if segment]
    if (
        parsed.scheme != "https"
        or parsed.netloc.casefold() != "github.com"
        or parsed.query
        or parsed.fragment
        or len(segments) != 2
    ):
        raise CandidatePreparationError(f"{label} must be a plain GitHub repository URL")
    return f"https://github.com/{segments[0]}/{segments[1]}".casefold()


def _require_sha256(value: object, label: str) -> str:
    if not isinstance(value, str) or SHA256_PATTERN.fullmatch(value) is None:
        raise CandidatePreparationError(f"{label} must be a lowercase SHA-256")
    return value


def load_provenance(path: Path) -> Mapping[str, object]:
    try:
        text = path.read_text(encoding="utf-8")
        value = json.loads(text, object_pairs_hook=_strict_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise CandidatePreparationError(f"cannot read strict UTF-8 provenance: {path}") from exc
    return _mapping(value, "provenance")


def validate_provenance(
    provenance: Mapping[str, object], official_jar_sha256: str
) -> None:
    official_sha = _require_sha256(official_jar_sha256, "official JAR SHA-256")
    _exact(provenance, "schemaVersion", 1, "provenance")

    candidate = _mapping(provenance.get("candidate"), "candidate")
    _exact(candidate, "modId", EXPECTED["mod_id"], "candidate")
    _exact(candidate, "version", EXPECTED["candidate_version"], "candidate")
    if _normalize_github_repository(candidate.get("repository"), "candidate.repository") != _normalize_github_repository(EXPECTED["candidate_repository"], "expected candidate repository"):
        raise CandidatePreparationError("candidate.repository is outside the reviewed candidate repository")

    upstream = _mapping(provenance.get("upstream"), "upstream")
    if _normalize_github_repository(upstream.get("repository"), "upstream.repository") != _normalize_github_repository(EXPECTED["upstream_repository"], "expected upstream repository"):
        raise CandidatePreparationError("upstream.repository is outside the reviewed source scope")
    _exact(upstream, "commit", EXPECTED["upstream_commit"], "upstream")
    _exact(upstream, "tree", EXPECTED["upstream_tree"], "upstream")
    _exact(upstream, "license", "MIT", "upstream")

    generator = _mapping(provenance.get("generator"), "generator")
    for key, expected in (
        ("name", "ModPorter"),
        ("version", TOOL_VERSION),
        ("repository", TOOL_REPOSITORY),
        ("license", "Apache-2.0"),
        ("jarFile", TOOL_JAR),
        ("invocation", EXPECTED_INVOCATION),
    ):
        _exact(generator, key, expected, "generator")
    recorded_sha = _require_sha256(generator.get("jarSha256"), "generator.jarSha256")
    if recorded_sha != official_sha:
        raise CandidatePreparationError(
            "generator.jarSha256 does not match the independently supplied official release SHA-256"
        )

    release = _mapping(generator.get("release"), "generator.release")
    for key, expected in (
        ("tag", TOOL_TAG),
        ("url", TOOL_RELEASE_URL),
        ("jarAssetUrl", f"{TOOL_ASSET_ROOT}/{TOOL_JAR}"),
        ("checksumsUrl", f"{TOOL_ASSET_ROOT}/SHA256SUMS"),
    ):
        _exact(release, key, expected, "generator.release")
    for key in ("id", "jarAssetId", "checksumsAssetId"):
        _positive_int(release, key, "generator.release")
    if not isinstance(release.get("publishedAt"), str) or not release["publishedAt"]:
        raise CandidatePreparationError("generator.release.publishedAt must be present")
    _require_sha256(release.get("checksumsSha256"), "generator.release.checksumsSha256")

    policy = _mapping(provenance.get("policy"), "policy")
    for key, expected in (
        ("modLogicHandPatches", False),
        ("metadataAndPackagingEditsOnly", True),
        ("upstreamRelicensed", False),
        ("reproducibleArchiveFlags", True),
        ("implementationTimestampRemoved", True),
    ):
        _exact(policy, key, expected, "policy")


def _hook_count(build_text: str) -> int:
    return build_text.splitlines().count(PUBLIC_HOOK_APPLY)


def prepare_candidate(
    project_root: Path, official_jar_sha256: str, *, check_only: bool = False
) -> bool:
    root = project_root.expanduser().resolve(strict=True)
    if not root.is_dir():
        raise CandidatePreparationError(f"project root is not a directory: {root}")
    hook = root / PUBLIC_HOOK
    if not hook.is_file():
        raise CandidatePreparationError(f"public build hook is missing: {hook}")
    provenance = load_provenance(root / PROVENANCE_FILE)
    validate_provenance(provenance, official_jar_sha256)

    build_file = root / "build.gradle"
    if not build_file.is_file():
        raise CandidatePreparationError(f"generated Groovy build file is missing: {build_file}")
    try:
        raw = build_file.read_bytes()
        build_text = raw.decode("utf-8")
    except (OSError, UnicodeError) as exc:
        raise CandidatePreparationError(f"cannot read generated build file as UTF-8: {build_file}") from exc

    count = _hook_count(build_text)
    noncanonical = [
        line for line in build_text.splitlines()
        if line.strip() == PUBLIC_HOOK_APPLY and line != PUBLIC_HOOK_APPLY
    ]
    if count > 1:
        raise CandidatePreparationError("public build hook is applied more than once")
    if noncanonical:
        raise CandidatePreparationError("public build hook exists only in noncanonical indentation")
    if check_only:
        if count != 1:
            raise CandidatePreparationError("public build hook has not been applied")
        return False
    if count == 1:
        return False

    newline = b"\r\n" if b"\r\n" in raw else b"\n"
    addition = b""
    if raw and not raw.endswith((b"\n", b"\r")):
        addition += newline
    if raw and not raw.endswith(newline + newline):
        addition += newline
    updated = raw + addition + PUBLIC_HOOK_APPLY.encode("utf-8") + newline
    build_file.write_bytes(updated)
    try:
        written = build_file.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise CandidatePreparationError(f"cannot verify written build file: {build_file}") from exc
    if _hook_count(written) != 1:
        raise CandidatePreparationError("public build hook write did not verify exactly once")
    return True


def parse_cli(arguments: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate an official ModPorter 0.4.0 export and apply its public build hook"
    )
    parser.add_argument("--project-root", type=Path)
    parser.add_argument(
        "--official-jar-sha256",
        required=True,
        help="SHA-256 copied independently from the official v0.4.0 SHA256SUMS asset",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify provenance and the existing hook without modifying build.gradle",
    )
    return parser.parse_args(list(arguments))


def main(arguments: Sequence[str] | None = None) -> int:
    parsed = parse_cli(sys.argv[1:] if arguments is None else arguments)
    root = parsed.project_root or Path(__file__).resolve().parent.parent
    try:
        changed = prepare_candidate(
            root, parsed.official_jar_sha256, check_only=parsed.check
        )
    except (CandidatePreparationError, OSError) as exc:
        print(f"candidate_preparation=failed reason={exc}", file=sys.stderr)
        return 2
    mode = "check" if parsed.check else "prepare"
    print(
        "candidate_preparation=passed "
        f"mode={mode} changed={str(changed).lower()} tool_version={TOOL_VERSION}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
