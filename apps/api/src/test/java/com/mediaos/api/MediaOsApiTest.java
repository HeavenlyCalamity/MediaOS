package com.mediaos.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class MediaOsApiTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void createsUserDefinedBrandCategoryAndContentAndRejectsInvalidTransition() throws Exception {
        String unique = UUID.randomUUID().toString();
        MvcResult brandResult = mockMvc.perform(post("/api/v1/brands").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Music Lab", "slug", "music-" + unique,
                                "contentPillars", new String[]{"production", "history"}, "audienceProfile", Map.of("primaryAudience", "Artists")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Music Lab")).andReturn();
        JsonNode brand = objectMapper.readTree(brandResult.getResponse().getContentAsString());
        String brandId = brand.get("id").asText();

        MvcResult categoryResult = mockMvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Music", "slug", "music-" + unique))))
                .andExpect(status().isOk()).andReturn();
        String categoryId = objectMapper.readTree(categoryResult.getResponse().getContentAsString()).get("id").asText();
        Map<String, Object> payload = Map.of("brandId", brandId, "categoryId", categoryId, "title", "How sampling changed pop",
                "contentType", "video essay", "language", "en", "brief", "A user-defined format", "targetChannelIds", new String[]{},
                "sourceRecords", new Object[]{}, "assets", new Object[]{});
        MvcResult itemResult = mockMvc.perform(post("/api/v1/content-items").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IDEA")).andReturn();
        String contentId = objectMapper.readTree(itemResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/content-items/{id}/transition", contentId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mockMvc.perform(post("/api/v1/content-items/{id}/transition", contentId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESEARCHING\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESEARCHING"));
    }

    @Test
    void exposesProviderCatalogAndReturnsExplicitUnsupportedRendererState() throws Exception {
        mockMvc.perform(get("/api/v1/providers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultMode").value("MOCK"))
                .andExpect(jsonPath("$.realProvidersEnabled").value(false))
                .andExpect(jsonPath("$.capabilities[1].capability").value("script"))
                .andExpect(jsonPath("$.capabilities[1].providers[0]").value("mock"));
        String unique = UUID.randomUUID().toString();
        MvcResult brandResult = mockMvc.perform(post("/api/v1/brands").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Audio brand", "slug", "audio-" + unique))))
                .andExpect(status().isOk()).andReturn();
        String brandId = objectMapper.readTree(brandResult.getResponse().getContentAsString()).get("id").asText();
        String workflow = "{\"workflow_id\":\"audio-only\",\"version\":\"1\",\"mode\":\"MOCK\",\"renderer\":\"audio-podcast-v1\"}";
        MvcResult profileResult = mockMvc.perform(post("/api/v1/production-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("brandId", brandId, "name", "Audio profile", "contentFormat", "podcast", "settings", Map.of("mediaos.workflow_json", workflow)))))
                .andExpect(status().isOk()).andReturn();
        String profileId = objectMapper.readTree(profileResult.getResponse().getContentAsString()).get("id").asText();
        MvcResult contentResult = mockMvc.perform(post("/api/v1/content-items").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("brandId", brandId, "title", "Podcast brief", "contentType", "podcast", "language", "en", "brief", "Talk about a topic", "productionProfileId", profileId))))
                .andExpect(status().isOk()).andReturn();
        String contentId = objectMapper.readTree(contentResult.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/v1/content-items/{id}/production-jobs", contentId).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("productionProfileId", profileId, "idempotencyKey", "unsupported-" + unique))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNSUPPORTED_RENDERER"));
    }
}
