package com.example.Scenith.repository;

import com.example.Scenith.entity.VideoFilterUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VideoFilterUploadRepository extends JpaRepository<VideoFilterUpload, Long> {
    List<VideoFilterUpload> findByUserId(Long userId);
}