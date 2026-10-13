package com.lolens.services.lcu;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * 自定义 SSLContext：信任 LCU 自签名证书（仅用于本地回环连接，不做任何远程验证绕过）。
 */
public final class SslTrustAllContexts {

    private SslTrustAllContexts() {
    }

    /** 信任所有证书的 TrustManager（仅限 LCU 本地回环 127.0.0.1）。 */
    private static final TrustManager[] TRUST_ALL = new TrustManager[]{
            new X509TrustManager() {
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            }
    };

    /** 创建信任自签名证书的 SSLContext。 */
    public static SSLContext createTrustAllContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, TRUST_ALL, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("创建 SSLContext 失败", e);
        }
    }
}
