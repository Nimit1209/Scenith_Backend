package com.example.Scenith.repository;

import com.example.Scenith.entity.ImageAsset;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImageAssetRepository extends JpaRepository<ImageAsset, Long> {
    List<ImageAsset> findByUserOrderByCreatedAtDesc(User user);
    List<ImageAsset> findByUserAndAssetType(User user, String assetType);
    Optional<ImageAsset> findByIdAndUser(Long id, User user);
}