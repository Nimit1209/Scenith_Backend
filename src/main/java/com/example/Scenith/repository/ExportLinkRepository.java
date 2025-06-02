package com.example.Scenith.repository;
import com.example.Scenith.entity.ExportLink;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ExportLinkRepository extends JpaRepository<ExportLink, Long> {
    List<ExportLink> findByProjectId(Long projectId);
}