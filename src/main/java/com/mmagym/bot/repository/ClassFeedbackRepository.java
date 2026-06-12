package com.mmagym.bot.repository;

import com.mmagym.bot.model.ClassFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ClassFeedbackRepository extends JpaRepository<ClassFeedback, Long> {

    @Query("""
        SELECT f.classType, AVG(f.rating), COUNT(f)
        FROM ClassFeedback f
        WHERE YEAR(f.classDate) = :year AND MONTH(f.classDate) = :month
        GROUP BY f.classType
        ORDER BY f.classType
        """)
    List<Object[]> avgRatingByClassTypeForMonth(int year, int month);

    @Query("SELECT COUNT(f) FROM ClassFeedback f WHERE YEAR(f.classDate) = :year AND MONTH(f.classDate) = :month")
    long countByMonth(int year, int month);
}
