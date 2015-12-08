package net.i2p.crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;

/**
 *  Java X.509 certificate utilities, consolidated from various places.
 *
 *  @since 0.9.9
 */
public class CertUtil {
        
    private static final int LINE_LENGTH = 64;

    /**
     *  Modified from:
     *  http://www.exampledepot.com/egs/java.security.cert/ExportCert.html
     *
     *  This method writes a certificate to a file in base64 format.
     *
     *  @return success
     *  @since 0.8.2, moved from SSLEepGet in 0.9.9
     */
    public static boolean saveCert(Certificate cert, File file) {
        OutputStream os = null;
        PrintWriter wr = null;
        try {
           // Get the encoded form which is suitable for exporting
           byte[] buf = cert.getEncoded();
           os = new SecureFileOutputStream(file);
           wr = new PrintWriter(new OutputStreamWriter(os, "UTF-8"));
           wr.println("-----BEGIN CERTIFICATE-----");
           String b64 = Base64.encode(buf, true);     // true = use standard alphabet
           for (int i = 0; i < b64.length(); i += LINE_LENGTH) {
               wr.println(b64.substring(i, Math.min(i + LINE_LENGTH, b64.length())));
           }
           wr.println("-----END CERTIFICATE-----");
           wr.flush();
           if (wr.checkError())
               throw new IOException("Failed write to " + file);
           return true;
        } catch (CertificateEncodingException cee) {
            error("Error writing X509 Certificate " + file.getAbsolutePath(), cee);
           return false;
        } catch (IOException ioe) {
            error("Error writing X509 Certificate " + file.getAbsolutePath(), ioe);
           return false;
        } finally {
            try { if (os != null) os.close(); } catch (IOException foo) {}
        }
    }

    /**
     *  Get a value out of the subject distinguished name.
     *
     *  Warning - unsupported in Android (no javax.naming), returns null.
     *
     *  @param type e.g. "CN"
     *  @return value or null if not found
     */
    public static String getSubjectValue(X509Certificate cert, String type) {
        X500Principal p = cert.getSubjectX500Principal();
        return getValue(p, type);
    }

    /**
     *  Get a value out of the issuer distinguished name.
     *
     *  Warning - unsupported in Android (no javax.naming), returns null.
     *
     *  @param type e.g. "CN"
     *  @return value or null if not found
     *  @since 0.9.24
     */
    public static String getIssuerValue(X509Certificate cert, String type) {
        X500Principal p = cert.getIssuerX500Principal();
        return getValue(p, type);
    }

    /**
     *  Get a value out of a X500Principal.
     *
     *  Warning - unsupported in Android (no javax.naming), returns null.
     *
     *  @param type e.g. "CN"
     *  @return value or null if not found
     */
    private static String getValue(X500Principal p, String type) {
        if (SystemVersion.isAndroid()) {
            error("Don't call this in Android", new UnsupportedOperationException("I did it"));
            return null;
        }
        if (p == null)
            return null;
        type = type.toUpperCase(Locale.US);
        String subj = p.getName();
        try {
            LdapName name = new LdapName(subj);
            for (Rdn rdn : name.getRdns()) {
                if (type.equals(rdn.getType().toUpperCase(Locale.US)))
                    return (String) rdn.getValue();
            }
        } catch (InvalidNameException ine) {}
        return null;
    }

    private static void error(String msg, Throwable t) {
        log(I2PAppContext.getGlobalContext(), Log.ERROR, msg, t);
    }

    //private static void error(I2PAppContext ctx, String msg, Throwable t) {
    //    log(ctx, Log.ERROR, msg, t);
    //}

    private static void log(I2PAppContext ctx, int level, String msg, Throwable t) {
        Log l = ctx.logManager().getLog(CertUtil.class);
        l.log(level, msg, t);
    }

    /**
     *  Get the Java public key from a X.509 certificate file.
     *  Throws if the certificate is invalid (e.g. expired).
     *
     *  @return non-null, throws on all errors including certificate invalid
     *  @since 0.9.24 moved from SU3File private method
     */
    public static PublicKey loadKey(File kd) throws IOException, GeneralSecurityException {
        return loadCert(kd).getPublicKey();
    }

    /**
     *  Get the certificate from a X.509 certificate file.
     *  Throws if the certificate is invalid (e.g. expired).
     *
     *  @return non-null, throws on all errors including certificate invalid
     *  @since 0.9.24 adapted from SU3File private method
     */
    public static X509Certificate loadCert(File kd) throws IOException, GeneralSecurityException {
        InputStream fis = null;
        try {
            fis = new FileInputStream(kd);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate)cf.generateCertificate(fis);
            cert.checkValidity();
            return cert;
        } catch (IllegalArgumentException iae) {
            // java 1.8.0_40-b10, openSUSE
            // Exception in thread "main" java.lang.IllegalArgumentException: Input byte array has wrong 4-byte ending unit
            // at java.util.Base64$Decoder.decode0(Base64.java:704)
            throw new GeneralSecurityException("cert error", iae);
        } finally {
            try { if (fis != null) fis.close(); } catch (IOException foo) {}
        }
    }
}
