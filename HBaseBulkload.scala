/*
* @Author: Zeyuan Shang
* @Date:   2016-08-02 12:59:59
* @Last Modified by:   Zeyuan Shang
* @Last Modified time: 2016-08-02 13:00:13
*/

package com.hulu.ap.trinity.hbase

import java.util.UUID

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.permission.FsPermission
import org.apache.hadoop.fs.{Path, FileSystem}
import org.apache.hadoop.hbase.{KeyValue, TableName}
import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.{HFileOutputFormat2, LoadIncrementalHFiles}
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.partition.TotalOrderPartitioner
import org.apache.spark.rdd.RDD
import org.apache.spark.Partitioner
import org.apache.spark.storage.StorageLevel

import scala.collection.JavaConversions._
import scala.reflect.ClassTag

object HBaseBulkload {

  private object HFilePartitioner {
    def apply(conf: Configuration, splits: Array[Array[Byte]], numFilesPerRegionPerFamily: Int) = {
      if (numFilesPerRegionPerFamily == 1)
        new SingleHFilePartitioner(splits)
      else {
        val fraction = 1 max numFilesPerRegionPerFamily min conf.getInt(LoadIncrementalHFiles.MAX_FILES_PER_REGION_PER_FAMILY, 32)
        new MultiHFilePartitioner(splits, fraction)
      }
    }
  }

  protected abstract class HFilePartitioner extends Partitioner {
    def extractKey(n: Any) = n match {
      case (k: Array[Byte], _) => k
      case ((k: Array[Byte], _), _) => k
    }
  }

  private class MultiHFilePartitioner(splits: Array[Array[Byte]], fraction: Int) extends HFilePartitioner {
    override def getPartition(key: Any): Int = {
      val k = extractKey(key)
      val h = (k.hashCode() & Int.MaxValue) % fraction
      for (i <- 1 until splits.length)
        if (Bytes.compareTo(k, splits(i)) < 0) return (i - 1) * fraction + h

      (splits.length - 1) * fraction + h
    }

    override def numPartitions: Int = splits.length * fraction
  }

  private class SingleHFilePartitioner(splits: Array[Array[Byte]]) extends HFilePartitioner {
    override def getPartition(key: Any): Int = {
      val k = extractKey(key)
      for (i <- 1 until splits.length)
        if (Bytes.compareTo(k, splits(i)) < 0) return i - 1

      splits.length - 1
    }

    override def numPartitions: Int = splits.length
  }

  protected def getPartitioner(conf: Configuration, regionLocator: RegionLocator, numFilesPerRegionPerFamily: Int) =
    HFilePartitioner(conf, regionLocator.getStartKeys, numFilesPerRegionPerFamily)

  protected def getPartitionedRdd[C: ClassTag, A: ClassTag](rdd: RDD[(C, A)], family: String, partitioner: HFilePartitioner)(implicit ord: Ordering[C]) = {
    rdd
      .repartitionAndSortWithinPartitions(partitioner)
      .map { case (cell: ((Array[Byte], Array[Byte]), Long), value: Array[Byte]) => (new ImmutableBytesWritable(cell._1._1), new KeyValue(cell._1._1, Bytes.toBytes(family), cell._1._2, cell._2, value)) }
  }

  protected def saveAsHFile(rdd: RDD[(ImmutableBytesWritable, KeyValue)], conf: Configuration, table: Table, regionLocator: RegionLocator, connection: Connection) = {
    val job = Job.getInstance(conf, this.getClass.getName.split('$')(0))

    HFileOutputFormat2.configureIncrementalLoad(job, table, regionLocator)

    // prepare path for HFiles output
    val fs = FileSystem.get(conf)
    val hFilePath = new Path("/tmp", table.getName.getQualifierAsString + "_" + UUID.randomUUID())
    fs.makeQualified(hFilePath)

    try {
      rdd
        .saveAsNewAPIHadoopFile(hFilePath.toString, classOf[ImmutableBytesWritable], classOf[KeyValue], classOf[HFileOutputFormat2], job.getConfiguration)

      // prepare HFiles for incremental load
      // set folders permissions read/write/exec for all
      val rwx = new FsPermission("777")
      def setRecursivePermission(path: Path): Unit = {
        val listFiles = fs.listStatus(path)
        listFiles foreach { f =>
          val p = f.getPath
          fs.setPermission(p, rwx)
          if (f.isDirectory && p.getName != "_tmp") {
            // create a "_tmp" folder that can be used for HFile splitting, so that we can
            // set permissions correctly. This is a workaround for unsecured HBase. It should not
            // be necessary for SecureBulkLoadEndpoint (see https://issues.apache.org/jira/browse/HBASE-8495
            // and http://comments.gmane.org/gmane.comp.java.hadoop.hbase.user/44273)
            FileSystem.mkdirs(fs, new Path(p, "_tmp"), rwx)
            setRecursivePermission(p)
          }
        }
      }
      setRecursivePermission(hFilePath)

      val lih = new LoadIncrementalHFiles(conf)
      // deprecated method still available in hbase 1.0.0, to be replaced with the method below since hbase 1.1.0
      lih.doBulkLoad(hFilePath, new HTable(conf, table.getName))
      // this is available since hbase 1.1.x
      //lih.doBulkLoad(hFilePath, connection.getAdmin, table, regionLocator)
    } finally {
      connection.close()

      fs.deleteOnExit(hFilePath)

      // clean HFileOutputFormat2 stuff
      fs.deleteOnExit(new Path(TotalOrderPartitioner.getPartitionFile(job.getConfiguration)))
    }
  }

  implicit val ordForBytes = new math.Ordering[Array[Byte]] {
    def compare(a: Array[Byte], b: Array[Byte]): Int = {
      Bytes.compareTo(a, b)
    }
  }

  implicit val ordForLong = new math.Ordering[Long] {
    def compare(a: Long, b: Long): Int = {
      if (a < b) {
        1
      } else if (a > b) {
        -1
      } else {
        0
      }
    }
  }

  def toHBaseBulk(mapRdd: RDD[(Array[Byte], Map[String, Array[(String, (String, Long))]])], conf: Configuration, tableNameStr: String, numFilesPerRegionPerFamily: Int = 1) = {
    mapRdd.persist(StorageLevel.DISK_ONLY)

    require(numFilesPerRegionPerFamily > 0)

    val tableName = TableName.valueOf(tableNameStr)
    val connection = ConnectionFactory.createConnection(conf)
    val regionLocator = connection.getRegionLocator(tableName)
    val table = connection.getTable(tableName)

    val families = table.getTableDescriptor.getFamiliesKeys map Bytes.toString
    val partitioner = getPartitioner(conf, regionLocator, numFilesPerRegionPerFamily)

    val rdds = for {
      f <- families
      rdd = mapRdd
        .collect { case (k, m) if m.contains(f) => (k, m(f)) }
        .flatMap {
          case (k, m) =>
            m map { case (h: String, v: (String, Long)) => (((k, Bytes.toBytes(h)), v._2), Bytes.toBytes(v._1)) }
        }
    } yield getPartitionedRdd(rdd, f, partitioner)


    saveAsHFile(rdds.reduce(_ ++ _), conf, table, regionLocator, connection)

    mapRdd.unpersist()
  }
}
