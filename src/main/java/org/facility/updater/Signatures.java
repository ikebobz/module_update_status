package org.facility.updater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA-3072 / SHA256withRSA signing of release manifests (works on Java 8+).
 * The private key stays with the release team; facilities only hold the public key.
 */
public final class Signatures {

    private Signatures() {}

    public static void generateKeyPair(Path privateOut, Path publicOut) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(3072);
        KeyPair kp = g.generateKeyPair();
        writePem(privateOut, "PRIVATE KEY", kp.getPrivate().getEncoded());
        writePem(publicOut, "PUBLIC KEY", kp.getPublic().getEncoded());
    }

    public static byte[] sign(Path privateKeyFile, byte[] data) throws Exception {
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(readPem(privateKeyFile)));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key);
        s.update(data);
        return Base64.getEncoder().encode(s.sign());
    }

    /** @param signatureB64 base64 signature as stored in manifest.json.sig */
    public static boolean verify(PublicKey key, byte[] data, byte[] signatureB64) {
        try {
            String sig = new String(signatureB64, StandardCharsets.US_ASCII).replaceAll("\\s", "");
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(key);
            s.update(data);
            return s.verify(Base64.getDecoder().decode(sig));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public static PublicKey loadPublicKey(Path file) throws Exception {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(readPem(file)));
    }

    public static PublicKey parsePublicKey(String base64OrPem) throws Exception {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decodePem(base64OrPem)));
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            try (java.io.InputStream in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) b.append(String.format("%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writePem(Path file, String type, byte[] der) throws IOException {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        String pem = "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
        Files.write(file, pem.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] readPem(Path file) throws IOException {
        return decodePem(new String(Files.readAllBytes(file), StandardCharsets.US_ASCII));
    }

    private static byte[] decodePem(String pem) {
        String b64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(b64);
    }
}
