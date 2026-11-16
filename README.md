# Sakura ModPorter Port

This repository hosts an unofficial developer baseline for bringing
[Sakura](https://github.com/0999312/Sakura_mod) from Minecraft Forge 1.20.1 to
NeoForge 1.21.1. It is intended for research, learning, community review, and
reducing the repetitive work required for a manual port.

The `1.21.1` candidate is generated with
[ModPorter 0.4.0](https://github.com/CrabMods/modporter), a rules-based Forge
1.20.1 to NeoForge 1.21.1 migration tool. Reusable transformation rules produce
the mod code; migration fixes belong in ModPorter so the candidate can be
regenerated. Use this code as a starting point for a reviewed manual port.

This project is not an official Sakura release and is not affiliated with or
endorsed by Sakura's original authors. Human review and focused playtesting are
expected before wider distribution.

## Current status

The generated `1.0.10-1.21.1-modporter.1` JAR passed the recorded server and
client gates on Minecraft 1.21.1, NeoForge 21.1.230, and Java 21. All 198
registered item IDs matched the pinned source; 36 rendered-row screenshots
cover the two relevant creative tabs. [VALIDATION.json](VALIDATION.json) binds
the results to the exact artifact.

## Branches

| Branch | Purpose |
| --- | --- |
| [`1.20.1`](https://github.com/CrabMods/sakura-modporter-port/tree/1.20.1) | Historical upstream-version branch |
| [`1.21.1`](https://github.com/CrabMods/sakura-modporter-port/tree/1.21.1) | ModPorter-generated NeoForge 1.21.1 developer baseline |
| [`1.21.1-manual`](https://github.com/CrabMods/sakura-modporter-port/tree/1.21.1-manual) | Preserved historical port with per-mod manual repairs |

Future porting work should improve reusable ModPorter rules and regenerate the
generated branch. The historical manual branch remains unchanged and available
for comparison.

## Source baseline

Generation is pinned to the public source at
[`0999312/Sakura_mod@0201301e5aa371d7f0816b1d786ca89e99936912`](https://github.com/0999312/Sakura_mod/tree/0201301e5aa371d7f0816b1d786ca89e99936912),
which identifies itself as version `1.0.10`. As checked on November 16, 2026,
the official
[download catalog](https://www.curseforge.com/minecraft/mc-mods/sakura/files/all)
lists `1.1.16` for Minecraft `1.20.1`, with no Minecraft `1.21.1` file listed.
The candidate version follows the pinned public source so developers can
compare this generated baseline with later upstream releases.

## Validation status

| Gate | Recorded packaged-JAR result |
| --- | --- |
| Public source build | Passed |
| Dedicated-server startup, save, and controlled stop | Passed |
| Client startup and saved-world load | Passed |
| Native creative inventory and item coverage | All 198 registered items; 2 tabs; 36 row screenshots |
| Occupied-slot icon evidence | 1,619/1,619 observations; zero icon exceptions |
| State cleanup | Client/server inventory and mouse state restored; original fixture unchanged |
| Runtime-log audit | Zero unexplained findings; pinned MMLib resource findings recorded |

These checks cover loading, the in-game item interface, and strict log review.
Detailed item, block, recipe, and integration behavior remains for developer
review and focused playtesting. The official MMLib dependency's welcome-sound,
subtitle, and development-refmap findings are tied to its exact JAR and resource
contents in [PORTING.md](PORTING.md).

## Improving the port

Review the generated source, build it, and test the mod-specific behavior you
intend to use. A repeatable migration fix should become a ModPorter rule and
regression test, followed by a fresh generation of this branch.

Please report migration failures and reusable rule improvements to the
[ModPorter issue tracker](https://github.com/CrabMods/modporter/issues).
Candidate-specific failures should include enough detail to reproduce the
underlying migration problem.

## Authors, copyright, and use

Sakura was created by 0999312 and its contributors. Their original credits,
MIT license, and 2019 copyright notice are preserved in [LICENSE](LICENSE).

Development and distribution should respect the original authors' wishes,
copyright, and the licenses that apply to the source and assets.

## License

The original Sakura source and assets remain governed by the MIT License and
copyright notice preserved in [LICENSE](LICENSE).

The public build and runtime adapter files listed in
[MODPORTER-ADAPTER-NOTICE](MODPORTER-ADAPTER-NOTICE) are separate ModPorter
components under the Apache License 2.0; see
[MODPORTER-ADAPTER-LICENSE](MODPORTER-ADAPTER-LICENSE). The independent
validation project is covered by
[validation/LICENSE](validation/LICENSE) and
[validation/THIRD_PARTY_NOTICES.md](validation/THIRD_PARTY_NOTICES.md).
These Apache-licensed layers do not relicense the generated upstream mod source
or assets. Any separately generated ModPorter support sources are identified
by their own `MODPORTER-SUPPORT-LICENSE` and
`MODPORTER-SUPPORT-NOTICE` files.

Shared compatibility libraries carry their corresponding source, component
licenses, and provenance inside their JARs. Their per-file terms are recorded
in the generated porting manifest.

Detailed generation provenance and the maintenance policy are recorded in
[PORTING.md](PORTING.md).
