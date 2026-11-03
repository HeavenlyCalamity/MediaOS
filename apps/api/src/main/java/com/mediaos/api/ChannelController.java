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
@RequestMapping("/api/v1")
public class ChannelController {
    private final MediaOsService service;
    public ChannelController(MediaOsService service) { this.service = service; }

    @GetMapping("/brands/{brandId}/channels")
    public List<Channel> byBrand(@PathVariable String brandId) { return service.listChannels(brandId); }
    @PostMapping("/channels") public Channel create(@RequestBody ChannelRequest request) { return service.createChannel(request); }
    @PutMapping("/channels/{id}") public Channel update(@PathVariable String id, @RequestBody ChannelRequest request) {
        return service.updateChannel(id, request);
    }
    @DeleteMapping("/channels/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { service.deleteChannel(id); }
}
