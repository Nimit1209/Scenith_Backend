package com.example.Scenith.service;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.VideoFilterUpload;
import com.example.Scenith.repository.VideoFilterUploadRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VideoFilterUploadServiceImpl implements VideoFilterUploadService {

    private static final Logger logger = LoggerFactory.getLogger(VideoFilterUploadServiceImpl.class);

    private final VideoFilterUploadRepository repository;
    private final CloudflareR2Service cloudflareR2Service;

    @Override
    public VideoFilterUpload uploadVideo(MultipartFile file, User user) throws Exception {
        if (file == null || file.isEmpty()) {
            logger.error("MultipartFile is null or empty for user: {}", user.getId());
            throw new IllegalArgumentException("File is null or empty");
        }

        // Define R2 path
        String userR2Dir = String.format("videos/filtered/%d/original", user.getId());
        String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        String r2Path = userR2Dir + "/" + fileName;

        // Upload to Cloudflare R2
        cloudflareR2Service.uploadFile(file, r2Path);
        logger.info("Uploaded video to R2: {}", r2Path);

        // Generate CDN URL
        String cdnUrl = cloudflareR2Service.generateDownloadUrl(r2Path, 3600);

        // Build and save VideoFilterUpload entity
        VideoFilterUpload upload = VideoFilterUpload.builder()
                .fileName(fileName)
                .filePath(r2Path)
                .cdnUrl(cdnUrl)
                .user(user)
                .build();

        VideoFilterUpload savedUpload = repository.save(upload);
        logger.info("Saved video upload metadata for user: {}, videoId: {}", user.getId(), savedUpload.getId());
        return savedUpload;
    }

    @Override
    public List<VideoFilterUpload> getUserVideos(User user) {
        List<VideoFilterUpload> videos = repository.findByUserId(user.getId());
        for (VideoFilterUpload video : videos) {
            if (video.getCdnUrl() != null) {
                try {
                    String cdnUrl = cloudflareR2Service.generateDownloadUrl(video.getFilePath(), 3600);
                    video.setCdnUrl(cdnUrl);
                    repository.save(video);
                } catch (Exception e) {
                    logger.warn("Failed to refresh CDN URL for videoId: {}, path: {}, error: {}",
                            video.getId(), video.getFilePath(), e.getMessage());
                }
            }
        }
        return videos;
    }

    @Override
    public VideoFilterUpload getVideoById(Long videoId, User user) {
        VideoFilterUpload video = repository.findById(videoId)
                .filter(v -> v.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new RuntimeException("Video not found or not authorized"));

        if (video.getCdnUrl() != null) {
            try {
                String cdnUrl = cloudflareR2Service.generateDownloadUrl(video.getFilePath(), 3600);
                video.setCdnUrl(cdnUrl);
                repository.save(video);
            } catch (Exception e) {
                logger.warn("Failed to refresh CDN URL for videoId: {}, path: {}, error: {}",
                        videoId, video.getFilePath(), e.getMessage());
            }
        }
        return video;
    }
}