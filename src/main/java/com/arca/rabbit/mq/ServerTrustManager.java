package com.arca.rabbit.mq;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;

public class ServerTrustManager implements X509TrustManager {

    private X509Certificate[] certificates;

    @Override
    public void checkClientTrusted(X509Certificate[] xcs, String string) throws CertificateException {
    }

    @Override
    public void checkServerTrusted(X509Certificate[] xcs, String string) throws CertificateException {
        certificates = xcs;
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return certificates;
    }
}
