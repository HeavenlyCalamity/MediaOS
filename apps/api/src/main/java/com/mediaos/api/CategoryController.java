package com.mediaos.api;

import static com.mediaos.api.Models.*;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {
    private final MediaOsService service;
    public CategoryController(MediaOsService service) { this.service = service; }

    @GetMapping public List<Category> list() { return service.listCategories(); }
    @PostMapping public Category create(@RequestBody CategoryRequest request) { return service.createCategory(request); }
    @GetMapping("/{id}") public Category get(@PathVariable String id) { return service.getCategory(id); }
    @PutMapping("/{id}") public Category update(@PathVariable String id, @RequestBody CategoryRequest request) {
        return service.updateCategory(id, request);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { service.deleteCategory(id); }
}
