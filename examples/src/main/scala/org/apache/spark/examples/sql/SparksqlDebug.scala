package org.apache.spark.examples.sql

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

import scala.annotation.nowarn


/**
 * @author liujian
 * @create 2025/1/13 11:11
 * @description
 */

object SparksqlDebug {

  def getSparkSession(appName: String): SparkSession = {
    val sparkConf = new SparkConf()
      .setAppName(s"${appName}")
      .setMaster("local[*]")
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.sql.hive.convertMetastoreParquet", "false")
      .set("spark.sql.storeAssignmentPolicy", "LEGACY")
    //.set("datanucleus.schema.autoCreateTables","true")
    SparkSession
      .builder()
      .config(sparkConf)
      .enableHiveSupport()
      .getOrCreate()
  }

  def main(args: Array[String]): Unit = {
    //    val sql =
    //      """
    //        |select
    //        |event,type,carrier,wifi
    //        |from
    //        |bondee_dw.dwd_event_user_general
    //        |where concat(p_day,' ',p_hour) in ('2024-01-05 11','2024-01-05 12','2024-01-05 13')
    //        |""".stripMargin
    val sql =
    """
      |select
      |event,
      |count(distinct uq_id) as uv,
      |count(distinct user_id) as uv1,
      |count(distinct os) as os_uv
      |from
      |bondee_dw.dwd_event_user_general
      |where p_day in ('2024-01-05') and p_hour ='13'
      |group by event
      |""".stripMargin
    val spark = getSparkSession(this.getClass.getSimpleName)
    val df = spark.sql(sql)
    val logical = df.queryExecution.optimizedPlan
    println("========优化逻辑计划========")
    println(logical)
    val sparkPlan = df.queryExecution.sparkPlan
    println("========物理执行计划========")
    println(sparkPlan)
    val plan = df.queryExecution.executedPlan
    println("========执行计划========")
    println(plan)
    val codegen = df.queryExecution.debug.codegen()
    println("========codegen代码========")
    println(codegen)
    println("========explain========")
    df.explain(true)
    df.count()
  }
}
