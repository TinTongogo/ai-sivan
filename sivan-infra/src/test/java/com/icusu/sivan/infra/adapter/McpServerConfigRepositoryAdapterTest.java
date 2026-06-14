package com.icusu.sivan.infra.adapter;

import com.icusu.sivan.domain.tool.IMcpServerConfigRepository;
import com.icusu.sivan.domain.tool.McpServerConfig;
import com.icusu.sivan.infra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Sql("/disable-fk.sql")
@Transactional
class McpServerConfigRepositoryAdapterTest extends AbstractIntegrationTest {

    @Autowired
    private IMcpServerConfigRepository repository;

    @Test
    void shouldSaveAndFindById() {
        McpServerConfig config = McpServerConfig.builder()
                .accountId(UUID.randomUUID())
                .name("测试MCP")
                .serverUrl("http://localhost:8080/mcp")
                .active(true)
                .build();
        repository.save(config);

        assertNotNull(config.getServerId());

        McpServerConfig found = repository.findById(config.getServerId()).orElse(null);
        assertNotNull(found);
        assertEquals("测试MCP", found.getName());
        assertEquals(true, found.getActive());
    }

    @Test
    void shouldFindAllByAccount() {
        UUID accountId = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            repository.save(McpServerConfig.builder()
                    .accountId(accountId).name("MCP" + i)
                    .serverUrl("url" + i).active(false)
                    .build());
        }

        List<McpServerConfig> configs = repository.findAllByAccount(accountId);
        assertEquals(2, configs.size());
    }

    @Test
    void shouldUpdate() {
        McpServerConfig config = McpServerConfig.builder()
                .accountId(UUID.randomUUID()).name("旧名")
                .serverUrl("url").active(false)
                .build();
        repository.save(config);

        config.setName("新名");
        config.setActive(true);
        repository.save(config);

        McpServerConfig found = repository.findById(config.getServerId()).orElse(null);
        assertEquals("新名", found.getName());
        assertEquals(true, found.getActive());
    }

    @Test
    void shouldDelete() {
        McpServerConfig config = McpServerConfig.builder()
                .accountId(UUID.randomUUID()).name("待删除")
                .serverUrl("url").active(false)
                .build();
        repository.save(config);
        UUID id = config.getServerId();

        repository.delete(id);
        assertTrue(repository.findById(id).isEmpty());
    }
}
