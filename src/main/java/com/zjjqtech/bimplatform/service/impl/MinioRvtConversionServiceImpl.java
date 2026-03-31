package com.zjjqtech.bimplatform.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjjqtech.bimplatform.infrastructure.exception.BizException;
import com.zjjqtech.bimplatform.model.BimProject;
import com.zjjqtech.bimplatform.model.RvtConversionStatus;
import com.zjjqtech.bimplatform.repository.BimProjectRepository;
import com.zjjqtech.bimplatform.service.RvtConversionService;
import io.minio.*;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Profile("minio")
@Service
@Transactional(rollbackFor = Exception.class)
public class MinioRvtConversionServiceImpl implements RvtConversionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };
    private static final String EXT_KEY = "rvtConversion";

    private final BimProjectRepository bimProjectRepository;
    private final ObjectMapper objectMapper;
    private final ApsClient apsClient;
    private final TaskExecutor taskExecutor;

    @Value("${s3.endpoint}")
    private String endpoint;
    @Value("${s3.accessKeyId}")
    private String accessKeyId;
    @Value("${s3.accessKeySecret}")
    private String accessKeySecret;
    @Value("${s3.bucketName}")
    private String bucketName;

    private MinioClient minio;

    public MinioRvtConversionServiceImpl(BimProjectRepository bimProjectRepository, ObjectMapper objectMapper, ApsClient apsClient, TaskExecutor taskExecutor) {
        this.bimProjectRepository = bimProjectRepository;
        this.objectMapper = objectMapper;
        this.apsClient = apsClient;
        this.taskExecutor = taskExecutor;
    }

    @SneakyThrows
    @PostConstruct
    public void postConstruct() {
        this.minio = MinioClient.builder().endpoint(endpoint).credentials(accessKeyId, accessKeySecret).build();
        if (!this.minio.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
            this.minio.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        }
    }

    @SneakyThrows
    @Override
    public RvtConversionStatus upload(String projectId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("validate.error.rvt-conversion.file.required");
        }
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename) || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".rvt")) {
            throw new BizException("validate.error.rvt-conversion.file.type");
        }
        BimProject project = getProject(projectId);
        String objectName = String.format("rvt/raw/%s/%d-%s.rvt", projectId, System.currentTimeMillis(), sanitizeFileName(originalFilename));
        try (InputStream inputStream = file.getInputStream()) {
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .contentType(StringUtils.hasText(file.getContentType()) ? file.getContentType() : "application/octet-stream")
                    .stream(inputStream, file.getSize(), -1)
                    .build());
        }
        RvtConversionStatus status = new RvtConversionStatus();
        status.setStatus(RvtConversionStatus.UPLOADED);
        status.setSourceFileName(originalFilename);
        status.setSourceSize(file.getSize());
        status.setSourceObjectName(objectName);
        status.setSourceContentType(file.getContentType());
        status.setRegion(apsClient.getRegion());
        status.setMessage("RVT uploaded. Ready to submit to APS.");
        status.setUploadedOn(LocalDateTime.now());
        status.setUpdatedOn(LocalDateTime.now());
        return saveStatus(project, status);
    }

    @Override
    public RvtConversionStatus submit(String projectId) {
        BimProject project = getProject(projectId);
        RvtConversionStatus status = getStoredStatus(project);
        if (!StringUtils.hasText(status.getSourceObjectName())) {
            throw new BizException("validate.error.rvt-conversion.file.non-existed");
        }
        if (!apsClient.isConfigured()) {
            status.setStatus(RvtConversionStatus.FAILED);
            status.setMessage("APS is not configured. Please set APS_ENABLED, APS_CLIENT_ID and APS_CLIENT_SECRET first.");
            status.setUpdatedOn(LocalDateTime.now());
            status.setFinishedOn(LocalDateTime.now());
            return saveStatus(project, status);
        }
        status.setStatus(RvtConversionStatus.SUBMITTING);
        status.setProgress("0%");
        status.setManifestStatus("pending");
        status.setMessage("Submitting RVT to APS.");
        status.setSubmittedOn(LocalDateTime.now());
        status.setUpdatedOn(LocalDateTime.now());
        saveStatus(project, status);
        taskExecutor.execute(() -> submitToAps(projectId));
        return status;
    }

    @Override
    public RvtConversionStatus getStatus(String projectId) {
        BimProject project = getProject(projectId);
        RvtConversionStatus status = getStoredStatus(project);
        if ((RvtConversionStatus.SUBMITTING.equals(status.getStatus()) || RvtConversionStatus.IN_PROGRESS.equals(status.getStatus()))
                && StringUtils.hasText(status.getApsUrn()) && apsClient.isConfigured()) {
            return refreshManifest(project, status);
        }
        return status;
    }

    @SneakyThrows
    protected void submitToAps(String projectId) {
        BimProject project = getProject(projectId);
        RvtConversionStatus status = getStoredStatus(project);
        try {
            String token = apsClient.authenticate();
            String apsBucketKey = buildApsBucketKey(projectId);
            String apsObjectName = buildApsObjectName(status.getSourceFileName());
            apsClient.ensureBucketExists(token, apsBucketKey);
            ApsClient.SignedUploadSession session = apsClient.createSignedUpload(token, apsBucketKey, apsObjectName, null);
            try (InputStream sourceStream = minio.getObject(GetObjectArgs.builder().bucket(bucketName).object(status.getSourceObjectName()).build())) {
                apsClient.uploadToSignedUrl(session.getUploadUrl(), sourceStream, status.getSourceSize(), status.getSourceContentType());
            }
            String objectId = resolveObjectId(apsClient.completeSignedUpload(token, apsBucketKey, apsObjectName, session.getUploadKey(), status.getSourceSize()), apsBucketKey, apsObjectName);
            String urn = apsClient.toDerivativeUrn(objectId);
            apsClient.startTranslationJob(token, urn);
            status.setStatus(RvtConversionStatus.IN_PROGRESS);
            status.setApsBucketKey(apsBucketKey);
            status.setApsObjectName(apsObjectName);
            status.setApsObjectId(objectId);
            status.setApsUrn(urn);
            status.setManifestStatus("inprogress");
            status.setProgress("0%");
            status.setMessage("APS translation job has started.");
            status.setUpdatedOn(LocalDateTime.now());
            saveStatus(project, status);
        } catch (Exception e) {
            log.error("submitToAps error, projectId={}", projectId, e);
            status.setStatus(RvtConversionStatus.FAILED);
            status.setManifestStatus("failed");
            status.setMessage(StringUtils.hasText(e.getMessage()) ? e.getMessage() : "APS translation failed.");
            status.setFinishedOn(LocalDateTime.now());
            status.setUpdatedOn(LocalDateTime.now());
            saveStatus(project, status);
        }
    }

    private RvtConversionStatus refreshManifest(BimProject project, RvtConversionStatus status) {
        try {
            String token = apsClient.authenticate();
            Map<String, Object> manifest = objectMapper.convertValue(apsClient.getManifest(token, status.getApsUrn()), MAP_TYPE);
            String manifestStatus = String.valueOf(manifest.getOrDefault("status", "pending"));
            String progress = String.valueOf(manifest.getOrDefault("progress", "0%"));
            status.setManifestStatus(manifestStatus);
            status.setProgress(progress);
            status.setUpdatedOn(LocalDateTime.now());
            if ("success".equalsIgnoreCase(manifestStatus)) {
                status.setStatus(RvtConversionStatus.SUCCESS);
                status.setMessage("APS translation completed. Local SVF import is the next step.");
                status.setFinishedOn(LocalDateTime.now());
            } else if ("failed".equalsIgnoreCase(manifestStatus) || "timeout".equalsIgnoreCase(manifestStatus)) {
                status.setStatus(RvtConversionStatus.FAILED);
                status.setMessage("APS translation failed. Please inspect the manifest and source model.");
                status.setFinishedOn(LocalDateTime.now());
            } else {
                status.setStatus(RvtConversionStatus.IN_PROGRESS);
                status.setMessage("APS translation is running.");
            }
            return saveStatus(project, status);
        } catch (Exception e) {
            log.warn("refreshManifest error, projectId={}", project.getId(), e);
            return status;
        }
    }

    private BimProject getProject(String projectId) {
        Optional<BimProject> holder = bimProjectRepository.findById(projectId);
        if (!holder.isPresent()) {
            throw new BizException("validate.error.bim-project.non-existed");
        }
        return holder.get();
    }

    private RvtConversionStatus getStoredStatus(BimProject project) {
        Map<String, Object> ext = readExt(project);
        Object statusNode = ext.get(EXT_KEY);
        return statusNode == null ? RvtConversionStatus.notStarted() : objectMapper.convertValue(statusNode, RvtConversionStatus.class);
    }

    private RvtConversionStatus saveStatus(BimProject project, RvtConversionStatus status) {
        Map<String, Object> ext = readExt(project);
        ext.put(EXT_KEY, status);
        project.setExt(objectMapper.valueToTree(ext));
        bimProjectRepository.save(project);
        return status;
    }

    private Map<String, Object> readExt(BimProject project) {
        if (project.getExt() == null || project.getExt().isNull()) {
            return new HashMap<>();
        }
        return objectMapper.convertValue(project.getExt(), MAP_TYPE);
    }

    private String sanitizeFileName(String fileName) {
        String sanitized = fileName.toLowerCase(Locale.ROOT)
                .replaceAll("\\.rvt$", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return StringUtils.hasText(sanitized) ? sanitized : "model";
    }

    private String buildApsBucketKey(String projectId) {
        String prefix = (apsClient.getBucketPrefix() + "-" + projectId).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (prefix.length() > 128) {
            prefix = prefix.substring(0, 128);
        }
        return prefix;
    }

    private String buildApsObjectName(String sourceFileName) {
        return System.currentTimeMillis() + "-" + sanitizeFileName(sourceFileName) + ".rvt";
    }

    private String resolveObjectId(com.fasterxml.jackson.databind.JsonNode completeResponse, String bucketKey, String objectName) {
        String objectId = completeResponse.path("objectId").asText();
        if (StringUtils.hasText(objectId)) {
            return objectId;
        }
        return "urn:adsk.objects:os.object:" + bucketKey + "/" + objectName;
    }
}
