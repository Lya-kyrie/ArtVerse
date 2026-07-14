package com.artverse.persistence;

import com.artverse.domain.MangaAgentRunStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MangaAgentRunStepRepository extends JpaRepository<MangaAgentRunStep, Long> {

    Optional<MangaAgentRunStep> findByRunIdAndPlanIdAndStepSequence(
            Long runId, String planId, int stepSequence);

    List<MangaAgentRunStep> findByRunIdOrderByStepSequenceAsc(Long runId);
}
