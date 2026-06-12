package com.mmagym.bot.repository;

import com.mmagym.bot.model.Attendance;
import com.mmagym.bot.model.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    Optional<Attendance> findByMemberAndClassDate(Member member, LocalDate classDate);

    boolean existsByMemberAndClassDate(Member member, LocalDate classDate);

    List<Attendance> findByMemberAndClassDateBetweenOrderByClassDateAsc(Member member, LocalDate from, LocalDate to);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.member = :member AND YEAR(a.classDate) = :year AND MONTH(a.classDate) = :month AND a.status = 'PRESENT'")
    long countPresentInMonth(Member member, int year, int month);

    @Query("SELECT a FROM Attendance a WHERE a.classDate = :date ORDER BY a.createdAt ASC")
    List<Attendance> findByClassDate(LocalDate date);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.classDate = :date AND a.status = 'PRESENT'")
    long countPresentOnDate(LocalDate date);

    @Query("""
        SELECT a FROM Attendance a
        WHERE a.member = :member
        ORDER BY a.classDate DESC
        """)
    List<Attendance> findAllByMemberOrderByDateDesc(Member member);

    @Query("SELECT COUNT(a) FROM Attendance a WHERE a.member = :member AND a.status = 'PRESENT'")
    long countAllPresentForMember(Member member);

    /** Bulk fetch — avoids N+1 queries in scheduler. Returns all present dates for a member in a range. */
    @Query("SELECT a.classDate FROM Attendance a WHERE a.member = :member AND a.classDate BETWEEN :from AND :to AND a.status = 'PRESENT'")
    List<LocalDate> findPresentDatesBetween(Member member, LocalDate from, LocalDate to);

    @Query(value = """
        SELECT m.*, COUNT(a.id) as cnt
        FROM attendance a
        JOIN members m ON a.member_id = m.id
        WHERE YEAR(a.class_date) = :year AND MONTH(a.class_date) = :month AND a.status = 'PRESENT'
        GROUP BY a.member_id
        ORDER BY cnt DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findTopAttendersForMonth(int year, int month, int limit);
}
