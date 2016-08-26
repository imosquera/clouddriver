/*
 * Copyright 2016 Google, Inc.
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

package com.netflix.spinnaker.controllers;

import com.netflix.spectator.api.Counter;
import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.DistributionSummary;
import com.netflix.spectator.api.Gauge;
import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.LongTaskTimer;
import com.netflix.spectator.api.Measurement;
import com.netflix.spectator.api.Meter;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.Tag;
import com.netflix.spectator.api.Timer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;


@RequestMapping("/json-metrics")
@RestController
@ConditionalOnExpression("${stackdriver.endpoint.enabled:true}")
public class MetricController {

  @Autowired
  Registry registry;

  @Value("${stackdriver.applicationName:}")
  public String APPLICATION_NAME;

  @Value("${stackdriver.applicationVersion:UnknownVerison}")
  public String APPLICATION_VERSION;

  @Value("${stackdriver.endpoint.nameFilter:.*}")
  public String NAME_FILTER;

  @Value("${stackdriver.endpoint.tagNameFilter:}")
  public String TAG_NAME_FILTER;

  @Value("${stackdriver.endpoint.tagValueFilter:}")
  public String TAG_VALUE_FILTER;


  /**
   * A serializable Measurement.
   * Measurement is a value at a given time.
   *
   * The id is implicit by the parent node containing this DataPoint.
   */
  public static class DataPoint {
    static public DataPoint make(Measurement m) {
      return new DataPoint(m.timestamp(), m.value());
    }

    public long getT() { return timestamp; }
    public double getV() { return value; }

    public DataPoint(Long timestamp, Double value) {
      this.timestamp = timestamp;
      this.value = value;
    }

    public void aggregate(Measurement m) {
      if (m.timestamp() > this.timestamp) {
        this.timestamp = m.timestamp();
      }
      this.value += m.value();
    }

    public String toString() {
        return "" + timestamp + " = " + value;
    }

    public boolean equals(Object obj) {
      if (obj == null) return false;
      if (!(obj instanceof DataPoint)) return false;
      DataPoint other = (DataPoint)obj;
      return timestamp == other.timestamp && value == other.value;
    }

    private long timestamp;
    private double value;
  };

  /**
   * A TagValue is an NameValue pair for a tag and its binding.
   */
  public static class TagValue {
    public String getKey() { return key; } 
    public String getValue() { return value; }

    public TagValue(String key, String value) {
      this.key = key;
      this.value = value;
    }

    public boolean equals(Object obj) {
      if (obj == null) return false;
      if (!(obj instanceof TagValue)) return false;
      TagValue other = (TagValue)obj;
      return key.equals(other.key) && value.equals(other.value);
    }

    public String toString() {
        return key + "=" + value;
    }

    private String key;
    private String value;
  };

  /**
   * A collection of DataPoint instances for a comment set of TagValues.
   */
  public static class TaggedDataPoints {
    public static TaggedDataPoints make(Id id, List<Measurement> measurements,
                                        boolean aggregate) {
      List<TagValue> tags = new ArrayList<TagValue>();
      List<DataPoint> dataPoints = new ArrayList<DataPoint>();
      for (Tag tag : id.tags()) {
          tags.add(new TagValue(tag.key(), tag.value()));
      }
      if (aggregate) {
        DataPoint point = null;
        for (Measurement measurement : measurements) {
           if (point == null) {
             point = DataPoint.make(measurement);
             dataPoints.add(point);
           } else {
             point.aggregate(measurement);
           }
        }
      } else {
        for (Measurement measurement : measurements) {
          dataPoints.add(DataPoint.make(measurement));
        }
      }
      return new TaggedDataPoints(tags, dataPoints);
    }

    public Iterable<TagValue> getTags() { return tags; }
    public Iterable<DataPoint> getValues() { return dataPoints; }

    public TaggedDataPoints(List<TagValue> tags, List<DataPoint> dataPoints) {
      this.tags = tags;
      this.dataPoints = dataPoints;
    }

    public String toString() {
        return "{TAGS={" + tags + "}, DATA={" + dataPoints + "}}";
    }

    public boolean equals(Object obj) {
      if (obj == null) return false;
      if (!(obj instanceof TaggedDataPoints)) return false;

      TaggedDataPoints other = (TaggedDataPoints)obj;
      return tags == other.tags && dataPoints == other.dataPoints;
    }

    private List<TagValue> tags;
    private List<DataPoint> dataPoints;
  };

  /**
   * A metric and all its tagged values
   */
  public static class MetricValues {
    public static MetricValues make(String kind, List<Measurement> collection) {
      boolean aggregate = kind.equals("AggrMeter");
      List<TaggedDataPoints> dataPoints = new ArrayList<TaggedDataPoints>();
      dataPoints.add(TaggedDataPoints.make(collection.get(0).id(), collection, aggregate));
      return new MetricValues(kind, dataPoints);
    }

    public String getKind() { return kind; }
    public Iterable<TaggedDataPoints> getValues() { return dataPoints; }

    public void addMeasurements(String kind, List<Measurement> collection) {
      boolean aggregate = kind.equals("AggrMeter");
      dataPoints.add(TaggedDataPoints.make(collection.get(0).id(), collection, aggregate));
    }

    public MetricValues(String kind, List<TaggedDataPoints> dataPoints) {
      this.kind = kind;
      this.dataPoints = dataPoints;
    }

    public boolean equals(Object obj) {
      if (obj == null) return false;
      if (!(obj instanceof MetricValues)) return false;

      // Ignore the kind because spectator internally transforms it
      // into internal types that we cannot test against.
      MetricValues other = (MetricValues)obj;
      return dataPoints == other.dataPoints;
    }

    public String toString() {
      return kind + dataPoints.toString();
    }

    private String kind;
    private List<TaggedDataPoints> dataPoints;
  };

  /**
   * Maps a metric name to its values.
   */
  public static class EncodedRegistry extends HashMap<String, MetricValues> {};

  @RequestMapping(method=RequestMethod.GET)
  public Map<String, Object> getMetrics(@RequestParam Map<String, String> filters) {
    String nameFilter = filters.get("nameFilter");
    if (nameFilter == null) nameFilter = NAME_FILTER;

    String tagNameFilter = filters.get("tagNameFilter");
    if (tagNameFilter == null) tagNameFilter = TAG_NAME_FILTER;

    String tagValueFilter = filters.get("tagValueFilter");
    if (tagValueFilter == null) tagValueFilter = TAG_VALUE_FILTER;

    Pattern namePattern = null;
    Pattern tagNamePattern = null;
    Pattern tagValuePattern = null;
    
    if (nameFilter.isEmpty()) {
      nameFilter = NAME_FILTER;
    }
    if (tagNameFilter.isEmpty()) {
      tagNameFilter = TAG_NAME_FILTER;
    }
    if (tagValueFilter.isEmpty()) {
      tagValueFilter = TAG_VALUE_FILTER;
    }
    if (!nameFilter.isEmpty() && !nameFilter.equals(".*")) {
      namePattern = Pattern.compile(nameFilter);
    }
    if (!tagNameFilter.isEmpty() && !tagNameFilter.equals(".*")) {
      tagNamePattern = Pattern.compile(tagNameFilter);
    }
    if (!tagValueFilter.isEmpty() && !tagValueFilter.equals(".*")) {
      tagValuePattern = Pattern.compile(tagValueFilter);
    }

    Map<String, Object> response = new HashMap<String, Object>();
    response.put("applicationName", APPLICATION_NAME);
    response.put("metrics",
                 encodeRegistry(registry, namePattern, tagNamePattern, tagValuePattern));
    return response;
  }

  EncodedRegistry encodeRegistry(
       Registry registry,
       Pattern namePattern, Pattern tagNamePattern, Pattern tagValuePattern) {
    EncodedRegistry metricMap = new EncodedRegistry();

    /**
     * Spectator meters seam to group measurements. The meter name is the
     * measurement name prefix. It seems that all the measurements within a
     * meter instance share the same tag values as the meter.
     * Different instances are different tag assignments, keeping the groups
     * of measurements together.
     *
     * For now, we are not going to make this assumption and will just flatten
     * out the whole measurement space. That makes fewer assumptions for now.
     * If we make grouping assumptions we can factor out the labels into a more
     * terse json. But the difference isnt necessarily that big and if the assumption
     * doesnt hold, then forming the groupings will be complicated and more expensive.
     */
    for (Meter meter : registry) {
      Map<Id, List<Measurement>> collection = new HashMap<Id, List<Measurement>>();
      collectValues(collection, meter, namePattern, tagNamePattern, tagValuePattern);

      if (!collection.isEmpty()) {
        String kind = meterToKind(meter);
        for (Map.Entry<Id, List<Measurement>> entry : collection.entrySet()) {
           String entryName = entry.getKey().name();
           MetricValues have = metricMap.get(entryName);
           if (have == null) {
             metricMap.put(entryName, MetricValues.make(kind, entry.getValue()));
           } else {
             have.addMeasurements(kind, entry.getValue());
           }
        }
      }
    }
    return metricMap;
  }

  /**
   * Collect all the meter values matching the tag pattern.
   */
  public static List<Id> collectValues(
      Map<Id, List<Measurement>> collection, Meter meter,
      Pattern namePattern, Pattern tagNamePattern, Pattern tagValuePattern) {
    List<Id> new_ids = new ArrayList<Id>();

    for (Measurement measurement : meter.measure()) {
      Id id = measurement.id();
      String name = id.name();
      if (namePattern != null && !namePattern.matcher(name).matches()) {
        continue;
      }

      if (tagNamePattern != null || tagValuePattern != null) {
        boolean ok = false;
        for (Tag tag : id.tags()) {
          boolean nameOk = tagNamePattern == null
                           || tagNamePattern.matcher(tag.key()).matches();
          boolean valueOk = tagValuePattern == null
                           || tagValuePattern.matcher(tag.value()).matches();
          if (nameOk && valueOk) {
            ok = true;
            break;
          }
        }
        if (!ok) {
          continue;
        }
      }

      List<Measurement> valueList = collection.get(id);
      if (valueList == null) {
        valueList = new ArrayList<Measurement>();
        collection.put(id, valueList);
        new_ids.add(id);
      }
      valueList.add(measurement);
    }
    return new_ids;
  }

  public static String meterToKind(Meter meter) {
    String kind;
    if (meter instanceof Counter) {
      kind = "Counter";
    } else if (meter instanceof Gauge) {
      kind = "Gauge";
    } else if ((meter instanceof Timer) || (meter instanceof LongTaskTimer)) {
      kind = "Timer";
    } else if (meter instanceof DistributionSummary) {
      kind = "Distribution";
    } else {
      kind = meter.getClass().getName();
      int dot = kind.lastIndexOf('.');
      kind = kind.substring(dot + 1);
    }
    return kind;  // This might be a class name of some other unforseen type.
  }
};
