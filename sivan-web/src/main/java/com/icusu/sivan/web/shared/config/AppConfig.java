package com.icusu.sivan.web.shared.config;

import com.icusu.sivan.domain.compression.MessageImportanceScorer;
import com.icusu.sivan.domain.shared.port.IEmbeddingService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CompressionConfig {
    @Bean
    MessageImportanceScorer messageImportanceScorer(ObjectProvider<IEmbeddingService> embeddingProvider) {
        IEmbeddingService svc = embeddingProvider.getIfAvailable();
        return svc != null ? new MessageImportanceScorer(svc) : new MessageImportanceScorer();
    }
}
