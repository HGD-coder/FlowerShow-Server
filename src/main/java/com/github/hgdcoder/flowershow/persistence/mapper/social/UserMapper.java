package com.github.hgdcoder.flowershow.persistence.mapper.social;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {

    List<UserRow> findAll();

    record UserRow(
            String id,
            String nickname,
            String avatarUrl,
            String bio,
            String location,
            String source,
            Timestamp createdAt
    ) {
    }
}
