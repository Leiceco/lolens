package com.lolens.services.lcu;

/**
 * 英雄联盟客户端 lockfile 内容。
 * 格式：进程名:pid:端口:密码:协议
 */
public record Lockfile(String processName, int pid, int port, String password, String protocol) {

    /** 基于 lockfile 拼出 HTTPS base URL。 */
    public String baseUrl() {
        return protocol + "://127.0.0.1:" + port;
    }
}
