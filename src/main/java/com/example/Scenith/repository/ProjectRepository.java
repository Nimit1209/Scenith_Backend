package com.example.Scenith.repository;
import com.example.Scenith.entity.Project;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByUserOrderByLastModifiedDesc(User user);
    Project findByIdAndUser(Long id, User user);
    Optional<Project> findByEditSession(String editSession); // New method
    // Add this if you prefer using findByUserId
    List<Project> findByUserId(Long userId);
    List<Project> findByUser(User user);
}