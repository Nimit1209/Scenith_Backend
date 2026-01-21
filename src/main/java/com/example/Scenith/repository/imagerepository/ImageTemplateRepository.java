package com.example.Scenith.repository.imagerepository;

import com.example.Scenith.entity.imageentity.ImageTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ImageTemplateRepository extends JpaRepository<ImageTemplate, Long> {

    List<ImageTemplate> findByStatusOrderByDisplayOrderAscCreatedAtDesc(String status);

    List<ImageTemplate> findByCategoryAndStatusOrderByDisplayOrderAsc(String category, String status);
}