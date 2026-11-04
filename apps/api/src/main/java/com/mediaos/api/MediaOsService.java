package com.mediaos.api;

import static com.mediaos.api.Models.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Local domain repository. Runtime state is atomically persisted to the configured app-data directory. */
@Service
public class MediaOsService {
    private final ObjectMapper objectMapper;
    private final Path stateFile;
    private final Map<String, Brand> brands = new LinkedHashMap<>();
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private final Map<String, ProductionProfile> profiles = new LinkedHashMap<>();
    private final Map<String, ContentItem> contentItems = new LinkedHashMap<>();

    private record PersistentState(Map<String, Brand> brands, Map<String, Category> categories,
                                   Map<String, Channel> channels, Map<String, ProductionProfile> profiles,
                                   Map<String, ContentItem> contentItems) {}

    /** Direct constructor keeps pure domain tests isolated and in-memory. */
    public MediaOsService() {
        this(new ObjectMapper(), (Path) null);
    }

    @Autowired
    public MediaOsService(ObjectMapper objectMapper,
                          @Value("${mediaos.data-dir:${user.home}/.mediaos}") String dataDirectory) {
        this(objectMapper, Path.of(dataDirectory).resolve("api-state.json"));
        loadState();
    }

    private MediaOsService(ObjectMapper objectMapper, Path stateFile) {
        this.objectMapper = objectMapper;
        this.stateFile = stateFile;
    }

    private void loadState() {
        if (!Files.exists(stateFile)) return;
        try {
            PersistentState state = objectMapper.readValue(stateFile.toFile(), PersistentState.class);
            if (state.brands() != null) brands.putAll(state.brands());
            if (state.categories() != null) categories.putAll(state.categories());
            if (state.channels() != null) channels.putAll(state.channels());
            if (state.profiles() != null) profiles.putAll(state.profiles());
            if (state.contentItems() != null) contentItems.putAll(state.contentItems());
        } catch (IOException error) {
            throw new IllegalStateException("Could not load local Media OS state from " + stateFile, error);
        }
    }

    private void persistState() {
        if (stateFile == null) return;
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(stateFile.getParent());
            PersistentState state = new PersistentState(brands, categories, channels, profiles, contentItems);
            objectMapper.writeValue(temporary.toFile(), state);
            try {
                Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new IllegalStateException("Could not persist local Media OS state to " + stateFile, error);
        }
    }
    private static final Set<String> CONTENT_STATES = Set.of(
            "IDEA", "RESEARCHING", "RESEARCH_READY", "SCRIPT_READY", "ASSETS_READY",
            "RENDERING", "QA_PENDING", "IN_REVIEW", "APPROVED", "EXPORT_READY",
            "REJECTED", "FAILED", "CANCELLED");
    private static final Map<String, Set<String>> TRANSITIONS = Map.ofEntries(
            Map.entry("IDEA", Set.of("RESEARCHING", "CANCELLED", "FAILED")),
            Map.entry("RESEARCHING", Set.of("RESEARCH_READY", "FAILED", "CANCELLED")),
            Map.entry("RESEARCH_READY", Set.of("SCRIPT_READY", "FAILED", "CANCELLED")),
            Map.entry("SCRIPT_READY", Set.of("ASSETS_READY", "FAILED", "CANCELLED")),
            Map.entry("ASSETS_READY", Set.of("RENDERING", "FAILED", "CANCELLED")),
            Map.entry("RENDERING", Set.of("QA_PENDING", "FAILED", "CANCELLED")),
            Map.entry("QA_PENDING", Set.of("IN_REVIEW", "FAILED", "CANCELLED")),
            Map.entry("IN_REVIEW", Set.of("FAILED", "CANCELLED")));

    public synchronized List<Brand> listBrands() { return List.copyOf(brands.values()); }
    public synchronized Brand getBrand(String id) { return require(brands, id, "brand"); }

