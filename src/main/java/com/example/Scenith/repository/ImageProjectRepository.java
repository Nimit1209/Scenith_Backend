package com.example.Scenith.repository;


import com.example.Scenith.entity.ImageProject;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImageProjectRepository extends JpaRepository<ImageProject, Long> {
    List<ImageProject> findByUserOrderByUpdatedAtDesc(User user);
    Optional<ImageProject> findByIdAndUser(Long id, User user);
    List<ImageProject> findByUserAndStatus(User user, String status);
}