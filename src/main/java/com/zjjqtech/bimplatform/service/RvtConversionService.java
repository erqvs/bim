package com.zjjqtech.bimplatform.service;

import com.zjjqtech.bimplatform.model.RvtConversionStatus;
import org.springframework.web.multipart.MultipartFile;

public interface RvtConversionService {

    RvtConversionStatus upload(String projectId, MultipartFile file);

    RvtConversionStatus submit(String projectId);

    RvtConversionStatus getStatus(String projectId);
}
