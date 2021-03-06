/**
 * Copyright (c) 2015, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.sparkts

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.nio.ByteBuffer
import java.sql.Timestamp
import java.util.Arrays

import scala.collection.mutable.ArrayBuffer

import breeze.linalg._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

import org.apache.spark._
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.linalg.distributed.{IndexedRow, IndexedRowMatrix, RowMatrix}
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row, SQLContext}
import org.apache.spark.sql.types._
import org.apache.spark.util.StatCounter

import org.joda.time.DateTime
import org.joda.time.DateTimeZone.UTC

/**
 * A lazy distributed collection of univariate series with a conformed time dimension. Lazy in the
 * sense that it is an RDD: it encapsulates all the information needed to generate its elements,
 * but doesn't materialize them upon instantiation. Distributed in the sense that different
 * univariate series within the collection can be stored and processed on different nodes. Within
 * each univariate series, observations are not distributed. The time dimension is conformed in the
 * sense that a single DateTimeIndex applies to all the univariate series. Each univariate series
 * within the RDD has a String key to identify it.
 *
 * @param index The DateTimeIndex shared by all the time series.
 */
class TimeSeriesRDD(val index: DateTimeIndex, parent: RDD[(String, Vector[Double])])
  extends RDD[(String, Vector[Double])](parent) {

  lazy val keys = parent.map(_._1).collect()

  /**
   * Collects the RDD as a local TimeSeries
   */
  def collectAsTimeSeries(): TimeSeries = {
    val elements = collect()
    if (elements.isEmpty) {
      new TimeSeries(index, new DenseMatrix[Double](0, 0), new Array[String](0))
    } else {
      val mat = new DenseMatrix[Double](elements.head._2.length, elements.length)
      val labels = new Array[String](elements.length)
      for (i <- elements.indices) {
        val (label, vec) = elements(i)
        mat(::, i) := vec
        labels(i) = label
      }
      new TimeSeries(index, mat, labels)
    }
  }

  /**
   * Finds a series in the TimeSeriesRDD with the given key.
   */
  def findSeries(key: String): Vector[Double] = {
    filter(_._1 == key).first()._2
  }

  /**
   * Returns a TimeSeriesRDD where each time series is differenced with the given order. The new
   * RDD will be missing the first n date-times.
   */
  def differences(n: Int): TimeSeriesRDD = {
    mapSeries(vec => diff(vec.toDenseVector, n), index.islice(n, index.size))
  }

  /**
   * Returns a TimeSeriesRDD where each time series is quotiented with the given order. The new
   * RDD will be missing the first n date-times.
   */
  def quotients(n: Int): TimeSeriesRDD = {
    mapSeries(UnivariateTimeSeries.quotients(_, n), index.islice(n, index.size))
  }

  /**
   * Returns a return rate series for each time series. Assumes periodic (as opposed to continuously
   * compounded) returns.
   */
  def returnRates(): TimeSeriesRDD = {
    mapSeries(vec => UnivariateTimeSeries.price2ret(vec, 1), index.islice(1, index.size))
  }

  override def filter(f: ((String, Vector[Double])) => Boolean): TimeSeriesRDD = {
    new TimeSeriesRDD(index, super.filter(f))
  }

  /**
   * Keep only time series whose first observation is before or equal to the given start date.
   */
  def filterStartingBefore(dt: DateTime): TimeSeriesRDD = {
    val startLoc = index.locAtDateTime(dt)
    filter { case (key, ts) => UnivariateTimeSeries.firstNotNaN(ts) <= startLoc }
  }

  /**
   * Keep only time series whose last observation is after or equal to the given end date.
   */
  def filterEndingAfter(dt: DateTime): TimeSeriesRDD = {
    val endLoc = index.locAtDateTime(dt)
    filter { case (key, ts) => UnivariateTimeSeries.lastNotNaN(ts) >= endLoc}
  }

  /**
   * Return a TimeSeriesRDD with all instants removed that have a NaN in one of the series.
   */
  def removeInstantsWithNaNs(): TimeSeriesRDD = {
    val zero = new Array[Boolean](index.size)
    def merge(arr: Array[Boolean], rec: (String, Vector[Double])): Array[Boolean] = {
      var i = 0
      while (i < arr.length) {
        arr(i) |= rec._2(i).isNaN
        i += 1
      }
      arr
    }
    def comb(arr1: Array[Boolean], arr2: Array[Boolean]): Array[Boolean] = {
      arr1.zip(arr2).map(x => x._1 || x._2)
    }
    val nans = aggregate(zero)(merge, comb)

    val activeIndices = nans.zipWithIndex.filter(!_._1).map(_._2)
    val newDates = activeIndices.map(index.dateTimeAtLoc)
    val newIndex = DateTimeIndex.irregular(newDates)
    mapSeries(series => {
      new DenseVector[Double](activeIndices.map(x => series(x)))
    }, newIndex)
  }

  /**
   * Returns a TimeSeriesRDD that's a sub-slice of the given series.
   * @param start The start date the for slice.
   * @param end The end date for the slice (inclusive).
   */
  def slice(start: DateTime, end: DateTime): TimeSeriesRDD = {
    val targetIndex = index.slice(start, end)
    val rebaser = TimeSeriesUtils.rebaser(index, targetIndex, Double.NaN)
    new TimeSeriesRDD(targetIndex, mapSeries(rebaser))
  }

  /**
   * Returns a TimeSeriesRDD that's a sub-slice of the given series.
   * @param start The start date the for slice.
   * @param end The end date for the slice (inclusive).
   */
  def slice(start: Long, end: Long): TimeSeriesRDD = {
    slice(new DateTime(start), new DateTime(end))
  }

  /**
   * Fills in missing data (NaNs) in each series according to a given imputation method.
   *
   * @param method "linear", "nearest", "next", or "previous"
   * @return A TimeSeriesRDD with missing observations filled in.
   */
  def fill(method: String): TimeSeriesRDD = {
    mapSeries(UnivariateTimeSeries.fillts(_, method))
  }

  /**
   * Applies a transformation to each time series that preserves the time index of this
   * TimeSeriesRDD.
   */
  def mapSeries[U](f: (Vector[Double]) => Vector[Double]): TimeSeriesRDD = {
    new TimeSeriesRDD(index, map(kt => (kt._1, f(kt._2))))
  }

  /**
   * Applies a transformation to each time series and returns a TimeSeriesRDD with the given index.
   * The caller is expected to ensure that the time series produced line up with the given index.
   */
  def mapSeries[U](f: (Vector[Double]) => Vector[Double], index: DateTimeIndex)
    : TimeSeriesRDD = {
    new TimeSeriesRDD(index, map(kt => (kt._1, f(kt._2))))
  }

  /**
   * Gets stats like min, max, mean, and standard deviation for each time series.
   */
  def seriesStats(): RDD[StatCounter] = {
    map(kt => new StatCounter(kt._2.valuesIterator))
  }

  /**
   * Essentially transposes the time series matrix to create an RDD where each record contains a
   * single instant in time and all the values that correspond to it. Involves a shuffle operation.
   *
   * In the returned RDD, the ordering of values within each record corresponds to the ordering of
   * the time series records in the original RDD. The records are ordered by time.
   */
  def toInstants(nPartitions: Int = -1): RDD[(DateTime, Vector[Double])] = {
    val maxChunkSize = 20

    val dividedOnMapSide = mapPartitionsWithIndex { case (partitionId, iter) =>
      new Iterator[((Int, Int), Vector[Double])] {
        // Each chunk is a buffer of time series
        var chunk = new ArrayBuffer[Vector[Double]]()
        // Current date time.  Gets reset for every chunk.
        var dtLoc: Int = _
        var chunkId: Int = -1

        override def hasNext: Boolean = iter.hasNext || dtLoc < index.size
        override def next(): ((Int, Int), Vector[Double]) = {
          if (chunkId == -1 || dtLoc == index.size) {
            chunk.clear()
            while (chunk.size < maxChunkSize && iter.hasNext) {
              chunk += iter.next()._2
            }
            dtLoc = 0
            chunkId += 1
          }

          val arr = new Array[Double](chunk.size)
          var i = 0
          while (i < chunk.size) {
            arr(i) = chunk(i)(dtLoc)
            i += 1
          }
          dtLoc += 1
          ((dtLoc - 1, partitionId * maxChunkSize + chunkId), new DenseVector(arr))
        }
      }
    }

    // At this point, dividedOnMapSide is an RDD of snippets of full samples that will be
    // assembled on the reduce side.  Each key is a tuple of
    // (date-time, position of snippet in full sample)

    val partitioner = new Partitioner() {
      val nPart = if (nPartitions == -1) parent.partitions.length else nPartitions
      override def numPartitions: Int = nPart
      override def getPartition(key: Any): Int = key.asInstanceOf[(Int, _)]._1 / nPart
    }
    implicit val ordering = new Ordering[(Int, Int)] {
      override def compare(a: (Int, Int), b: (Int, Int)): Int = {
        val dtDiff = a._1 - b._1
        if (dtDiff != 0){
          dtDiff
        } else {
          a._2 - b._2
        }
      }
    }
    val repartitioned = dividedOnMapSide.repartitionAndSortWithinPartitions(partitioner)
    repartitioned.mapPartitions { iter0: Iterator[((Int, Int), Vector[Double])] =>
      new Iterator[(DateTime, Vector[Double])] {
        var snipsPerSample = -1
        var elementsPerSample = -1
        var iter: Iterator[((Int, Int), Vector[Double])] = _

        // Read the first sample specially so that we know the number of elements and snippets
        // for succeeding samples.
        def firstSample(): ArrayBuffer[((Int, Int), Vector[Double])] = {
          var snip = iter0.next()
          val snippets = new ArrayBuffer[((Int, Int), Vector[Double])]()
          val firstDtLoc = snip._1._1

          while (snip != null && snip._1._1 == firstDtLoc) {
            snippets += snip
            snip = if (iter0.hasNext) iter0.next() else null
          }
          iter = if (snip == null) iter0 else Iterator(snip) ++ iter0
          snippets
        }

        def assembleSnips(snips: Iterator[((Int, Int), Vector[Double])])
          : (DateTime, Vector[Double]) = {
          val resVec = DenseVector.zeros[Double](elementsPerSample)
          var dtLoc = -1
          var i = 0
          for (j <- 0 until snipsPerSample) {
            val ((loc, _), snipVec) = snips.next()
            dtLoc = loc
            resVec(i until i + snipVec.length) := snipVec
            i += snipVec.length
          }
          (index.dateTimeAtLoc(dtLoc), resVec)
        }

        override def hasNext: Boolean = {
          if (iter == null) {
            iter0.hasNext
          } else {
            iter.hasNext
          }
        }

        override def next(): (DateTime, Vector[Double]) = {
          if (snipsPerSample == -1) {
            val firstSnips = firstSample()
            snipsPerSample = firstSnips.length
            elementsPerSample = firstSnips.map(_._2.length).sum
            assembleSnips(firstSnips.toIterator)
          } else {
            assembleSnips(iter)
          }
        }
      }
    }
  }

  /**
   * Performs the same operations as toInstants but returns a DataFrame instead.
   *
   * The schema of the DataFrame returned will be a java.sql.Timestamp column named "instant"
   * and Double columns named identically to their keys in the TimeSeriesRDD
   */
  def toInstantsDataFrame(sqlContext: SQLContext, nPartitions: Int = -1): DataFrame = {
    val instantsRDD = toInstants(nPartitions)

    import sqlContext.implicits._

    val result = instantsRDD.map { case (dt, v) =>
      val timestamp = new Timestamp(dt.getMillis())
      (timestamp, v.toArray)
    }.toDF()

    val dataColExpr = keys.zipWithIndex.map { case (key, i) => s"_2[$i] AS $key" }
    val allColsExpr = "_1 AS instant" +: dataColExpr

    result.selectExpr(allColsExpr: _*)
  }

  /**
   * Returns a DataFrame where each row is an observation containing a timestamp, a key, and a
   * value.
   */
  def toObservationsDataFrame(
      sqlContext: SQLContext,
      tsCol: String = "timestamp",
      keyCol: String = "key",
      valueCol: String = "value"): DataFrame = {
    val rowRdd = flatMap { case (key, series) =>
      series.iterator.flatMap { case (i, value) =>
        if (value.isNaN) {
          None
        } else {
          Some(Row(new Timestamp(index.dateTimeAtLoc(i).getMillis), key, value))
        }
      }
    }

    val schema = new StructType(Array(
      new StructField(tsCol, TimestampType),
      new StructField(keyCol, StringType),
      new StructField(valueCol, DoubleType)
    ))
    sqlContext.createDataFrame(rowRdd, schema)
  }

  /**
   * Converts a TimeSeriesRDD into a distributed IndexedRowMatrix, useful to take advantage
   * of Spark MLlib's statistic functions on matrices in a distributed fashion. This is only
   * supported for cases with a uniform time series index. See
   * [[http://spark.apache.org/docs/latest/mllib-data-types.html]] for more information on the
   * matrix data structure
   * @param nPartitions number of partitions, default to -1, which represents the same number
   *                    as currently used for the TimeSeriesRDD
   * @return an equivalent IndexedRowMatrix
   */
  def toIndexedRowMatrix(nPartitions: Int = -1): IndexedRowMatrix = {
    if (!index.isInstanceOf[UniformDateTimeIndex]) {
      throw new UnsupportedOperationException("only supported for uniform indices")
    }
    // each record contains a value per time series, in original order
    // and records are ordered by time
    val uniformIndex = index.asInstanceOf[UniformDateTimeIndex]
    val instants = toInstants(nPartitions)
    val start = uniformIndex.first
    val rows = instants.map { x =>
      val rowIndex = uniformIndex.frequency.difference(start, x._1)
      val rowData = Vectors.dense(x._2.toArray)
      IndexedRow(rowIndex, rowData)
    }
    new IndexedRowMatrix(rows)
  }

  /**
   * Converts a TimeSeriesRDD into a distributed RowMatrix, note that indices in
   * a RowMatrix are not significant, and thus this is a valid operation regardless
   * of the type of time index.  See
   * [[http://spark.apache.org/docs/latest/mllib-data-types.html]] for more information on the
   * matrix data structure
   * @param nPartitions
   * @return an equivalent RowMatrix
   */
  def toRowMatrix(nPartitions: Int = -1): RowMatrix = {
    val instants = toInstants(nPartitions)
    val rows = instants.map { x => Vectors.dense(x._2.toArray) }
    new RowMatrix(rows)
  }

  def compute(split: Partition, context: TaskContext): Iterator[(String, Vector[Double])] = {
    parent.iterator(split, context)
  }

  protected def getPartitions: Array[Partition] = parent.partitions

  /**
   * Writes out the contents of this TimeSeriesRDD to a set of CSV files in the given directory,
   * with an accompanying file in the same directory including the time index.
   */
  def saveAsCsv(path: String): Unit = {
    // Write out contents
    parent.map { case (key, vec) => key + "," + vec.valuesIterator.mkString(",") }
      .saveAsTextFile(path)

    // Write out time index
    val fs = FileSystem.get(new Configuration())
    val os = fs.create(new Path(path + "/timeIndex"))
    val ps = new PrintStream(os)
    ps.println(index.toString)
    ps.close()
  }

  /**
   * Returns a TimeSeriesRDD rebased on top of a new index.  Any timestamps that exist in the new
   * index but not in the existing index will be filled in with NaNs.
   *
   * @param newIndex The DateTimeIndex for the new RDD
   */
  def withIndex(newIndex: DateTimeIndex): TimeSeriesRDD = {
    val rebaser = TimeSeriesUtils.rebaser(index, newIndex, Double.NaN)
    mapSeries(rebaser, newIndex)
  }
}

