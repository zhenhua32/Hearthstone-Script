package club.xiaojiawei.hsscriptcardsdk.mcts

import club.xiaojiawei.hsscriptcardsdk.bean.InitAction
import club.xiaojiawei.hsscriptbase.bean.LRunnable
import club.xiaojiawei.hsscriptcardsdk.bean.MCTSArg
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptbase.config.CALC_THREAD_POOL
import club.xiaojiawei.hsscriptbase.config.log
import club.xiaojiawei.hsscriptbase.util.randomSelect
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Function
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** 蒙特卡洛树默认最大选择深度，防止动作环或异常模型产生无限下降。 */
const val MCTS_DEFAULT_DEPTH = 10

/**
 * 基于可模拟 [War] 的蒙特卡洛树搜索实现。
 *
 * 单次迭代遵循 Selection -> Expansion -> Simulation -> Back-propagation：
 * - Selection 用 UCB 在已完全展开节点中平衡探索与利用；
 * - Expansion 随机选择一个尚未展开的合法动作；
 * - Simulation 在临时 War 上随机执行动作直到结束或超时；
 * - Back-propagation 把相对根节点评分是否提高作为胜负信号回传。
 *
 * 搜索永远操作 War 副本，返回的是从根到最佳终局节点的路径。多线程模式按根动作分片，
 * 每个任务拥有独立根分支，避免多个线程同时写同一 War；最终按路径末端评分选优。
 *
 * @param maxDepth Selection 阶段允许向下遍历的最大层数。
 * @author 肖嘉威
 * @date 2025/1/10 10:04
 */
class MonteCarloTreeSearch(val maxDepth: Int = MCTS_DEFAULT_DEPTH) {

    /** 从根开始重复选择 UCB 最大的子节点，直到遇到可扩展节点、叶子、深度或时间上限。 */
    private fun select(rootNode: MonteCarloTreeNode, endTime: Long): MonteCarloTreeNode {
        var node: MonteCarloTreeNode = rootNode
        var maxUCB = Int.MIN_VALUE.toDouble()
        var level = 0
        while (node.isFullExpanded() && !node.isLeaf() && System.currentTimeMillis() < endTime) {
            val parentNode = node
            val children = node.children
            for (child in children) {
                val ucb = child.state.calcUCB(parentNode.state.visitCount)
                if (ucb > maxUCB) {
                    maxUCB = ucb
                    node = child
                }
            }
            level++
            if (level > maxDepth) {
                break
            }
        }
        return node
    }

    /** 随机展开一个未访问动作；节点已完全展开时返回 `null`。 */
    private fun expand(node: MonteCarloTreeNode): MonteCarloTreeNode? {
        var nextNode: MonteCarloTreeNode? = null
        if (!node.isFullExpanded()) {
            val unExpanded = node.getUnExpanded()
            val action = unExpanded.randomSelect()
            nextNode = node.expand(action)
        }
        return nextNode
    }

    /**
     * 从给定节点随机走到终局/超时，并比较叶子评分与本任务根评分。
     * 第一动作复制 War，后续动作复用该模拟分支，减少深层 rollout 的复制成本。
     */
    private fun simulate(node: MonteCarloTreeNode, rootNode: MonteCarloTreeNode, endTime: Long): Boolean {
        var tempNode = node
        var isFirstTempNode = true
        while (!tempNode.isEnd() && System.currentTimeMillis() < endTime) {
            val actions = tempNode.actions
            val action = actions.randomSelect()

            val nextTempNode = if (isFirstTempNode) {
                isFirstTempNode = false
                tempNode.buildNextNode(action, cloneWar = true)
            } else tempNode.buildNextNode(action, cloneWar = false)

            tempNode = nextTempNode
        }
        val score = tempNode.state.score
        return score > rootNode.state.score
    }

    /** 将 rollout 结果沿 parent 链回传到任务根；`null` 沿用节点上一次结果。 */
    private fun backPropagation(node: MonteCarloTreeNode, win: Boolean?) {
        var tempNode: MonteCarloTreeNode? = node
        while (tempNode != null) {
            tempNode.state.update(win)
            tempNode = tempNode.parent
        }
    }

