package com.mmagym.bot.repository;

import com.mmagym.bot.model.Member;
import com.mmagym.bot.model.Progress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProgressRepository extends JpaRepository<Progress, Long> {

    List<Progress> findTop10ByMemberOrderByLogDateDesc(Member member);
}
