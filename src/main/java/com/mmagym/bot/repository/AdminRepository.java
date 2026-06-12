package com.mmagym.bot.repository;

import com.mmagym.bot.model.Admin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdminRepository extends JpaRepository<Admin, Long> {

    Optional<Admin> findByPhoneAndActiveTrue(String phone);

    List<Admin> findAllByActiveTrue();

    boolean existsByPhoneAndActiveTrue(String phone);
}