    /**
     * 在已生成树中找到评分最高的终局节点，再反向构造根到终局的动作路径。
     * 当前实现按模型评分选终局，而不是按访问次数或 UCB 直接选根子节点。
     */
    private fun buildBest(rootNode: MonteCarloTreeNode): MutableList<MonteCarloTreeNode> {
        val result = mutableListOf<MonteCarloTreeNode>()

        var maxNode: MonteCarloTreeNode? = rootNode
        var maxScore = Int.MIN_VALUE.toDouble()
        var maxVisit = Int.MIN_VALUE
        var children = rootNode.children.toList()
        while (children.isNotEmpty()) {
            val list = mutableListOf<MonteCarloTreeNode>()
            for (child in children) {
                if (child.isEnd()) {
                    val score = child.state.score
                    if (score > maxScore) {
                        maxNode = child
                        maxScore = score
                    }
//                    if (child.state.visitCount > maxVisit) {
//                        maxNode = child
//                        maxVisit = child.state.visitCount
//                    }
//                    val ucb = child.state.calcUCB(totalCount)
//                    if (ucb > maxUCB) {
//                        maxUCB = ucb
//                        maxNode = child
//                    }
                }
                list.addAll(child.children)
            }
            children = list
        }

        var tempNode: MonteCarloTreeNode? = maxNode
        while (tempNode != null) {
            result.addFirst(tempNode)
            tempNode = tempNode.parent
        }

//        var node: MonteCarloTreeNode? = rootNode
//        while (node != null) {
//            result.add(node)
//            var maxVisit = Int.MIN_VALUE
//            var maxNode: MonteCarloTreeNode? = null
//            for (child in node.children) {
//                if (child.state.visitCount > maxVisit) {
//                    maxNode = child
//                    maxVisit = child.state.visitCount
//                }
//            }
//            node = maxNode
//        }

        return result
    }

    /**
     * 在 [MCTSArg.endMillisTime] 与迭代预算内搜索最佳动作路径。
     *
     * 对手手牌属于未知信息，当前实现先从克隆战局中清空；这是一种保守近似，并非完整
     * 信息集搜索。等待并行任务时同样受总时间预算限制，超时后返回已收集到的最佳结果。
     */
    fun searchBestNode(
        war: War, arg: MCTSArg
    ): MutableList<MonteCarloTreeNode> {
        val totalMillisTime = arg.endMillisTime - System.currentTimeMillis()
        val newWar = war.clone()
//        因为对手手牌不可知，所以去除模拟，todo 非正确处理方式
        newWar.rival.handArea.cards.clear()
        val newArg = MCTSArg(
            arg.endMillisTime,
            arg.turnCount,
            arg.turnFactor,
            arg.countPerTurn,
            arg.scoreCalculator,
            false
        )
        val endTime = arg.endMillisTime
        val rootNode = MonteCarloTreeNode(newWar, InitAction, newArg)
        val results = Collections.synchronizedList(mutableListOf<MutableList<MonteCarloTreeNode>>())
        val tasks = mutableListOf<CompletableFuture<Void>>()
        val tasker = Function<MonteCarloTreeNode, MutableList<MonteCarloTreeNode>> { newRootNode ->
            var totalCount = 0
            var node: MonteCarloTreeNode

            while (totalCount < newArg.countPerTurn && System.currentTimeMillis() < endTime) {
                node = select(newRootNode, endTime)
                var win: Boolean? = null
                if (!node.isEnd()) {
                    expand(node)?.let {
                        node = it
                        win = simulate(node, newRootNode, endTime)
                    }
                }
                backPropagation(node, win)
                totalCount++
            }

            buildBest(newRootNode)
        }

        if (arg.enableMultiThread) {
            val maxTaskSize = Runtime.getRuntime().availableProcessors()
            val size = rootNode.actions.size
            val countPerTask = ceil(size / maxTaskSize.toDouble()).toInt()
            var index = 0
            while (index < size && System.currentTimeMillis() < endTime) {
                val endIndex = min(index + countPerTask, size)
                val rootNodesList = mutableListOf<MonteCarloTreeNode>()
                val counts = endIndex - index
                val childArg = MCTSArg(
                    arg.endMillisTime,
                    arg.turnCount,
                    arg.turnFactor,
                    floor(arg.countPerTurn / counts.toDouble()).toInt(),
                    arg.scoreCalculator,
                    false
                )
                for (i in index until endIndex) {
                    rootNode.expand(rootNode.actions[i], childArg)?.let { newRootNode ->
                        rootNodesList.add(newRootNode)
                    }
                }
                tasks.add(
                    CompletableFuture.runAsync(
                        LRunnable {
                            for (newRootNode in rootNodesList.reversed()) {
                                results.add(tasker.apply(newRootNode))
                            }
                        }, CALC_THREAD_POOL
                    )
                )
                index = endIndex
            }
        } else {
            results.add(tasker.apply(rootNode))
        }

        if (tasks.isNotEmpty()) {
            try {
                CompletableFuture.allOf(*tasks.toTypedArray()).get(totalMillisTime, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                log.warn(e) { "计算超时" }
            } catch (e: InterruptedException) {
                log.warn(e) { "计算中断" }
            } catch (e: Exception) {
                log.error(e) { "计算异常" }
            }
        }

        var maxScore = Int.MIN_VALUE.toDouble()
        var bestResult: MutableList<MonteCarloTreeNode>? = null
        if (results.isEmpty()) {
            bestResult = buildBest(rootNode)
        } else {
            for (result in results) {
                if (result.isNotEmpty()) {
                    val score = result.last().state.score
                    if (score > maxScore) {
                        maxScore = score
                        bestResult = result
                    }
                }
            }
        }

        return bestResult ?: mutableListOf()
    }

}
