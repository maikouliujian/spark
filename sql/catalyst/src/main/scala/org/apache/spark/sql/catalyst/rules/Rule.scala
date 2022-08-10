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
import org.apache.spark.sql.catalyst.trees.TreeNode

abstract class Rule[TreeType <: TreeNode[_]] extends Logging {

  /** Name for this rule, automatically inferred based on class name. */
  /** todo 当前规则的名称，根据类名自动推断 */
  val ruleName: String = {
    val className = getClass.getName
    if (className endsWith "$") className.dropRight(1) else className
  }
  /** todo 最核心的应用函数 */
  //todo 其中 TreeType 代表的是 TreeNode 的任意一个子类，而 TreeNode 则是 Spark SQL 中所有树型结构的父类，
  // 像之前源码中的逻辑计划（LogicalPlan）就是它的一个子类。
  def apply(plan: TreeType): TreeType
}
