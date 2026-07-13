package com.artverse.application;

import com.artverse.config.ArtVerseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class MangaAgentRunWatchdog {

    private final MangaAgentRunService mangaAgentRunService;
    private final ArtVerseProperties properties;

    @Scheduled(fixedDelayString = "${artverse.agent.run-watchdog-interval-ms:30000}")
    public void interruptStalledRuns() {
        OffsetDateTime now = OffsetDateTime.now();
        int interrupted = mangaAgentRunService.interruptStalledRunningRuns(
                now.minusSeconds(properties.getAgent().getModelIdleTimeoutSeconds()),
                now.minusSeconds(properties.getAgent().getToolIdleTimeoutSeconds())
        );
        if (interrupted > 0) {
            log.warn("Interrupted {} manga agent runs that exceeded their phase idle budget", interrupted);
        }
    }
}
