package com.artverse.ai;

import com.artverse.application.UserProviderConfig;
import reactor.core.publisher.Mono;

public interface Image2Client {

    Mono<GeneratedImage> generate(ImageGenerationRequest request, UserProviderConfig providerConfig);
}
