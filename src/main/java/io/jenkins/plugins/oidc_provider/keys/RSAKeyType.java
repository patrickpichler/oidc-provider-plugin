package io.jenkins.plugins.oidc_provider.keys;

import hudson.Extension;
import hudson.model.Descriptor;
import io.jenkins.plugins.oidc_provider.IdTokenCredentials.KeyTypeDescriptor;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

public class RSAKeyType extends KeyType {
    private final int keySize;

    @DataBoundConstructor
    public RSAKeyType(int keySize) {
        super("RSA");
        this.keySize = keySize;
    }

    @Extension
    public static final class DescriptorImpl extends KeyTypeDescriptor {}
}
