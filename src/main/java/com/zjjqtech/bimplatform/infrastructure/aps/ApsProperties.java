package com.zjjqtech.bimplatform.infrastructure.aps;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * APS(Model Derivative) runtime configuration.
 */
@Data
@Component
@ConfigurationProperties(prefix = "aps")
public class ApsProperties {

    private boolean enabled;
    private String clientId;
    private String clientSecret;
    private String region = "US";
    private String bucketPrefix = "my-bim";
    private String bucketPolicy = "transient";

    public boolean isConfigured() {
        return enabled && StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }
}
