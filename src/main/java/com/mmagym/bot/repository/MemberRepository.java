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

    // Wildcards escaped so searching "%" or "_" doesn't match everything
    @Query("SELECT m FROM Member m WHERE LOWER(m.name) LIKE LOWER(CONCAT('%', REPLACE(REPLACE(:term,'%','\\%'),'_','\\_'), '%')) ESCAPE '\\' OR m.phone LIKE CONCAT('%', :term, '%')")
    List<Member> searchByNameOrPhone(String term);

    @Query("SELECT m FROM Member m WHERE m.status = 'ACTIVE' AND m.paymentStatus = 'PAID'")
    List<Member> findActivePaidMembers();

    @Query("SELECT m FROM Member m WHERE MONTH(m.dob) = :month AND DAY(m.dob) = :day AND m.status = 'ACTIVE'")
    List<Member> findByDobMonthAndDay(int month, int day);
}
