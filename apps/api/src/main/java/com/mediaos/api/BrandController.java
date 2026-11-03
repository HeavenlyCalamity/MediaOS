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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/brands")
public class BrandController {
    private final MediaOsService service;
    public BrandController(MediaOsService service) { this.service = service; }

    @GetMapping public List<Brand> list() { return service.listBrands(); }
    @PostMapping public Brand create(@RequestBody BrandRequest request) { return service.createBrand(request); }
    @GetMapping("/{id}") public Brand get(@PathVariable String id) { return service.getBrand(id); }
    @PutMapping("/{id}") public Brand update(@PathVariable String id, @RequestBody BrandRequest request) {
        return service.updateBrand(id, request);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { service.deleteBrand(id); }
}
