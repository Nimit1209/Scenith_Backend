package com.example.Scenith.repository;

import com.example.Scenith.entity.CompressedMedia;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompressedMediaRepository extends JpaRepository<CompressedMedia, Long> {
    List<CompressedMedia> findByUser(User user);
}