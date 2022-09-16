/*
 * The MIT License
 *
 * Copyright 2022 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.plugins.oidc_provider;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.InvisibleAction;
import hudson.model.UnprotectedRootAction;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.Secret;
import io.jsonwebtoken.SignatureAlgorithm;
import java.io.Serializable;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.logging.Logger;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Serves OIDC definition and JWKS.
 * @see <a href="https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfig">Obtaining OpenID Provider Configuration Information</a>
 * @see <a href="https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderMetadata">OpenID Provider Metadata</a>
 */
@Extension public final class Keys extends InvisibleAction implements UnprotectedRootAction {

    private static final Logger LOGGER = Logger.getLogger(Keys.class.getName());

    static final String URL_NAME = "oidc";
    static final String WELL_KNOWN_OPENID_CONFIGURATION = "/.well-known/openid-configuration";
    static final String JWKS = "/jwks";

    @Override public String getUrlName() {
        return URL_NAME;
    }

    public JSONObject doDynamic(StaplerRequest req) {
        String path = req.getRestOfPath();
        try (ACLContext context = ACL.as2(ACL.SYSTEM2)) { // both forUri and credentials might check permissions
            Issuer i = findIssuer(path, WELL_KNOWN_OPENID_CONFIGURATION);
            if (i != null) {
                return openidConfiguration(i.url());
            } else {
                i = findIssuer(path, JWKS);
                if (i != null) {
                    // pending https://github.com/jwtk/jjwt/issues/236
                    // compare https://github.com/jenkinsci/blueocean-plugin/blob/1f92e1624287e7588fc89aa5ce4e4147dd00f3d7/blueocean-jwt/src/main/java/io/jenkins/blueocean/auth/jwt/SigningPublicKey.java#L45-L52
                    JSONArray keys = new JSONArray();
                    for (IdTokenCredentials creds : i.credentials()) {
                        if (creds.getIssuer() != null) {
                            LOGGER.fine(() -> "declining to serve key for " + creds.getId() + " since it would be served from " + creds.getIssuer());
                            continue;
                        }
                        keys.element(key(creds));
                    }
                    return new JSONObject().accumulate("keys", keys);
                }
            }
            throw HttpResponses.notFound();
        }
    }

    static JSONObject openidConfiguration(String issuer) {
        // TODO search for matching IdTokenCredential to display correct signing alg values
        return new JSONObject().
            accumulate("issuer", issuer).
            accumulate("jwks_uri", issuer + JWKS).
            accumulate("response_types_supported", new JSONArray().element("code")).
            accumulate("subject_types_supported", new JSONArray().element("public")).
            accumulate("id_token_signing_alg_values_supported", new JSONArray().element("RS256")).
            accumulate("authorization_endpoint", "https://unimplemented").
            accumulate("token_endpoint", "https://unimplemented");
    }

    static JSONObject key(IdTokenCredentials creds) {
        SupportedKeyAlgorithm algorithm = creds.getAlgorithm();

        if (algorithm.isRsa()) {
            return encodeRsaJsonWebKey(algorithm, creds.getId(), (RSAPublicKey) creds.publicKey());
        }

        if (algorithm.isEllipticCurve()) {
            return encodeECJsonWebKey(algorithm, creds.getId(), (ECPublicKey) creds.publicKey());
        }

        throw new IllegalArgumentException("Cannot encode creds with algorithm " + algorithm.name());
    }

    /**
     * @param path e.g. {@code /path/subpath/jwks}
     * @param suffix e.g. {@code /jwks}
     */
    private static @CheckForNull Issuer findIssuer(String path, String suffix) {
        if (path.endsWith(suffix)) {
            String uri = path.substring(0, path.length() - suffix.length()); // e.g. "" or "/path/subpath"
            LOGGER.fine(() -> "looking up issuer for " + uri);
            for (Issuer.Factory f : ExtensionList.lookup(Issuer.Factory.class)) {
                Issuer i = f.forUri(uri);
                if (i != null) {
                    if (!i.uri().equals(uri)) {
                        LOGGER.warning(() -> i + " was expected to have URI " + uri);
                        return null;
                    }
                    if (i.credentials().stream().noneMatch(c -> c.getIssuer() == null)) {
                        LOGGER.fine(() -> "found " + i
                            + " but has no credentials with default issuer; not advertising existence of a folder");
                        return null;
                    }
                    LOGGER.fine(() -> "found " + i);
                    return i;
                }
            }
        }
        return null;
    }

