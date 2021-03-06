/* Copyright 2015 Alessandro Maria Rizzi
 * Copyright 2016 Eugenio Gianniti
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package parser

import java.text.SimpleDateFormat
import java.util.Locale

case class Waiting (tm : Map[String, (Long, Long)], vs : Seq[String],
                    tv : Map[String, Seq[String]], tc : Map[String, String],
                    to : Seq[String], cn : Map[String, String],
                    stm : Map[String, (Long, Long)], sb : Map[String, Long], regex : StatusRegex)
  extends Status (tm, vs, tv, tc, to, cn, stm, sb, regex)

class Start (statusRegex : StatusRegex)
  extends Waiting (Map(), Seq(), Map(), Map(), Seq(), Map(), Map(), Map(), statusRegex)

object Start {
  def apply (statusRegex: StatusRegex): Start = new Start(statusRegex)
}

case class Init (name : String, tm : Map[String, (Long, Long)], vs : Seq[String],
                 tv : Map[String, Seq[String]], tc : Map[String, String],
                 to : Seq[String], cn : Map[String, String],
                 stm : Map[String, (Long, Long)], sb : Map[String, Long], regex : StatusRegex)
  extends Status (tm, vs, tv, tc, to, cn, stm, sb, regex)

case class Started (name : String, startTime : Long, endTime : Long,
                    tm : Map[String, (Long, Long)], vs : Seq[String],
                    tv : Map[String, Seq[String]], tc : Map[String, String],
                    to : Seq[String], cn : Map[String, String],
                    stm : Map[String, (Long, Long)], sb : Map[String, Long],
                    regex : StatusRegex)
  extends Status (tm, vs, tv, tc, to, cn, stm, sb, regex)

case class Shuffling (name : String, startTask : Long, endTask : Long,
                      startShuffle : Long, tm : Map[String, (Long, Long)],
                      vs : Seq[String], tv : Map[String, Seq[String]],
                      tc : Map[String, String], to : Seq[String],
                      cn : Map[String, String], stm : Map[String, (Long, Long)],
                      sb : Map[String, Long], regex : StatusRegex)
  extends Status (tm, vs, tv, tc, to, cn, stm, sb, regex)

class CleanStatus (tm : Map[String, (Long, Long)], vs : Seq[String], tv : Map[String, Seq[String]],
                   tc : Map[String, String], to : Seq[String], cn : Map[String, String],
                   stm : Map[String, (Long, Long)], sb : Map[String, Long], regex : StatusRegex)
  extends Status (tm, vs, tv, tc, to, cn, stm, sb, regex)

sealed abstract class Status (val times : Map[String, (Long, Long)], val vertices : Seq[String],
                              val taskToVertices : Map[String, Seq[String]],
                              val taskToContainers : Map[String, String],
                              val taskOrder : Seq[String], val containerToNodes : Map[String, String],
                              val shuffleTimes : Map[String, (Long, Long)],
                              val shuffleBytes : Map[String, Long], statusRegex : StatusRegex) {

  private def parseTime(input : String) =
    new SimpleDateFormat ("yyyy-MM-dd HH:mm:ss,SSS", Locale.ENGLISH).parse (input).getTime

  lazy val clearDuplicateAttempts : Status = {
    def cleanMap[T] (kvs : Map[String, T]) = kvs groupBy {
      case (key, _) =>
        key split "_" dropRight 1 mkString "_"
    } flatMap {
      case (_, partialMap) =>
        val lastAttempt = partialMap.keys maxBy { _.split("_").last }
        partialMap filterKeys lastAttempt.eq
    }
    val cleanTasks = taskOrder groupBy { _ split "_" dropRight 1 mkString "_" } map {
      case (_, list) => list maxBy { _.split("_").last }
    }
    new CleanStatus (cleanMap(times), vertices, taskToVertices, taskToContainers,
      cleanTasks.toSeq, containerToNodes, cleanMap(shuffleTimes), cleanMap(shuffleBytes), statusRegex)
  }

  def next (line : String) : Status = {

    lazy val nextVertices = statusRegex.vertex findFirstMatchIn line match {
      case Some (m) => vertices :+ (m group 1)
      case None => vertices
    }

    lazy val nextTaskToVertices =
      statusRegex.taskToVertex findFirstMatchIn line match {
        case Some (m) =>
          val (vertex, task) = (m group "vertex", m group "task")
          if (taskToVertices contains vertex) {
            val nextSeq = taskToVertices (vertex) :+ task
            taskToVertices + (vertex -> nextSeq)
          } else {
            val nextSeq = Seq (task)
            taskToVertices + (vertex -> nextSeq)
          }
        case None => taskToVertices
      }

    lazy val nextTaskToContainers =
      statusRegex.taskToContainer findFirstMatchIn line match {
        case Some (m) =>
          val (task, container) = (m group "task", m group "container")
          taskToContainers + (task -> container)
        case None => taskToContainers
      }

    lazy val nextTaskOrder =
      statusRegex.taskToContainer findFirstMatchIn line match {
        case Some (m) => taskOrder :+ (m group "task")
        case None => taskOrder
      }

    lazy val nextContainerToNodes =
      statusRegex.receivedContainer findFirstMatchIn line match {
        case Some (m) =>
          val (container, node) = (m group "container", m group "node")
          containerToNodes + (container -> node)
        case None => containerToNodes
      }

    def nextShuffleBytes(taskName: String) =
      statusRegex.shuffleBytes findFirstMatchIn line match {
        case Some (m) =>
          val bytes = { m group "bytes" }.toLong
          val task = try { m group "task" }
          catch { case e: ArrayIndexOutOfBoundsException => taskName }
          shuffleBytes + (task -> bytes)
        case None => shuffleBytes
      }

    this match {
      case Waiting (_, _, _, _, _, _, _, _, _) =>
        statusRegex.init findFirstIn line match {
          case Some(name) =>
            Init (name, times, nextVertices, nextTaskToVertices, nextTaskToContainers,
              nextTaskOrder, nextContainerToNodes, shuffleTimes, shuffleBytes, statusRegex)
          case None =>
            Waiting (times, nextVertices, nextTaskToVertices, nextTaskToContainers,
              nextTaskOrder, nextContainerToNodes, shuffleTimes, shuffleBytes, statusRegex)
        }

      case Init (name, _, _, _, _, _, _, _, _, _) =>
        statusRegex.date findFirstIn line match {
          case Some(when) =>
            val time = parseTime(when)
            Started (name, time, time, times, nextVertices, nextTaskToVertices,
              nextTaskToContainers, nextTaskOrder, nextContainerToNodes,
              shuffleTimes, nextShuffleBytes(name), statusRegex)
          case None =>
            Init (name, times, nextVertices, nextTaskToVertices, nextTaskToContainers,
              nextTaskOrder, nextContainerToNodes, shuffleTimes, nextShuffleBytes(name),
              statusRegex)
        }

      case Started (name, start, end, _, _, _, _, _, _, _, _, _) =>
        lazy val lookForShuffle = {
          val maybeTime = statusRegex.date findFirstIn line
          lazy val nextWithNewTime = {
            val time = maybeTime map parseTime getOrElse end
            Started (name, start, time, times, nextVertices, nextTaskToVertices,
              nextTaskToContainers, nextTaskOrder, nextContainerToNodes,
              shuffleTimes, nextShuffleBytes(name), statusRegex)
          }
          statusRegex.startingShuffle findFirstMatchIn line match {
            case Some (_) =>
              val time = parseTime(maybeTime.get)
              Shuffling (name, start, time, time, times, nextVertices,
                nextTaskToVertices, nextTaskToContainers,
                nextTaskOrder, nextContainerToNodes, shuffleTimes,
                nextShuffleBytes(name), statusRegex)
            case None => nextWithNewTime
          }
        }
        if (line.isEmpty) Waiting (times + (name -> (start, end)), vertices,
          taskToVertices, taskToContainers, taskOrder, containerToNodes,
          shuffleTimes, shuffleBytes, statusRegex)
        else lookForShuffle

      case Shuffling (name, startTask, endTask, startShuffle, _, _, _, _, _, _, _, _, _) =>
        val maybeTime = statusRegex.date findFirstIn line
        lazy val lookForEnding = {
          lazy val nextWithNewTime = {
            val time = maybeTime map parseTime getOrElse endTask
            Shuffling(name, startTask, time, startShuffle, times,
              nextVertices, nextTaskToVertices, nextTaskToContainers,
              nextTaskOrder, nextContainerToNodes, shuffleTimes,
              nextShuffleBytes(name), statusRegex)
          }
          if (line.isEmpty) Waiting(times + (name -> (startTask, endTask)), vertices,
            taskToVertices, taskToContainers, taskOrder, containerToNodes,
            shuffleTimes + (name -> (startShuffle, endTask)), shuffleBytes, statusRegex)
          else nextWithNewTime
        }
        statusRegex.endingShuffle findFirstMatchIn line match {
          case Some (_) =>
            val time = parseTime(maybeTime.get)
            Started (name, startTask, time, times, nextVertices,
              nextTaskToVertices, nextTaskToContainers, nextTaskOrder,
              nextContainerToNodes,
              shuffleTimes + (name -> (startShuffle, time)),
              nextShuffleBytes(name), statusRegex)
          case None => lookForEnding
        }

      case _ =>
        // To avoid warnings for possible failures due to CleanStatus not being matched
        this
    }
  }
}
