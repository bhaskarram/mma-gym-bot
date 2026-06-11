package com.mmagym.bot.repository;

import com.mmagym.bot.model.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByPhone(String phone);

    List<Member> findByStatus(Member.MemberStatus status);

    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.planExpiry BETWEEN :from AND :to")
    List<Member> findExpiringBetween(LocalDate from, LocalDate to);

    @Query("SELECT m FROM Member m WHERE m.paymentStatus != 'PAID' AND m.status = 'ACTIVE'")
    List<Member> findDefaulters();

    @Query("SELECT m FROM Member m WHERE LOWER(m.name) LIKE LOWER(CONCAT('%', :term, '%')) OR m.phone LIKE CONCAT('%', :term, '%')")
    List<Member> searchByNameOrPhone(String term);
}
