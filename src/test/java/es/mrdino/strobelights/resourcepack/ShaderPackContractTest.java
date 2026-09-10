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
    private static final Path GENERATED_PACK = Path.of(
        "build/generated/legacy-carrier-pack-opaque"
    );

    @Test
    void carriesAnIntegrationRevisionThatInvalidatesMergedPackCaches() throws IOException {
        Path integration = PACK.resolve(
            "assets/strobelights/strobelights-integration.json"
        );
        assertTrue(Files.isRegularFile(integration));
        assertContains(integration, "\"version\": \"0.10.23\"");
        assertContains(integration, "\"render_pipeline\": \"light_painter_rgb\"");
        assertContains(integration, "\"marker_protocol\": \"typed_guarded_5px_v5\"");
    }

    @Test
    void targetsMinecraft1201AndContainsTheLightPipeline() throws IOException {
        assertContains(PACK.resolve("pack.mcmeta"), "\"pack_format\": 15");
        Path pipeline = PACK.resolve(
            "assets/minecraft/shaders/post/transparency.json"
        );
        assertContains(pipeline, "\"name\": \"light\"");
        assertContains(pipeline, "\"intarget\": \"itemEntity\"");
        assertContains(pipeline, "\"outtarget\": \"lightmap3\"");
        assertContains(pipeline, "\"name\": \"ItemEntityLightSampler\"");
        assertContains(
            PACK.resolve("assets/minecraft/shaders/program/transparency.fsh"),
            "color.rgb += itemLight * color.a * 0.55"
        );
        assertContains(
            PACK.resolve("assets/minecraft/shaders/program/light.fsh"),
            "lightDist < lightRadius"
        );
        for (String shaderName : new String[] {
            "filter.fsh",
            "aggregate_6.fsh",
            "light.fsh",
            "light_t.fsh",
            "transparency.fsh"
        }) {
            Path shader = PACK.resolve("assets/minecraft/shaders/program")
                .resolve(shaderName);
            assertNotContains(shader, "#moj_import");
        }
        assertContains(
            PACK.resolve("assets/minecraft/shaders/program/filter.fsh"),
            "#define LIGHTDEPTH 0.025"
        );
        for (String target : new String[] {
            "\"water\"",
            "\"translucent\"",
            "\"itemEntity\"",
            "\"particles\"",
            "\"clouds\"",
            "\"weather\""
        }) {
            assertContains(pipeline, target);
        }
        Path coreDefinition = PACK.resolve(
            "assets/minecraft/shaders/core/"
                + "rendertype_item_entity_translucent_cull.json"
        );
        assertContains(coreDefinition, "\"blend\": {");
        assertContains(
            coreDefinition,
            "\"vertex\": \"rendertype_item_entity_translucent_cull\""
        );
        assertNotContains(
            coreDefinition,
            "minecraft:core/rendertype_item_entity_translucent_cull"
        );
        assertContains(coreDefinition, "\"attributes\": [");
        assertContains(coreDefinition, "\"Position\"");
        assertContains(coreDefinition, "\"Color\"");
        assertContains(coreDefinition, "\"UV0\"");
        assertContains(coreDefinition, "\"UV1\"");
        assertContains(coreDefinition, "\"UV2\"");
        assertContains(coreDefinition, "\"Normal\"");
        assertContains(coreDefinition, "\"IViewRotMat\"");
        Path coreVertex = PACK.resolve(
            "assets/minecraft/shaders/core/"
                + "rendertype_item_entity_translucent_cull.vsh"
        );
        assertContains(coreVertex, "uniform mat3 IViewRotMat");
        assertContains(coreVertex, "in vec3 Position");
        assertContains(coreVertex, "in vec4 Color");
        assertContains(coreVertex, "in vec2 UV0");
        assertContains(coreVertex, "in vec2 UV1");
        assertContains(coreVertex, "in ivec2 UV2");
        assertContains(coreVertex, "in vec3 Normal");
        assertContains(coreVertex, "bool encodedTechnicalCarrier");
        assertContains(coreVertex, "&& encodedTechnicalCarrier");
        assertNotContains(coreVertex, "technicalCarrierGeometry");
        assertNotContains(coreVertex, "Position.y - 8.0");
    }

    @Test
    void doesNotConfigureTheProjectionUniformRemovedByTheLinker() throws IOException {
        Path shaderDirectory = PACK.resolve("assets/minecraft/shaders/program");
        Path pipeline = PACK.resolve("assets/minecraft/post_effect/transparency.json");
        String pipelineSource = Files.readString(pipeline, StandardCharsets.UTF_8);
        Pattern lightPass = Pattern.compile(
            "(?s)\\\"program\\\"\\s*:\\s*\\\"minecraft:post/light(?:_t)?\\\"(.*?)(?=\\\"program\\\"|$)"
        );
        var passes = lightPass.matcher(pipelineSource);
        int matched = 0;
        while (passes.find()) {
            assertFalse(passes.group().contains("\"name\":\"FOV\""));
            matched++;
        }
        assertEquals(3, matched);
        for (String file : new String[] {
            "light.vsh", "light.fsh", "light_t.fsh", "light.json", "light_t.json"
        }) {
            assertNotContains(shaderDirectory.resolve(file), "FOV");
            assertNotContains(shaderDirectory.resolve(file), "conversionK");
        }
    }

    @Test
    void visibleItemTexturesCannotImpersonateTechnicalLightMarkers() throws IOException {
        Path visibleTextures = PACK.resolve("assets/strobelights/textures/item");
        try (var files = Files.walk(visibleTextures)) {
            for (Path texture : files.filter(path -> path.toString().endsWith(".png")).toList()) {
                var image = ImageIO.read(texture.toFile());
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int argb = image.getRGB(x, y);
                        int alpha = argb >>> 24;
                        int red = argb >> 16 & 0xFF;
                        int green = argb >> 8 & 0xFF;
                        int blue = argb & 0xFF;
                        int peak = Math.max(red, Math.max(green, blue));
                        int base = Math.min(red, Math.min(green, blue));
                        boolean reservedAlpha = alpha >= 22 && alpha <= 26;
                        boolean neutralCarrier = peak >= alpha * 0.5
                            && base >= peak * 0.75;
                        assertFalse(
                            reservedAlpha && neutralCarrier,
                            () -> texture + " contains a false technical marker"
                        );
                    }
                }
            }
        }
    }

    @Test
    void generatesAnOptiFineSafeBlockItemCarrier() throws IOException {
        Path technicalItem = GENERATED_PACK.resolve(
            "assets/minecraft/models/item/lime_stained_glass.json"
        );
        assertContains(technicalItem, "\"custom_model_data\": 4000000");
        assertContains(technicalItem, "\"custom_model_data\": 4000255");
        assertContains(technicalItem, "\"custom_model_data\": 4001000");
        assertContains(technicalItem, "\"custom_model_data\": 4001255");
        assertContains(technicalItem, "strobelights:item/carrier/source_00");
        assertContains(technicalItem, "strobelights:item/carrier/flash_ff");

        Path manager = Path.of(
            "src/main/java/es/mrdino/strobelights/service/StrobeManager.java"
        );
        assertContains(manager, "Material.LIME_STAINED_GLASS");
        assertContains(manager, "applyTechnicalMarker");
        assertContains(manager, "new Display.Brightness(");
        assertNotContains(manager, "Material.LEATHER_HORSE_ARMOR");
        assertNotContains(manager, "LeatherArmorMeta");

        Path sourceModel = PACK.resolve(
            "assets/strobelights/models/item/carrier_source_base.json"
        );
        Path flashModel = PACK.resolve(
            "assets/strobelights/models/item/carrier_flash_base.json"
        );
        assertContains(sourceModel, "\"from\": [4, 8, 4]");
        assertContains(sourceModel, "\"to\": [12, 8, 12]");
        assertContains(sourceModel, "\"scale\": [0, 0, 0]");
        assertContains(sourceModel, "\"uv\": [0, 0, 16, 16]");
        assertContains(flashModel, "\"uv\": [0, 0, 16, 16]");

        Path payloadModels = GENERATED_PACK.resolve(
            "assets/strobelights/models/item/carrier"
        );
        try (var files = Files.list(payloadModels)) {
            assertEquals(512L, files.filter(Files::isRegularFile).count());
        }
        Path payloadTextures = GENERATED_PACK.resolve(
            "assets/strobelights/textures/item/carrier"
        );
        try (var files = Files.list(payloadTextures)) {
            assertEquals(512L, files.filter(Files::isRegularFile).count());
        }
        var sourceTexture = ImageIO.read(payloadTextures.resolve(
            "source_7f.png"
        ).toFile());
        var flashTexture = ImageIO.read(payloadTextures.resolve(
            "flash_7f.png"
        ).toFile());
        assertEquals(253, sourceTexture.getRGB(0, 0) >>> 24);
        assertEquals(253, flashTexture.getRGB(0, 0) >>> 24);
        assertEquals(64, sourceTexture.getWidth());
        assertEquals(64, sourceTexture.getHeight());
        assertEquals(0x7F20E0, sourceTexture.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0x7F20E0, sourceTexture.getRGB(63, 63) & 0xFFFFFF);
        assertEquals(0x7FE020, flashTexture.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0x7FE020, flashTexture.getRGB(63, 63) & 0xFFFFFF);
        for (int highByte = 0; highByte < 256; highByte++) {
            String suffix = String.format("%02x.png", highByte);
            var source = ImageIO.read(payloadTextures.resolve("source_" + suffix).toFile());
            var flash = ImageIO.read(payloadTextures.resolve("flash_" + suffix).toFile());
            assertEquals(64, source.getWidth());
            assertEquals(64, source.getHeight());
            assertEquals(64, flash.getWidth());
            assertEquals(64, flash.getHeight());
            assertEquals(0xFD0020E0 | (highByte << 16), source.getRGB(32, 32));
            assertEquals(0xFD00E020 | (highByte << 16), flash.getRGB(32, 32));
        }

        assertNotContains(
            PACK.resolve("assets/minecraft/models/item/leather_horse_armor.json"),
            "\"custom_model_data\": 4000000"
        );
        assertNotContains(
            PACK.resolve("assets/minecraft/models/item/potion.json"),
            "\"custom_model_data\": 4000000"
        );
        assertContains(manager, "display.setBillboard(Display.Billboard.FIXED)");
    }

    @Test
    void decodesOpaqueMipZeroPayloadFromTextureAndLightmap()
        throws IOException {
        Path core = PACK.resolve(
            "assets/minecraft/shaders/core/"
                + "rendertype_item_entity_translucent_cull.vsh"
        );
        assertContains(core, "textureLod(Sampler0, UV0, 0.0)");
        assertContains(core, "markerTextureBytes.a == 253.0");
        assertContains(core, "markerTextureBytes.g == 32.0");
        assertContains(core, "markerTextureBytes.b == 224.0");
        assertContains(core, "decodeTechnicalPayload(tmpcol, UV2)");
        assertContains(core, "carrierTexel.r / max(reference");
        assertContains(core, "lightCoordinates.x / 16");
        assertContains(core, "lightCoordinates.y / 16");
        assertContains(core, "sourceTexture");
        assertContains(core, "flashTexture");
        assertContains(core, "markerTextureBytes.a == 253.0");
        assertNotContains(core, "abs(tmpcol.a - LIGHTALPHA)");
        assertNotContains(core, "markerTexturePeak");
        assertContains(core, "&& encodedTechnicalCarrier");
        assertNotContains(core, "vertexColor = vec4(Color.rgb, 1.0)");
        assertNotContains(core, "bool hand = isHand(FogStart, FogEnd)");
    }

    @Test
    void transportsMarkersAsOpaqueGuardedBytesThroughItemEntityDepthRoute()
        throws IOException {
        Path core = PACK.resolve(
            "assets/minecraft/shaders/core/"
                + "rendertype_item_entity_translucent_cull.fsh"
        );
        Path filter = PACK.resolve("assets/minecraft/shaders/program/filter.fsh");
        Path filterDefinition = PACK.resolve(
            "assets/minecraft/shaders/program/filter.json"
        );
        Path aggregate = PACK.resolve("assets/minecraft/shaders/program/aggregate_6.fsh");
        Path aggregateDefinition = PACK.resolve(
            "assets/minecraft/shaders/program/aggregate_6.json"
        );
        Path pipeline = PACK.resolve("assets/minecraft/shaders/post/transparency.json");

        assertContains(core, "inverse(uvPerPixel) * (texCoord2 - vec2(0.5))");
        assertContains(core, "vec4(194.0, 69.0, 253.0, 255.0)");
        assertContains(core, "vec4(markerPayload.rgb, 1.0)");
        assertContains(core, "vec4(61.0, 186.0, 2.0, 255.0)");
        assertNotContains(core, "2.0 / 255.0");
        assertNotContains(core, "bitColor * 0.5");
        assertContains(core, "gl_FragDepth = centerDepth * LIGHTDEPTH");
        assertContains(filter, "if (depth < LIGHTDEPTH)");
        assertContains(filter, "depth / LIGHTDEPTH");
        assertContains(filter, "int leftGuardType(vec4 color)");
        assertContains(filter, "ivec4(194, 69, 253, 255)");
        assertContains(filter, "bool rightGuard(vec4 color)");
        assertContains(filter, "ivec4(61, 186, 2, 255)");
        assertContains(filter, "candidate.a >= 254.5 / 255.0");
        assertContains(filter, "sourceMetadata == 0 ? 1.0");
        assertContains(filterDefinition, "\"InSize\"");
        assertNotContains(filterDefinition, "\"DiffuseSize\"");
        assertNotContains(filter, "payloadIndex");
        assertNotContains(filter, "cellBytes.a - 2");
        assertContains(aggregate, "texture(ItemEntityDepthSampler, samplepos).r");
        assertContains(aggregateDefinition, "\"ItemEntityDepthSampler\"");
        assertNotContains(aggregateDefinition, "\"MainDepthSampler\"");
        assertContains(pipeline, "\"intarget\": \"itemEntity\"");
        assertContains(pipeline, "\"outtarget\": \"swap1\"");
        assertTrue(Files.exists(PACK.resolve(
            "assets/minecraft/shaders/core/"
                + "rendertype_item_entity_translucent_cull.fsh"
        )));
        assertFalse(Files.exists(PACK.resolve(
            "assets/minecraft/shaders/core/rendertype_entity_translucent_cull.fsh"
        )));
        assertNotContains(pipeline, "carriers");
    }

    @Test
    void usesMinecraft1201PostPassSizeUniforms() throws IOException {
        Path programs = PACK.resolve("assets/minecraft/shaders/program");
        for (String shaderName : new String[] {
            "filter.fsh",
            "centers.vsh",
            "aggregate.vsh",
            "aggregate_1.fsh",
            "aggregate_2.fsh",
            "aggregate_3.fsh",
            "aggregate_4.fsh",
            "aggregate_5.fsh",
            "aggregate_6.vsh",
            "aggregate_6.fsh"
        }) {
            assertContains(programs.resolve(shaderName), "uniform vec2 InSize");
        }
        for (String definitionName : new String[] {
            "filter.json",
            "centers.json",
            "aggregate_1.json",
            "aggregate_2.json",
            "aggregate_3.json",
            "aggregate_4.json",
            "aggregate_5.json",
            "aggregate_6.json"
        }) {
            assertContains(programs.resolve(definitionName), "\"name\": \"InSize\"");
        }

        Path lightVertex = programs.resolve("light.vsh");
        assertContains(lightVertex, "uniform vec2 InSize");
        assertContains(lightVertex, "uniform vec2 AuxSize1");
        assertContains(lightVertex, "oneTexelAux1 = 1.0 / AuxSize1");
        for (String definitionName : new String[] {"light.json", "light_t.json"}) {
            Path definition = programs.resolve(definitionName);
            assertContains(definition, "\"name\": \"InSize\"");
            assertContains(definition, "\"name\": \"AuxSize1\"");
        }

        Path flashVertex = programs.resolve("flash_apply.vsh");
        assertContains(flashVertex, "uniform vec2 AuxSize0");
        assertContains(flashVertex, "oneTexelLights = 1.0 / AuxSize0");
        assertContains(
            programs.resolve("flash_apply.json"),
            "\"name\": \"AuxSize0\""
        );

        try (var shaders = Files.walk(programs)) {
            for (Path shader : shaders.filter(Files::isRegularFile).toList()) {
                assertNotContains(shader, "DiffuseSize");
                assertNotContains(shader, "DiffuseDepthSize");
                assertNotContains(shader, "LightsSize");
            }
        }
    }

    @Test
    void keepsWorldLightVerticesAtTheSavedSourceWithFrustumCullingDisabled()
        throws IOException {
        Path manager = Path.of(
            "src/main/java/es/mrdino/strobelights/service/StrobeManager.java"
        );
        assertContains(manager, "updateFixedSourceViewers(strobe, state)");
        assertContains(manager, "Location markerLocation = fixedSourceLocation(strobe)");
        assertContains(manager, "source.distanceSquared(player.getEyeLocation()) > maximumDistanceSquared");
        assertContains(manager, "spawnFixedLightDisplay(markerLocation");
        assertContains(manager, "display.setDisplayWidth(carrier.displayWidth())");
        assertContains(manager, "display.setDisplayHeight(carrier.displayHeight())");
        assertContains(manager, "new Vector3f(0.0f, carrier.translationY(), 0.0f)");
        assertContains(manager, "sourceY,\n            0.0f,\n            0.0f,\n            0.0f");
        assertContains(manager, "state.marker.setItemStack(new ItemStack(Material.AIR))");
        assertContains(manager, "state.marker.setBrightness(new Display.Brightness(0, 0))");
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
        Path flashShader = PACK.resolve("assets/minecraft/shaders/program/flash_apply.fsh");
        assertContains(flashShader, "isCameraFlash");
        assertContains(flashShader, "flashColor");
        assertContains(flashShader, "mix(outColor.rgb, flashColor");
        assertContains(
            PACK.resolve("assets/minecraft/shaders/post/transparency.json"),
            "\"name\": \"flash_apply\""
        );
        assertNotContains(flashShader, "int packed =");
        assertNotContains(flashShader, "int packed)");
        assertNotContains(
            PACK.resolve("assets/minecraft/shaders/program/light.fsh"),
            "int packed ="
        );
        assertNotContains(
            PACK.resolve("assets/minecraft/shaders/program/light_t.fsh"),
            "int packed ="
        );
    }

    @Test
    void reconstructsNearAndBehindLightsFromPrivateMarkers() throws IOException {
        Path core = PACK.resolve(
            "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.vsh"
        );
        Path aggregate = PACK.resolve("assets/minecraft/shaders/program/aggregate_6.fsh");
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
            Path shader = PACK.resolve("assets/minecraft/shaders/program").resolve(shaderName);
            assertContains(shader, "isOffscreenLight");
            assertContains(shader, "reconstructOffscreenLight");
            assertContains(shader, "int mode = (encodedValue >> 20) & 7");
            assertContains(shader, "(projectionBytes.r << 8) | projectionBytes.g");
            assertContains(shader, "float axisInverse = 16.0");
            assertContains(shader, "float depthInverse = 4.0");
            assertContains(shader, "return proxyCoord");
            assertContains(shader, "if (mode == 4)");
            assertContains(shader, "if (mode == 5)");
            assertContains(shader, "markerConversionK = decodeExactProjectionK(");
            assertContains(shader, "screenCoord * markerConversionK * depth");
            assertNotContains(shader, "return color * intensity");
            assertContains(shader, "if (lightDist < lightRadius");
            assertNotContains(shader, "edgeFade");
        }
    }

    @Test
    void keepsAdjacentLightsAsIndependentCenters()
        throws IOException {
        Path centers = PACK.resolve("assets/minecraft/shaders/program/centers.fsh");
        assertContains(centers, "bool sameEncodedMarker");
        assertContains(centers, "vec4(1.5 / 255.0)");
        assertContains(centers, "sameEncodedMarker(outColor, c1)");
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
        Path filter = PACK.resolve("assets/minecraft/shaders/program/filter.fsh");
        Path aggregate = PACK.resolve("assets/minecraft/shaders/program/aggregate_6.fsh");
        Path pipeline = PACK.resolve("assets/minecraft/shaders/post/transparency.json");

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
        assertContains(pipeline, "\"lights\"");
        for (String shaderName : new String[] {"light.fsh", "light_t.fsh"}) {
            Path shader = PACK.resolve("assets/minecraft/shaders/program").resolve(shaderName);
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
        Path pipeline = PACK.resolve("assets/minecraft/shaders/post/transparency.json");
        assertNotContains(pipeline, "minecraft:post/blur_custom");
        assertNotContains(pipeline, "\"sampler_name\": \"Blur\"");
        assertFalse(Files.exists(PACK.resolve(
            "assets/minecraft/shaders/program/blur_custom.fsh"
        )));
        assertFalse(Files.exists(PACK.resolve(
            "assets/minecraft/shaders/program/blur_custom.json"
        )));
        assertFalse(Files.exists(PACK.resolve(
            "assets/minecraft/shaders/program/blur_custom.vsh"
        )));
        for (String shaderName : new String[] {"light_apply.fsh", "light_apply_t.fsh"}) {
            Path shader = PACK.resolve("assets/minecraft/shaders/program").resolve(shaderName);
            assertNotContains(shader, "BlurSampler");
            assertNotContains(shader, "blurColor");
            assertNotContains(shader, "vec3 illumination = Intensity * lightColor");
            assertContains(shader, "vec3 baseColor = outColor.rgb");
        }
    }

    @Test
    void containsGuiOnlyCustomPaperIcons() throws IOException {
        Path definition = PACK.resolve("assets/minecraft/models/item/paper.json");
        assertContains(definition, "\"custom_model_data\": 6800");
        assertContains(definition, "\"custom_model_data\": 6815");
        assertContains(definition, "\"custom_model_data\": 6822");
        assertContains(definition, "\"parent\": \"minecraft:item/generated\"");
        Path potionDefinition = PACK.resolve("assets/minecraft/models/item/potion.json");
        assertContains(potionDefinition, "\"custom_model_data\": 6821");
        assertContains(potionDefinition, "strobelights:item/gui/color_swatch");
        Path guiSource = Path.of("src/main/java/es/mrdino/strobelights/ui/StrobeGui.java");
        assertContains(guiSource, "ItemStack stack = item(Material.POTION");
        assertContains(guiSource, "meta.setColor(Color.fromRGB(rgb & 0xFFFFFF))");
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
        assertContains(definition, "\"custom_model_data\": 6823");
        assertContains(definition, "strobelights:item/gui/groups");
        assertContains(definition, "\"custom_model_data\": 6824");
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
    void illuminatesSurfacesWithoutGeometryOcclusion() throws IOException {
        Path core = PACK.resolve(
            "assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.vsh"
        );
        assertContains(core, "vec4 tmp = ModelViewMat * vec4(Position, 1.0)");
        assertNotContains(core, "ModelViewMat * vec4(vec3(0.5), 1.0)");
        assertContains(core, "offscreenProxy");
        assertContains(core, "float axisScale = 0.0625");
        assertContains(core, "float depthScale = 0.25");
        for (String shaderName : new String[] {"light.fsh", "light_t.fsh"}) {
            Path shader = PACK.resolve("assets/minecraft/shaders/program").resolve(shaderName);
            assertNotContains(shader, "lightBlocked");
            assertNotContains(shader, "lightTransmission");
            assertNotContains(shader, "rayIndex");
            assertNotContains(shader, "depthGap");
            assertNotContains(shader, "stepOcclusion");
            assertNotContains(shader, "blockedWeight");
            assertContains(shader, "float axisInverse = 16.0");
            assertContains(shader, "float depthInverse = 4.0");
            assertContains(shader, "lightRadius = mix(");
            assertContains(shader, "float radialFalloff = pow(");
        }
        assertContains(
            PACK.resolve("assets/minecraft/shaders/include/utils.glsl"),
            "#define LIGHTR 18.0"
        );
        assertContains(
            PACK.resolve("assets/minecraft/shaders/program/light_apply.fsh"),
            "vec3 colorized"
        );
        assertNotContains(
            PACK.resolve("assets/minecraft/shaders/program/light_apply.fsh"),
            "+ illumination * 0.22"
        );
        assertContains(
            PACK.resolve("assets/minecraft/shaders/program/light_apply.fsh"),
            "vec3 baseColor = outColor.rgb"
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
        Path definition = PACK.resolve("assets/minecraft/models/item/snowball.json");
        assertContains(definition, "\"custom_model_data\": 6900");
        assertContains(definition, "\"parent\": \"minecraft:item/generated\"");
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
        assertContains(manager, "Particle.FLASH");
        assertContains(manager, "0.0,\n            null,\n            true");
        assertContains(manager, "applyVanillaFallback(strobe, state);\n\n        // The entity");
        assertContains(manager, "current.getLevel() == level");
        assertContains(manager, "target.setBlockData(light, true)");
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
        Path launcherDefinition = PACK.resolve(
            "assets/minecraft/models/item/blaze_rod.json"
        );
        Path cartridgeDefinition = PACK.resolve(
            "assets/minecraft/models/item/leather_horse_armor.json"
        );
        assertContains(launcherDefinition, "\"custom_model_data\": 6910");
        assertContains(launcherDefinition, "strobelights:item/flare_launcher");
        assertContains(cartridgeDefinition, "\"custom_model_data\": 6911");
        assertContains(cartridgeDefinition, "strobelights:item/flare_cartridge");
        assertContains(cartridgeDefinition, "\"custom_model_data\": 6912");
        assertContains(cartridgeDefinition, "strobelights:item/flare_core");
        assertContains(cartridgeDefinition, "\"custom_model_data\": 6913");
        assertContains(cartridgeDefinition, "strobelights:item/flare_hot_core");

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
        Path coreTexture = PACK.resolve(
            "assets/strobelights/textures/item/flare_core.png"
        );
        assertTrue(Files.isRegularFile(PACK.resolve(
            "assets/strobelights/models/item/flare_core.json"
        )));
        assertTrue(Files.isRegularFile(PACK.resolve(
            "assets/strobelights/models/item/flare_hot_core.json"
        )));
        var coreImage = ImageIO.read(coreTexture.toFile());
        assertEquals(256, coreImage.getWidth());
        assertEquals(256, coreImage.getHeight());
        assertEquals(0, coreImage.getRGB(0, 0) >>> 24);
        assertTrue((coreImage.getRGB(128, 128) >>> 24) > 240);
        Path soundDefinition = PACK.resolve("assets/strobelights/sounds.json");
        for (String sound : new String[] {
            "flare_reload_open",
            "flare_reload_insert",
            "flare_reload_close",
            "flare_fire",
            "flare_flight",
            "flare_ignite",
            "flare_burn"
        }) {
            assertContains(soundDefinition, "\"" + sound + "\"");
            assertTrue(Files.isRegularFile(PACK.resolve(
                "assets/strobelights/sounds/" + sound + ".ogg"
            )));
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
        assertContains(service, "meta.setCustomModelData(modelData)");
        assertContains(service, "event.setCancelled(true)");
        assertContains(service, "world.spawn(location, ItemDisplay.class");
        assertContains(service, "Float.compare(scale, previousScale) != 0");
        assertContains(service, "FLARE_SURFACE_CLEARANCE = 0.30");
        assertContains(service, "collision.getHitBlockFace().getDirection()");
        assertContains(service, "impactOutsideSurface(");
        assertNotContains(service, "normalize().multiply(0.025)");
        assertContains(service, "strobelights:flare_reload_open");
        assertContains(service, "strobelights:flare_fire");
        assertNotContains(service, "Particle.");
        assertNotContains(service, "tickBurstSparks(burn)");
        assertContains(service, "flare.reload-required");
        assertNotContains(service, "Material.CROSSBOW");
        assertNotContains(service, "Firework");
        assertContains(config, "reload-required: true");
        assertContains(config, "burn-duration-ticks: 800");
        assertContains(config, "burn-size: 3.2");
        assertContains(config, "view-range: 256.0");
        assertContains(config, "ignition-velocity-retention: 0.45");
        assertContains(config, "minimum-horizontal-speed: 0.035");
        assertContains(config, "horizontal-drag: 0.992");
        assertContains(config, "gravity: 0.0035");
        assertContains(config, "terminal-fall-speed: 0.06");
        assertContains(config, "wind-acceleration: 0.00018");
        assertContains(config, "scene-light-expansion: 4.0");
        assertContains(config, "flight-light-expansion: 2.0");
        assertContains(config, "maximum-duration-ticks: 50");
        assertContains(config, "strength-percent: 85");
        assertContains(config, "config-version: 8");
        assertContains(config, "ground-projection:");
        assertContains(config, "maximum-drop-distance: 128.0");
        assertContains(config, "scene-view-range: 192.0");
        assertNotContains(config, "particle-count");
        assertNotContains(config, "\n    fall-speed:");
        assertNotContains(config, "\n    drift-speed:");
        assertNotContains(service, "flare.trail-points-per-block");
        assertContains(service, "nextBurnVelocity(");
        assertContains(service, "burn.grounded = true");
        assertContains(service, "moveFlareLight(burn.lightId, burn.location)");
        assertContains(service, "moveFlareGroundLight(burn.groundLightId, burn.location)");
        assertContains(service, "refreshFlareCameraGlare(burn.location, burn.color.rgb)");
        assertContains(manager, "public void moveFlareLight(UUID id, Location location)");
        assertContains(manager, "public UUID beginFlareGroundLight(Location flareLocation, int rgb)");
        assertContains(manager, "public void moveFlareGroundLight(UUID id, Location flareLocation)");
        assertContains(manager, "new Vector(0.0, -1.0, 0.0)");
        assertContains(manager, "public UUID beginFlareFlightLight(Location location, int rgb)");
        assertContains(manager, "public void refreshFlareCameraGlare(Location explosion, int rgb)");
        Path plugin = Path.of(
            "src/main/java/es/mrdino/strobelights/StrobeLightsPlugin.java"
        );
        assertContains(plugin, "migrateConfiguration();");
        assertContains(plugin, "burn-duration-ticks\", 600, 800");
        assertContains(plugin, "render.display-view-range\", 128.0, 192.0");
        assertContains(plugin, "flare.visual.view-range\", 192.0, 256.0");
        assertFalse(Pattern.compile(
            "Particle\\.FLASH,[\\s\\S]{0,180}Color\\."
        ).matcher(Files.readString(manager, StandardCharsets.UTF_8)).find());

        Path launcherModel = PACK.resolve(
            "assets/strobelights/models/item/flare_launcher.json"
        );
        assertContains(launcherModel, "\"firstperson_righthand\"");
        assertContains(launcherModel, "\"scale\": [0.32, 0.32, 0.32]");
        assertContains(launcherModel, "\"rotation\": [0, -90, -35]");
        assertContains(launcherModel, "\"rotation\": [0, 90, 35]");
    }

    @Test
    void keepsRgbSourcesAndCameraEffectsIndependentOfGeometryOcclusion() throws IOException {
        Path manager = Path.of(
            "src/main/java/es/mrdino/strobelights/service/StrobeManager.java"
        );
        Path config = Path.of("src/main/resources/config.yml");
        assertContains(manager, "boolean visible = nearby;");
        assertNotContains(manager, "sourceBlockedForPlayer");
        assertNotContains(manager, "blockedByGeometry");
        assertNotContains(manager, "blocksLight");
        assertContains(manager, "material == Material.BARRIER || material == Material.LIGHT");
        assertContains(manager, "rayTraceBlocksIgnoringTechnicalBlocks(");
        assertContains(
            Path.of("src/main/java/es/mrdino/strobelights/service/FlareService.java"),
            "StrobeManager.rayTraceBlocksIgnoringTechnicalBlocks("
        );
        assertContains(
            Path.of("src/main/java/es/mrdino/strobelights/command/StrobeCommand.java"),
            "StrobeManager.rayTraceBlocksIgnoringTechnicalBlocks("
        );
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

    private static int countOccurrences(String text, String expected) {
        return text.split(Pattern.quote(expected), -1).length - 1;
    }
}
