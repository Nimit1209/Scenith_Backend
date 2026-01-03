package com.example.Scenith.repository;

import com.example.Scenith.entity.DocumentUpload;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DocumentUploadRepository extends JpaRepository<DocumentUpload, Long> {
    List<DocumentUpload> findByUserOrderByCreatedAtDesc(User user);
    List<DocumentUpload> findByUserAndFileType(User user, String fileType);
    List<DocumentUpload> findByCreatedAtBefore(LocalDateTime dateTime);
    long countByUserAndCreatedAtAfter(User user, LocalDateTime dateTime);
}