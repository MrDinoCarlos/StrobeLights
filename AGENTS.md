# StrobeLights maintenance

- `main` is the unified source branch. The historical `paper-*` worktrees are
  references, not alternate places to implement fixes.
- Shared Java lives in `src/main/java`. Only old-API implementations belong in
  `compatibility/legacy/java`; Gradle replaces matching source files for 1.20.1.
- `resource-pack` is the 1.21.4 baseline. Version overlays and explicit exclusions
  live in `compatibility/<version>`. The later legacy Fabulous pipeline shares
  its unchanged files through `compatibility/modern/resource-pack`.
- Preserve the fixed source carrier used by 1.21.11. Preserve the 1.20.1 model,
  brightness and transport encoding. Shader ABIs are not interchangeable.
- Minecraft 26.3 uses `end_of_frame`, explicit shader locations, `#include` and
  OIT-aware item hooks. Do not reintroduce its removed transparency post chain.
- `gradlew build` must pass the seven API test suites and `verifyExports`.
  Export exactly five family JARs to `build/exports`: `1.20.X`, `1.21.X`,
  `26.1.X`, `26.2.X` and `26.3.X`, with matching embedded packs.
  Never combine the 26.x releases into a single `26.X` JAR.
- Family filenames do not imply support for every patch. Add and test an explicit
  `MinecraftProfile` before claiming another server/client version.
- GLSL compile validation is separate from an actual client visual test. Document
  which was performed; do not claim GPU/OptiFine/TRP/Nexo runtime validation from
  Java unit tests alone.
- The 26.3 renderer uses reversed-Z (clear depth zero, GREATER, near depth one).
  After GLSL expansion, run `gpuTest263` to check real capture/composition pixels.
  This hidden OpenGL regression is not a full Minecraft/mod compatibility test.
- The 26.3 point carrier relies on CPU-transformed `Position`. Keep all four
  model vertices coincident; a literal model-local center loses the real source.
