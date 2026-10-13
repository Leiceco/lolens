package com.lolens.kernel.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务统一调度：模块不应自建线程池，统一通过内核调度器执行周期任务。
 */
public class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "lolens-scheduler");
        t.setDaemon(true);
        return t;
    });

    /** 周期执行任务。 */
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        return executor.scheduleAtFixedRate(wrap(task), initialDelay, period, unit);
    }

    /**
     * 周期执行任务（固定延迟：上一次执行结束后才开始计时）。
     * 轮询任务推荐使用本方法，避免单次超时后 scheduleAtFixedRate 的背靠背补跑风暴。
     */
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit unit) {
        return executor.scheduleWithFixedDelay(wrap(task), initialDelay, delay, unit);
    }

    /** 延迟执行一次。 */
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return executor.schedule(wrap(task), delay, unit);
    }

    private Runnable wrap(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                // 兜底：调度任务异常不允许影响内核其他部分
                log.error("调度任务执行失败", e);
            }
        };
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
