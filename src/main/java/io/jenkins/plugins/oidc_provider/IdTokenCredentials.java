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

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.Util;
import hudson.model.Run;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;
import hudson.util.Secret;
import io.jenkins.plugins.oidc_provider.Keys.SecretKeyPair;
import io.jenkins.plugins.oidc_provider.Keys.SupportedKeyAlgorithm;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public abstract class IdTokenCredentials extends BaseStandardCredentials {

    private static final long serialVersionUID = 1;

    /**
     * Public/private RSA keypair.
     * {@link #privateKey} is the persistent form.
     */
    private transient KeyPair kp;

    /**
     * Encrypted {@link Base64} encoding of private key in {@link RSAPrivateCrtKey} / {@link PKCS8EncodedKeySpec}
     * format.
     * The public key is inferred from this to reload {@link #kp}.
     * <br/>
     * The field has been replaced by {@link #secretKeyPair} and only stays around for
     * compatibility reasons.
     */
    @Deprecated private transient Secret privateKey;

    private final SecretKeyPair secretKeyPair;

    private @CheckForNull String issuer;

    private @CheckForNull String audience;

    private transient @CheckForNull Run<?, ?> build;

    private @CheckForNull SupportedKeyAlgorithm algorithm;

    protected IdTokenCredentials(CredentialsScope scope, String id, String description,
        SupportedKeyAlgorithm algorithm) {
        this(scope, id, description, algorithm.generateKeyPair(), algorithm);
    }

    private IdTokenCredentials(CredentialsScope scope, String id, String description, KeyPair kp,
        SupportedKeyAlgorithm algorithm) {
        this(scope, id, description, kp, algorithm, Keys.SecretKeyPair.fromKeyPair(algorithm, kp));
    }

    protected IdTokenCredentials(CredentialsScope scope, String id, String description, KeyPair kp,
        SupportedKeyAlgorithm algorithm, SecretKeyPair secretKeyPair) {
        super(scope, id, description);
        this.kp = kp;
        this.algorithm = algorithm;
        this.secretKeyPair = secretKeyPair;
    }

    protected Object readResolve() throws Exception {
        // the private key should only be set for old versions of the credentials
        // back then, only RSA256 ws supported and this block is handling the conversion
        if (privateKey != null) {
            KeyFactory kf = KeyFactory.getInstance("RSA");

            RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey.getPlainText())));
            kp = new KeyPair(kf.generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent())),
                priv);
            algorithm = SupportedKeyAlgorithm.RS256;

            return this;
        }

        kp = secretKeyPair.toKeyPair();

        return this;
    }

    public final String getIssuer() {
        return issuer;
    }

    @DataBoundSetter public final void setIssuer(String issuer) {
        this.issuer = Util.fixEmpty(issuer);
    }

    public final String getAudience() {
        return audience;
    }

    @DataBoundSetter public final void setAudience(String audience) {
        this.audience = Util.fixEmpty(audience);
    }

    @DataBoundSetter public void setAlgorithm(SupportedKeyAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public SupportedKeyAlgorithm getAlgorithm() {
        return algorithm;
    }

    protected abstract IdTokenCredentials clone(KeyPair kp, SupportedKeyAlgorithm algorithm, SecretKeyPair secretKeyPair);

    @Override public final Credentials forRun(Run<?, ?> context) {
        IdTokenCredentials clone = clone(kp, algorithm, secretKeyPair);
        clone.issuer = issuer;
        clone.audience = audience;
        clone.build = context;
        return clone;
    }

    PublicKey publicKey() {
        return kp.getPublic();
    }

    protected final @NonNull String token() {
        JwtBuilder builder = Jwts.builder().
            setHeaderParam("kid", getId()).
            setIssuer(issuer != null ? issuer : findIssuer().url()).
            setAudience(audience).
            setExpiration(Date.from(new Date().toInstant().plus(1, ChronoUnit.HOURS))).
            setIssuedAt(new Date());
        if (build != null) {
            builder.setSubject(build.getParent().getAbsoluteUrl()).
                claim("build_number", build.getNumber());
        } else {
            builder.setSubject(Jenkins.get().getRootUrl());
        }
        return builder.
            signWith(kp.getPrivate()).
            compact();
    }

    protected @NonNull Issuer findIssuer() {
        Run<?, ?> context = build;
        if (context == null) {
            return ExtensionList.lookupSingleton(RootIssuer.class);
        } else {
            for (Issuer.Factory f : ExtensionList.lookup(Issuer.Factory.class)) {
                for (Issuer i : f.forContext(context)) {
                    if (i.credentials().contains(this)) {
                        return i;
                    }
                }
            }
        }
        throw new IllegalStateException("Could not find issuer corresponding to " + getId() + " for " + context.getExternalizableId());
    }

    protected static abstract class IdTokenCredentialsDescriptor extends BaseStandardCredentialsDescriptor {

        private static @CheckForNull Issuer issuerFromRequest(@NonNull StaplerRequest req) {
            Issuer i = ExtensionList.lookup(Issuer.Factory.class).stream().map(f -> f.forConfig(req)).filter(Objects::nonNull).findFirst().orElse(null);
            if (i != null) {
                i.checkExtendedReadPermission();
            }
            return i;
        }

        public final FormValidation doCheckIssuer(StaplerRequest req, @QueryParameter String id, @QueryParameter String issuer) {
            Issuer i = issuerFromRequest(req);
            if (Util.fixEmpty(issuer) == null) {
                if (i != null) {
                    return FormValidation.okWithMarkup("Issuer URI: <code>" + Util.escape(i.url()) + "</code>");
                } else {
                    return FormValidation.warning("Unable to determine the issuer URI");
                }
            } else {
                try {
                    URI u = new URI(issuer);
                    if (!"https".equals(u.getScheme())) {
                        return FormValidation.errorWithMarkup("Issuer URIs should use <code>https</code> scheme");
                    }
                    if (u.getQuery() != null) {
                        return FormValidation.error("Issuer URIs must not have a query component");
                    }
                    if (u.getFragment() != null) {
                        return FormValidation.error("Issuer URIs must not have a fragment component");
                    }
                    if (u.getPath() != null && u.getPath().endsWith("/")) {
                        return FormValidation.errorWithMarkup("Issuer URIs should not end with a slash (<code>/</code>) in this context");
                    }
                } catch (URISyntaxException x) {
                    return FormValidation.error("Not a well-formed URI");
                }
                if (i != null) {
                    IdTokenCredentials c = i.credentials().stream().filter(creds -> creds.getId().equals(id) && issuer.equals(creds.getIssuer())).findFirst().orElse(null);
                    if (c != null) {
                        String base = req.getRequestURI().replaceFirst("/checkIssuer$", "");
                        return FormValidation.okWithMarkup(
                            "Serve <code>" + Util.xmlEscape(issuer) + Keys.WELL_KNOWN_OPENID_CONFIGURATION +
                            "</code> with <a href=\"" + base + "/wellKnownOpenidConfiguration?issuer=" + Util.escape(issuer) +
                            "\" target=\"_blank\" rel=\"noopener noreferrer\">this content</a> and <code>" +
                            Util.xmlEscape(issuer) + Keys.JWKS + "</code> with <a href=\"" +
                            base + "/jwks?id=" + Util.escape(id) + "&issuer=" + Util.escape(issuer) +
                            "\" target=\"_blank\" rel=\"noopener noreferrer\">this content</a> (both as <code>application/json</code>)." +
                            "<br>Note that the JWKS document will need to be updated if you resave these credentials.");
                    } else {
                        return FormValidation.ok("Save these credentials, then return to this screen for instructions");
                    }
                } else {
                    return FormValidation.warning("Unable to determine where these credentials are being saved");
                }
            }
        }

        public JSONObject doWellKnownOpenidConfiguration(@QueryParameter String issuer) {
            return Keys.openidConfiguration(issuer);
        }

        public JSONObject doJwks(StaplerRequest req, @QueryParameter String id, @QueryParameter String issuer) {
            Issuer i = issuerFromRequest(req);
            if (i == null) {
                throw HttpResponses.notFound();
            }
            IdTokenCredentials c = i.credentials().stream().filter(creds -> creds.getId().equals(id) && issuer.equals(creds.getIssuer())).findFirst().orElse(null);
            if (c == null) {
                throw HttpResponses.notFound();
            }
            return new JSONObject().accumulate("keys", new JSONArray().element(Keys.key(c)));
        }

        public ListBoxModel doFillAlgorithmItems() {
            return new ListBoxModel(
                Arrays.stream(SignatureAlgorithm.values())
                    .map(a -> new Option(a.name()))
                    .toArray(Option[]::new)
            );
        }
    }

}
