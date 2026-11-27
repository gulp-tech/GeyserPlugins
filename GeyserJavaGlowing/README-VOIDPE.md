# GeyserJavaGlowing: VoidPE maintenance build

Derived from GeyserJavaGlowing v0.2.13, source commit 77032b6c44b44d016e0a3f0c40fb29deb2e1c53b (https://github.com/gamelist1990/GeyserJavaGlowing). Original authors and bundled pack credits are retained. The voidpe.1 embedded pack was extracted byte-for-byte from the user's 0.2.13 JAR; its SHA-256 is d63e07dac405b20b6544c5c28a3ba3be2f90208339efe684ece9ebc3441f3b75. In both builds no texture, player model, or material has been modified.

This variant avoids revisiting glowing players on scoreboard-team packets that do not change their visible team color. It does not optimize the client-side geometry and may not address client rendering or player-appearance lag. Full restart and real Bedrock visual/performance tests are required.

## Experimental voidpe.2 FPS build

The 66 glow render-controller conditions check the glow mode before other conditions. Logical conditions and all geometry, textures, animations and materials remain unchanged. The pack manifest version is updated. This may reduce Molang condition evaluation for non-glowing players if the Bedrock runtime short-circuits these expressions, but FPS gains and visuals have not been verified in game. Build or validate using `python3 tools/pack_tool.py build|test src/main/resources/GeyserJavaGlowing.mcpack`.

## Experimental voidpe.3: reduce outline rig cost

The same condition ordering as voidpe.2 is retained. This pack folds 435 leaf bones per outline geometry into their unchanged parents (499 -> 64 for each player model). The validator independently compares every original cube and its remaining ancestor transform chain: 6,278 slim / 6,534 wide cubes, UVs and inflate values are preserved. Armor, cape, elytra, textures, materials, animations and render controllers are unchanged. The manifest version is incremented so Bedrock receives the updated pack. There is no proof that rig size is the user's FPS bottleneck; the number of cubes is unchanged and there is no guarantee that FPS will improve. Changes in draw order or external packs can still affect appearance; test in Bedrock and revert if necessary.
