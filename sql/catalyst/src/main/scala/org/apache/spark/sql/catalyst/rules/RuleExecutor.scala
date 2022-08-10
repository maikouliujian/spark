/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.rules

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.QueryPlanningTracker
import org.apache.spark.sql.catalyst.errors.TreeNodeException
import org.apache.spark.sql.catalyst.trees.TreeNode
import org.apache.spark.sql.catalyst.util.DateTimeConstants.NANOS_PER_SECOND
import org.apache.spark.sql.catalyst.util.sideBySide
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.util.Utils

object RuleExecutor {
  protected val queryExecutionMeter = QueryExecutionMetering()

  /** Dump statistics about time spent running specific rules. */
  def dumpTimeSpent(): String = {
    queryExecutionMeter.dumpTimeSpent()
  }

  /** Resets statistics about time spent running specific rules */
  def resetMetrics(): Unit = {
    queryExecutionMeter.resetMetrics()
  }

  def getCurrentMetrics(): QueryExecutionMetrics = {
    queryExecutionMeter.getMetrics()
  }
}

abstract class RuleExecutor[TreeType <: TreeNode[_]] extends Logging {

  /**
   * An execution strategy for rules that indicates the maximum number of executions. If the
   * execution reaches fix point (i.e. converge) before maxIterations, it will stop.
   */
  abstract class Strategy {

    /** The maximum number of executions. */
    /** 执行最大迭代次数 */
    def maxIterations: Int

    /** Whether to throw exception when exceeding the maximum number. */
    /** 超过最大次数后是否抛出异常 */
    def errorOnExceed: Boolean = false

    /** The key of SQLConf setting to tune maxIterations */
    /** SQLConf 中用来配置最大迭代次数的键 */
    def maxIterationsSetting: String = null
  }

  /** A strategy that is run once and idempotent. */
  case object Once extends Strategy { val maxIterations = 1 }

  /**
   * A strategy that runs until fix point or maxIterations times, whichever comes first.
   * Especially, a FixedPoint(1) batch is supposed to run only once.
   */
  //todo 如果在maxIterations中无法解决该计划，analyzer将抛出异常以通知用户增加SQLConf.ANALYZER_MAX_ITERATIONS 的值
  //todo SQLConf.ANALYZER_MAX_ITERATIONS 代表的是配置项spark.sql.analyzer.maxIterations
  //todo FixedPoint 代表运行到定点或最大迭代次数的策略，以先到者为准。
  case class FixedPoint(
    override val maxIterations: Int,
    override val errorOnExceed: Boolean = false,
    override val maxIterationsSetting: String = null) extends Strategy

  /** A batch of rules. */
  protected case class Batch(name: String, strategy: Strategy, rules: Rule[TreeType]*)

  /** Defines a sequence of rule batches, to be overridden by the implementation. */
  protected def batches: Seq[Batch]

  /** Once batches that are blacklisted in the idempotence checker */
  protected val blacklistedOnceBatches: Set[String] = Set.empty

  /**
   * Defines a check function that checks for structural integrity of the plan after the execution
   * of each rule. For example, we can check whether a plan is still resolved after each rule in
   * `Optimizer`, so we can catch rules that return invalid plans. The check function returns
   * `false` if the given plan doesn't pass the structural integrity check.
   */
  protected def isPlanIntegral(previousPlan: TreeType, currentPlan: TreeType): Boolean = true

  /**
   * Util method for checking whether a plan remains the same if re-optimized.
   */
  private def checkBatchIdempotence(batch: Batch, plan: TreeType): Unit = {
    val reOptimized = batch.rules.foldLeft(plan) { case (p, rule) => rule(p) }
    if (!plan.fastEquals(reOptimized)) {
      val message =
        s"""
           |Once strategy's idempotence is broken for batch ${batch.name}
           |${sideBySide(plan.treeString, reOptimized.treeString).mkString("\n")}
          """.stripMargin
      throw new TreeNodeException(reOptimized, message, null)
    }
  }

