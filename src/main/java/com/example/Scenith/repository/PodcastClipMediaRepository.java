package com.example.Scenith.repository;

import com.example.Scenith.entity.PodcastClipMedia;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PodcastClipMediaRepository extends JpaRepository<PodcastClipMedia, Long> {
    List<PodcastClipMedia> findByUser(User user);
}