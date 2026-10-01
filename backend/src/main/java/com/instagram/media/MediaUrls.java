package com.instagram.media;

import com.instagram.config.AppProperties;
import org.springframework.stereotype.Component;

/** Builds public media URLs: CloudFront in prod, LocalStack S3 path-style URL locally. */
@Component
public class MediaUrls {
    private final String baseUrl;

    public MediaUrls(AppProperties props) {
        String base = props.media().cdnBaseUrl();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public String url(String key) {
        return key == null ? null : baseUrl + "/" + key;
    }
}
