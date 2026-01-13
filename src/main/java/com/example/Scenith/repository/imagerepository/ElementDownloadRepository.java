package com.example.Scenith.repository.imagerepository;

import com.example.Scenith.entity.imageentity.ElementDownload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ElementDownloadRepository extends JpaRepository<ElementDownload, Long> {
    
    @Query("SELECT COUNT(e) FROM ElementDownload e WHERE e.elementId = :elementId")
    Long countDownloadsByElementId(Long elementId);
    
    @Query("SELECT COUNT(e) FROM ElementDownload e WHERE e.elementId = :elementId AND e.downloadFormat = :format")
    Long countDownloadsByElementIdAndFormat(Long elementId, String format);
}