  /**
   * Executes the batches of rules defined by the subclass, and also tracks timing info for each
   * rule using the provided tracker.
   * @see [[execute]]
   */
  def executeAndTrack(plan: TreeType, tracker: QueryPlanningTracker): TreeType = {
    QueryPlanningTracker.withTracker(tracker) {
      //todo 执行
      execute(plan)
    }
  }

  /**
   * Executes the batches of rules defined by the subclass. The batches are executed serially
   * using the defined execution strategy. Within each batch, rules are also executed serially.
   */
    //todo /**
    // * 执行子类定义的规则批。
    // * 规则批会使用定义好的执行策略串行执行。
    // * 在每个批中，规则也会连续执行。
    // */
  def execute(plan: TreeType): TreeType = {
    var curPlan = plan
      //todo 这个是用于统计一些运行信息的，比如花了多少时间，跑了规则批等等
    val queryExecutionMetrics = RuleExecutor.queryExecutionMeter
      //todo 当规则或者规则批被应用后，日志记录一下逻辑计划的改变
    val planChangeLogger = new PlanChangeLogger()
    //todo 还是那个查询计划追踪器，参见第一讲
    val tracker: Option[QueryPlanningTracker] = QueryPlanningTracker.get
      //todo 执行前的度量信息
    val beforeMetrics = RuleExecutor.getCurrentMetrics()

    // Run the structural integrity checker against the initial input
      //todo 这个是用来检查逻辑计划的完整性的，主要是通过校验命名表达式（NamedExpression）的 ID （exprId）是否唯一不重复
    if (!isPlanIntegral(plan, plan)) {
      val message = "The structural integrity of the input plan is broken in " +
        s"${this.getClass.getName.stripSuffix("$")}."
      throw new TreeNodeException(plan, message, null)
    }

      //todo 每一个batch有多个rules，循环【每一个batch的所有rule作用于plan，直到不再变化】,开始下一个batch
    batches.foreach { batch =>
      // todo 规则批从哪一个逻辑计划开始
      val batchStartPlan = curPlan
      // todo 迭代次数
      var iteration = 1
      // todo 记录当前的逻辑计划
      var lastPlan = curPlan
      // todo 表示是否继续循环的标识
      var continue = true
      // Run until fix point (or the max number of iterations as specified in the strategy.
      //todo  运行到固定点（或者执行策略中规定的最大迭代次数）
      while (continue) {
         curPlan = batch.rules.foldLeft(curPlan) {
          case (plan, rule) =>
            //todo 规则运行的开始时间
            val startTime = System.nanoTime()
            // todo 对逻辑计划应用规则【核心逻辑】
            val result = rule(plan)
            // todo 规则的运行时间
            val runTime = System.nanoTime() - startTime
            //todo 不等说明规则起了作用，是有效的
            val effective = !result.fastEquals(plan)

            if (effective) {
              //todo //记录一下有效的规则数量queryExecutionMetrics.incNumEffectiveExecution(rule.ruleName)
              //              // 记录一下有效的运行时间queryExecutionMetrics.incTimeEffectiveExecutionBy(rule.ruleName, runTime)
              //              // 日志打印一下规则是怎么变的
              queryExecutionMetrics.incNumEffectiveExecution(rule.ruleName)
              queryExecutionMetrics.incTimeEffectiveExecutionBy(rule.ruleName, runTime)
              planChangeLogger.logRule(rule.ruleName, plan, result)
            }
            //todo 记录一下执行时间
            queryExecutionMetrics.incExecutionTimeBy(rule.ruleName, runTime)
            //todo 记录一下跑了多少个规则
            queryExecutionMetrics.incNumExecution(rule.ruleName)
            //todo  使用查询计划追踪器记录一下一些和时间相关的信息
            // Record timing information using QueryPlanningTracker
            tracker.foreach(_.recordRuleInvocation(rule.ruleName, runTime, effective))

            // Run the structural integrity checker against the plan after each rule.
            //todo 跑完每个规则后都要检查一下逻辑计划的完整性
            if (!isPlanIntegral(plan, result)) {
              val message = s"After applying rule ${rule.ruleName} in batch ${batch.name}, " +
                "the structural integrity of the plan is broken."
              throw new TreeNodeException(result, message, null)
            }

            result
        }
        //todo 迭代次数加 1
        iteration += 1
        if (iteration > batch.strategy.maxIterations) {
          //todo 只会日志打印那些运行次数大于 1 次的规则
          // Only log if this is a rule that is supposed to run more than once.
          if (iteration != 2) {
            val endingMsg = if (batch.strategy.maxIterationsSetting == null) {
              "."
            } else {
              s", please set '${batch.strategy.maxIterationsSetting}' to a larger value."
            }
            val message = s"Max iterations (${iteration - 1}) reached for batch ${batch.name}" +
              s"$endingMsg"
            if (Utils.isTesting || batch.strategy.errorOnExceed) {
              throw new TreeNodeException(curPlan, message, null)
            } else {
              logWarning(message)
            }
          }
          // Check idempotence for Once batches.
          //todo 检查单次规则批的幂等性
          if (batch.strategy == Once &&
            Utils.isTesting && !blacklistedOnceBatches.contains(batch.name)) {
            checkBatchIdempotence(batch, curPlan)
          }
          continue = false
        }

        // todo 如果在一个batch的所有规则的作用下逻辑计划没变，此时就需要退出循环进行下一batch的计算
        if (curPlan.fastEquals(lastPlan)) {
          logTrace(
            s"Fixed point reached for batch ${batch.name} after ${iteration - 1} iterations.")
          continue = false
        }
        lastPlan = curPlan
      }
      //todo 日志打印一下逻辑计划从开始到现在是怎么变的
      planChangeLogger.logBatch(batch.name, batchStartPlan, curPlan)
    }
      //todo 日志打印一些度量信息
    planChangeLogger.logMetrics(RuleExecutor.getCurrentMetrics() - beforeMetrics)

    curPlan
  }