    public synchronized Brand createBrand(BrandRequest request) {
        validate(request != null, "brand request is required");
        String slug = required(request.slug(), "slug");
        ensureUnique(brands.values().stream().map(Brand::slug).toList(), slug, "brand slug");
        Instant now = Instant.now();
        Brand item = new Brand(id(), required(request.name(), "name"), slug, clean(request.description()),
                defaultValue(request.defaultLanguage(), "und"), clean(request.tone()),
                strings(request.contentPillars()), stringMap(request.audienceProfile()), true, now, now);
        brands.put(item.id(), item);
        persistState();
        return item;
    }

    public synchronized Brand updateBrand(String id, BrandRequest request) {
        Brand old = require(brands, id, "brand");
        validate(request != null, "brand request is required");
        String slug = required(request.slug(), "slug");
        ensureUnique(brands.values().stream().filter(x -> !x.id().equals(id)).map(Brand::slug).toList(), slug, "brand slug");
        Brand item = new Brand(id, required(request.name(), "name"), slug, clean(request.description()),
                defaultValue(request.defaultLanguage(), "und"), clean(request.tone()),
                strings(request.contentPillars()), stringMap(request.audienceProfile()), old.enabled(), old.createdAt(), Instant.now());
        brands.put(id, item);
        persistState();
        return item;
    }

    public synchronized void deleteBrand(String id) {
        Brand brand = require(brands, id, "brand");
        boolean used = channels.values().stream().anyMatch(x -> id.equals(x.brandId()))
                || profiles.values().stream().anyMatch(x -> id.equals(x.brandId()))
                || contentItems.values().stream().anyMatch(x -> id.equals(x.brandId()));
        if (used) throw new ConflictException("brand is referenced by a channel, production profile, or content item");
        brands.remove(brand.id());
        persistState();
    }

    public synchronized List<Category> listCategories() { return List.copyOf(categories.values()); }
    public synchronized Category getCategory(String id) { return require(categories, id, "category"); }

    public synchronized Category createCategory(CategoryRequest request) {
        validate(request != null, "category request is required");
        String slug = required(request.slug(), "slug");
        ensureUnique(categories.values().stream().map(Category::slug).toList(), slug, "category slug");
        String parent = optional(request.parentCategoryId());
        if (parent != null) require(categories, parent, "parent category");
        Instant now = Instant.now();
        Category item = new Category(id(), required(request.name(), "name"), slug, clean(request.description()), parent, now, now);
        categories.put(item.id(), item);
        persistState();
        return item;
    }

    public synchronized Category updateCategory(String id, CategoryRequest request) {
        Category old = require(categories, id, "category");
        validate(request != null, "category request is required");
        String slug = required(request.slug(), "slug");
        ensureUnique(categories.values().stream().filter(x -> !x.id().equals(id)).map(Category::slug).toList(), slug, "category slug");
        String parent = optional(request.parentCategoryId());
        if (parent != null) {
            require(categories, parent, "parent category");
            validate(!parent.equals(id) && !isDescendant(parent, id), "category hierarchy cannot contain a cycle");
        }
        Category item = new Category(id, required(request.name(), "name"), slug, clean(request.description()), parent, old.createdAt(), Instant.now());
        categories.put(id, item);
        persistState();
        return item;
    }

    public synchronized void deleteCategory(String id) {
        Category category = require(categories, id, "category");
        boolean used = categories.values().stream().anyMatch(x -> id.equals(x.parentCategoryId()))
                || profiles.values().stream().anyMatch(x -> id.equals(x.categoryId()))
                || contentItems.values().stream().anyMatch(x -> id.equals(x.categoryId()));
        if (used) throw new ConflictException("category is referenced by a child category, production profile, or content item");
        categories.remove(category.id());
        persistState();
    }

    public synchronized List<Channel> listChannels(String brandId) {
        require(brands, brandId, "brand");
        return channels.values().stream().filter(x -> x.brandId().equals(brandId)).toList();
    }

    public synchronized Channel createChannel(ChannelRequest request) {
        validate(request != null, "channel request is required");
        String brandId = required(request.brandId(), "brandId");
        require(brands, brandId, "brand");
        Instant now = Instant.now();
        Channel item = new Channel(id(), brandId, required(request.platform(), "platform"),
                required(request.displayName(), "displayName"), clean(request.accountReference()),
                defaultValue(request.status(), "NOT_CONNECTED"), now, now);
        channels.put(item.id(), item);
        persistState();
        return item;
    }

