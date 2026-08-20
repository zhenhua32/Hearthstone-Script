package club.xiaojiawei.hsscriptbase.config

import club.xiaojiawei.hsscriptbase.bean.ReadableThread
import club.xiaojiawei.hsscriptbase.bean.WritableThread
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/*
 * 全局线程池按职责隔离，避免日志轮询、程序启动、策略计算互相耗尽线程。
 *
 * `WritableThread` 用于需要保留可写线程上下文的系统/启动任务，`ReadableThread` 用于
 * 普通后台与计算任务。所有有界线程池都使用 AbortPolicy：队列满时应让调用方明确感知
 * 拒绝，而不是静默丢弃会改变游戏状态的任务。
 *
 */
/** 战网、炉石等外部程序启动/重试调度；任务通常包含进程等待。 */
val LAUNCH_PROGRAM_THREAD_POOL: ScheduledThreadPoolExecutor by lazy {
    ScheduledThreadPoolExecutor(2, object : ThreadFactory {
        private val num = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread {
            return WritableThread(r, "LaunchProgramPool Thread-" + num.getAndIncrement())
        }
    }, ThreadPoolExecutor.AbortPolicy())
}

/** LoadingScreen、Decks、Power 等日志的短周期轮询，禁止提交长时间阻塞任务。 */
val LISTEN_LOG_THREAD_POOL: ScheduledThreadPoolExecutor by lazy {
    ScheduledThreadPoolExecutor(4, object : ThreadFactory {
        private val num = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread {
            return WritableThread(r, "ListenLogPool Thread-" + num.getAndIncrement())
        }
    }, ThreadPoolExecutor.AbortPolicy())
}

/** UI 之外的通用延迟、通知、监听回调和轻量后台任务。 */
val EXTRA_THREAD_POOL: ScheduledThreadPoolExecutor by lazy {
    ScheduledThreadPoolExecutor(10, object : ThreadFactory {
        private val num = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread {
            return ReadableThread(r, "ExtraPool Thread-" + num.getAndIncrement())
        }
    }, ThreadPoolExecutor.AbortPolicy())
}

/** 启动/重启责任链入口；小队列用于抑制并发重复启动。 */
val CORE_THREAD_POOL: ThreadPoolExecutor by lazy {
    ThreadPoolExecutor(2, 2, 5, TimeUnit.SECONDS, ArrayBlockingQueue(1), object : ThreadFactory {
        private val num = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread {
            return WritableThread(r, "CorePool Thread-" + num.getAndIncrement())
        }
    }, ThreadPoolExecutor.AbortPolicy())
}

/** MCTS 与其他 CPU 密集计算；核心线程数按处理器数量配置，并允许短时扩容。 */
val CALC_THREAD_POOL: ThreadPoolExecutor by lazy {
    ThreadPoolExecutor(
        Runtime.getRuntime().availableProcessors(),
        Runtime.getRuntime().availableProcessors() * 2,
        120,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(8),
        object : ThreadFactory {
            private val num = AtomicInteger(0)
            override fun newThread(r: Runnable): Thread {
                return ReadableThread(r, "CalcPool Thread-" + num.getAndIncrement())
            }
        },
        ThreadPoolExecutor.AbortPolicy()
    )
}

/** 提交通用后台任务并返回 Future，调用方可按生命周期取消。 */
fun submitExtra(block: () -> Unit): Future<*> {
    return EXTRA_THREAD_POOL.submit(block)
}

/** 适合大量短生命周期阻塞操作的虚拟线程执行器，不应用于持续占用 CPU 的计算。 */
val VIRTUAL_THREAD_POOL: ExecutorService =
    Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("VPool Thread-", 0).factory());
