package com.zjjqtech.bimplatform.controller;

import com.zjjqtech.bimplatform.model.RvtConversionStatus;
import com.zjjqtech.bimplatform.service.RvtConversionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rvt-convert")
public class RvtConversionController {

    private final RvtConversionService rvtConversionService;

    public RvtConversionController(RvtConversionService rvtConversionService) {
        this.rvtConversionService = rvtConversionService;
    }

    @PreAuthorize("hasRole('ADMIN') || @bimProjectService.checkIsOwnerOfBimProject(#projectId)")
    @PostMapping("/{projectId}/upload")
    public RvtConversionStatus upload(@PathVariable String projectId, @RequestParam("file") MultipartFile file) {
        return rvtConversionService.upload(projectId, file);
    }

    @PreAuthorize("hasRole('ADMIN') || @bimProjectService.checkIsOwnerOfBimProject(#projectId)")
    @PostMapping("/{projectId}/submit")
    public RvtConversionStatus submit(@PathVariable String projectId) {
        return rvtConversionService.submit(projectId);
    }

    @PreAuthorize("hasRole('ADMIN') || @bimProjectService.checkIsOwnerOfBimProject(#projectId)")
    @GetMapping("/{projectId}/status")
    public RvtConversionStatus status(@PathVariable String projectId) {
        return rvtConversionService.getStatus(projectId);
    }
}
