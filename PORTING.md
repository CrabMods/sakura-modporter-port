# Porting record

Sakura Mod candidate `1.0.10-1.21.1-modporter.1` was generated on November 16, 2026 with the official ModPorter 0.4.0 release and validated as its exact packaged JAR.

## Manual history

The earlier manual port remains on [1.21.1-manual](https://github.com/CrabMods/sakura-modporter-port/tree/1.21.1-manual), preserved at `d24468696972b0f9cb60ee52d33d1b40f8144bb1`. Its manually repaired code baseline is `2204aedd9936307bf05f5a46de46fc4cccd317b1`. The generated `1.21.1` branch starts again from the pinned upstream source below.

## Provenance

| Field | Recorded value |
| --- | --- |
| Upstream repository | https://github.com/0999312/Sakura_mod.git |
| Upstream commit | `0201301e5aa371d7f0816b1d786ca89e99936912` |
| Upstream Git tree | `ef9f417b0ff116654add3b45c5b632df60cbabdd` |
| Upstream license | MIT; original copyright preserved |
| Tool release | [ModPorter v0.4.0 Developer Preview](https://github.com/CrabMods/modporter/releases/tag/v0.4.0) |
| Tool source commit | `da08fa920f733f572a45329b25b78a712de47452` |
| Tool source Git tree | `6d932849fa41e29880205cb3dfffa2ef53e154a5` |
| Official tool JAR SHA-256 | `1134fa8ae04d50d904396fc2b59b77d7e668464f8dd63cfcf4c2eb521d37a9f4` |
| Exporter SHA-256 | `0250f995115805e4b99a85231093a36a7833d6f5c5499e8a865197b6a8bf73d3` |
| Generated output tree SHA-256 | `cf6b747a083719af53dfafe0933a48f380c9e9ed2b2a60cf75c1f33c5dc4ec6c` |
| Build source commit | `e4d3a51e11007e234c3219751e4c5e91b134b023` |
| Build source Git tree | `3474c4a040b73ff380736d09ebc4df2e83e269fd` |
| Candidate JAR | `Sakura-1.0.10-1.21.1-modporter.1.jar` |
| Candidate JAR SHA-256 | `fa3daee008be81a4a53fe0965662d27f5382104d3b34135bc7d6c0f127df1332` |
| Game / loader / Java | Minecraft 1.21.1 / NeoForge 21.1.230 / Java 21 |

## Generated source and import

All 2407 production source and resource files match the generated export byte for byte. Mod logic has no hand patches. Repository metadata, the public build adapter, documentation, and the independent validation project are separate layers. The uncompiled upstream backup resources are omitted; the pinned upstream revision retains them.

The output-tree fingerprint uses sorted relative paths, file sizes, and file SHA-256 values (`sha256-relative-nul-size-nul-file-sha256-lf-v1`). `MODPORTER-PROVENANCE.json` and `MODPORTER-IMPORT.json` identify the source, tool, and import boundary.

## Recorded validation

| Lane | Process starts | World loads | Scenarios | Cleanup failures | Unexplained log findings |
| --- | --- | --- | --- | --- | --- |
| Dedicated server | 1 | 1 | 2 | 0 | 0 |
| Client | 1 | 1 | 4 | 0 | 0 |

The client matched all **198 registered item IDs** to the pinned upstream source, opened both relevant creative tabs, and captured **36 rendered rows**. All 1619 occupied slot observations have decoded nonblank icon evidence, including 824 target slot observations; icon exceptions are zero. Native item/component equality, client and server inventory cleanup, mouse-state restoration, saved-world shutdown, and the unchanged original fixture all passed.

`VALIDATION.json` records the exact artifact checksum, lane results, source-bound receipts, and evidence hashes. The checks cover loading and item-interface presentation. Detailed item, block, recipe, and integration behavior remains for developer review and focused playtesting.

### Recorded dependency resources

The official [MMLib 1.3.16 artifact](https://modrinth.com/mod/mmlib/version/UZbuAxuL), SHA-256 `6010a3ba353a3f88343630bc053250105df49b331dfcdd254810fbb46ef7f8a4`, references a missing welcome sound/subtitle and development refmap. These three exact client messages are retained as verified dependency findings. Classification checks the native loaded JAR hash, its ZIP contents, and the absence of target overrides of those resources. Other warnings and errors remain blocking findings.

## Reproduce generation

Use the exact official release JAR and matching v0.4.0 exporter. The source clone disables automatic line-ending conversion so blob-bound checks use the original upstream bytes. Export to a fresh S: directory.

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
  --repo-url https://github.com/CrabMods/sakura-modporter-port `
  --mod-id sakura `
  --display-name "Sakura Mod" `
  --candidate-version 1.0.10-1.21.1-modporter.1 `
  --upstream-url https://github.com/0999312/Sakura_mod.git `
  --upstream-commit 0201301e5aa371d7f0816b1d786ca89e99936912
```

After reviewing the generated output, retain the repository-owned public build adapter and prepare its hook:

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

## Reproduce packaged validation

The checked-in `validation/fixtures/client-world.zip` supplies the exact owned, player-free vanilla fixture. Extract it into a separate scratch directory, set the ignored target configuration from `validation/targets/sakura.properties`, and follow [the validation guide](validation/README.md). The target is loaded only from its regular JAR in the clean `mods` directory; the validation harness is a separate source-set mod.

## Repository policy

The Minecraft `1.21.1` branch contains this generated developer baseline; later game versions use separate version branches. Migration fixes belong in reusable [ModPorter rules and issues](https://github.com/CrabMods/modporter/issues). The original mod remains MIT with its original copyright. Public build and validation components retain their separate Apache-2.0 notices. Human review is part of preparing a generated port for broader distribution.

The packaging metadata fixes `rootProject.name` to `sakura`, keeping the JAR manifest title independent of the checkout directory. Production code and resource payloads remain unchanged.

## Repository ownership

This repository was transferred to the CrabMods organization on November 16, 2026. Its GitHub repository ID remains `1156141731`; the existing release, tags, and manual branch are preserved. `MODPORTER-PROVENANCE.json` retains the former URL recorded during generation, and GitHub redirects it to this repository. New generations use the canonical CrabMods URL. The tested JAR remains unchanged.