    public synchronized Channel updateChannel(String id, ChannelRequest request) {
        Channel old = require(channels, id, "channel");
        validate(request != null, "channel request is required");
        String brandId = required(request.brandId(), "brandId");
        require(brands, brandId, "brand");
        if (!old.brandId().equals(brandId) && contentItems.values().stream().anyMatch(x -> x.targetChannelIds().contains(id)))
            throw new ConflictException("cannot move a channel to another brand while content targets it");
        Channel item = new Channel(id, brandId, required(request.platform(), "platform"), required(request.displayName(), "displayName"),
                clean(request.accountReference()), defaultValue(request.status(), old.status()), old.createdAt(), Instant.now());
        channels.put(id, item);
        persistState();
        return item;
    }

    public synchronized void deleteChannel(String id) {
        Channel channel = require(channels, id, "channel");
        if (contentItems.values().stream().anyMatch(x -> x.targetChannelIds().contains(id)))
            throw new ConflictException("channel is referenced by a content item");
        channels.remove(channel.id());
        persistState();
    }

    public synchronized List<ProductionProfile> listProfiles() { return List.copyOf(profiles.values()); }
    public synchronized ProductionProfile getProfile(String id) { return require(profiles, id, "production profile"); }
    public synchronized List<ProductionProfile> listBrandProfiles(String brandId) {
        require(brands, brandId, "brand");
        return profiles.values().stream().filter(x -> x.brandId() == null || x.brandId().equals(brandId)).toList();
    }

    public synchronized ProductionProfile createProfile(ProductionProfileRequest request) {
        validate(request != null, "production profile request is required");
        String brandId = optional(request.brandId());
        if (brandId != null) require(brands, brandId, "brand");
        String categoryId = optional(request.categoryId());
        if (categoryId != null) require(categories, categoryId, "category");
        Instant now = Instant.now();
        ProductionProfile item = new ProductionProfile(id(), brandId, required(request.name(), "name"), categoryId,
                required(request.contentFormat(), "contentFormat"), strings(request.workflowStages()),
                profileSettings(request.settings()), now, now);
        profiles.put(item.id(), item);
        persistState();
        return item;
    }

    public synchronized ProductionProfile updateProfile(String id, ProductionProfileRequest request) {
        ProductionProfile old = require(profiles, id, "production profile");
        validate(request != null, "production profile request is required");
        String brandId = optional(request.brandId());
        if (brandId != null) require(brands, brandId, "brand");
        String categoryId = optional(request.categoryId());
        if (categoryId != null) require(categories, categoryId, "category");
        for (ContentItem content : contentItems.values()) {
            if (id.equals(content.productionProfileId())
                    && (!java.util.Objects.equals(categoryId, content.categoryId())
                    || (brandId != null && !brandId.equals(content.brandId()))))
                throw new ConflictException("production profile is referenced by content with a different brand or category");
        }
        ProductionProfile item = new ProductionProfile(id, brandId, required(request.name(), "name"), categoryId,
                required(request.contentFormat(), "contentFormat"), strings(request.workflowStages()),
                profileSettings(request.settings()), old.createdAt(), Instant.now());
        profiles.put(id, item);
        persistState();
        return item;
    }

    public synchronized void deleteProfile(String id) {
        ProductionProfile profile = require(profiles, id, "production profile");
        if (contentItems.values().stream().anyMatch(x -> id.equals(x.productionProfileId())))
            throw new ConflictException("production profile is referenced by a content item");
        profiles.remove(profile.id());
        persistState();
    }

    public synchronized List<ContentItem> listContent(String brandId) {
        if (brandId != null && !brandId.isBlank()) require(brands, brandId, "brand");
        return contentItems.values().stream().filter(x -> brandId == null || brandId.isBlank() || x.brandId().equals(brandId)).toList();
    }
    public synchronized ContentItem getContent(String id) { return require(contentItems, id, "content item"); }

