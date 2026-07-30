package com.github.hgdcoder.flowershow.model;

public record GenerateAuthorAccountsRequest(
        String defaultPassword,
        Boolean overwriteExistingPassword
) {
    public String effectivePassword() {
        return defaultPassword == null || defaultPassword.isBlank() ? "Flower@123456" : defaultPassword;
    }

    public boolean shouldOverwriteExistingPassword() {
        return overwriteExistingPassword != null && overwriteExistingPassword;
    }
}