object TimeSeriesRDD {
  /**
   * Instantiates a TimeSeriesRDD.
   *
   * @param targetIndex DateTimeIndex to conform all the indices to.
   * @param seriesRDD RDD of time series, each with their own DateTimeIndex.
   */
  def timeSeriesRDD(
      targetIndex: UniformDateTimeIndex,
      seriesRDD: RDD[(String, UniformDateTimeIndex, Vector[Double])]): TimeSeriesRDD = {
    val rdd = seriesRDD.map { case (key, index, vec) =>
      val newVec = TimeSeriesUtils.rebase(index, targetIndex, vec, Double.NaN)
      (key, newVec)
    }
    new TimeSeriesRDD(targetIndex, rdd)
  }

  /**
   * Instantiates a TimeSeriesRDD from an RDD of TimeSeries.
   *
   * @param targetIndex DateTimeIndex to conform all the indices to.
   * @param seriesRDD RDD of time series, each with their own DateTimeIndex.
   */
  def timeSeriesRDD(targetIndex: DateTimeIndex, seriesRDD: RDD[TimeSeries]): TimeSeriesRDD = {
    val rdd = seriesRDD.flatMap { series =>
      series.univariateKeyAndSeriesIterator().map { case (key, vec) =>
        (key, TimeSeriesUtils.rebase(series.index, targetIndex, vec, Double.NaN))
      }
    }
    new TimeSeriesRDD(targetIndex, rdd)
  }

