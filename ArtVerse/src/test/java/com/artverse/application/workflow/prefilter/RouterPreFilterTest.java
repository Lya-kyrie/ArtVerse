package com.artverse.application.workflow.prefilter;

import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.application.workflow.MangaWorkflowRoute;
import com.artverse.application.workflow.MangaWorkflowRouter;
import com.artverse.application.workflow.RoutingDecision;
import com.artverse.config.ArtVerseProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouterPreFilterTest {

    @Test
    void emptyMessageFilterOwnsOnlyBlankInput() {
        RouterPreFilter filter = new EmptyMessageRouterPreFilter();

        assertThat(filter.filter(null)).get().extracting(RoutingDecision::reasonCode).isEqualTo("empty_message");
        assertThat(filter.filter(" \t\n")).get().extracting(RoutingDecision::reasonCode).isEqualTo("empty_message");
        assertThat(filter.filter("a")).isEmpty();
    }

    @Test
    void tooShortFilterCountsUnicodeCodePointsAndLeavesBlankInputToEarlierFilter() {
        RouterPreFilter filter = new TooShortMessageRouterPreFilter();

        assertThat(filter.filter(" ")).isEmpty();
        assertThat(filter.filter("你")).get().extracting(RoutingDecision::reasonCode).isEqualTo("too_short");
        assertThat(filter.filter("\uD83D\uDE00")).get().extracting(RoutingDecision::reasonCode).isEqualTo("too_short");
        assertThat(filter.filter("好的")).isEmpty();
    }

    @Test
    void routerSortsFiltersAndStopsAfterFirstMatch() {
        AgentScopeHarnessAgentGateway gateway = mock(AgentScopeHarnessAgentGateway.class);
        RouterPreFilter first = mock(RouterPreFilter.class);
        RouterPreFilter second = mock(RouterPreFilter.class);
        RoutingDecision expected = RoutingDecision.fixed(MangaWorkflowRoute.CONVERSATION, "first_match");
        when(first.getOrder()).thenReturn(10);
        when(second.getOrder()).thenReturn(20);
        when(first.filter("message")).thenReturn(Optional.of(expected));

        MangaWorkflowRouter router = new MangaWorkflowRouter(
                gateway, new ArtVerseProperties(), List.of(second, first),
                mock(com.artverse.application.workflow.MangaRoutingMetrics.class),
                new com.artverse.application.workflow.ExecutionPlanValidator(),
                new com.artverse.application.workflow.RouteContractValidator(
                        new com.artverse.application.workflow.ExecutionPlanValidator()));

        RoutingDecision actual = router.route(null, "message", UUID.randomUUID(), null, null);

        assertThat(actual).isSameAs(expected);
        verify(first).filter("message");
        verify(second, never()).filter("message");
    }
}
