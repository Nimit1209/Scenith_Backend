package com.example.Scenith.service;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.VideoFilterUpload;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface VideoFilterUploadService {
    VideoFilterUpload uploadVideo(MultipartFile file, User user) throws Exception;
    List<VideoFilterUpload> getUserVideos(User user);
    VideoFilterUpload getVideoById(Long videoId, User user);
}