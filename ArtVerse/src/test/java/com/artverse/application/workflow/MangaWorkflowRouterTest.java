package com.artverse.application.workflow;

import com.artverse.agent.AgentModelSpec;
import com.artverse.agent.gateway.AgentScopeHarnessAgentGateway;
import com.artverse.application.workflow.prefilter.EmptyMessageRouterPreFilter;
import com.artverse.application.workflow.prefilter.ReviewReportRouterPreFilter;
import com.artverse.application.workflow.prefilter.TooShortMessageRouterPreFilter;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MangaWorkflowRouterTest {

    private AgentScopeHarnessAgentGateway gateway;
    private MangaWorkflowRouter router;
    private MangaAgentConversation conversation;
    private ExecutionPlanValidator planValidator;
    private RouteContractValidator contractValidator;

    @BeforeEach
    void setUp() {
        gateway = mock(AgentScopeHarnessAgentGateway.class);
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getAgent().setAutoRoutingEnabled(true);
        properties.getAgent().setRoutingDirectThreshold(0.8);
        properties.getAgent().setRoutingReadOnlyThreshold(0.55);
        planValidator = new ExecutionPlanValidator();
        contractValidator = new RouteContractValidator(planValidator);
        router = new MangaWorkflowRouter(gateway, properties,
                List.of(new EmptyMessageRouterPreFilter(), new TooShortMessageRouterPreFilter(),
                        new ReviewReportRouterPreFilter()),
                mock(MangaRoutingMetrics.class), planValidator, contractValidator);

        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        conversation = mock(MangaAgentConversation.class);
        when(conversation.getUser()).thenReturn(user);
        when(conversation.getConversationUuid()).thenReturn(UUID.randomUUID());
        when(conversation.getStory()).thenReturn(mock(com.artverse.domain.Story.class));
        when(conversation.getChapter()).thenReturn(mock(com.artverse.domain.Chapter.class));
        when(conversation.getStory().getId()).thenReturn(11L);
        when(conversation.getChapter().getId()).thenReturn(13L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"generate an image", "请直接出图", "把这一幕绘制成漫画"})
    void routesSemanticallyClassifiedUnavailableCapabilityWithoutKeywordFiltering(String message) {
        RoutingDecision classified = decision(
                MangaWorkflowRoute.CONVERSATION,
                Set.of(MangaWorkflowCapability.IMAGE_GENERATION),
                List.of(MangaWorkflowRoute.CONVERSATION)
        );
        when(gateway.generateStructured(any(), any())).thenReturn(Mono.just(classified));

        RoutingDecision result = route(message);

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.CONVERSATION);
        assertThat(result.reasonCode()).isEqualTo("unsupported_capability:IMAGE_GENERATION");
        assertThat(result.fallbackReason()).isNotNull();
        assertThat(result.fallbackReason().category()).isEqualTo("unsupported_capability");
        assertThat(result.requiredCapabilities()).containsExactly(MangaWorkflowCapability.IMAGE_GENERATION);
        verify(gateway).generateStructured(any(), any());
    }

    @Test
    void rejectsRouteThatDoesNotProvideRequiredCapability() {
        RoutingDecision classified = decision(
                MangaWorkflowRoute.CREATIVE,
                Set.of(MangaWorkflowCapability.STORYBOARD_REVIEW),
                List.of(MangaWorkflowRoute.CREATIVE)
        );
        when(gateway.generateStructured(any(), any())).thenReturn(Mono.just(classified));

        RoutingDecision result = route("审查分镜连续性");

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.CONVERSATION);
        assertThat(result.reasonCode()).isEqualTo("invalid_plan:capability_route_mismatch");
    }

    @Test
    void correctsUnsafeStoryboardPlanWhenCapabilitiesRequireReadOnlyReview() {
        RoutingDecision classified = decision(
                MangaWorkflowRoute.STORYBOARD,
                Set.of(MangaWorkflowCapability.STORYBOARD_READ, MangaWorkflowCapability.STORYBOARD_REVIEW),
                List.of(MangaWorkflowRoute.STORYBOARD)
        );
        when(gateway.generateStructured(any(), any())).thenReturn(Mono.just(classified));

        RoutingDecision result = route("review this chapter's manga status and novel quality in detail");

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.REVIEW);
        assertThat(result.mutating()).isFalse();
        assertThat(result.suggestedSteps()).containsExactly(MangaWorkflowRoute.REVIEW);
    }

    @Test
    void derivesMutationFromCapabilitiesInsteadOfModelFlag() {
        RoutingDecision classified = new RoutingDecision(
                MangaWorkflowRoute.STORYBOARD,
                0.95,
                List.of("rewrite"),
                false,
                false,
                "storyboard_rewrite",
                List.of(MangaWorkflowRoute.STORYBOARD),
                "model-version",
                Set.of(MangaWorkflowCapability.STORYBOARD_WRITE)
        );
        when(gateway.generateStructured(any(), any())).thenReturn(Mono.just(classified));

        RoutingDecision result = route("重写当前分镜");

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.STORYBOARD);
        assertThat(result.mutating()).isTrue();
        assertThat(result.expectedToolPolicy()).isEqualTo(RoutingDecision.ExpectedToolPolicy.WRITE_WITH_HITL);
        assertThat(result.requiredContextFields()).contains("chapter_source_excerpt", "character_summary");
        assertThat(result.outputContract().schemaName()).isEqualTo("storyboard.mutation_result");
    }

    @Test
    void invalidToolPolicyContractFallsBackBeforeDispatch() {
        RoutingDecision classified = new RoutingDecision(
                MangaWorkflowRoute.STORYBOARD,
                0.95,
                List.of("rewrite"),
                true,
                false,
                "storyboard_rewrite",
                List.of(MangaWorkflowRoute.STORYBOARD),
                "model-version",
                Set.of(MangaWorkflowCapability.STORYBOARD_WRITE),
                RoutingDecision.ExpectedToolPolicy.READ_ONLY_CONTEXT,
                RoutingDecision.contextFieldsFor(List.of(MangaWorkflowRoute.STORYBOARD)),
                RoutingDecision.RouteOutputContract.forRoute(MangaWorkflowRoute.STORYBOARD),
                null
        );
        when(gateway.generateStructured(any(), any())).thenReturn(Mono.just(classified));

        RoutingDecision result = route("rewrite storyboard");

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.CONVERSATION);
        assertThat(result.reasonCode()).isEqualTo("invalid_contract:tool_policy_mismatch");
        assertThat(result.fallbackReason().category()).isEqualTo("invalid_contract");
        assertThat(result.fallbackReason().code()).isEqualTo("tool_policy_mismatch");
    }

    @Test
    void incompleteContextContractFallsBackBeforeDispatch() {
        RoutingDecision classified = new RoutingDecision(
                MangaWorkflowRoute.REVIEW,
                0.95,
                List.of("review"),
                false,
                false,
                "review",
                List.of(MangaWorkflowRoute.REVIEW),
                "model-version",
                Set.of(MangaWorkflowCapability.STORYBOARD_REVIEW),
                RoutingDecision.ExpectedToolPolicy.READ_ONLY_CONTEXT,
                Set.of("conversation_summary"),
                RoutingDecision.RouteOutputContract.forRoute(MangaWorkflowRoute.REVIEW),
                null
        );
        when(gateway.generateStructured(any(), any())).thenReturn(Mono.just(classified));

        RoutingDecision result = route("review storyboard continuity");

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.CONVERSATION);
        assertThat(result.reasonCode()).isEqualTo("invalid_contract:context_fields_incomplete");
        assertThat(result.fallbackReason().code()).isEqualTo("context_fields_incomplete");
    }

    @Test
    void dispatchesNormalizedSingleStepWhenModelRouteDisagrees() {
        RoutingDecision classified = decision(
                MangaWorkflowRoute.CREATIVE,
                Set.of(MangaWorkflowCapability.STORYBOARD_REVIEW),
                List.of(MangaWorkflowRoute.REVIEW)
        );
        when(gateway.generateStructured(any(), any())).thenReturn(Mono.just(classified));

        RoutingDecision result = route("审查分镜连续性");

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.REVIEW);
    }

    @Test
    void invalidEmptyPlanFallsBackBeforeDispatch() {
        RoutingDecision classified = decision(
                MangaWorkflowRoute.REVIEW,
                Set.of(MangaWorkflowCapability.STORYBOARD_REVIEW),
                List.of());
        when(gateway.generateStructured(any(), any())).thenReturn(Mono.just(classified));

        RoutingDecision result = route("审查当前分镜");

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.CONVERSATION);
        assertThat(result.reasonCode()).isEqualTo("invalid_plan:empty_steps");
    }

    @Test
    void invalidRecursiveOrOversizedPlanFallsBackBeforeDirector() {
        RoutingDecision recursive = decision(
                MangaWorkflowRoute.DIRECTOR,
                Set.of(MangaWorkflowCapability.STORYBOARD_REVIEW),
                List.of(MangaWorkflowRoute.REVIEW, MangaWorkflowRoute.DIRECTOR));
        when(gateway.generateStructured(any(), any())).thenReturn(Mono.just(recursive));
        assertThat(route("审查后总结").reasonCode()).isEqualTo("invalid_plan:recursive_director");

        RoutingDecision oversized = decision(
                MangaWorkflowRoute.DIRECTOR,
                Set.of(),
                List.of(MangaWorkflowRoute.CONVERSATION, MangaWorkflowRoute.CREATIVE,
                        MangaWorkflowRoute.REVIEW, MangaWorkflowRoute.CONVERSATION));
        when(gateway.generateStructured(any(), any())).thenReturn(Mono.just(oversized));
        assertThat(route("执行很多步骤").reasonCode()).isEqualTo("invalid_plan:too_many_steps");
    }

    @Test
    void nullStepAndUnknownEnumCannotEscapeAsExecutionErrors() throws Exception {
        java.util.ArrayList<MangaWorkflowRoute> steps = new java.util.ArrayList<>();
        steps.add(null);
        RoutingDecision nullStep = decision(MangaWorkflowRoute.CONVERSATION, Set.of(), steps);
        when(gateway.generateStructured(any(), any())).thenReturn(Mono.just(nullStep));
        assertThat(route("普通问题").reasonCode()).isEqualTo("invalid_plan:null_step");

        when(gateway.generateStructured(any(), any())).thenReturn(Mono.error(
                new com.fasterxml.jackson.databind.exc.InvalidFormatException(null,
                        "unknown route", "ALIEN", MangaWorkflowRoute.class)));
        assertThat(route("恶意结构化输出").reasonCode()).isEqualTo("router_unavailable_safe_fallback");
    }

    @Test
    void readsPersistedV1DecisionWithoutCapabilityField() throws Exception {
        String persisted = """
                {"route":"CREATIVE","confidence":0.9,"intents":["idea"],"mutating":false,
                 "needsClarification":false,"reasonCode":"creative","suggestedSteps":["CREATIVE"],
                 "routerVersion":"v1"}
                """;

        RoutingDecision restored = new ObjectMapper().readValue(persisted, RoutingDecision.class);

        assertThat(restored.requiredCapabilities()).isEmpty();
        assertThat(restored.routerVersion()).isEqualTo("v1");
    }

    @Test
    void keepsStructuralValidationOutsideSemanticRouter() {
        RoutingDecision result = route(" ");

        assertThat(result.reasonCode()).isEqualTo("empty_message");
        verify(gateway, never()).generateStructured(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "本章的漫画情况和小说质量怎么样，给我一份详细的分析报告",
            "本章的漫画情况和小说质量怎么样，给我一份详细的分析报告 "
    })
    void routesCurrentChapterQualityReportToReviewWithoutSemanticGuessing(String message) {
        RoutingDecision result = route(message);

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.REVIEW);
        assertThat(result.reasonCode()).isEqualTo("prefilter:chapter_quality_report");
        assertThat(result.requiredCapabilities()).contains(
                MangaWorkflowCapability.STORYBOARD_READ,
                MangaWorkflowCapability.STORYBOARD_REVIEW);
        verify(gateway, never()).generateStructured(any(), any());
    }

    @Test
    void disabledRoutingUsesSafeConcreteFallbackInsteadOfRecursiveDirector() {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getAgent().setAutoRoutingEnabled(false);
        MangaWorkflowRouter disabled = new MangaWorkflowRouter(
                gateway, properties, List.of(), mock(MangaRoutingMetrics.class), planValidator, contractValidator);

        RoutingDecision result = disabled.route(conversation, "rewrite", UUID.randomUUID(),
                mock(AgentModelSpec.class), "key");

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.CONVERSATION);
        assertThat(result.suggestedSteps()).containsExactly(MangaWorkflowRoute.CONVERSATION);
        assertThat(result.fallbackReason().code()).isEqualTo("automatic_routing_disabled_safe_fallback");
    }

    @Test
    void shadowModeRecordsClassificationButExecutesSafeConversationRoute() {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getAgent().setAutoRoutingEnabled(true);
        properties.getAgent().setRoutingShadowMode(true);
        MangaWorkflowRouter shadow = new MangaWorkflowRouter(
                gateway, properties, List.of(), mock(MangaRoutingMetrics.class), planValidator, contractValidator);
        RoutingDecision classified = decision(
                MangaWorkflowRoute.REVIEW,
                Set.of(MangaWorkflowCapability.STORYBOARD_REVIEW),
                List.of(MangaWorkflowRoute.REVIEW));
        when(gateway.generateStructured(any(), any())).thenReturn(Mono.just(classified));

        RoutingDecision result = shadow.route(conversation, "review", UUID.randomUUID(),
                mock(AgentModelSpec.class), "key");

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.CONVERSATION);
        assertThat(result.reasonCode()).isEqualTo("shadow:REVIEW");
    }

    @Test
    void shadowModeSkipsClarificationForLowConfidenceMutation() {
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getAgent().setAutoRoutingEnabled(true);
        properties.getAgent().setRoutingShadowMode(true);
        // Use default thresholds: routingDirectThreshold=0.80
        MangaWorkflowRouter shadow = new MangaWorkflowRouter(
                gateway, properties, List.of(), mock(MangaRoutingMetrics.class), planValidator, contractValidator);
        RoutingDecision classified = new RoutingDecision(
                MangaWorkflowRoute.STORYBOARD,
                0.65,  // below routingDirectThreshold of 0.80 — would trigger clarification normally
                List.of("storyboard_rewrite"),
                true,  // mutating
                false,
                "storyboard_request",
                List.of(MangaWorkflowRoute.STORYBOARD),
                RoutingDecision.CURRENT_VERSION,
                Set.of(MangaWorkflowCapability.STORYBOARD_WRITE));
        when(gateway.generateStructured(any(), any())).thenReturn(Mono.just(classified));

        // Must NOT throw — shadow mode suppresses clarification
        RoutingDecision result = shadow.route(conversation, "rewrite storyboard with low confidence",
                UUID.randomUUID(), mock(AgentModelSpec.class), "key");

        assertThat(result.route()).isEqualTo(MangaWorkflowRoute.CONVERSATION);
        assertThat(result.reasonCode()).startsWith("shadow:");
        assertThat(result.mutating()).isFalse();
        assertThat(result.suggestedSteps()).containsExactly(MangaWorkflowRoute.CONVERSATION);
    }

    private RoutingDecision route(String message) {
        return router.route(conversation, message, UUID.randomUUID(), mock(AgentModelSpec.class), "key");
    }

    private RoutingDecision decision(MangaWorkflowRoute route,
                                     Set<MangaWorkflowCapability> capabilities,
                                     List<MangaWorkflowRoute> steps) {
        return new RoutingDecision(route, 0.95, List.of(), route.isMutating(), false,
                "classified", steps, "model-version", capabilities);
    }
}
