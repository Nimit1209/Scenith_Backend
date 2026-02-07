package com.example.Scenith.repository;


import com.example.Scenith.entity.AspectRatioMedia;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AspectRatioMediaRepository extends JpaRepository<AspectRatioMedia, Long> {
    List<AspectRatioMedia> findByUser(User user);
}