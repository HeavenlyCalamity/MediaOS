package com.mediaos.api;

import static com.mediaos.api.Models.*;

import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1")
public class ProductionController {
    private final ProductionService service;
    public ProductionController(ProductionService service) { this.service = service; }

    @GetMapping("/providers") public ProviderCatalog providers() { return service.catalog(); }
    @GetMapping("/production-jobs") public List<ProductionJob> jobs(@RequestParam(required = false) String contentId) { return service.list(contentId); }
    @GetMapping("/production-jobs/{id}") public ProductionJob job(@PathVariable String id) { return service.get(id); }
    @DeleteMapping("/production-jobs/{id}") public ProductionJob cancel(@PathVariable String id) { return service.cancel(id); }
    @GetMapping(value = "/production-jobs/{id}/assets/{assetId}", produces = {MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE})
    public ResponseEntity<Resource> visual(@PathVariable String id, @PathVariable String assetId) {
        Resource resource = service.visual(id, assetId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(service.visualMimeType(id, assetId))).body(resource);
    }
    @GetMapping(value = "/production-jobs/{id}/audio", produces = "audio/wav")
    public Resource audio(@PathVariable String id) { return service.audio(id); }
}
