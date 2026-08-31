package es.mrdino.strobelights.resourcepack;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ShaderPackContractTest {

    private static final Path PACK = Path.of("resource-pack");

    @Test
    void targetsMinecraft1214AndContainsTheLightPipeline() throws IOException {
        assertContains(PACK.resolve("pack.mcmeta"), "\"pack_format\": 46");
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
    void reservesTheOriginalInvisibleTechnicalPointMarker() throws IOException {
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
            "\"from\": [8,8,8]"
        );
        assertContains(
            PACK.resolve("assets/minecraft/models/item/lp_custom.json"),
            "\"scale\":[0.0,0.0,0.0]"
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
        for (String shaderName : new String[] {"light.fsh", "light_t.fsh"}) {
            Path shader = PACK.resolve("assets/minecraft/shaders/post").resolve(shaderName);
            assertContains(shader, "isOffscreenLight");
            assertContains(shader, "reconstructOffscreenLight");
            assertContains(shader, "int mode = (encodedValue >> 18) & 7");
            assertContains(shader, "float inverseScale = 4.0");
            assertContains(shader, "return proxyCoord");
            assertContains(shader, "if (mode == 4)");
            assertContains(shader, "if (mode == 5)");
            assertContains(shader, "if (lightDist < lightRadius");
            assertNotContains(shader, "edgeFade");
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
    }

    @Test
    void anchorsSourcesClientSideWithoutProjectingScreenSpaceShadows() throws IOException {
        Path core = PACK.resolve(
            "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.vsh"
        );
        assertContains(core, "offscreenProxy");
        assertContains(core, "sourceOnScreen");
        for (String shaderName : new String[] {"light.fsh", "light_t.fsh"}) {
            Path shader = PACK.resolve("assets/minecraft/shaders/post").resolve(shaderName);
            assertNotContains(shader, "rayOccluded");
            assertNotContains(shader, "stepIndex <= 16");
            assertNotContains(shader, "sampledDepth");
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
        assertContains(manager, "throwable-flashbang.scene-view-range");
        assertNotContains(manager, "throwable-flashbang.scene-light-radius");
        assertContains(config, "maximum-flight-ticks: 100");
        assertContains(config, "scene-view-range: 128.0");
    }

    private static void assertContains(Path file, String expected) throws IOException {
        assertTrue(Files.readString(file).contains(expected), () -> file + " no contiene " + expected);
    }

    private static void assertNotContains(Path file, String forbidden) throws IOException {
        assertFalse(
            Files.readString(file).contains(forbidden),
            () -> file + " contiene la palabra GLSL reservada " + forbidden
        );
    }
}