    public synchronized ContentItem createContent(ContentItemRequest request) {
        validate(request != null, "content request is required");
        validateContentReferences(request);
        Instant now = Instant.now();
        ContentItem item = new ContentItem(id(), required(request.brandId(), "brandId"), optional(request.categoryId()), required(request.title(), "title"),
                required(request.contentType(), "contentType"), defaultValue(request.language(), "und"), clean(request.brief()),
                optional(request.productionProfileId()), strings(request.targetChannelIds()), sources(request.sourceRecords()),
                assets(request.assets()), clean(request.description()), clean(request.script()), strings(request.tags()),
                platformMetadata(request.platformMetadata()), "IDEA", 1, now, now);
        contentItems.put(item.id(), item);
        persistState();
        return item;
    }

    public synchronized ContentItem updateContent(String id, ContentItemRequest request) {
        ContentItem old = require(contentItems, id, "content item");
        validate(request != null, "content request is required");
        validateContentReferences(request);
        ContentItem item = new ContentItem(id, required(request.brandId(), "brandId"), optional(request.categoryId()), required(request.title(), "title"),
                required(request.contentType(), "contentType"), defaultValue(request.language(), "und"), clean(request.brief()),
                optional(request.productionProfileId()), strings(request.targetChannelIds()), sources(request.sourceRecords()),
                assets(request.assets()), request.description() == null ? old.description() : clean(request.description()),
                request.script() == null ? old.script() : clean(request.script()),
                request.tags() == null ? old.tags() : strings(request.tags()),
                request.platformMetadata() == null ? old.platformMetadata() : platformMetadata(request.platformMetadata()),
                "APPROVED".equals(old.status()) ? "IN_REVIEW" : old.status(),
                old.version() + 1, old.createdAt(), Instant.now());
        contentItems.put(id, item);
        persistState();
        return item;
    }

    public synchronized ContentItem transitionContent(String id, String target) {
        ContentItem old = require(contentItems, id, "content item");
        String state = required(target, "status").toUpperCase();
        validate(CONTENT_STATES.contains(state), "unknown content status: " + state);
        if (!TRANSITIONS.getOrDefault(old.status(), Set.of()).contains(state))
            throw new IllegalArgumentException("invalid content transition: " + old.status() + " -> " + state);
        if (Set.of("APPROVED", "EXPORT_READY", "REJECTED").contains(state))
            throw new IllegalArgumentException(state + " can only be set through the explicit review workflow");
        ContentItem item = new ContentItem(old.id(), old.brandId(), old.categoryId(), old.title(), old.contentType(), old.language(),
                old.brief(), old.productionProfileId(), old.targetChannelIds(), old.sourceRecords(), old.assets(),
                old.description(), old.script(), old.tags(), old.platformMetadata(), state, old.version() + 1, old.createdAt(), Instant.now());
        contentItems.put(id, item);
        persistState();
        return item;
    }

    synchronized ContentItem setReviewStatus(String id, String status) {
        ContentItem old = require(contentItems, id, "content item");
        ContentItem item = withStatus(old, status, old.version());
        contentItems.put(id, item);
        persistState();
        return item;
    }

    synchronized ContentItem resubmitContent(String id) {
        ContentItem old = require(contentItems, id, "content item");
        validate("REJECTED".equals(old.status()), "only rejected content can be resubmitted");
        ContentItem item = withStatus(old, "IN_REVIEW", old.version() + 1);
        contentItems.put(id, item);
        persistState();
        return item;
    }

    private static ContentItem withStatus(ContentItem old, String status, int version) {
        return new ContentItem(old.id(), old.brandId(), old.categoryId(), old.title(), old.contentType(), old.language(),
                old.brief(), old.productionProfileId(), old.targetChannelIds(), old.sourceRecords(), old.assets(),
                old.description(), old.script(), old.tags(), old.platformMetadata(), status, version, old.createdAt(), Instant.now());
    }

