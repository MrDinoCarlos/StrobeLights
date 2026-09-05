package es.mrdino.strobelights.resourcepack;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ShaderPackContractTest {

    private static final Path PACK = Path.of("resource-pack");

    @Test
    void targetsMinecraft12110AndContainsTheLightPipeline() throws IOException {
        assertContains(PACK.resolve("pack.mcmeta"), "\"pack_format\": 69");
        assertContains(
            PACK.resolve("assets/minecraft/post_effect/transparency.json"),
            "minecraft:post/light"
        );
        assertContains(
            PACK.resolve("assets/minecraft/shaders/post/light.fsh"),
            "lightDist < lightRadius"
        );
    }

    @Test
    void keepsTheTechnicalMarkerMicroscopicButNonDegenerateForOptiFine()
        throws IOException {
        assertContains(
            PACK.resolve("assets/minecraft/items/lime_stained_glass.json"),
            "\"threshold\": 6700"
        );
        assertContains(
            PACK.resolve("assets/minecraft/items/lime_stained_glass.json"),
            "minecraft:custom_model_data"
        );
        assertContains(
            PACK.resolve("assets/minecraft/models/item/lp_custom.json"),
            "\"from\": [7.5,8,7.5]"
        );
        assertContains(
            PACK.resolve("assets/minecraft/models/item/lp_custom.json"),
            "\"to\": [8.5,8,8.5]"
        );
        assertContains(
            PACK.resolve("assets/minecraft/models/item/lp_custom.json"),
            "\"scale\":[0.002,0.002,0.002]"
        );
        assertNotContains(
            PACK.resolve("assets/minecraft/models/item/lp_custom.json"),
            "\"scale\":[0.0,0.0,0.0]"
        );
        assertContains(
            PACK.resolve("assets/minecraft/models/item/lp.json"),
            "\"from\": [7.5,8,7.5]"
        );
        assertContains(
            PACK.resolve("assets/minecraft/models/item/lp.json"),
            "\"scale\":[0.002,0.002,0.002]"
        );
        assertNotContains(
            PACK.resolve(
                "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.vsh"
            ),
            "ModelViewMat * vec4(0.0, 0.0, 0.0, 1.0)"
        );
        assertContains(
            PACK.resolve(
                "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.vsh"
            ),
            "ModelViewMat * vec4(Position, 1.0)"
        );
        assertContains(
            PACK.resolve(
                "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.vsh"
            ),
            "#define HALFMARKER tmp.z / 64.0"
        );
        assertFalse(Files.exists(PACK.resolve(
            "assets/minecraft/optifine/emissive.properties"
        )));
        assertFalse(Files.exists(PACK.resolve(
            "assets/minecraft/textures/misc/white_e.png"
        )));
        assertFalse(Files.exists(PACK.resolve(
            "assets/minecraft/optifine/dynamic_lights.properties"
        )));
    }

    @Test
    void acceptsOptiFineAlphaQuantizationWithoutUsingFogAsAWorldMarkerGate()
        throws IOException {
        Path utils = PACK.resolve("assets/minecraft/shaders/include/utils.glsl");
        Path core = PACK.resolve(
            "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.vsh"
        );
        assertContains(utils, "#define LIGHTALPHATOLERANCE (2.0 / 255.0)");
        assertContains(
            core,
            "abs(tmpcol.a - LIGHTALPHA) <= LIGHTALPHATOLERANCE"
        );
        assertContains(core, "float markerTextureFloor = tmpcol.a * 0.5");
        assertContains(
            core,
            "float markerTexturePeak = max(max(tmpcol.r, tmpcol.g), tmpcol.b)"
        );
        assertContains(
            core,
            "float markerTextureBase = min(min(tmpcol.r, tmpcol.g), tmpcol.b)"
        );
        assertContains(core, "markerTexturePeak >= markerTextureFloor");
        assertContains(
            core,
            "markerTextureBase >= markerTexturePeak * 0.75"
        );
        assertNotContains(core, "tmpcol.a == LIGHTALPHA");
        assertContains(
            core,
            "marker = float(!gui && markerAlpha && markerTextureCarrier)"
        );
        assertContains(core, "vertexColor = vec4(Color.rgb, 1.0)");
        assertNotContains(core, "min(min(tmpcol.r, tmpcol.g), tmpcol.b) > 0.99");
        assertNotContains(core, "bool hand = isHand(FogStart, FogEnd)");
        assertNotContains(core, "!hand && !gui");
        assertNotContains(
            PACK.resolve(
                "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.fsh"
            ),
            "bool hand = isHand(FogStart, FogEnd)"
        );
    }

    @Test
    void transportsMarkersThroughTheOptiFineCompatibleColorAttachment()
        throws IOException {
        Path utils = PACK.resolve("assets/minecraft/shaders/include/utils.glsl");
        Path core = PACK.resolve(
            "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.fsh"
        );
        Path filter = PACK.resolve("assets/minecraft/shaders/post/filter.fsh");
        Path aggregate = PACK.resolve("assets/minecraft/shaders/post/aggregate_6.fsh");
        Path pipeline = PACK.resolve("assets/minecraft/post_effect/transparency.json");

        assertNotContains(utils, "DEPTHCODEPRECISION");
        assertNotContains(utils, "decodeLightPositionDepth");
        assertContains(core, "inverse(uvPerPixel) * (texCoord2 - vec2(0.5))");
        assertContains(core, "fragColor = vec4(vec3(0.4), 5.0 / 255.0)");
        assertContains(core, "fragColor = vec4(bitColor * 0.5, 2.0 / 255.0)");
        assertContains(core, "gl_FragDepth = centerDepth * LIGHTDEPTH");
        assertNotContains(core, "fragColor = vec4(vertexColor.rgb, 1.0)");
        assertNotContains(core, "DEPTHCODESIGNATURE");
        assertContains(filter, "uniform sampler2D DiffuseSampler");
        assertContains(filter, "depth / LIGHTDEPTH");
        assertContains(filter, "int encodedValue = 0");
        assertContains(filter, "encodedValue |= triplet << (payloadIndex * 3)");
        assertContains(filter, "if (validCarrier)");
        assertContains(
            aggregate,
            "texture(ItemEntityDepthSampler, samplepos).r / LIGHTDEPTH"
        );
        assertContains(pipeline, "\"vertex_shader\": \"minecraft:post/filter\"");
        assertNotContains(pipeline, "markerdata");
    }

    @Test
    void keepsWorldLightVerticesAtTheSavedSourceWithFrustumCullingDisabled()
        throws IOException {
        Path manager = Path.of(
            "src/main/java/es/mrdino/strobelights/service/StrobeManager.java"
        );
        assertContains(manager, "updateFixedSourceViewers(strobe, state)");
        assertContains(manager, "Location markerLocation = fixedSourceLocation(strobe)");
        assertContains(manager, "Vector toLight = source.toVector().subtract(eye.toVector())");
        assertContains(manager, "spawnFixedLightDisplay(markerLocation");
        assertContains(manager, "display.setDisplayWidth(carrier.displayWidth())");
        assertContains(manager, "display.setDisplayHeight(carrier.displayHeight())");
        assertContains(manager, "new Vector3f(0.0f, carrier.translationY(), 0.0f)");
        assertContains(manager, "sourceY,\n            0.0f,\n            0.0f,\n            0.0f");
        assertContains(manager, "state.marker.setItemStack(technicalMarker(0))");
        assertNotContains(manager, "state.marker.setItemStack(new ItemStack(Material.AIR))");
        assertNotContains(manager, "displayAnchor(");
        assertNotContains(manager, "setDisplaySourceOffset(");
        assertNotContains(manager, "spawnOffscreenProxy(");
    }

    @Test
    void carriesTheUpstreamMitLicense() throws IOException {
        assertContains(PACK.resolve("LICENSE-Light-Painter.txt"), "MIT License");
        assertContains(PACK.resolve("LICENSE-Light-Painter.txt"), "Copyright (c) 2020 Bradley Qu");
    }

    @Test
    void containsThePlayerOnlyRgbCameraFlashPass() throws IOException {
        Path flashShader = PACK.resolve("assets/minecraft/shaders/post/flash_apply.fsh");
        assertContains(flashShader, "isCameraFlash");
        assertContains(flashShader, "flashColor");
        assertContains(flashShader, "mix(outColor.rgb, flashColor");
        assertContains(
            PACK.resolve("assets/minecraft/post_effect/transparency.json"),
            "minecraft:post/flash_apply"
        );
        assertNotContains(flashShader, "int packed =");
        assertNotContains(flashShader, "int packed)");
        assertNotContains(
            PACK.resolve("assets/minecraft/shaders/post/light.fsh"),
            "int packed ="
        );
        assertNotContains(
            PACK.resolve("assets/minecraft/shaders/post/light_t.fsh"),
            "int packed ="
        );
    }

    @Test
    void reconstructsNearAndBehindLightsFromPrivateMarkers() throws IOException {
        Path core = PACK.resolve(
            "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.vsh"
        );
        Path aggregate = PACK.resolve("assets/minecraft/shaders/post/aggregate_6.fsh");
        Path utils = PACK.resolve("assets/minecraft/shaders/include/utils.glsl");
        assertContains(core, "2.0 / max(abs(ProjMat[1][1]), 0.0001)");
        assertContains(core, "encodeProjectionK(projectionK)");
        assertContains(core, "((projectionCode & 15) << 16)");
        assertContains(aggregate, "markerConversionK = decodeProjectionK(projectionCode)");
        assertContains(utils, "int encodeProjectionK(float value)");
        assertContains(utils, "float decodeProjectionK(int code)");
        assertContains(utils, "if (code == 5) return 0.400");
        assertContains(utils, "if (code == 13) return 2.000");
        for (String shaderName : new String[] {"light.fsh", "light_t.fsh"}) {
            Path shader = PACK.resolve("assets/minecraft/shaders/post").resolve(shaderName);
            assertContains(shader, "isOffscreenLight");
            assertContains(shader, "reconstructOffscreenLight");
            assertContains(shader, "int mode = (encodedValue >> 20) & 7");
            assertContains(shader, "int projectionCode = (encodedValue >> 16) & 15");
            assertContains(shader, "float axisInverse = 16.0");
            assertContains(shader, "float depthInverse = 4.0");
            assertContains(shader, "return proxyCoord");
            assertContains(shader, "if (mode == 4)");
            assertContains(shader, "if (mode == 5)");
            assertContains(shader, "markerConversionK = decodeProjectionK(projectionCode)");
            assertContains(shader, "screenCoord * markerConversionK * depth");
            assertNotContains(shader, "return color * intensity");
            assertContains(shader, "if (lightDist < lightRadius");
            assertNotContains(shader, "edgeFade");
        }
    }

    @Test
    void keepsAdjacentLightsAsIndependentCenters()
        throws IOException {
        Path centers = PACK.resolve("assets/minecraft/shaders/post/centers.fsh");
        assertContains(centers, "bool sameEncodedMarker");
        assertContains(centers, "vec3(1.5 / 255.0)");
        assertContains(centers, "sameEncodedMarker(outColor.rgb, c1)");
        assertContains(centers, "texCoord + vec2(oneTexel.x");
    }

    @Test
    void carriesPerStrobeExpansionWithoutReducingRgbOrZoomMetadata()
        throws IOException {
        Path coreVertex = PACK.resolve(
            "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.vsh"
        );
        Path coreFragment = PACK.resolve(
            "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.fsh"
        );
        Path filter = PACK.resolve("assets/minecraft/shaders/post/filter.fsh");
        Path aggregate = PACK.resolve("assets/minecraft/shaders/post/aggregate_6.fsh");
        Path pipeline = PACK.resolve("assets/minecraft/post_effect/transparency.json");

        assertContains(coreVertex, "bool isSourceLight(int encodedValue)");
        assertContains(coreVertex, "lightExpansionCode = (encodedValue >> 16) & 15");
        assertContains(coreVertex, "((expansionCode & 15) << 12)");
        assertContains(coreVertex, "| (color4.r << 8)");
        assertContains(coreVertex, "| (color4.g << 4)");
        assertContains(coreVertex, "| color4.b");
        assertNotContains(coreFragment, "expansionCode");
        assertNotContains(filter, "expectedExpansionSignature");
        assertNotContains(aggregate, "MarkerDataSampler");
        assertContains(aggregate, "expansionCode = (encodedValue >> 12) & 15");
        assertNotContains(pipeline, "markerdata");
        assertContains(pipeline, "\"height\": 5");
        for (String shaderName : new String[] {"light.fsh", "light_t.fsh"}) {
            Path shader = PACK.resolve("assets/minecraft/shaders/post").resolve(shaderName);
            assertContains(shader, "return (encodedValue >> 23) == 0");
            assertContains(shader, "float((encodedValue >> 8) & 15)");
            assertContains(shader, "float((encodedValue >> 4) & 15)");
            assertContains(shader, "float(encodedValue & 15)");
            assertContains(shader, "decodeExpansionScale(expansionCode)");
        }
    }

    @Test
    void appliesLightWithoutSceneColorBlurOrDriverDependentShadowSamplers()
        throws IOException {
        Path pipeline = PACK.resolve("assets/minecraft/post_effect/transparency.json");
        assertNotContains(pipeline, "minecraft:post/blur_custom");
        assertNotContains(pipeline, "\"sampler_name\": \"Blur\"");
        assertFalse(Files.exists(PACK.resolve(
            "assets/minecraft/shaders/post/blur_custom.fsh"
        )));
        assertFalse(Files.exists(PACK.resolve(
            "assets/minecraft/shaders/post/blur_custom.json"
        )));
        assertFalse(Files.exists(PACK.resolve(
            "assets/minecraft/shaders/post/blur_custom.vsh"
        )));
        for (String shaderName : new String[] {"light_apply.fsh", "light_apply_t.fsh"}) {
            Path shader = PACK.resolve("assets/minecraft/shaders/post").resolve(shaderName);
            assertNotContains(shader, "BlurSampler");
            assertNotContains(shader, "blurColor");
            assertContains(shader, "vec3 illumination = Intensity * lightColor");
        }
    }

    @Test
    void containsGuiOnlyCustomPaperIcons() throws IOException {
        Path definition = PACK.resolve("assets/minecraft/items/paper.json");
        assertContains(definition, "\"threshold\": 6800");
        assertContains(definition, "\"threshold\": 6815");
        assertContains(definition, "\"threshold\": 6822.5");
        assertContains(definition, "\"model\": \"minecraft:item/paper\"");
        assertTrue(Files.isRegularFile(PACK.resolve(
            "assets/strobelights/textures/item/gui/strobe.png"
        )));
        assertTrue(Files.isRegularFile(PACK.resolve(
            "assets/strobelights/textures/item/gui/move.png"
        )));
        assertTrue(Files.isRegularFile(PACK.resolve(
            "assets/strobelights/textures/item/gui/color_swatch.png"
        )));
        assertTrue(Files.isRegularFile(PACK.resolve(
            "assets/strobelights/textures/item/gui/close.png"
        )));
        assertTrue(Files.isRegularFile(PACK.resolve(
            "assets/strobelights/textures/item/gui/flash_power.png"
        )));
        assertTrue(Files.isRegularFile(PACK.resolve(
            "assets/strobelights/textures/item/gui/no_strobes.png"
        )));
        assertTrue(Files.isRegularFile(PACK.resolve(
            "assets/strobelights/textures/item/gui/groups.png"
        )));
        assertTrue(Files.isRegularFile(PACK.resolve(
            "assets/strobelights/textures/item/gui/expansion.png"
        )));
        var groupsIcon = ImageIO.read(PACK.resolve(
            "assets/strobelights/textures/item/gui/groups.png"
        ).toFile());
        var expansionIcon = ImageIO.read(PACK.resolve(
            "assets/strobelights/textures/item/gui/expansion.png"
        ).toFile());
        assertEquals(32, groupsIcon.getWidth());
        assertEquals(32, groupsIcon.getHeight());
        assertEquals(32, expansionIcon.getWidth());
        assertEquals(32, expansionIcon.getHeight());
        assertContains(definition, "\"threshold\": 6823");
        assertContains(definition, "strobelights:item/gui/groups");
        assertContains(definition, "\"threshold\": 6824");
        assertContains(definition, "strobelights:item/gui/expansion");
        Path gui = Path.of("src/main/java/es/mrdino/strobelights/ui/StrobeGui.java");
        assertContains(gui, "title(tr(player, \"gui.delete.confirm\"), NamedTextColor.RED)");
        assertContains(gui, "GuiIcon.DELETE");
        assertContains(gui, "GuiIcon.GROUPS");
        assertContains(gui, "GuiIcon.EXPANSION");
        assertContains(gui, "EDITOR_INTENSITY_SLOT = 12");
        assertContains(gui, "EDITOR_EXPANSION_SLOT = 13");
        assertContains(gui, "EDITOR_REFRESH_SLOT = 14");
        assertContains(gui, "EDITOR_MOVE_SLOT = 19");
        assertContains(gui, "EDITOR_TELEPORT_SLOT = 20");
        assertContains(gui, "EDITOR_GROUP_SLOT = 21");
        assertContains(gui, "EDITOR_RENAME_SLOT = 22");
        assertNotContains(gui, "Material.LIME_CONCRETE");
    }

    @Test
    void anchorsSourcesClientSideWithoutProjectingScreenSpaceShadows() throws IOException {
        Path core = PACK.resolve(
            "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.vsh"
        );
        assertContains(core, "offscreenProxy");
        assertContains(core, "float axisScale = 0.0625");
        assertContains(core, "float depthScale = 0.25");
        for (String shaderName : new String[] {"light.fsh", "light_t.fsh"}) {
            Path shader = PACK.resolve("assets/minecraft/shaders/post").resolve(shaderName);
            assertNotContains(shader, "lightBlocked");
            assertNotContains(shader, "rayIndex < 24");
            assertNotContains(shader, "depthGap > depthBias");
            assertContains(shader, "float axisInverse = 16.0");
            assertContains(shader, "float depthInverse = 4.0");
            assertContains(shader, "float lightRadius = mix(");
            assertContains(shader, "float radialFalloff = pow(");
        }
        assertContains(
            PACK.resolve("assets/minecraft/shaders/include/utils.glsl"),
            "#define LIGHTR 18.0"
        );
        assertContains(
            PACK.resolve("assets/minecraft/shaders/post/light_apply.fsh"),
            "vec3 colorized"
        );
    }

    @Test
    void avoidsReservedIdentifiersRejectedBySomeGlsl150Drivers() throws IOException {
        Pattern reserved = Pattern.compile(
            "\\b(?:packed|input|output|sample|filter|common|partition|active|superp)\\b"
        );
        try (var shaders = Files.walk(PACK.resolve("assets/minecraft/shaders"))) {
            for (Path shader : shaders
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".vsh")
                    || path.toString().endsWith(".fsh")
                    || path.toString().endsWith(".glsl"))
                .toList()) {
                String source = Files.readString(shader);
                assertFalse(
                    reserved.matcher(source).find(),
                    () -> shader + " contains a GLSL reserved identifier"
                );
            }
        }
    }

    @Test
    void containsAnIsolatedFlashbangModelAndVorbisSound() throws IOException {
        Path definition = PACK.resolve("assets/minecraft/items/snowball.json");
        assertContains(definition, "\"threshold\": 6900");
        assertContains(definition, "\"threshold\": 6900.5");
        assertContains(definition, "\"model\": \"minecraft:item/snowball\"");
        assertTrue(Files.isRegularFile(PACK.resolve(
            "assets/strobelights/models/item/flashbang.json"
        )));
        assertTrue(Files.isRegularFile(PACK.resolve(
            "assets/strobelights/textures/item/flashbang.png"
        )));
        assertContains(
            PACK.resolve("assets/strobelights/sounds.json"),
            "strobelights:flashbang"
        );
        byte[] ogg = Files.readAllBytes(PACK.resolve(
            "assets/strobelights/sounds/flashbang.ogg"
        ));
        assertTrue(ogg.length > 4);
        assertTrue(new String(ogg, 0, 4, StandardCharsets.US_ASCII).equals("OggS"));

        Path flashbangService = Path.of(
            "src/main/java/es/mrdino/strobelights/service/FlashbangService.java"
        );
        Path manager = Path.of(
            "src/main/java/es/mrdino/strobelights/service/StrobeManager.java"
        );
        Path config = Path.of("src/main/resources/config.yml");
        assertContains(flashbangService, "scheduleFlightTimeout(projectile)");
        assertContains(flashbangService, "throwable-flashbang.maximum-flight-ticks");
        assertContains(flashbangService, "ProjectileLaunchEvent event");
        assertContains(flashbangService, "isFlashbang(projectile.getItem())");
        assertContains(flashbangService, "addPluginChunkTicket(plugin)");
        assertContains(flashbangService, "scheduleDetonation(impact, delay)");
        assertContains(flashbangService, "sceneRetentionTicks()");
        assertContains(manager, "throwable-flashbang.scene-view-range");
        assertNotContains(manager, "throwable-flashbang.scene-light-radius");
        assertContains(config, "maximum-flight-ticks: 1200");
        assertContains(config, "scene-view-range: 128.0");
    }

    @Test
    void containsAProjectileFreeSimulatedFlareAndTintedCartridge() throws IOException {
        Path launcherDefinition = PACK.resolve("assets/minecraft/items/blaze_rod.json");
        Path cartridgeDefinition = PACK.resolve(
            "assets/minecraft/items/leather_horse_armor.json"
        );
        assertContains(launcherDefinition, "\"threshold\": 6910");
        assertContains(launcherDefinition, "strobelights:item/flare_launcher");
        assertContains(cartridgeDefinition, "\"threshold\": 6911");
        assertContains(cartridgeDefinition, "strobelights:item/flare_cartridge");
        assertContains(cartridgeDefinition, "minecraft:custom_model_data");

        for (String asset : new String[] {"flare_launcher", "flare_cartridge"}) {
            assertTrue(Files.isRegularFile(PACK.resolve(
                "assets/strobelights/models/item/" + asset + ".json"
            )));
            Path texture = PACK.resolve(
                "assets/strobelights/textures/item/" + asset + ".png"
            );
            assertTrue(Files.isRegularFile(texture));
            var image = ImageIO.read(texture.toFile());
            assertEquals(64, image.getWidth());
            assertEquals(64, image.getHeight());
            assertEquals(0, image.getRGB(0, 0) >>> 24);
        }

        Path service = Path.of(
            "src/main/java/es/mrdino/strobelights/service/FlareService.java"
        );
        Path manager = Path.of(
            "src/main/java/es/mrdino/strobelights/service/StrobeManager.java"
        );
        Path config = Path.of("src/main/resources/config.yml");
        assertContains(service, "new ItemStack(Material.BLAZE_ROD)");
        assertContains(service, "new ItemStack(Material.LEATHER_HORSE_ARMOR)");
        assertContains(service, "setColors(List.of(tint))");
        assertContains(service, "event.setCancelled(true)");
        assertContains(service, "Particle.CAMPFIRE_COSY_SMOKE");
        assertContains(service, "tickBurstSparks(burn)");
        assertContains(service, "flare.reload-required");
        assertNotContains(service, "Material.CROSSBOW");
        assertNotContains(service, "Firework");
        assertContains(config, "reload-required: true");
        assertContains(config, "burn-duration-ticks: 600");
        assertContains(config, "burst-particle-count: 14");
        assertContains(config, "fall-speed: 0.012");
        assertContains(config, "config-version: 2");
        assertContains(service, "flare.trail-points-per-block");
        assertContains(service, "moveFlareLight(burn.lightId, burn.location)");
        assertContains(manager, "public void moveFlareLight(UUID id, Location location)");
        Path plugin = Path.of(
            "src/main/java/es/mrdino/strobelights/StrobeLightsPlugin.java"
        );
        assertContains(plugin, "migrateConfiguration();");
        assertContains(plugin, "burn-duration-ticks\", 160, 600");
        assertFalse(Pattern.compile(
            "Particle\\.FLASH,[\\s\\S]{0,180}Color\\."
        ).matcher(Files.readString(manager, StandardCharsets.UTF_8)).find());

        Path launcherModel = PACK.resolve(
            "assets/strobelights/models/item/flare_launcher.json"
        );
        assertContains(launcherModel, "\"firstperson_righthand\"");
        assertContains(launcherModel, "\"scale\": [0.32, 0.32, 0.32]");
    }

    @Test
    void makesOpaqueBlockOcclusionMandatoryForEveryRgbEffect() throws IOException {
        Path manager = Path.of(
            "src/main/java/es/mrdino/strobelights/service/StrobeManager.java"
        );
        Path config = Path.of("src/main/resources/config.yml");
        assertContains(manager, "sourceBlockedForPlayer(player, source)");
        assertContains(manager, "sourceBlockedForPlayer(player, scene.location)");
        assertContains(manager, "if (blockedByGeometry(eye, direction, distance))");
        assertContains(manager, "blocksLight(world.getBlockAt(blockX, blockY, blockZ).getType())");
        assertNotContains(manager, "requireLineOfSight");
        assertNotContains(config, "require-line-of-sight");
    }

    private static void assertContains(Path file, String expected) throws IOException {
        assertTrue(read(file).contains(expected), () -> file + " no contiene " + expected);
    }

    private static void assertNotContains(Path file, String forbidden) throws IOException {
        assertFalse(
            read(file).contains(forbidden),
            () -> file + " contiene la palabra GLSL reservada " + forbidden
        );
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file).replace("\r\n", "\n");
    }
}
