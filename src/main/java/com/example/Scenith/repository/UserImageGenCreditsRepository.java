// NEW FILE: src/main/java/com/example/Scenith/repository/imagerepository/UserImageGenCreditsRepository.java

package com.example.Scenith.repository;

import com.example.Scenith.entity.User;
import com.example.Scenith.entity.UserImageGenCredits;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserImageGenCreditsRepository extends JpaRepository<UserImageGenCredits, Long> {
    Optional<UserImageGenCredits> findByUserAndCreditMonth(User user, String creditMonth);
}