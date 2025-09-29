package com.example.Scenith.repository;

import com.example.Scenith.entity.VideoFilterJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VideoFilterJobRepository extends JpaRepository<VideoFilterJob, Long> {
    Optional<VideoFilterJob> findByUploadedVideoIdAndStatus(Long uploadedVideoId, VideoFilterJob.ProcessingStatus status);

    List<VideoFilterJob> findByUserId(Long userId);

    @Query("SELECT j FROM VideoFilterJob j JOIN FETCH j.uploadedVideo JOIN FETCH j.user WHERE j.id = :id")
    Optional<VideoFilterJob> findByIdWithUploadedVideo(Long id);
}