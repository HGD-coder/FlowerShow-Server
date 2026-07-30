package com.github.hgdcoder.flowershow.model;

import java.util.List;

public record GenerateAuthorAccountsResult(
        int totalAuthors,
        int created,
        int existing,
        int passwordUpdated,
        String defaultPassword,
        List<AccountDto> accounts
) {
}
