package com.icusu.sivan.agent.tool;

import com.icusu.sivan.infra.sandbox.SandboxExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Bash 命令执行服务。
 * <p>
 * 安全策略（纵深防御三层）：
 * <ol>
 *   <li><b>模式检测</b> — 正则拦截已知危险模式，执行前阻断</li>
 *   <li><b>OS 沙箱</b> — 平台自适应沙箱隔离（sandbox-exec / bwrap / ulimit）</li>
 *   <li><b>资源限制</b> — ulimit 六项 + 超时强杀进程树</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BashService {

    private final SandboxExecutor sandboxExecutor;

    /** 是否允许网络出站连接（默认禁止，自主执行场景下始终禁止）。 */
    @Value("${sivan.bash.allow-network:false}")
    private boolean allowNetwork;

    // ============================================================
    // 危险命令模式（层 1：预过滤）
    // ============================================================

    /** 可执行文件创建（绕过 file_write 可执行文件拦截） */
    private static final List<Pattern> DANGEROUS_EXECUTABLE = List.of(
            Pattern.compile("\\bchmod\\s\\+x"),
            Pattern.compile("\\bgcc\\s+.*-o\\s"),
            Pattern.compile("\\bg\\+\\+\\s+.*-o\\s"),
            Pattern.compile("\\brustc\\s+.*-o\\s"),
            Pattern.compile("\\bgo\\s+build\\s+.*-o\\s"),
            Pattern.compile("\\bclang\\s+.*-o\\s"),
            Pattern.compile("\\bnasm\\s+.*-o\\s"),
            Pattern.compile("\\bld\\s+.*-o\\s"),
            Pattern.compile("\\bpyinstaller\\b"),
            Pattern.compile("\\bcompile\\s+[a-zA-Z]")
    );

    /** 文件系统破坏 */
    private static final List<Pattern> DANGEROUS_FILESYSTEM = List.of(
            Pattern.compile("\\brm\\s+.*-(r|f|rf|fr).*\\s+(/(\\s|\\*|\\.[\\s\\*]?|$|;|\\|)|~(/|\\s|$)|\\$HOME)"),
            Pattern.compile("\\brm\\s+.*-(r|f|rf|fr).*\\s+/(home|root|bin|boot|dev|etc|lib|opt|sbin|srv|sys|usr|var)(/|\\s|$)"),
            Pattern.compile("\\bfind\\s+.*-(?:delete|exec\\s+rm)"),
            Pattern.compile("\\bmkfs\\b"),
            Pattern.compile("\\bdd\\s+if=.*of=/dev/(sd|hd|nvme|mmcblk|disk)"),
            Pattern.compile("\\b(mount|umount)\\s"),
            Pattern.compile("[>|]\\s*/dev/(sd|hd|nvme|mmcblk|disk|mem|kmem|port)"),
            Pattern.compile("\\bchmod\\s+.*-(R|recursive)?\\s*[7].*/(bin|boot|dev|etc|lib|opt|sbin|srv|sys|usr|var)(/|\\s|$)")
    );

    /** 进程逃逸 */
    private static final List<Pattern> DANGEROUS_PROCESS = List.of(
            Pattern.compile("\\b(nohup|disown|setsid)\\b"),
            Pattern.compile("\\b(screen|tmux)\\b"),
            Pattern.compile("[^<]&\\s*(\\n|;|$)"),
            Pattern.compile(": *\\(\\s*\\)\\s*\\{")
    );

    /** 网络外泄 + 出站（当 allowNetwork=false 时，任何 curl/wget/nc 都拦截） */
    private static final List<Pattern> DANGEROUS_NETWORK = List.of(
            Pattern.compile("\\bcurl\\s+.*(-d|--data|--data-binary|--data-urlencode|@|--upload-file|-F|--form)"),
            Pattern.compile("\\bwget\\s+.*(--post-data|--post-file|--header)"),
            Pattern.compile("\\bn(c|cat)\\s+.*(<|--send-only)"),
            Pattern.compile("/dev/tcp/"),
            // 无载荷出站（仅 allowNetwork=false 时启用）·
            Pattern.compile("<<network-block>>")
    );

    /** 敏感文件访问 */
    private static final List<Pattern> DANGEROUS_SENSITIVE = List.of(
            Pattern.compile("~?/?\\.ssh/"),
            Pattern.compile("~?/?\\.gnupg/"),
            Pattern.compile("~?/?\\.(aws|azure|gcloud|config/gcloud)"),
            Pattern.compile("/etc/(passwd|shadow|sudoers|master\\.passwd)"),
            Pattern.compile("/etc/(ssh|ssl|tls|certs)/"),
            Pattern.compile("/proc/(sys|net|\\d+|self)"),
            Pattern.compile("/sys/(kernel|fs|devices)")
    );

    /** 混淆检测 + 脚本内容扫描 */
    private static final List<Pattern> DANGEROUS_OBFUSCATION = List.of(
            // base64 pipe to bash/sh
            Pattern.compile("(base64|b64)\\s*-d\\s*(\\||;)\\s*(bash|sh)"),
            // hex encode pipe
            Pattern.compile("xxd\\s+-r\\s*(\\||;)\\s*(bash|sh)"),
            // python dangerous imports in -c
            Pattern.compile("python[23]?\\s+-c\\s+['\"].*(socket\\.|subprocess\\.|os\\.system|os\\.popen|open\\s*\\([^)]*['\"]/etc|shutil\\.)"),
            // encoded command via eval
            Pattern.compile("\\beval\\s*\\$?\\(?\\s*(echo|base64|printf)"),
            // curl/wget pipe to bash (common remote code execution pattern)
            Pattern.compile("(curl|wget)\\s+\\S+\\s*(\\||;)\\s*(bash|sh)")
    );

    /** 当 allowNetwork=false 时额外激活的出站拦截规则 */
    private static final List<Pattern> NETWORK_BLOCK = List.of(
            Pattern.compile("\\bcurl\\s+"),
            Pattern.compile("\\bwget\\s+"),
            Pattern.compile("\\bnc\\s+"),
            Pattern.compile("\\bncat\\s+"),
            Pattern.compile("\\bwget\\s+-"),
            Pattern.compile("\\btelnet\\s+"),
            Pattern.compile("\\bssh\\s+"),
            Pattern.compile("\\bscp\\s+"),
            Pattern.compile("\\brsync\\s+"),
            Pattern.compile("\\btftp\\s+"),
            Pattern.compile("\\bftp\\s+")
    );

    private static final Pattern[] ALL_DANGEROUS = mergePatterns(
            DANGEROUS_FILESYSTEM, DANGEROUS_PROCESS, DANGEROUS_NETWORK, DANGEROUS_SENSITIVE,
            DANGEROUS_OBFUSCATION);


    @SafeVarargs
    private static Pattern[] mergePatterns(List<Pattern>... groups) {
        int total = 0;
        for (var g : groups) total += g.size();
        Pattern[] all = new Pattern[total];
        int i = 0;
        for (var g : groups) {
            for (var p : g) all[i++] = p;
        }
        return all;
    }

    // ============================================================
    // 执行
    // ============================================================

    /**
     * 在项目根目录中执行 shell 命令。
     *
     * @param command      要执行的命令
     * @param fileRootPath 项目根目录（工作目录）
     * @return 命令输出（stdout + stderr）
     */
    public String execute(String command, String fileRootPath) {
        return execute(command, fileRootPath, false);
    }

    public String execute(String command, String fileRootPath, boolean archived) {
        if (command == null || command.isBlank()) {
            return "错误：命令为空";
        }
        if (fileRootPath == null || fileRootPath.isBlank()) {
            return "错误：缺少项目根目录";
        }
        if (archived) {
            return "错误：项目已归档，禁止执行命令";
        }

        File workDir = new File(fileRootPath);
        if (!workDir.exists() || !workDir.isDirectory()) {
            return "错误：项目目录不存在: " + fileRootPath;
        }

        // 层 1：危险命令预过滤（剥离 heredoc 正文后再检测，避免文件内容误判）
        String commandSkeleton = stripHeredocBodies(command);
        String blocked = checkDangerous(commandSkeleton);
        if (blocked != null) {
            log.warn("bash 危险命令已拦截: rule={} command={}", blocked, truncateForLog(command));
            return "错误：命令被安全策略拦截（" + blocked + "）";
        }

        // 可执行文件创建拦截（绕过 file_write 的安全限制）
        String execBlocked = checkExecutableCreation(commandSkeleton);
        if (execBlocked != null) {
            log.warn("bash 可执行文件创建已拦截: rule={} command={}", execBlocked, truncateForLog(command));
            return "错误：bash 创建可执行文件已被禁止（" + execBlocked + "）";
        }

        // 网络阻断检查（开启时额外拦截 curl/wget/nc 等出站工具）
        if (!allowNetwork) {
            String networkBlocked = checkNetworkBlock(commandSkeleton);
            if (networkBlocked != null) {
                log.warn("bash 出站连接已拦截: rule={} command={}", networkBlocked, truncateForLog(command));
                return "错误：出站网络连接已被禁止（" + networkBlocked + "）";
            }
        }

        // 层 2+3：OS 沙箱 + 资源限制（委托 SandboxExecutor）
        log.debug("bash 执行: commandLen={} allowNetwork={}", command.length(), allowNetwork);
        return sandboxExecutor.execute(command, fileRootPath, allowNetwork);
    }

    /** 剥离 heredoc 正文，只保留命令骨架供安全检测。 */
    static String stripHeredocBodies(String command) {
        if (command == null || !command.contains("<<")) return command;
        // 匹配 << DELIMITER ... DELIMITER 模式（DELIMITER 可为引号包围）
        return command.replaceAll("<<\\s*'?(\\w+)'?[\\s\\n][\\s\\S]*?\\n\\1(\\s|$)", "");
    }

    // ============================================================
    // 检测
    // ============================================================

    private String checkDangerous(String command) {
        for (Pattern p : ALL_DANGEROUS) {
            if (p.matcher(command).find()) {
                return classifyRule(p);
            }
        }
        return null;
    }

    /** 网络出站阻断检查（仅 allowNetwork=false 时调用）。 */
    private String checkNetworkBlock(String command) {
        for (Pattern p : NETWORK_BLOCK) {
            if (p.matcher(command).find()) {
                String tool = p.pattern().replaceAll("\\\\b", "").replaceAll("\\\\s\\+", "");
                return "出站连接: " + tool;
            }
        }
        return null;
    }

    /** 可执行文件创建检查。 */
    private String checkExecutableCreation(String command) {
        for (Pattern p : DANGEROUS_EXECUTABLE) {
            if (p.matcher(command).find()) {
                String rule = p.pattern().replaceAll("\\\\b", "").replaceAll("\\\\s\\+", "");
                return "可执行文件: " + rule;
            }
        }
        return null;
    }

    private String classifyRule(Pattern p) {
        for (var r : DANGEROUS_FILESYSTEM) if (r.equals(p)) return "文件系统破坏";
        for (var r : DANGEROUS_PROCESS) if (r.equals(p)) return "进程逃逸";
        for (var r : DANGEROUS_NETWORK) if (r.equals(p)) return "网络外泄";
        for (var r : DANGEROUS_SENSITIVE) if (r.equals(p)) return "敏感文件访问";
        for (var r : DANGEROUS_OBFUSCATION) if (r.equals(p)) return "混淆/脚本扫描";
        return "未知规则";
    }

    private static String truncateForLog(String s) {
        if (s == null) return "null";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