  /**
   * Instantiates a TimeSeriesRDD from a DataFrame of observations.
   *
   * @param targetIndex DateTimeIndex to conform all the series to.
   * @param df The DataFrame.
   * @param tsCol The Timestamp column telling when the observation occurred.
   * @param keyCol The string column labeling which string key the observation belongs to..
   * @param valueCol The observed value..
   */
  def timeSeriesRDDFromObservations(
      targetIndex: DateTimeIndex,
      df: DataFrame,
      tsCol: String,
      keyCol: String,
      valueCol: String): TimeSeriesRDD = {
    val rdd = df.select(tsCol, keyCol, valueCol).rdd.map { row =>
      ((row.getString(1), row.getAs[Timestamp](0)), row.getDouble(2))
    }
    implicit val ordering = new Ordering[(String, Timestamp)] {
      override def compare(a: (String, Timestamp), b: (String, Timestamp)): Int = {
        val strCompare = a._1.compareTo(b._1)
        if (strCompare != 0) strCompare else a._2.compareTo(b._2)
      }
    }

    val shuffled = rdd.repartitionAndSortWithinPartitions(new Partitioner() {
      val hashPartitioner = new HashPartitioner(rdd.partitions.size)
      override def numPartitions: Int = hashPartitioner.numPartitions
      override def getPartition(key: Any): Int =
        hashPartitioner.getPartition(key.asInstanceOf[Tuple2[Any, Any]]._1)
    })
    new TimeSeriesRDD(targetIndex, shuffled.mapPartitions { iter =>
      val bufferedIter = iter.buffered
      new Iterator[(String, DenseVector[Double])]() {
        override def hasNext: Boolean = bufferedIter.hasNext

        override def next(): (String, DenseVector[Double]) = {
          // TODO: this will be slow for Irregular DateTimeIndexes because it will result in an
          // O(log n) lookup for each element.
          val series = new Array[Double](targetIndex.size)
          Arrays.fill(series, Double.NaN)
          val first = bufferedIter.next()
          val firstLoc = targetIndex.locAtDateTime(new DateTime(first._1._2, UTC))
          if (firstLoc >= 0) {
            series(firstLoc) = first._2
          }
          val key = first._1._1
          while (bufferedIter.hasNext && bufferedIter.head._1._1 == key) {
            val sample = bufferedIter.next()
            val sampleLoc = targetIndex.locAtDateTime(new DateTime(sample._1._2, UTC))
            if (sampleLoc >= 0) {
              series(sampleLoc) = sample._2
            }
          }
          (key, new DenseVector[Double](series))
        }
      }
    })
  }

