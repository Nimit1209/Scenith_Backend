package com.example.Scenith.repository;

import com.example.Scenith.entity.StandaloneImage;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StandaloneImageRepository extends JpaRepository<StandaloneImage, Long> {
    List<StandaloneImage> findByUser(User user);
}