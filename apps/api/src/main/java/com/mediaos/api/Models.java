package com.mediaos.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response contracts. Category and format values remain user-defined strings. */
public final class Models {
    private Models() {}

    public record BrandRequest(String name, String slug, String description, String defaultLanguage,
                               String tone, List<String> contentPillars, Map<String, String> audienceProfile) {}
    public record Brand(String id, String name, String slug, String description, String defaultLanguage,
                        String tone, List<String> contentPillars, Map<String, String> audienceProfile,
                        boolean enabled, Instant createdAt, Instant updatedAt) {}

    public record CategoryRequest(String name, String slug, String description, String parentCategoryId) {}
    public record Category(String id, String name, String slug, String description, String parentCategoryId,
                           Instant createdAt, Instant updatedAt) {}

    public record ChannelRequest(String brandId, String platform, String displayName,
                                 String accountReference, String status) {}
    public record Channel(String id, String brandId, String platform, String displayName,
                          String accountReference, String status, Instant createdAt, Instant updatedAt) {}

    public record ProductionProfileRequest(String brandId, String name, String categoryId, String contentFormat,
                                           List<String> workflowStages, Map<String, String> settings) {}
    public record ProductionProfile(String id, String brandId, String name, String categoryId, String contentFormat,
                                    List<String> workflowStages, Map<String, String> settings,
                                    Instant createdAt, Instant updatedAt) {}

    public record SourceRecord(String title, String url, String publisher, String accessedAt) {}
    public record AssetRequest(String kind, String uri, String mimeType, String owner,
                               String licenseType, String licenseEvidence, String attribution,
                               List<String> permittedUses) {}
    public record ContentItemRequest(String brandId, String categoryId, String title, String contentType,
                                     String language, String brief, String productionProfileId,
                                     List<String> targetChannelIds, List<SourceRecord> sourceRecords,
                                     List<AssetRequest> assets) {}
    public record ContentItem(String id, String brandId, String categoryId, String title, String contentType,
                              String language, String brief, String productionProfileId,
                              List<String> targetChannelIds, List<SourceRecord> sourceRecords,
                              List<AssetRequest> assets, String status, int version,
                              Instant createdAt, Instant updatedAt) {}

    public record TransitionRequest(String status) {}
}
