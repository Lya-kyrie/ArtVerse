package com.artverse.application.workflow;

import com.artverse.application.MangaAgentConversationService;
import com.artverse.application.MangaAgentRunService;
import com.artverse.domain.MangaAgentConversation;
import com.artverse.domain.MessageRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The only normal success path for an assistant response. It keeps candidate
 * output out of the conversation until all business facts are authoritative.
 */
@Service
public class ResultFinalizer {

    private final MangaAgentConversationService conversationService;
    private final MangaAgentRunService runService;
    private final ExecutionFactVerifier factVerifier;

    public ResultFinalizer(MangaAgentConversationService conversationService,
                           MangaAgentRunService runService,
                           ExecutionFactVerifier factVerifier) {
        this.conversationService = conversationService;
        this.runService = runService;
        this.factVerifier = factVerifier;
    }

    @Transactional
    public MangaWorkflowResult finalizeResult(MangaAgentConversation conversation, UUID requestId,
                                               MangaWorkflowRoute route, MangaWorkflowResult candidate) {
        runService.requireFinalizable(conversation, requestId);
        ExecutionFactVerifier.VerifiedFacts verified = factVerifier.verify(conversation, requestId, route, candidate);
        Map<String, Object> attributes = new LinkedHashMap<>(candidate.attributes());
        attributes.put("result_verification_status", "PASSED");
        attributes.put("result_schema", verified.resultSchema());
        attributes.put("verified_result", verified.facts());
        MangaWorkflowResult result = candidate.withAttributes(attributes);

        conversationService.saveMessage(conversation, MessageRole.ASSISTANT, result.reply(), requestId);
        runService.completeVerified(conversation, requestId, result.reply(), result.degraded(),
                verified.resultSchema(), verified.facts());
        return result;
    }
}
