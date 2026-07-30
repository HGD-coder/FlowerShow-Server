package com.github.hgdcoder.flowershow.persistence.mapper.social;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuthorAccountMapper {

    List<AuthorRow> findContentAuthors();

    AccountRow findByUserId(@Param("userId") String userId);

    int insertAccount(
            @Param("id") String id,
            @Param("userId") String userId,
            @Param("username") String username,
            @Param("passwordHash") String passwordHash
    );

    int updatePassword(
            @Param("accountId") String accountId,
            @Param("passwordHash") String passwordHash,
            @Param("updatedAt") Timestamp updatedAt
    );

    long countByUsername(@Param("username") String username);

    List<AccountResultRow> findAccountsForContentAuthors();

    record AuthorRow(String userId, String nickname) {
    }

    record AccountRow(String id, String username) {
    }

    record AccountResultRow(
            String id,
            String userId,
            String nickname,
            String username,
            String role,
            String status,
            boolean generated,
            Timestamp createdAt
    ) {
    }
}