  /**
   * Loads a TimeSeriesRDD from a directory containing a set of CSV files and a date-time index.
   */
  def timeSeriesRDDFromCsv(path: String, sc: SparkContext)
    : TimeSeriesRDD = {
    val rdd = sc.textFile(path).map { line =>
      val tokens = line.split(",")
      val series = new DenseVector[Double](tokens.tail.map(_.toDouble))
      (tokens.head, series.asInstanceOf[Vector[Double]])
    }

    val fs = FileSystem.get(new Configuration())
    val is = fs.open(new Path(path + "/timeIndex"))
    val dtIndex = DateTimeIndex.fromString(new BufferedReader(new InputStreamReader(is)).readLine())
    is.close()

    new TimeSeriesRDD(dtIndex, rdd)
  }

  /**
   * Creates a TimeSeriesRDD from rows in a binary format that Python can write to.
   * Not a public API. For use only by the Python API.
   */
  def timeSeriesRDDFromPython(index: DateTimeIndex, pyRdd: RDD[Array[Byte]]): TimeSeriesRDD = {
    new TimeSeriesRDD(index, pyRdd.map { arr =>
      val buf = ByteBuffer.wrap(arr)
      val numChars = buf.getInt()
      val keyChars = new Array[Char](numChars)
      var i = 0
      while (i < numChars) {
        keyChars(i) = buf.getChar()
        i += 1
      }

      val seriesSize = buf.getInt()
      val series = new Array[Double](seriesSize)
      i = 0
      while (i < seriesSize) {
        series(i) = buf.getDouble()
        i += 1
      }
      (new String(keyChars), new DenseVector[Double](series))
    })
  }
}
