package com.artverse.application.workflow.prefilter;

import com.artverse.application.workflow.RoutingDecision;
import org.springframework.core.Ordered;

import java.util.Optional;

/**
 * Deterministic validation performed before the semantic workflow router.
 */
public interface RouterPreFilter extends Ordered {

    Optional<RoutingDecision> filter(String message);
}
