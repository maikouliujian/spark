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

package org.apache.spark.scheduler

import org.apache.spark.util.ListenerBus

/**
 * A [[SparkListenerEvent]] bus that relays [[SparkListenerEvent]]s to its listeners
  * 用于将SparkListenerEvent类型的事件投递到SparkListenerInterface类型的监听器。
 */
private[spark] trait SparkListenerBus
  extends ListenerBus[SparkListenerInterface, SparkListenerEvent] {

  // 通过对SparkListenerEvent事件的匹配，执行SparkListenerInterface监听器的相应方法
  protected override def doPostEvent(
      listener: SparkListenerInterface,
      event: SparkListenerEvent): Unit = {
    // 对事件进行匹配，分别调用监听器的不同方法进行处理
    event match {
      case stageSubmitted: SparkListenerStageSubmitted =>
        listener.onStageSubmitted(stageSubmitted)
      case stageCompleted: SparkListenerStageCompleted =>
        listener.onStageCompleted(stageCompleted)
      case jobStart: SparkListenerJobStart =>
        listener.onJobStart(jobStart)
      case jobEnd: SparkListenerJobEnd =>
        listener.onJobEnd(jobEnd)
      case taskStart: SparkListenerTaskStart =>
        listener.onTaskStart(taskStart)
      case taskGettingResult: SparkListenerTaskGettingResult =>
        listener.onTaskGettingResult(taskGettingResult)
      case taskEnd: SparkListenerTaskEnd =>
        listener.onTaskEnd(taskEnd)
      case environmentUpdate: SparkListenerEnvironmentUpdate =>
        listener.onEnvironmentUpdate(environmentUpdate)
      case blockManagerAdded: SparkListenerBlockManagerAdded =>
        listener.onBlockManagerAdded(blockManagerAdded)
      case blockManagerRemoved: SparkListenerBlockManagerRemoved =>
        listener.onBlockManagerRemoved(blockManagerRemoved)
      case unpersistRDD: SparkListenerUnpersistRDD =>
        listener.onUnpersistRDD(unpersistRDD)
      case applicationStart: SparkListenerApplicationStart =>
        listener.onApplicationStart(applicationStart)
      case applicationEnd: SparkListenerApplicationEnd =>
        listener.onApplicationEnd(applicationEnd)
      case metricsUpdate: SparkListenerExecutorMetricsUpdate =>
        listener.onExecutorMetricsUpdate(metricsUpdate)
      case executorAdded: SparkListenerExecutorAdded =>
        listener.onExecutorAdded(executorAdded)
      case executorRemoved: SparkListenerExecutorRemoved =>
        listener.onExecutorRemoved(executorRemoved)
      case blockUpdated: SparkListenerBlockUpdated =>
        listener.onBlockUpdated(blockUpdated)
      case logStart: SparkListenerLogStart => // ignore event log metadata
      case _ => listener.onOtherEvent(event)
    }
  }

}
