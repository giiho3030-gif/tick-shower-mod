# Break Ticks

Client-side-only Fabric mod for Minecraft **26.2**: while you are actively breaking a block in survival or
adventure, the elapsed/total break ticks (`12/45t`) are drawn flat **on the exact face you are hitting**, on
top of the vanilla crack overlay. It disappears the moment you stop, switch target, break the block, or if the
block is unbreakable. Creative hides it. No config, no keybind, no Mod Menu, no server side, one jar:
one entrypoint class plus two tiny mixins (~190 lines of code, the rest is comments on the face maths and tick maths).

## Build / run

```bash
./gradlew build      # jar in build/libs/breakticks-1.0.0.jar
./gradlew runClient  # dev client with the mod + Fabric API already on the classpath
```

Needs JDK 25 and Gradle 9.5.1 (the wrapper downloads it); toolchain and mappings are pinned in
`gradle.properties` (Loom `1.17.20`, Loader `0.19.5`, Fabric API `0.159.0+26.2`, official Mojang mappings —
there is no `mappings` line in `build.gradle` on purpose).

`submitCustom` is an interface Fabric API injects into `OrderedSubmitNodeCollector` through its transitive
classtweaker, so it resolves as long as Fabric API is on the classpath; if your setup does not apply transitive
wideners, cast the receiver: `((FabricOrderedSubmitNodeCollector) context.submitNodeCollector()).submitCustom(...)`.

## How the number is computed

Vanilla only ever does `destroyProgress += state.getDestroyProgress(player, level, pos)` per tick and breaks
the block at `destroyProgress >= 1.0`, so the exact tick count for the block you are looking at is
`ceil(1 / delta)` and the elapsed ticks are `round(progress * total)`. Asking the block state for the delta
instead of re-implementing mining speed means tools, efficiency, haste, mining fatigue, water, standing in
lava, wrong tool and unbreakable blocks (`delta <= 0` → hidden) all match vanilla for free.

## Minecraft 26.1.2 notes

The tick side is identical (`MultiPlayerGameMode` keeps `destroyBlockPos`, `destroyProgress`, `isDestroying()`
and `startDestroyBlock` / `continueDestroyBlock` / `stopDestroyBlock`), and `LevelRenderEvents.BEFORE_GIZMOS`
exists there too. Only the submit call uses 26.2 APIs (`SubmitRenderPhases`, `submitCustom`,
`TextFeatureRenderer.Submit`). Set `minecraft_version=26.1.2`, `fabric_api_version=0.155.2+26.1.2`, keep
JDK 25 / Gradle 9.5.1 / Loom 1.17, and pick one:

* Quick swap — the collector's older text submit, same tail of arguments, drop the `AFTER_TERRAIN` phase:

  ```java
  context.submitNodeCollector().submitText(poseStack, -width / 2.0F, -4.5F, renderLabel, false,
          Font.DisplayMode.POLYGON_OFFSET, 15728880, 0xFFFFFFFF, 0xB0000000, 0);
  ```

  It lands in the `texts` phase, which in 26.1.2 runs *before* the breaking overlay, so the number sits under
  the cracks (right face, slightly tinted).
* Keep it on top — 26.1.2 still has `context.bufferSource()` (a `MultiBufferSource.BufferSource`, gone in
  26.2), so move the registration to `LevelRenderEvents.END_MAIN` and replace the submit with
  `client.font.drawInBatch(renderLabel, -width / 2.0F, -4.5F, 0xFFFFFFFF, false, poseStack.last().pose(),
  context.bufferSource(), Font.DisplayMode.POLYGON_OFFSET, 0xB0000000, 15728880);` inside the exact same
  push / translate / `mulPose` / scale block, then pop.
