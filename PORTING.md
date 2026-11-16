# Porting record

This file records how the Minecraft 1.21.1 / NeoForge candidate relates to the
original Sakura source, the historical manually repaired port, and the fresh
ModPorter 0.4.0 generation.

## Status

The fresh generated baseline and its final validation receipts are still being
prepared. Until the fields and gates below are complete, the `1.21.1` branch
must not be described as a finished or fully validated port.

The repository's GitHub default branch remains `1.20.1` during preparation.
After the fresh `1.21.1` branch and its exact packaged artifact pass the
recorded gates, the repository can switch its default branch to `1.21.1`.

## Source provenance

| Role | Commit | Notes |
| --- | --- | --- |
| Intended upstream input | `0201301e5aa371d7f0816b1d786ca89e99936912` | Sakura upstream Minecraft 1.20.1 source used as the intended fresh-generation input |
| Historical initial port | `65643b9e2acf1413172696683bb6b67941244769` | Beginning of the earlier 1.21.1 port history |
| Historical manually repaired code baseline | `2204aedd9936307bf05f5a46de46fc4cccd317b1` | Preserved beneath the documentation commits on `1.21.1-manual`; this is not the fresh generated baseline |
| Preserved manual branch head | `d24468696972b0f9cb60ee52d33d1b40f8144bb1` | Current public `1.21.1-manual` head; keep this branch and its history unchanged |

Upstream project: <https://github.com/0999312/Sakura_mod>

| Upstream field | Value |
| --- | --- |
| Git tree | `ef9f417b0ff116654add3b45c5b632df60cbabdd` |
| Commit date | `2025-08-08T13:57:33+08:00` |
| Source version | `1.0.10-1.20.1` |
| Source Minecraft version | `1.20.1` |
| Source loader | Forge `47.3.0`, built with the NeoForge ModDev `legacyforge` plugin |
| Source Java toolchain | `17` |
| License | MIT, Copyright (c) 2019 0999312 |

## Fresh generation receipt

The release preparation process must replace every `Pending` field with an
exact, reproducible value.

