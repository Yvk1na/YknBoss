package cn.yknboss.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.BetterModelPlatform;
import kr.toxicity.model.api.BetterModelConfig;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.data.blueprint.BlueprintElement;
import kr.toxicity.model.api.data.raw.ModelData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChestModelTest {
    private MockedStatic<BetterModel> api;

    @BeforeEach
    void setUp() {
        // Only supply the platform boundary (e.g. the AIR item used by built-in bone tags).
        // Gson, the bone registry, animations and strict blueprint loading remain real.
        var platform = mock(BetterModelPlatform.class, RETURNS_DEEP_STUBS);
        api = mockStatic(BetterModel.class);
        api.when(BetterModel::platform).thenReturn(platform);
        var config = mock(BetterModelConfig.class);
        when(config.lerpFrameTime()).thenReturn(1);
        api.when(BetterModel::config).thenReturn(config);
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"copper", "silver", "gold"})
    void everyElementDeclaresAllSixFaces(String tier) throws Exception {
        // BetterModel 3.4.1 calls ModelUV.hasTexture() on every side; a missing face aborts
        // loading of every model on the server, not just this one.
        var root =
                com.google.gson.JsonParser.parseString(
                                Files.readString(
                                        Path.of(
                                                "packs/bettermodel/models/yknboss_chest_"
                                                        + tier
                                                        + ".bbmodel")))
                        .getAsJsonObject();
        for (var element : root.getAsJsonArray("elements")) {
            var faces = element.getAsJsonObject().getAsJsonObject("faces");
            for (String side : List.of("north", "east", "south", "west", "up", "down")) {
                assertTrue(
                        faces != null && faces.has(side) && faces.get(side).isJsonObject(),
                        tier + " " + element.getAsJsonObject().get("name") + " missing " + side);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"copper", "silver", "gold"})
    void betterModel341ParsesBonesBrightnessAndHeldOpeningAnimation(String tier) throws Exception {
        var model =
                ModelData.GSON.fromJson(
                        Files.readString(
                                Path.of(
                                        "packs/bettermodel/models/yknboss_chest_"
                                                + tier
                                                + ".bbmodel")),
                        ModelData.class);
        model.assertSupported();
        var loaded = model.loadBlueprint("yknboss_chest_" + tier, true);
        assertTrue(loaded.errors().isEmpty(), () -> loaded.errors().toString());
        var blueprint = loaded.blueprint();
        assertEquals(
                Set.of("base", "lid"),
                blueprint.elements().stream()
                        .map(e -> ((BlueprintElement.Group) e).name().rawName())
                        .collect(Collectors.toSet()));
        var base =
                (BlueprintElement.Group)
                        blueprint.elements().stream()
                                .filter(
                                        e ->
                                                ((BlueprintElement.Group) e)
                                                        .name()
                                                        .rawName()
                                                        .equals("base"))
                                .findFirst()
                                .orElseThrow();
        assertTrue(
                base.children().stream()
                        .anyMatch(
                                e ->
                                        e instanceof BlueprintElement.Group group
                                                && group.name().rawName().equals("treasure")
                                                && group.children().size() == 4
                                                && group.children().stream()
                                                        .allMatch(
                                                                child ->
                                                                        child
                                                                                        instanceof
                                                                                        BlueprintElement
                                                                                                        .Cube
                                                                                                cube
                                                                                && Integer.valueOf(
                                                                                                15)
                                                                                        .equals(
                                                                                                cube
                                                                                                        .lightEmission()))));
        var treasure =
                model.groups().stream()
                        .filter(g -> g.name().equals("treasure"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(15, treasure.lightEmission());
        var lid =
                model.groups().stream()
                        .filter(g -> g.name().equals("lid"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                tier.equals("gold") ? List.of(0f, 17f, 11f) : List.of(10.5f, 9f, 0f),
                List.of(lid.origin().x(), lid.origin().y(), lid.origin().z()));
        assertEquals(Set.of("open"), blueprint.animations().keySet());
        var animation = blueprint.animations().get("open");
        assertEquals(.5f, animation.length());
        assertEquals(AnimationIterator.Type.HOLD_ON_LAST, animation.loop());
        assertEquals(
                Set.of("lid"),
                animation.animator().keySet().stream()
                        .map(bone -> bone.rawName())
                        .collect(Collectors.toSet()));
    }
}
