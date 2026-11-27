# GeyserJavaGlowing: VoidPE maintenance build

Derived from GeyserJavaGlowing v0.2.13, source commit 77032b6c44b44d016e0a3f0c40fb29deb2e1c53b (https://github.com/gamelist1990/GeyserJavaGlowing). Original authors and bundled pack credits are retained. The embedded pack was extracted byte-for-byte from the user's 0.2.13 JAR; its SHA-256 is d63e07dac405b20b6544c5c28a3ba3be2f90208339efe684ece9ebc3441f3b75. No texture, player model, or material has been modified.

This variant avoids revisiting glowing players on scoreboard-team packets that do not change their visible team color. It does not optimize the client-side geometry and may not address client rendering or player-appearance lag. Full restart and real Bedrock visual/performance tests are required.
