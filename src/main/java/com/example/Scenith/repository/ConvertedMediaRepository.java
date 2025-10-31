package com.example.Scenith.repository;


import com.example.Scenith.entity.ConvertedMedia;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConvertedMediaRepository extends JpaRepository<ConvertedMedia, Long> {
    List<ConvertedMedia> findByUser(User user);
}