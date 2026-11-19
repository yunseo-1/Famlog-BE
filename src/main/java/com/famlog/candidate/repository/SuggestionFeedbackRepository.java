package com.famlog.candidate.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.famlog.candidate.domain.SuggestionFeedback;

public interface SuggestionFeedbackRepository extends JpaRepository<SuggestionFeedback, Long> {
}
