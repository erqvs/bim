package com.zjjqtech.bimplatform.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjjqtech.bimplatform.infrastructure.aps.ApsProperties;
import com.zjjqtech.bimplatform.infrastructure.exception.BizException;
import lombok.Data;
import okhttp3.*;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class ApsClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded; charset=utf-8");
    private static final String APS_HOST = "https://developer.api.autodesk.com";
    private static final String APS_SCOPE = "data:read data:write data:create bucket:read bucket:create";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ApsProperties apsProperties;

    public ApsClient(ObjectMapper objectMapper, ApsProperties apsProperties) {
        this.objectMapper = objectMapper;
        this.apsProperties = apsProperties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES)
                .build();
    }

    public boolean isConfigured() {
        return apsProperties.isConfigured();
    }

    public String getRegion() {
        return apsProperties.getRegion();
    }

    public String getBucketPolicy() {
        return apsProperties.getBucketPolicy();
    }

    public String getBucketPrefix() {
        return apsProperties.getBucketPrefix();
    }

    public String authenticate() {
        RequestBody requestBody = RequestBody.create(
                "grant_type=client_credentials&client_id=" + encodeForm(apsProperties.getClientId()) +
                        "&client_secret=" + encodeForm(apsProperties.getClientSecret()) +
                        "&scope=" + encodeForm(APS_SCOPE),
                FORM
        );
        Request request = new Request.Builder()
                .url(APS_HOST + "/authentication/v2/token")
                .post(requestBody)
                .build();
        JsonNode response = executeJson(request, 200);
        return response.path("access_token").asText();
    }

    public void ensureBucketExists(String token, String bucketKey) {
        Request.Builder builder = authorizedBuilder(token, APS_HOST + "/oss/v2/buckets")
                .post(RequestBody.create("{\"bucketKey\":\"" + bucketKey + "\",\"policyKey\":\"" + getBucketPolicy() + "\"}", JSON));
        applyRegionHeader(builder);
        executeJson(builder.build(), 200, 409);
    }

    public SignedUploadSession createSignedUpload(String token, String bucketKey, String objectKey, String uploadKey) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(APS_HOST + "/oss/v2/buckets/" + bucketKey + "/objects/" + objectKey + "/signeds3upload")
                .newBuilder()
                .addQueryParameter("parts", "1")
                .addQueryParameter("firstPart", "1");
        if (StringUtils.hasText(uploadKey)) {
            urlBuilder.addQueryParameter("uploadKey", uploadKey);
        }
        Request request = authorizedBuilder(token, urlBuilder.build().toString()).get().build();
        JsonNode response = executeJson(request, 200);
        SignedUploadSession session = new SignedUploadSession();
        session.setUploadKey(response.path("uploadKey").asText());
        JsonNode urls = response.path("urls");
        if (!urls.isArray() || urls.size() == 0) {
            throw new BizException("validate.error.rvt-conversion.aps.upload-url");
        }
        session.setUploadUrl(urls.get(0).asText());
        return session;
    }

    public JsonNode completeSignedUpload(String token, String bucketKey, String objectKey, String uploadKey, long size) {
        RequestBody requestBody = RequestBody.create("{\"uploadKey\":\"" + uploadKey + "\",\"size\":" + size + "}", JSON);
        Request request = authorizedBuilder(token, APS_HOST + "/oss/v2/buckets/" + bucketKey + "/objects/" + objectKey + "/signeds3upload")
                .post(requestBody)
                .build();
        return executeJson(request, 200, 201);
    }

    public void uploadToSignedUrl(String uploadUrl, final InputStream inputStream, final long size, String contentType) {
        MediaType mediaType = MediaType.parse(StringUtils.hasText(contentType) ? contentType : "application/octet-stream");
        RequestBody requestBody = new RequestBody() {
            @Override
            public MediaType contentType() {
                return mediaType;
            }

            @Override
            public long contentLength() {
                return size;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                try (Source source = Okio.source(inputStream)) {
                    sink.writeAll(source);
                }
            }
        };
        Request request = new Request.Builder().url(uploadUrl).put(requestBody).build();
        executeEmpty(request, 200);
    }

    public JsonNode startTranslationJob(String token, String urn) {
        String requestJson = "{\"input\":{\"urn\":\"" + urn + "\"},\"output\":{\"formats\":[{\"type\":\"svf\",\"views\":[\"2d\",\"3d\"],\"advanced\":{\"2dviews\":\"pdf\"}}]}}";
        HttpUrl.Builder urlBuilder = HttpUrl.parse(APS_HOST + "/modelderivative/v2/designdata/job").newBuilder();
        if (StringUtils.hasText(apsProperties.getRegion())) {
            urlBuilder.addQueryParameter("region", apsProperties.getRegion().toUpperCase(Locale.ROOT));
        }
        Request request = authorizedBuilder(token, urlBuilder.build().toString())
                .post(RequestBody.create(requestJson, JSON))
                .build();
        return executeJson(request, 200, 201, 202);
    }

    public JsonNode getManifest(String token, String urn) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(APS_HOST + "/modelderivative/v2/designdata/" + urn + "/manifest").newBuilder();
        if (StringUtils.hasText(apsProperties.getRegion())) {
            urlBuilder.addQueryParameter("region", apsProperties.getRegion().toUpperCase(Locale.ROOT));
        }
        Request request = authorizedBuilder(token, urlBuilder.build().toString()).get().build();
        return executeJson(request, 200, 202);
    }

    public String toDerivativeUrn(String objectId) {
        return Base64.getEncoder().withoutPadding()
                .encodeToString(objectId.getBytes(StandardCharsets.UTF_8))
                .replace('+', '-')
                .replace('/', '_');
    }

    private Request.Builder authorizedBuilder(String token, String url) {
        return new Request.Builder().url(url).addHeader("Authorization", "Bearer " + token);
    }

    private void applyRegionHeader(Request.Builder builder) {
        if (StringUtils.hasText(apsProperties.getRegion()) && !"US".equalsIgnoreCase(apsProperties.getRegion())) {
            builder.addHeader("x-ads-region", apsProperties.getRegion().toUpperCase(Locale.ROOT));
        }
    }

    private JsonNode executeJson(Request request, int... acceptedCodes) {
        try (Response response = httpClient.newCall(request).execute()) {
            if (!isAccepted(response.code(), acceptedCodes)) {
                throw buildApsException(response);
            }
            String body = response.body() == null ? "{}" : response.body().string();
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new BizException("validate.error.rvt-conversion.aps.request");
        }
    }

    private void executeEmpty(Request request, int... acceptedCodes) {
        try (Response response = httpClient.newCall(request).execute()) {
            if (!isAccepted(response.code(), acceptedCodes)) {
                throw buildApsException(response);
            }
        } catch (IOException e) {
            throw new BizException("validate.error.rvt-conversion.aps.request");
        }
    }

    private boolean isAccepted(int code, int... acceptedCodes) {
        for (int acceptedCode : acceptedCodes) {
            if (acceptedCode == code) {
                return true;
            }
        }
        return false;
    }

    private BizException buildApsException(Response response) throws IOException {
        String errorBody = response.body() == null ? "" : response.body().string();
        String message = StringUtils.hasText(errorBody) ? errorBody : response.message();
        return new BizException("APS request failed: " + message, "validate.error.rvt-conversion.aps.request");
    }

    private String encodeForm(String value) {
        return value == null ? "" : HttpUrl.parse("https://dummy.local").newBuilder().addQueryParameter("v", value).build().queryParameter("v");
    }

    @Data
    public static class SignedUploadSession {
        private String uploadKey;
        private String uploadUrl;
    }
}