| Field | Value |
| --- | --- |
| Required ModPorter version | `0.4.0` |
| Official release tag and publication evidence | [v0.4.0 Developer Preview](https://github.com/CrabMods/modporter/releases/tag/v0.4.0) |
| Official ModPorter source commit | `da08fa920f733f572a45329b25b78a712de47452` |
| Official ModPorter source Git tree | `6d932849fa41e29880205cb3dfffa2ef53e154a5` |
| Official release JAR SHA-256 | `1134fa8ae04d50d904396fc2b59b77d7e668464f8dd63cfcf4c2eb521d37a9f4` |
| Exporter script SHA-256 | `0250f995115805e4b99a85231093a36a7833d6f5c5499e8a865197b6a8bf73d3` |
| Invocation | The bounded official-release export command below with `--add-tool-credit`. |
| Generation date | 2026-11-16 |
| Generated output tree SHA-256 | `cf6b747a083719af53dfafe0933a48f380c9e9ed2b2a60cf75c1f33c5dc4ec6c` |
| Imported candidate Git tree | Pending |
| Candidate artifact SHA-256 | Pending |

## Reproducible generation and import

Use the final published ModPorter 0.4.0 fat JAR, its matching
`scripts/export_research_port.py`, and the exact upstream commit above. The
bounded exporter verifies the clean source revision and released tool, runs
ModPorter against a Git archive, preserves the original metadata, and writes
the publication notice and machine-readable provenance record. Export into a
new empty directory; do not run the tool over either the historical manual
branch or the candidate repository working tree.

The exporter verifies the public versioned release and its published
`SHA256SUMS` before running the generator. Generated plant compatibility
sources, when present, remain tracked with their licenses and provenance so
the public checkout contains the complete build inputs.

```powershell
$ScratchRoot = 'S:\modporter-candidate-export\sakura-0.4.0'
$env:TEMP = Join-Path $ScratchRoot 'temp'
$env:TMP = $env:TEMP
$env:PYTHONDONTWRITEBYTECODE = '1'
$Upstream = Join-Path $ScratchRoot 'upstream-0201301'
$Export = Join-Path $ScratchRoot 'official-export'
$ModPorterSource = 'S:\path\to\official-modporter-v0.4.0-source'
$ModPorterJar = 'S:\path\to\official-modporter-v0.4.0-release\modporter-0.4.0-all.jar'
$OfficialJarSha256 = '<copy from the official v0.4.0 SHA256SUMS asset>'

New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
git clone --config core.autocrlf=false --filter=blob:none --no-checkout `
  https://github.com/0999312/Sakura_mod.git $Upstream
git -C $Upstream checkout --detach `
  0201301e5aa371d7f0816b1d786ca89e99936912

python -B "$ModPorterSource\scripts\export_research_port.py" `
  --jar $ModPorterJar `
  --src $Upstream `
  --out $Export `
  --repo-url https://github.com/crabsatellite/sakura-modporter-port `
  --mod-id sakura `
  --display-name "Sakura Mod" `
  --candidate-version 1.0.10-1.21.1-modporter.1 `
  --upstream-url https://github.com/0999312/Sakura_mod.git `
  --upstream-commit 0201301e5aa371d7f0816b1d786ca89e99936912
```

The scratch root is explicit on `S:` and remains outside every
`.modporter/tmp` producer-owned subtree. `$ModPorterSource`, `$ModPorterJar`,
and `$OfficialJarSha256` must come from the published public `v0.4.0` source
and release assets. Do not substitute an unpublished or locally built JAR.
The official `v0.4.0` Developer Preview is acceptable only after its GitHub
release and published `SHA256SUMS` have been verified.

Before importing the generated output:

1. Review `MODPORTER-PROVENANCE.json` and confirm its ModPorter version, JAR
   SHA-256, exact invocation, upstream commit, and upstream
   Git tree.
2. Confirm the output contains no source checkout metadata, local run
   directories, Gradle caches, logs, benchmark workspaces, or packaged JARs.
3. Export the generated tree into a fresh temporary Git worktree based on
   `origin/1.21.1`; keep repository policy files, `LICENSE`, `README.md`,
   `PORTING.md`, `.github/`, and `validation/` from this preparation branch.
4. Replace the prior candidate source and project files with the generated
   output. Do not copy files from `1.21.1-manual`.
5. Retain the repository-owned public build adapter at the candidate root:
   `AGENTS.md`, `mod-build.ps1`, `mod-build.cmd`, `scripts/`, and
   `gradle/modporter-testing.gradle`. Run the preparation command below; it
   applies the hook only after the official provenance and reviewed upstream
   pin pass. CI needs no `gh` command, GitHub token, or private framework
   credentials; any optional maintainer sidecar stays outside Git.
6. Review `git status`, the full diff, license and copyright notices, mod
   metadata, the ModPorter report, and the generated tree hash before
   committing.
7. Run source-project validation, build the public candidate JAR from the
   committed tree, and then repeat client/server validation against that exact
   JAR from clean `mods` directories.

After the generated files and retained adapter are in the fresh candidate
worktree, prepare, check, and invoke the public build with:

```powershell
python -B .\scripts\prepare_candidate_import.py `
  --project-root . `
  --official-jar-sha256 $OfficialJarSha256
python -B .\scripts\prepare_candidate_import.py `
  --project-root . `
  --official-jar-sha256 $OfficialJarSha256 `
  --check
.\mod-build.ps1 ensure
.\mod-build.ps1 build
```

The preparation command changes only the generated `build.gradle` and applies
`gradle/modporter-testing.gradle` as one canonical top-level line. It does not
patch Java or Kotlin. The canonical launcher checks that exact application
again before any Gradle command.

After import, record the candidate Git tree and artifact hashes with:

```powershell
git write-tree
Get-FileHash -Algorithm SHA256 $ModPorterJar
Get-FileHash -Algorithm SHA256 .\path\to\sakura-candidate.jar
```

`git write-tree` must be run only after the candidate index contains exactly
the intended generated source plus the preserved repository policy files.

## Validation receipt

Validation must run against the fresh generated tree and the artifact produced
from it.

| Gate | Result | Evidence |
| --- | --- | --- |
| Compile | Pending | Pending |
| Dedicated-server startup | Pending | Pending |
| Client startup | Pending | Pending |
| Saved-world loading and controlled save | Pending | Pending |
| Relevant creative tabs open and every displayed stack can be created | Pending | Pending |
| Runtime-log audit | Pending | Pending |

These gates establish a useful compatibility baseline. They do not constitute
exhaustive testing of every item, block, recipe, enchantment, teleport,
interaction, rendering path, or gameplay detail.

## Maintenance model

After validation and the planned default-branch switch, `1.21.1` will be the
generated research and learning baseline. Reusable migration defects should be fixed in
[ModPorter](https://github.com/CrabMods/modporter), followed by regeneration
and renewed validation. This repository is not intended to accumulate an
independent layer of per-mod manual compatibility repairs.

Migration reports and rule proposals belong in the
[ModPorter issue tracker](https://github.com/CrabMods/modporter/issues).
Include the input construct, generated output, expected output, and a minimal
reproduction when possible.

## Release and rights checklist

Before publishing a candidate artifact:

- Complete the generation and validation receipts above.
- Label it as an unofficial, ModPorter-generated candidate with human review.
- Include the repository `LICENSE` in the packaged artifact.
- Preserve the original authorship and copyright notices.
- Avoid implying endorsement or support by Sakura's original authors.
- State that individual content details have not been exhaustively validated.

The preserved MIT license applies according to its terms. Source generation
does not transfer ownership of Sakura code or assets and does not create extra
permissions beyond the licenses that already apply.

## Repository metadata proposal

Suggested repository description:

> Unofficial ModPorter-generated Sakura 1.21.1 NeoForge developer research baseline.

Suggested topics:

- `minecraft`
- `minecraft-mod`
- `neoforge`
- `minecraft-1-21-1`
- `modporter`
- `developer-research`
- `unofficial-port`

## Import scope

The imported production code and resources match the generated export byte for
byte. The public build hook, verification project, repository metadata, and
developer documentation are maintained separately. Uncompiled upstream backup resources are excluded from this developer baseline; they remain available at the
pinned upstream revision.
