package com.lolens.services.lcu;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 LeagueClientUx.exe 进程命令行解析 LCU 连接信息。
 * <p>
 * 国际服（Riot 客户端）与国服（WeGame 客户端）的 LCU 进程命令行都携带
 * {@code --app-port} 与 {@code --remoting-auth-token}，因此本解析器可覆盖
 * 安装路径非默认（如国服 / 自定义盘符）而 lockfile 不在默认位置的情况。
 */
public final class LcuProcessResolver {

    private static final Pattern APP_PORT = Pattern.compile("--app-port=(\\d+)");
    private static final Pattern REMOTING_TOKEN = Pattern.compile("--remoting-auth-token=([^\\s\"]+)");
    private static final Pattern APP_PID = Pattern.compile("--app-pid=(\\d+)");

    private LcuProcessResolver() {
    }

    /** 读取当前 LeagueClientUx.exe 进程命令行并解析连接信息。 */
    public static Optional<Lockfile> resolve() {
        String commandLine = readCommandLine();
        return commandLine == null ? Optional.empty() : parseFromCommandLine(commandLine);
    }

    /**
     * 纯解析逻辑（与操作系统解耦，便于测试）：
     * 从进程命令行提取 {@code --app-port} 与 {@code --remoting-auth-token}。
     * 注意区别于 Riot 客户端自身的 {@code --riotclient-app-port} / {@code --riotclient-auth-token}。
     */
    static Optional<Lockfile> parseFromCommandLine(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return Optional.empty();
        }
        Matcher portMatcher = APP_PORT.matcher(commandLine);
        Matcher tokenMatcher = REMOTING_TOKEN.matcher(commandLine);
        if (!portMatcher.find() || !tokenMatcher.find()) {
            return Optional.empty();
        }
        int port;
        try {
            port = Integer.parseInt(portMatcher.group(1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (port <= 0 || port > 65535) {
            return Optional.empty();
        }
        int pid = 0;
        Matcher pidMatcher = APP_PID.matcher(commandLine);
        if (pidMatcher.find()) {
            try {
                pid = Integer.parseInt(pidMatcher.group(1));
            } catch (NumberFormatException ignored) {
                // pid 仅用于日志，解析失败不阻断连接
            }
        }
        return Optional.of(new Lockfile("LeagueClient", pid, port, tokenMatcher.group(1), "https"));
    }

    private static String readCommandLine() {
        // 优先 PowerShell（Win11 已移除 wmic），失败回退 wmic。
        // 注意：不能使用 -Filter "Name='LeagueClientUx.exe'"——命令内部的双引号
        // 经 ProcessBuilder -> CreateProcess 传参时会被剥掉，导致 Get-CimInstance
        // 报 InvalidArgument（参数错误），探测永远失败。
        // 改用 Where-Object + 单引号写法，单引号在 Windows 命令行解析中无特殊含义，可安全传递。
        String viaPowerShell = exec("powershell.exe", "-NoProfile", "-Command",
                "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'LeagueClientUx.exe' } | Select-Object -ExpandProperty CommandLine");
        if (viaPowerShell != null && !viaPowerShell.isBlank()) {
            return viaPowerShell;
        }
        return exec("wmic", "process", "where", "name='LeagueClientUx.exe'", "get", "CommandLine");
    }

    private static String exec(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return out.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
