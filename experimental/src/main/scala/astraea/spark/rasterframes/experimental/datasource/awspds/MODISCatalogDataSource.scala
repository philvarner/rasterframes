/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright 2018 Astraea. Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     [http://www.apache.org/licenses/LICENSE-2.0]
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 *
 */

package astraea.spark.rasterframes.experimental.datasource.awspds

import java.net.URI
import java.time.LocalDate
import java.time.temporal.ChronoUnit

import astraea.spark.rasterframes.util.withResource
import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.fs.{FileSystem, Path ⇒ HadoopPath}
import org.apache.hadoop.io.IOUtils
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.sources.{BaseRelation, DataSourceRegister, RelationProvider}


/**
 * DataSource over the catalog of AWS PDS for MODIS MCD43A4 Surface Reflectance data product
 * Param
 *
 * See https://docs.opendata.aws/modis-pds/readme.html for details
 *
 * @since 5/4/18
 */
class MODISCatalogDataSource extends DataSourceRegister with RelationProvider with LazyLogging  {
  override def shortName(): String = MODISCatalogDataSource.NAME
  /**
     * Create a MODIS catalog data source.
     * @param sqlContext spark stuff
     * @param parameters optional parameters are:
     *                   `start`-start date for first scene files to fetch. default: "2013-01-01"
     *                   `end`-end date for last scene file to fetch. default: today's date - 7 days
     *                    `useBlacklist`-if false, ignore list of known missing scene files on AWS
     */
  override def createRelation(sqlContext: SQLContext, parameters: Map[String, String]): BaseRelation = {
    require(parameters.get("path").isEmpty, "MODISCatalogDataSource doesn't support specifying a path. Please use `load()`.")
    val start = parameters.get("start").map(LocalDate.parse).getOrElse(LocalDate.of(2013, 1, 1))
    val end = parameters.get("end").map(LocalDate.parse).getOrElse(LocalDate.now().minusDays(7))
    val useBlacklist = parameters.get("useBlacklist").forall(_.toBoolean)

    val conf = sqlContext.sparkContext.hadoopConfiguration
    implicit val fs = FileSystem.get(conf)
    val path = MODISCatalogDataSource.sceneListFile(start, end, useBlacklist)
    MODISCatalogRelation(sqlContext, path)
  }
}

object MODISCatalogDataSource extends LazyLogging with ResourceCacheSupport {
  val NAME = "modis-catalog"
  val MCD43A4_BASE = "https://modis-pds.s3.amazonaws.com/MCD43A4.006/"
  override def maxCacheFileAgeHours: Int = Int.MaxValue

  // List of missing days
  private val blacklist = Seq[String](
    //"2018-05-06"
  )

  private def sceneFiles(start: LocalDate, end: LocalDate, useBlacklist: Boolean) = {
    val numDays = ChronoUnit.DAYS.between(start, end).toInt
    for {
      dayOffset <- 0 to numDays
      currDay = start.plusDays(dayOffset)
      if !useBlacklist || !blacklist.contains(currDay.toString)
    } yield URI.create(s"$MCD43A4_BASE${currDay}_scenes.txt")
  }

  private def sceneListFile(start: LocalDate, end: LocalDate, useBlacklist: Boolean)(implicit fs: FileSystem): HadoopPath = {
    logger.info(s"Using '$cacheDir' for scene file cache")
    val basename = new HadoopPath(s"$NAME-$start-to-$end.csv")
    cachedFile(basename).getOrElse {
      val retval = cacheName(Right(basename))
      val inputs = sceneFiles(start, end, useBlacklist).par
        .flatMap(cachedURI(_))
        .toArray
      try {
        fs.concat(retval, inputs)
      }
      catch {
        case _ :UnsupportedOperationException ⇒
          // concat not supporty by RawLocalFileSystem
          withResource(fs.create(retval)) { out ⇒
            inputs.foreach { p ⇒
              withResource(fs.open(p)) { in ⇒
                IOUtils.copyBytes(in, out, 1 << 15)
              }
            }
          }
      }
      retval
    }
  }
}
