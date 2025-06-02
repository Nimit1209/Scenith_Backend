package com.example.Scenith.repository;

import com.example.Scenith.entity.Element;
import com.example.Scenith.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ElementRepository extends JpaRepository<Element, String> {
    List<Element> findByUserEmail(String email);

    @Query("SELECT e FROM Element e WHERE e.fileName = :fileName AND e.user = :user")
    Optional<Element> findByFileNameAndUser(@Param("fileName") String fileName, @Param("user") User user);
}