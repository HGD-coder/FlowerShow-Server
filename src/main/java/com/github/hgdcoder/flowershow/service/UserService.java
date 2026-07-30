package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.model.UserDto;
import com.github.hgdcoder.flowershow.persistence.mapper.social.UserMapper;
import com.github.hgdcoder.flowershow.persistence.mapper.social.UserMapper.UserRow;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public List<UserDto> findUsers() {
        return userMapper.findAll().stream()
                .map(UserService::toDto)
                .toList();
    }

    public UserDto findUser(String id) {
        return findUsers().stream()
                .filter(user -> user.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found: " + id));
    }

    static String toIso(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private static UserDto toDto(UserRow row) {
        return new UserDto(
                row.id(),
                row.nickname(),
                row.avatarUrl(),
                row.bio(),
                row.location(),
                row.source(),
                toIso(row.createdAt())
        );
    }
}