  private class PlanChangeLogger {

    private val logLevel = SQLConf.get.optimizerPlanChangeLogLevel

    private val logRules = SQLConf.get.optimizerPlanChangeRules.map(Utils.stringToSeq)

    private val logBatches = SQLConf.get.optimizerPlanChangeBatches.map(Utils.stringToSeq)

    def logRule(ruleName: String, oldPlan: TreeType, newPlan: TreeType): Unit = {
      if (logRules.isEmpty || logRules.get.contains(ruleName)) {
        def message(): String = {
          s"""
             |=== Applying Rule ${ruleName} ===
             |${sideBySide(oldPlan.treeString, newPlan.treeString).mkString("\n")}
           """.stripMargin
        }

        logBasedOnLevel(message)
      }
    }

    def logBatch(batchName: String, oldPlan: TreeType, newPlan: TreeType): Unit = {
      if (logBatches.isEmpty || logBatches.get.contains(batchName)) {
        def message(): String = {
          if (!oldPlan.fastEquals(newPlan)) {
            s"""
               |=== Result of Batch ${batchName} ===
               |${sideBySide(oldPlan.treeString, newPlan.treeString).mkString("\n")}
            """.stripMargin
          } else {
            s"Batch ${batchName} has no effect."
          }
        }

        logBasedOnLevel(message)
      }
    }

    def logMetrics(metrics: QueryExecutionMetrics): Unit = {
      val totalTime = metrics.time / NANOS_PER_SECOND.toDouble
      val totalTimeEffective = metrics.timeEffective / NANOS_PER_SECOND.toDouble
      val message =
        s"""
           |=== Metrics of Executed Rules ===
           |Total number of runs: ${metrics.numRuns}
           |Total time: ${totalTime} seconds
           |Total number of effective runs: ${metrics.numEffectiveRuns}
           |Total time of effective runs: ${totalTimeEffective} seconds
        """.stripMargin

      logBasedOnLevel(message)
    }

    private def logBasedOnLevel(f: => String): Unit = {
      logLevel match {
        case "TRACE" => logTrace(f)
        case "DEBUG" => logDebug(f)
        case "INFO" => logInfo(f)
        case "WARN" => logWarning(f)
        case "ERROR" => logError(f)
        case _ => logTrace(f)
      }
    }
  }
}
