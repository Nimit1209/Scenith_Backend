package com.example.Scenith.repository.imagerepository;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.imageentity.SoleImageGen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SoleImageGenRepository extends JpaRepository<SoleImageGen, Long> {
    List<SoleImageGen> findByUserOrderByCreatedAtDesc(User user);
}