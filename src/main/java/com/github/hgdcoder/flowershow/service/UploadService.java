package com.github.hgdcoder.flowershow.service;

import com.github.hgdcoder.flowershow.model.UploadResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class UploadService {

    private final Path uploadRoot;

    public UploadService(@Value("${flower-show.upload.root:uploads}") String uploadRoot) {
        this.uploadRoot = Paths.get(uploadRoot).toAbsolutePath().normalize();
    }

    public UploadResponse store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "File is required.");
        }

        try {
            Files.createDirectories(uploadRoot);
            String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
            String extension = extensionOf(original);
            String fileName = "upl_" + UUID.randomUUID().toString().replace("-", "") + extension;
            Path target = uploadRoot.resolve(fileName).normalize();
            if (!target.startsWith(uploadRoot)) {
                throw new ResponseStatusException(BAD_REQUEST, "Invalid upload path.");
            }
            file.transferTo(target);
            return new UploadResponse(
                    fileName,
                    "/uploads/" + fileName,
                    fileName,
                    file.getContentType(),
                    file.getSize()
            );
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Cannot store uploaded file.", e);
        }
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(dot).toLowerCase();
        return ext.length() > 16 ? "" : ext;
    }
}