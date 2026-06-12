package com.icusu.sivan.agent.security;

import com.icusu.sivan.domain.security.*;
import com.icusu.sivan.agent.tool.BashService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ShellPolicy implements Policy<Action> {

    private static final Logger log = LoggerFactory.getLogger(ShellPolicy.class);

    private final BashService bashService;

    public ShellPolicy(BashService bashService) {
        this.bashService = bashService;
    }

    @Override
    public void validate(Action action, SecurityContext ctx) {
        if (!(action instanceof ShellExec shell)) {
            throw new PolicyViolationException("不支持的 Shell 操作: " + action.getClass().getSimpleName());
        }
        // execute 内部已做危险命令检测，返回 null/空 = 校验通过，报异常 = 拒绝
        bashService.execute(shell.command(), ctx.projectRoot());
    }

    @Override
    public Class<Action> actionType() {
        return Action.class;
    }

    @Override
    public String requiredPermission() {
        return "shell";
    }
}
