package com.icusu.sivan.application.memory;

import com.icusu.sivan.domain.memory.SharedTemplate;
import com.icusu.sivan.infra.memory.shared.SharedTemplateService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 共享模板应用服务 — 包装 infra 层 SharedTemplateService，
 * 作为 web 层访问模板共享能力的唯一入口。
 */
@Service
public class SharedTemplateAppService {

    private final SharedTemplateService sharedTemplateService;

    public SharedTemplateAppService(SharedTemplateService sharedTemplateService) {
        this.sharedTemplateService = sharedTemplateService;
    }

    public SharedTemplate share(UUID patternId, UUID ownerId, SharedTemplate.Visibility visibility) {
        return sharedTemplateService.share(patternId, ownerId, visibility);
    }

    public void unshare(UUID templateId, UUID ownerId) {
        sharedTemplateService.unshare(templateId, ownerId);
    }

    public List<SharedTemplate> findAccessibleTemplates(UUID accountId) {
        return sharedTemplateService.findAccessibleTemplates(accountId);
    }
}
