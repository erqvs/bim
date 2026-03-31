package com.zjjqtech.bimplatform.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class RvtConversionStatus implements Serializable {

    public static final String NOT_STARTED = "NOT_STARTED";
    public static final String UPLOADED = "UPLOADED";
    public static final String SUBMITTING = "SUBMITTING";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private String status = NOT_STARTED;
    private String sourceFileName;
    private Long sourceSize;
    private String sourceObjectName;
    private String sourceContentType;
    private String apsBucketKey;
    private String apsObjectName;
    private String apsObjectId;
    private String apsUrn;
    private String region;
    private String manifestStatus;
    private String progress;
    private String message;
    private LocalDateTime uploadedOn;
    private LocalDateTime submittedOn;
    private LocalDateTime finishedOn;
    private LocalDateTime updatedOn;

    public static RvtConversionStatus notStarted() {
        return new RvtConversionStatus();
    }
}
