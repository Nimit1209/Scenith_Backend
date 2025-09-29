package com.example.Scenith.repository;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.VideoSpeed;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VideoSpeedRepository extends JpaRepository<VideoSpeed, Long> {
    Optional<VideoSpeed> findByIdAndUser(Long id, User user);
    List<VideoSpeed> findByUser(User user);
}