package com.mmagym.bot.repository;

import com.mmagym.bot.model.Member;
import com.mmagym.bot.model.MilestoneAward;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MilestoneAwardRepository extends JpaRepository<MilestoneAward, Long> {
    boolean existsByMemberAndMilestone(Member member, int milestone);
}
