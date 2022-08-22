package com.arca.rabbit.mq;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

public class ClientSSL {

    private String certificate;
    private String password;

    public ClientSSL(String certificate, String password) {
        this.certificate = certificate;
        this.password = password;
    }

    public ClientSSL() {
    }

//    public Client newClient() {
//        try {
//            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
//            KeyStore keyStore = KeyStore.getInstance("PKCS12");
//            try (InputStream keyInput = new FileInputStream(
//                    new File(System.getProperty("jboss.server.config.dir") + File.separator
//                            + "ng" + File.separator + "signature" + File.separator + certificate))) {
//                keyStore.load(keyInput, password.toCharArray());
//            }
//            kmf.init(keyStore, password.toCharArray());
//            SSLContext context = SSLContext.getInstance("TLS");
//            ServerTrustManager trustManager = new ServerTrustManager();
//            context.init(kmf.getKeyManagers(), new TrustManager[]{trustManager}, new SecureRandom());
//            return ClientBuilder.newBuilder().sslContext(context).build();
//        } catch (IOException | KeyManagementException | KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException | CertificateException ex) {
//            Logger.getLogger(ClientSSL.class.getName()).log(Level.SEVERE, null, ex);
//        }
//        return null;
//    }
    public SSLContext getSSLContext(String type) throws IOException, KeyManagementException, KeyStoreException,
            NoSuchAlgorithmException, UnrecoverableKeyException, CertificateException {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream keyInput = new FileInputStream(new File(certificate))) {
            keyStore.load(keyInput, password.toCharArray());
        }
        kmf.init(keyStore, password.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        if (type != null && type.equals("rabbitmq")) {
            context = SSLContext.getInstance("TLSv1.2");
        }
        ServerTrustManager trustManager = new ServerTrustManager();
        context.init(kmf.getKeyManagers(), new TrustManager[]{trustManager}, new SecureRandom());
        
        return context;
    }

    public String getCertificate() {
        return certificate;
    }

    public void setCertificate(String certificate) {
        this.certificate = certificate;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
