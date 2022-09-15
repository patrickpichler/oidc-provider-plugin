package io.jenkins.plugins.oidc_provider.keys;

import hudson.ExtensionPoint;
import hudson.model.Describable;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;

public abstract class KeyType implements ExtensionPoint, Describable<KeyType> {
    protected String name;

    public KeyType(String name) {
        this.name = name;
    }

    @Override
    public Descriptor<KeyType> getDescriptor() {
        return Jenkins.get().getDescriptor(getClass());
    }

}
