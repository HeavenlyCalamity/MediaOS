package com.mediaos.api;

import static com.mediaos.api.Models.*;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/production-profiles")
public class ProductionProfileController {
    private final MediaOsService service;
    public ProductionProfileController(MediaOsService service) { this.service = service; }

    @GetMapping public List<ProductionProfile> list(@RequestParam(required = false) String brandId) {
        return brandId == null ? service.listProfiles() : service.listBrandProfiles(brandId);
    }
    @PostMapping public ProductionProfile create(@RequestBody ProductionProfileRequest request) { return service.createProfile(request); }
    @PutMapping("/{id}") public ProductionProfile update(@PathVariable String id, @RequestBody ProductionProfileRequest request) {
        return service.updateProfile(id, request);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { service.deleteProfile(id); }
}
