package com.example.Scenith.repository;


import com.example.Scenith.entity.SoleTTS;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SoleTTSRepository extends JpaRepository<SoleTTS, Long> {
    List<SoleTTS> findByUser(User user);
}