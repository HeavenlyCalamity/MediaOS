package com.mediaos.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}
