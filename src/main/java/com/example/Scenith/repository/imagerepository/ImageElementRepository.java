package com.example.Scenith.repository.imagerepository;

import com.example.Scenith.entity.imageentity.ImageElement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ImageElementRepository extends JpaRepository<ImageElement, Long> {
    List<ImageElement> findByIsActiveTrueOrderByDisplayOrderAscCreatedAtDesc();
    List<ImageElement> findByCategoryAndIsActiveTrueOrderByDisplayOrderAsc(String category);
    List<ImageElement> findByIsActiveTrueOrderByCreatedAtDesc();
}