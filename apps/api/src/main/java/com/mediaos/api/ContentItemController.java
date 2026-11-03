package com.mediaos.api;

import static com.mediaos.api.Models.*;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/content-items")
public class ContentItemController {
    private final MediaOsService service;
    public ContentItemController(MediaOsService service) { this.service = service; }

    @GetMapping public List<ContentItem> list(@RequestParam(required = false) String brandId) { return service.listContent(brandId); }
    @PostMapping public ContentItem create(@RequestBody ContentItemRequest request) { return service.createContent(request); }
    @GetMapping("/{id}") public ContentItem get(@PathVariable String id) { return service.getContent(id); }
    @PutMapping("/{id}") public ContentItem update(@PathVariable String id, @RequestBody ContentItemRequest request) {
        return service.updateContent(id, request);
    }
    @PostMapping("/{id}/transition") public ContentItem transition(@PathVariable String id, @RequestBody TransitionRequest request) {
        return service.transitionContent(id, request.status());
    }
}
