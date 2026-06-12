package com.mmagym.bot.repository;

import com.mmagym.bot.model.Member;
import com.mmagym.bot.model.Progress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProgressRepository extends JpaRepository<Progress, Long> {

    List<Progress> findTop10ByMemberOrderByLogDateDesc(Member member);

    Optional<Progress> findTopByMemberAndMetricTypeOrderByLogDateDesc(Member member, String metricType);

    List<Progress> findTop5ByMemberAndMetricTypeOrderByLogDateDesc(Member member, String metricType);
}
