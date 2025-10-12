package com.example.Scenith.repository;


import com.example.Scenith.entity.SubtitleMedia;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubtitleMediaRepository extends JpaRepository<SubtitleMedia, Long> {
    List<SubtitleMedia> findByUser(User user);
}