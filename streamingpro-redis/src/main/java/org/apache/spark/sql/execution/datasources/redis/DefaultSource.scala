package org.apache.spark.sql.execution.datasources.redis

import com.redislabs.provider.redis.{RedisConfig, RedisEndpoint, RedisNode}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{DataFrame, SQLContext, SaveMode}
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.StructType
import redis.clients.jedis.Protocol
import redis.clients.util.JedisClusterCRC16

/**
  * Created by allwefantasy on 19/11/2017.
  */
class DefaultSource extends RelationProvider with CreatableRelationProvider with DataSourceRegister {


  override def createRelation(sqlContext: SQLContext, parameters: Map[String, String]): BaseRelation = ???

  override def createRelation(sqlContext: SQLContext, mode: SaveMode, parameters: Map[String, String], data: DataFrame): BaseRelation = {
    val relation = InsertRedisRelation(data, parameters, mode)(sqlContext)
    relation.insert(data, mode == SaveMode.Overwrite)
    relation
  }

  override def shortName(): String = "redis"
}


case class InsertRedisRelation(
                                dataFrame: DataFrame,
                                parameters: Map[String, String], mode: SaveMode
                              )(@transient val sqlContext: SQLContext)
  extends BaseRelation with InsertableRelation with Logging {

  val tableName: String = parameters.getOrElse("outputTableName", "PANIC")

  val redisConfig: RedisConfig = {
    new RedisConfig({
      if ((parameters.keySet & Set("host", "port", "auth", "dbNum", "timeout")).size == 0) {
        new RedisEndpoint(sqlContext.sparkContext.getConf)
      } else {
        val host = parameters.getOrElse("host", Protocol.DEFAULT_HOST)
        val port = parameters.getOrElse("port", Protocol.DEFAULT_PORT.toString).toInt
        val auth = parameters.getOrElse("auth", null)
        val dbNum = parameters.getOrElse("dbNum", Protocol.DEFAULT_DATABASE.toString).toInt
        val timeout = parameters.getOrElse("timeout", Protocol.DEFAULT_TIMEOUT.toString).toInt
        new RedisEndpoint(host, port, auth, dbNum, timeout)
      }
    }
    )
  }

  override def schema: StructType = dataFrame.schema

  def getNode(key: String): RedisNode = {
    val slot = JedisClusterCRC16.getSlot(key)
    /* Master only */
    redisConfig.hosts.filter(node => {
      node.startSlot <= slot && node.endSlot >= slot
    }).filter(_.idx == 0)(0)
  }

  def getNode(): RedisNode = {
    redisConfig.hosts.head
  }

  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    parameters.get("insertType") match {
      case Some("listInsert") =>
        val conn = getNode().connect
        val pipeline = conn.pipelined
        val list_data = data.collect().map(f => f.getString(0))
        if (overwrite) {
          pipeline.del(tableName, tableName)
        }
        list_data.foreach(f => pipeline.lpush(tableName, f))
        if (parameters.contains("expire")) {
          pipeline.expire(tableName, time_parse(parameters.get("expire").get))
        }
        pipeline.sync
        conn.close
      case Some("listInsertAsString") =>
        val conn = getNode().connect
        val pipeline = conn.pipelined
        val list_data = data.collect().map(f => f.getString(0)).mkString(parameters.getOrElse("join", ",").toString())
        if (overwrite) {
          pipeline.del(tableName, tableName)
        }
        pipeline.set(tableName, list_data)
        if (parameters.contains("expire")) {
          pipeline.expire(tableName, time_parse(parameters.get("expire").get))
        }
        pipeline.sync
        conn.close
      case None =>
    }
  }

  def time_parse(time: String) = {
    if (time.endsWith("h")) {
      time.split("h").head.toInt * 60 * 60
    } else if (time.endsWith("d")) {
      time.split("d").head.toInt * 60 * 60 * 24
    }
    else if (time.endsWith("m")) {
      time.split("m").head.toInt * 60
    }
    else if (time.endsWith("s")) {
      time.split("s").head.toInt
    }
    else {
      time.toInt
    }

  }
}
