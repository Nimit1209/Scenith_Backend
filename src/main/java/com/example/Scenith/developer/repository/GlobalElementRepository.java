package com.example.Scenith.developer.repository;

import com.example.Scenith.developer.entity.GlobalElement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GlobalElementRepository extends JpaRepository<GlobalElement, Long> {
    @Query("SELECT e FROM GlobalElement e WHERE JSON_EXTRACT(e.globalElementJson, '$.imageFileName') = :imageFileName")
    Optional<GlobalElement> findByFileName(@Param("imageFileName") String imageFileName);
}