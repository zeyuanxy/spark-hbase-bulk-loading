# spark-hbase-bulk-loading
A single Scala file providing bulk-loading for HBase in Spark.

# Usage

Construct a RDD whose type is **[(Array[Byte], Map[String, Array[(String, (String, Long))]])]**, which is **[ROW_KEY, Map[COLUMN_FAMILY, Array[(COLUMN_QUALIFIER, (VALUE, TIMESTAMP))]])]**, then call **toHBaseBulk** to go.

# Reference
[HBase RDD](https://github.com/unicredit/hbase-rdd)