    public static JSONObject encodeECJsonWebKey(SupportedKeyAlgorithm algorithm, String keyId, ECPublicKey publicKey) {
        Encoder encoder = Base64.getEncoder();

        String x = encoder.encodeToString(publicKey.getW().getAffineX().toByteArray());
        String y = encoder.encodeToString(publicKey.getW().getAffineY().toByteArray());

        return new JSONObject()
            .accumulate("alg", algorithm.name())
            .accumulate("kty", "EC")
            .accumulate("use", "sig")
            .accumulate("kid", keyId)
            .accumulate("crv", algorithm.curve)
            .accumulate("x", x)
            .accumulate("y", y);
    }

    public static JSONObject encodeRsaJsonWebKey(SupportedKeyAlgorithm algorithm, String keyId, RSAPublicKey key) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return new JSONObject().
            accumulate("kid", keyId).
            accumulate("kty", "RSA").
            accumulate("alg", algorithm.name()).
            accumulate("use", "sig").
            accumulate("n", encoder.encodeToString(key.getModulus().toByteArray())).
            accumulate("e", encoder.encodeToString(key.getPublicExponent().toByteArray()));
    }

    public enum SupportedKeyAlgorithm {
        ES256(SignatureAlgorithm.ES256, "P-256"),
        ES384(SignatureAlgorithm.ES384, "P-384"),
        ES512(SignatureAlgorithm.ES512, "P-521"),
        RS256(SignatureAlgorithm.RS256),
        RS384(SignatureAlgorithm.RS384),
        RS512(SignatureAlgorithm.RS512);

        private final SignatureAlgorithm algorithm;
        private final String curve;

        SupportedKeyAlgorithm(SignatureAlgorithm algorithm) {
            this(algorithm, null);
        }

        SupportedKeyAlgorithm(SignatureAlgorithm algorithm, String curve) {
            this.algorithm = algorithm;
            this.curve = curve;
        }

        public KeyPair generateKeyPair() {
            return io.jsonwebtoken.security.Keys.keyPairFor(algorithm);
        }

        public boolean isRsa() {
            return algorithm.isRsa();
        }

        public boolean isEllipticCurve() {
            return algorithm.isEllipticCurve();
        }
    }

    public static class SecretKeyPair implements Serializable {
        private static final long serialVersionUID = 2448941858110252020L;
        private final Secret privateKey;
        private final Secret publicKey;
        private final SupportedKeyAlgorithm algorithm;

        private SecretKeyPair(SupportedKeyAlgorithm algorithm, byte[] privateKey, byte[] publicKey) {
            this.privateKey = Secret.fromString(Base64.getEncoder().encodeToString(privateKey));
            this.publicKey = Secret.fromString(Base64.getEncoder().encodeToString(publicKey));
            this.algorithm = algorithm;
        }

        public KeyPair toKeyPair() throws Exception {
            if (this.algorithm.isRsa()) {
                KeyFactory kf = KeyFactory.getInstance("RSA");

                RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey.getPlainText())));
                return new KeyPair(kf.generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent())),
                    priv);
            } else if (this.algorithm.isEllipticCurve()){
                KeyFactory kf = KeyFactory.getInstance("EC");

                PrivateKey priv = kf.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey.getPlainText())));

                PublicKey pub = kf.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.getPlainText())));

                return new KeyPair(pub, priv);
            }

            throw new RuntimeException("Cannot restore keypair from " + this.algorithm.name() + " algorithm");
        }

        public static SecretKeyPair fromKeyPair(SupportedKeyAlgorithm algorithm, KeyPair keyPair) {
            return new SecretKeyPair(algorithm, keyPair.getPrivate().getEncoded(), keyPair.getPublic().getEncoded());
        }

    }
}
