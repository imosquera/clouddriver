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

// package com.netflix.spinnaker.controllers;

import com.netflix.spinnaker.controllers.MetricController;

import com.netflix.spectator.api.BasicTag;
import com.netflix.spectator.api.Clock;
import com.netflix.spectator.api.DefaultCounter;
import com.netflix.spectator.api.DefaultTimer;
import com.netflix.spectator.api.DefaultId as ApiDefaultId;
import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.Meter;
import com.netflix.spectator.api.Measurement;
import com.netflix.spectator.api.Tag;

import java.util.regex.Pattern

import spock.lang.Shared
import spock.lang.Specification


class MetricControllerSpec extends Specification {
  static class TestMeter extends Meter {
    Id myId
    Measurement[] myMeasurements
    boolean expired = false

    TestMeter(name, measures) {
      myId = new ApiDefaultId(name)
      myMeasurements = measures
    }
    Id id() { return myId }
    Iterable<Measurement> measure() {
      return Arrays.asList(myMeasurements)
    }
    boolean hasExpired() { return expired }
  }

  MetricController controller = new MetricController()
  Id idA = new ApiDefaultId("idA")
  Id idB = new ApiDefaultId("idB")
  Id idAXY = idA.withTag("tagA", "X").withTag("tagB", "Y")
  Id idAYX = idA.withTag("tagA", "Y").withTag("tagB", "X")
  Id idAXZ = idA.withTag("tagA", "Y").withTag("tagZ", "Z")
  Id idBXY = idB.withTag("tagA", "X").withTag("tagB", "Y")

  Measurement measureAXY = new Measurement(idAXY, 11, 11.11)
  Measurement measureAXY2 = new Measurement(idAXY, 20, 20.20)
  Measurement measureAYX = new Measurement(idAYX, 12, 12.12)
  Measurement measureAXZ = new Measurement(idAXZ, 13, 13.13)
  Measurement measureBXY = new Measurement(idBXY, 50, 50.50)

  Meter meterA = new TestMeter("ignoreA", [measureAXY, measureAYX, measureAXZ])
  Meter meterA2 = new TestMeter("ignoreA2", [measureAXY2])
  Meter meterB = new TestMeter("ignoreB", [measureBXY])

  long millis = 12345L
  Clock clock = new Clock() {
    long  wallTime() { return millis; }
    long monotonicTime() { return millis; }
  }

  HashMap<Id, List<Measurement>> collection
  Pattern namePattern
  Pattern tagNamePattern
  Pattern tagValuePattern

  void setup() {
    collection = new HashMap<Id, List<Measurement>>();
    namePattern = null
    tagNamePattern = null
    tagValuePattern = null
  }

  List<Measurement> toMeasurementList(array) {
    List<Measurement> list = Arrays.toList(array)
    return list
  }

  static Map<Id, List<Measurement>> toIdMap(map) {
    return new HashMap(map)
  }

  void "collectDisjointValues"() {
    when:
      List<Id> added = MetricController.collectValues(
        collection, meterA, namePattern, tagNamePattern, tagValuePattern)

    then:
      collection ==  [(idAXY) : [measureAXY],
                      (idAYX) : [measureAYX],
                      (idAXZ) : [measureAXZ]]
  }

  void "collectRepeatedValues"() {
    when:
      MetricController.collectValues(
        collection, meterA, namePattern, tagNamePattern, tagValuePattern)
      MetricController.collectValues(
        collection, meterA2, namePattern, tagNamePattern, tagValuePattern)

    then:
      collection == [(idAXY) : [measureAXY, measureAXY2],
                     (idAYX) : [measureAYX],
                     (idAXZ) : [measureAXZ]]
  }

  void "collectSimilarMetrics"() {
    when:
      MetricController.collectValues(
        collection, meterA, namePattern, tagNamePattern, tagValuePattern)
      MetricController.collectValues(
        collection, meterB, namePattern, tagNamePattern, tagValuePattern)

    then:
      collection == [(idAXY) : [measureAXY],
                     (idBXY) : [measureBXY],
                     (idAYX) : [measureAYX],
                     (idAXZ) : [measureAXZ]]
  }

  void "collectFilteredName"() {
    given:
      namePattern = Pattern.compile("idA")

    when:
      MetricController.collectValues(
        collection, meterA, namePattern, tagNamePattern, tagValuePattern)
      MetricController.collectValues(
        collection, meterB, namePattern, tagNamePattern, tagValuePattern)

    then:
      collection == [(idAXY) : [measureAXY],
                     (idAYX) : [measureAYX],
                     (idAXZ) : [measureAXZ]]
  }

  void "collectFilteredTagName"() {
    given:
      tagNamePattern = Pattern.compile("tagZ")

    when:
      MetricController.collectValues(
        collection, meterA, namePattern, tagNamePattern, tagValuePattern)

    then:
      collection == [(idAXZ) : [measureAXZ]]
  }

  void "collectFilteredTagValue"() {
    given:
      tagValuePattern = Pattern.compile("X")

    when:
      MetricController.collectValues(
        collection, meterA, namePattern, tagNamePattern, tagValuePattern)

    then:
      collection == [(idAXY) : [measureAXY],
                     (idAYX) : [measureAYX]]
  }

  void "collectNotFound"() {
    given:
      tagNamePattern = Pattern.compile("tagZ")
      tagValuePattern = Pattern.compile("X")

    when:
      MetricController.collectValues(
        collection, meterA, namePattern, tagNamePattern, tagValuePattern)

    then:
      collection == toIdMap([:])
  }

  void "meterToKind"() {
    given:
      String kind

    when:
      kind = MetricController.meterToKind(new DefaultCounter(clock, idAXY))
    then:
      kind.equals("Counter")

    when:
      kind = MetricController.meterToKind(new DefaultTimer(clock, idAXY))
    then:
      kind.equals("Timer")

    when:
      kind = MetricController.meterToKind(meterA)
    then:
      kind.equals("MetricControllerSpec\$TestMeter")
  }

/***
  void "encodeSimpleRegistry"() {
    given:
      MetricController.EncodedRegistry got
      MetricController.EncodedRegistry expect
      DefaultRegistry registry = new DefaultRegistry(clock)
      Meter counter = new DefaultCounter(clock, idAXY)
      counter.increment(4)

      List<MetricController.TaggedDataPoints> expected_tagged_data_points = [
          new MetricController.TaggedDataPoints(
              [new MetricController.TagValue("tagA", "X"),
               new MetricController.TagValue("tagB", "Y")],
              [new MetricController.DataPoint(millis, 4)])
      ]
      registry.register(counter)


    when:
      got = controller.encodeRegistry(
          registry, namePattern, tagNamePattern, tagValuePattern)

    then:
     System.out.println(" GOT " + got.get("idA"));
      got == ["idA" : new MetricController.MetricValues("Counter", expected_tagged_data_points)]   
  }
***/
}