    private void validateContentReferences(ContentItemRequest request) {
        String brandId = required(request.brandId(), "brandId");
        require(brands, brandId, "brand");
        String categoryId = optional(request.categoryId());
        if (categoryId != null) require(categories, categoryId, "category");
        String profileId = optional(request.productionProfileId());
        if (profileId != null) {
            ProductionProfile profile = require(profiles, profileId, "production profile");
            validate(profile.brandId() == null || profile.brandId().equals(brandId), "production profile must be global or belong to the content brand");
            validate(profile.categoryId() == null || profile.categoryId().equals(categoryId), "production profile category must match the content category");
        }
        for (String channelId : strings(request.targetChannelIds())) {
            Channel channel = require(channels, channelId, "channel");
            validate(channel.brandId().equals(brandId), "target channel must belong to the content brand");
        }
        for (SourceRecord source : sources(request.sourceRecords())) {
            validate(source != null, "source record is required");
            required(source.title(), "source title");
            required(source.url(), "source url");
        }
        for (AssetRequest asset : assets(request.assets())) {
            validate(asset != null, "asset record is required");
            required(asset.kind(), "asset kind");
            required(asset.owner(), "asset owner");
            String license = required(asset.licenseType(), "asset licenseType");
            validate(!asset.permittedUses().isEmpty(), "asset permittedUses must include at least one usage");
            if (!"owned".equalsIgnoreCase(license)) {
                required(asset.licenseEvidence(), "asset licenseEvidence");
                required(asset.attribution(), "asset attribution");
            }
        }
    }

    private boolean isDescendant(String candidate, String ancestor) {
        Category item = categories.get(candidate);
        while (item != null && item.parentCategoryId() != null) {
            if (item.parentCategoryId().equals(ancestor)) return true;
            item = categories.get(item.parentCategoryId());
        }
        return false;
    }

    private static List<SourceRecord> sources(List<SourceRecord> values) {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }
    private static List<AssetRequest> assets(List<AssetRequest> values) {
        if (values == null) return List.of();
        return values.stream().map(x -> x == null ? null : new AssetRequest(x.kind(), clean(x.uri()), clean(x.mimeType()), clean(x.owner()),
                clean(x.licenseType()), clean(x.licenseEvidence()), clean(x.attribution()), strings(x.permittedUses()))).toList();
    }
    private static List<String> strings(List<String> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull).map(String::trim).filter(x -> !x.isBlank()).toList();
    }
    private static Map<String, String> stringMap(Map<String, String> values) {
        if (values == null) return Map.of();
        Map<String, String> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) throw new IllegalArgumentException("profile and audience settings require non-empty keys and values");
            copy.put(key.trim(), value.trim());
        });
        return Collections.unmodifiableMap(copy);
    }

    private Map<String, String> profileSettings(Map<String, String> values) {
        Map<String, String> settings = stringMap(values);
        ProviderConfigSecurity.rejectSecretFields(settings);
        String workflow = settings.get("mediaos.workflow_json");
        if (workflow != null && !workflow.isBlank()) {
            try {
                ProviderConfigSecurity.rejectSecretFields(objectMapper.readValue(workflow, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            } catch (IOException error) {
                throw new IllegalArgumentException("mediaos.workflow_json must contain a valid JSON object", error);
            }
        }
        return settings;
    }
    private static Map<String, Map<String, String>> platformMetadata(Map<String, Map<String, String>> values) {
        if (values == null) return Map.of();
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        values.forEach((platform, metadata) -> {
            if (platform == null || platform.isBlank()) throw new IllegalArgumentException("platform metadata keys must not be empty");
            copy.put(platform.trim(), stringMap(metadata));
        });
        return Collections.unmodifiableMap(copy);
    }
    private static <T> T require(Map<String, T> values, String id, String entity) {
        T item = values.get(id);
        if (item == null) throw new NotFoundException(entity + " not found: " + id);
        return item;
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static void validate(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void ensureUnique(List<String> existing, String candidate, String label) {
        if (existing.contains(candidate)) throw new ConflictException(label + " already exists: " + candidate);
    }
}
