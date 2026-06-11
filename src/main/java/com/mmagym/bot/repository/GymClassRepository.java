package com.mmagym.bot.repository;

import com.mmagym.bot.model.GymClass;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GymClassRepository extends JpaRepository<GymClass, Long> {

    List<GymClass> findByIsActiveTrueOrderByDayOfWeekAscClassTimeAsc();

    List<GymClass> findByDayOfWeekAndIsActiveTrue(int dayOfWeek);
}
