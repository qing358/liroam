package com.liroam.supplier.adapter.quadcell;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Quadcell 连接配置（前缀 {@code liroam.supplier.quadcell}）。密钥只从环境变量 / 密钥管理注入，
 * 例如 {@code LIROAM_SUPPLIER_QUADCELL_APIKEY}；仓库与日志里不出现值。
 */
@ConfigurationProperties(QuadcellProperties.PREFIX)
public class QuadcellProperties {

    public static final String PREFIX = "liroam.supplier.quadcell";

    /** Open API §2：文档只有这一个地址（正式网址），测试 key 也打这里。 */
    public static final String OFFICIAL_BASE_URL = "https://mvne-api.quadcell.com:8443/open-api/1.0";

    /** 有值才装配适配器；为空时启用了该供应商的进程会因缺适配器启动失败。 */
    private String apiKey;

    private String baseUrl = OFFICIAL_BASE_URL;

    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 写请求发出后超过它仍无响应 = 结果未知（先查档再定）。 */
    private Duration readTimeout = Duration.ofSeconds(20);

    /** 8.7 请求头 {@code time-zone}；上游时间串不带偏移时按它解释。 */
    private String timeZone = "Asia/Hong_Kong";

    /** 上游流量单位 MB 换算字节的进制。1024×1024 还是 10^6 待 Quadcell 书面确认。 */
    private long bytesPerMb = 1024L * 1024L;

    /**
     * 开订阅必填的 Sim profile 日期模式（1：24hrs Period，2：Calendar Day）。套餐映射载荷 {@code mode} 优先；
     * 两处都没有则开卡被拒（不替运营猜）。
     */
    private Integer defaultMode;

    /** 上游 429 且未给 {@code Retry-After} 时的等待。 */
    private Duration throttleRetryAfter = Duration.ofSeconds(30);

    /** 可选：经固定出口 IP 的 HTTP 代理访问（白名单场景）。 */
    private String proxyHost;

    private Integer proxyPort;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public long getBytesPerMb() {
        return bytesPerMb;
    }

    public void setBytesPerMb(long bytesPerMb) {
        this.bytesPerMb = bytesPerMb;
    }

    public Integer getDefaultMode() {
        return defaultMode;
    }

    public void setDefaultMode(Integer defaultMode) {
        this.defaultMode = defaultMode;
    }

    public Duration getThrottleRetryAfter() {
        return throttleRetryAfter;
    }

    public void setThrottleRetryAfter(Duration throttleRetryAfter) {
        this.throttleRetryAfter = throttleRetryAfter;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public Integer getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(Integer proxyPort) {
        this.proxyPort = proxyPort;
    }
}
