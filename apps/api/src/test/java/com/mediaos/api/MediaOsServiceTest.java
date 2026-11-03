package com.mediaos.api;

import static com.mediaos.api.Models.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MediaOsServiceTest {
    private MediaOsService service;

    @BeforeEach void setUp() { service = new MediaOsService(); }

    private Brand brand(String name, String slug) {
        return service.createBrand(new BrandRequest(name, slug, "", "en", "", List.of("Pillar A", "Pillar B"), Map.of("primaryAudience", "Viewers")));
    }

    @Test
    void acceptsArbitraryBrandsAndHierarchicalUserDefinedCategories() {
        Brand gaming = brand("Arcade Atlas", "arcade-atlas");
        Brand food = brand("Kitchen Notes", "kitchen-notes");
        Category gameCategory = service.createCategory(new CategoryRequest("Gaming", "gaming", "", null));
        Category subcategory = service.createCategory(new CategoryRequest("Speedrunning", "speedrunning", "", gameCategory.id()));

        assertEquals(2, service.listBrands().size());
        assertEquals(food.id(), service.getBrand(food.id()).id());
        assertEquals(gameCategory.id(), service.getCategory(subcategory.id()).parentCategoryId());
        assertEquals("Arcade Atlas", service.updateBrand(gaming.id(), new BrandRequest("Arcade Atlas", "arcade-atlas", "Updated", "en", "playful", List.of("Lore"), Map.of())).name());
        assertThrows(IllegalArgumentException.class, () -> service.updateCategory(gameCategory.id(),
                new CategoryRequest("Gaming", "gaming", "", subcategory.id())));
    }

    @Test
    void supportsMultipleDestinationsAndFormatSpecificProfilesForAUserBrand() {
        Brand brand = brand("Independent Studio", "independent-studio");
        Channel channelA = service.createChannel(new ChannelRequest(brand.id(), "YouTube", "Main", "channel-1", "NOT_CONNECTED"));
        Channel channelB = service.createChannel(new ChannelRequest(brand.id(), "YouTube", "Clips", "channel-2", "NOT_CONNECTED"));
        ProductionProfile video = service.createProfile(new ProductionProfileRequest(brand.id(), "Gameplay cut", null,
                "vertical gameplay", List.of("capture", "edit", "review"), Map.of("aspectRatio", "9:16")));
        ProductionProfile audio = service.createProfile(new ProductionProfileRequest(brand.id(), "Music visualizer", null,
                "audio visualizer", List.of("rights-check", "visualize", "review"), Map.of("audioRequired", "true")));

        assertEquals(2, service.listChannels(brand.id()).size());
        assertEquals(List.of(channelA.id(), channelB.id()), service.listChannels(brand.id()).stream().map(Channel::id).toList());
        assertEquals(List.of(video.id(), audio.id()), service.listBrandProfiles(brand.id()).stream().map(ProductionProfile::id).toList());
    }

    @Test
    void contentKeepsSourcesRightsAndTargetsAndValidatesLifecycle() {
        Brand brand = brand("Field Journal", "field-journal");
        Category category = service.createCategory(new CategoryRequest("Nature", "nature", "", null));
        Channel channel = service.createChannel(new ChannelRequest(brand.id(), "YouTube", "Channel", "acct-123", "NOT_CONNECTED"));
        ProductionProfile profile = service.createProfile(new ProductionProfileRequest(brand.id(), "Documentary", category.id(),
                "mini documentary", List.of("research", "narration", "edit"), Map.of()));
        SourceRecord source = new SourceRecord("Field guide", "https://example.test/guide", "Example", "2026-01-01");
        AssetRequest music = new AssetRequest("music", "asset://local/music", "audio/mpeg", "Studio", "licensed", "license-record-1",
                "Composer credit", List.of("social video", "commercial"));
        ContentItem item = service.createContent(new ContentItemRequest(brand.id(), category.id(), "Forest at dawn", "mini documentary",
                "en", "A field note", profile.id(), List.of(channel.id()), List.of(source), List.of(music)));

        assertEquals("licensed", item.assets().getFirst().licenseType());
        assertEquals("https://example.test/guide", item.sourceRecords().getFirst().url());
        assertEquals(List.of(channel.id()), item.targetChannelIds());
        assertThrows(IllegalArgumentException.class, () -> service.transitionContent(item.id(), "APPROVED"));
        ContentItem progressed = service.transitionContent(item.id(), "RESEARCHING");
        assertEquals("RESEARCHING", progressed.status());
        assertEquals(2, progressed.version());
        for (String state : List.of("RESEARCH_READY", "SCRIPT_READY", "ASSETS_READY", "RENDERING", "QA_PENDING", "IN_REVIEW"))
            service.transitionContent(item.id(), state);
        assertThrows(IllegalArgumentException.class, () -> service.transitionContent(item.id(), "APPROVED"),
                "Stage 1 must not create an approval without an approval record");
        assertEquals("REJECTED", service.transitionContent(item.id(), "REJECTED").status());
        assertThrows(ConflictException.class, () -> service.deleteCategory(category.id()));
    }

    @Test
    void contentCannotTargetAnotherBrandsChannelOrUseAnUntrackedAssetLicense() {
        Brand owner = brand("Owner Brand", "owner-brand");
        Brand other = brand("Other Brand", "other-brand");
        Channel otherChannel = service.createChannel(new ChannelRequest(other.id(), "Streaming", "Other", "ref", "NOT_CONNECTED"));
        ContentItemRequest crossBrand = new ContentItemRequest(owner.id(), null, "Title", "podcast", "en", "", null,
                List.of(otherChannel.id()), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.createContent(crossBrand));
        ContentItemRequest incompleteRights = new ContentItemRequest(owner.id(), null, "Title", "podcast", "en", "", null,
                List.of(), List.of(), List.of(new AssetRequest("music", "", "", "", "", "", "", List.of("social"))));
        assertThrows(IllegalArgumentException.class, () -> service.createContent(incompleteRights));
    }
}
