package com.github.hgdcoder.flowershow.model;

public record GenerateAuthorAccountsRequest(
        String defaultPassword,
        Boolean overwriteExistingPassword
) {
    public String effectivePassword() {
        if (defaultPassword == null || defaultPassword.isBlank()) {
            return null;
        }
        return defaultPassword.trim();
    }

    public boolean shouldOverwriteExistingPassword() {
        return overwriteExistingPassword != null && overwriteExistingPassword;
    }
}
