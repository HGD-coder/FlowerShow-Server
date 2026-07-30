package com.github.hgdcoder.flowershow.controller;

import com.github.hgdcoder.flowershow.model.AccountDto;
import com.github.hgdcoder.flowershow.model.GenerateAuthorAccountsRequest;
import com.github.hgdcoder.flowershow.model.GenerateAuthorAccountsResult;
import com.github.hgdcoder.flowershow.service.AuthorAccountService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AuthorAccountService accountService;

    public AccountController(AuthorAccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/authors")
    public List<AccountDto> authorAccounts() {
        return accountService.findAccountsForVideoAuthors();
    }

    @PostMapping("/authors/generate")
    public GenerateAuthorAccountsResult generateAuthorAccounts(@RequestBody(required = false) GenerateAuthorAccountsRequest request) {
        return accountService.generateForVideoAuthors(request);
    }
}
