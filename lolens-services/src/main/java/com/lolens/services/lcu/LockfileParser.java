package com.lolens.services.lcu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * lockfile 解析器：从英雄联盟客户端安装目录的 lockfile 读取连接信息。
 * 典型路径：C:/Riot Games/League of Legends/lockfile
 */
public final class LockfileParser {

    private LockfileParser() {
    }

    /**
     * 解析指定路径下的 lockfile。
     */
    public static Optional<Lockfile> parse(Path lockfilePath) {
        if (lockfilePath == null || !Files.exists(lockfilePath)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(lockfilePath).trim();
            return parseFromContent(content);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** 从 lockfile 文本内容解析。 */
    public static Optional<Lockfile> parseFromContent(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        // 限制分段数：密码后不应再有冒号，防御性避免尾随内容污染 protocol 段
        String[] parts = content.split(":", 5);
        if (parts.length < 5) {
            return Optional.empty();
        }
        try {
            int port = Integer.parseInt(parts[2]);
            String protocol = parts[4].trim();
            // 仅接受 HTTPS 本地回环协议：拒绝 http 明文，避免 lockfile 被篡改后泄露凭据
            if (!"https".equalsIgnoreCase(protocol) || port <= 0 || port > 65535 || parts[3].isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Lockfile(
                    parts[0],
                    Integer.parseInt(parts[1]),
                    port,
                    parts[3],
                    protocol
            ));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
