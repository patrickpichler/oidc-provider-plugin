package io.jenkins.plugins.oidc_provider;

import hudson.util.Secret;
import io.jenkins.plugins.oidc_provider.Keys.SupportedKeyAlgorithm;
import java.io.Serializable;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class SecretKeyPair implements Serializable {
    private static final long serialVersionUID = 2448941858110252020L;

    /**
     * Encrypted base64 encoding of a private key in {@link PKCS8EncodedKeySpec}
     */
    private final Secret privateKey;

    /**
     * Encrypted base64 encoding of a public key in {@link X509EncodedKeySpec}
     */
    private final Secret publicKey;
    private final SupportedKeyAlgorithm algorithm;

    private SecretKeyPair(SupportedKeyAlgorithm algorithm, byte[] privateKey, byte[] publicKey) {
        this.privateKey = Secret.fromString(Base64.getEncoder().encodeToString(privateKey));
        this.publicKey = Secret.fromString(Base64.getEncoder().encodeToString(publicKey));
        this.algorithm = algorithm;
    }

    public KeyPair toKeyPair() throws Exception {
        KeyFactory keyFactory;

        switch (algorithm.getType()) {
            case RSA:
                keyFactory = KeyFactory.getInstance("RSA");
                break;
            case ELLIPTIC_CURVE:
                keyFactory = KeyFactory.getInstance("EC");
                break;
            default:
                throw new RuntimeException("Cannot restore keypair from " + this.algorithm.name() + " algorithm");
        }

        PrivateKey priv = keyFactory.generatePrivate(
            new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey.getPlainText())));

        PublicKey pub = keyFactory.generatePublic(
            new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.getPlainText())));

        return new KeyPair(pub, priv);
    }

    public static SecretKeyPair fromKeyPair(SupportedKeyAlgorithm algorithm, KeyPair keyPair) {
        return new SecretKeyPair(algorithm, keyPair.getPrivate().getEncoded(), keyPair.getPublic().getEncoded());
    }

}
