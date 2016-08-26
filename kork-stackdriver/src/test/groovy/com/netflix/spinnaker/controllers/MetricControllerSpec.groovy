/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.controllers

import com.netflix.spinnaker.controllers.MetricController

import com.netflix.spectator.api.ArrayTagSet;
import com.netflix.spectator.api.DefaultCounter;
import com.netflix.spectator.api.DefaultId;
import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Meter;
import com.netflix.spectator.api.Measurement;
import com.netflix.spectator.api.Tag;

import java.util.regex.Pattern
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

import spock.lang.Shared
import spock.lang.Specification


class TestMetric extends Metric {
  Id myId
  Measurement[] myMeasurements
  boolean expired = false

  TestMetric(id, measures) {
    myId = id
    myMeasurements = measures
  }
  Id id() { return myId }
  Iterable<Measurement> measure() {
    return Arrays.asList(myMeasurements)
  }
  boolean hasExpired() { return expired }
}

List<Measurement> toMeasurementList(array) {
 List<Measurment> list = Arrays.toList(array)
 return list
}

static Map<Id, List<Measurement>> toIdMap(map) {
  return map
}

class MetricControllerSpec extends Specification {
  @Shared
  MetricController controller

  def setup() {
    millis = 12345L
    clock = Clock.fixed(Instance.ofEpochMilli(millis), ZoneId.systemDefault())

    registry = new DefaultRegistry(clock)
    controller = new MetricController()
    idA = DefaultId("idA")
    idB = DefaultId("idB")
    idAXY = DefaultId("idA", [BasicTag("tagA", "X"), BasicTag("tagB", "Y")])
    idAYX = DefaultId("idA", [BasicTag("tagA", "Y"), BasicTag("tagB", "X")])
    idAXZ = DefaultId("idA", [BasicTag("tagA", "Y"), BasicTag("tagZ", "Z")])
    idBXY = DefaultId("idB", [BasicTag("tagA", "X"), BasicTag("tagB", "Y")])

    measureAXY = new Measurement(idAXY, 11, 11.11)
    measureAXY2 = new Measurement(idAYX, 10, 10.10)
    measureAYX = new Measurement(idAYX, 12, 12.12)
    measureAXZ = new Measurement(idAXZ, 13, 13.13)
    meterA = new TestMeter("ignoreA", [measureAXY, measureAYX, measureAXZ])
    meterA2 = new TestMeter("ignoreA2", [measureAXY2])
    meterB = new TestMeter("ignoreB", [measureBXY])
    null
  }

  void "collectDisjointValues"() {
    given:
      collection = new Map<Id, List<Measurement>>();
      namePattern = null
      tagNamePattern = null
      tagValuePattern = null

    when:
      MetricController.collectValues(
        collection, meterA, namePattern, tagNamePattern, tagValuePattern)

    then:
      collection == toIdMap([idAXY : [measureAXY],
                             idAYX : [measureAYX],
                             idAYZ : [measureAYZ]])
  }

  void "collectRepeatedValues"() {
    given:
      collection = new Map<Id, List<Measurement>>();
      namePattern = null
      tagNamePattern = null
      tagValuePattern = null

    when:
      MetricController.collectValues(
        collection, meterA, namePattern, tagNamePattern, tagValuePattern)
      MetricController.collectValues(
        collection, meterA2, namePattern, tagNamePattern, tagValuePattern)

    then:
      collection == toIdMap([idAXY : [measureAXY, measureAXY2],
                             idAYX : [measureAYX],
                             idAYZ : [measureAYZ]])
  }

  void "collectSimilarMetrics"() {
    given:
      collection = new Map<Id, List<Measurement>>();
      namePattern = null
      tagNamePattern = null
      tagValuePattern = null

    when:
      MetricController.collectValues(
        collection, meterA, namePattern, tagNamePattern, tagValuePattern)
      MetricController.collectValues(
        collection, meterB, namePattern, tagNamePattern, tagValuePattern)

    then:
      collection == toIdMap([idAXY : [measureAXY],
                             idBXY : [measureBXY],
                             idAYX : [measureAYX],
                             idAYZ : [measureAYZ]])
  }

  void "collectFilteredName"() {
    given:
      collection = new Map<Id, List<Measurement>>();
      namePattern =  Pattern("idA")
      tagNamePattern = null
      tagValuePattern = null

    when:
      MetricController.collectValues(
        collection, meterA, namePattern, tagNamePattern, tagValuePattern)
      MetricController.collectValues(
        collection, meterB, namePattern, tagNamePattern, tagValuePattern)

    then:
      collection == toIdMap([idAXY : [measureAXY],
                             idAYX : [measureAYX],
                             idAXZ : [measureAXZ]])
  }

  void "collectFilteredTagName"() {
    given:
      collection = new Map<Id, List<Measurement>>();
      namePattern = null
      tagNamePattern = Pattern("tagZ")
      tagValuePattern = null

    when:
      MetricController.collectValues(
        collection, meterA, namePattern, tagNamePattern, tagValuePattern)

    then:
      collection == toIdMap([idAYZ : [measureAYZ]])
  }

  void "collectFilteredTagValue"() {
    given:
      collection = new Map<Id, List<Measurement>>();
      namePattern = null
      tagNamePattern = null
      tagValuePattern = Pattern("X")

    when:
      MetricController.collectValues(
        collection, meterA, namePattern, tagNamePattern, tagValuePattern)

    then:
      collection == toIdMap([idAXY : [measureAXY],
                             idAYX : [measureAYX]])
  }

  void "collectNotFound"() {
    given:
      collection = new Map<Id, List<Measurement>>();
      namePattern = null
      tagNamePattern = Pattern("tagZ")
      tagValuePattern = Pattern("X")

    when:
      MetricController.collectValues(
        collection, meterA, namePattern, tagNamePattern, tagValuePattern)

    then:
      collection == toIdMap([:])
  }
}
