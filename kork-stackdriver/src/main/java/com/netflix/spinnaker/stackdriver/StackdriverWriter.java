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

package com.netflix.spinnaker.stackdriver;


import com.netflix.spinnaker.controllers.MetricController;

import com.google.api.services.monitoring.v3.Monitoring;
import com.google.api.services.monitoring.v3.MonitoringScopes;
import com.google.api.services.monitoring.v3.model.CreateTimeSeriesRequest;
import com.google.api.services.monitoring.v3.model.LabelDescriptor;
import com.google.api.services.monitoring.v3.model.ListMetricDescriptorsResponse;
import com.google.api.services.monitoring.v3.model.Metric;
import com.google.api.services.monitoring.v3.model.MetricDescriptor;
import com.google.api.services.monitoring.v3.model.Point;
import com.google.api.services.monitoring.v3.model.TimeInterval;
import com.google.api.services.monitoring.v3.model.TimeSeries;
import com.google.api.services.monitoring.v3.model.TypedValue;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.common.collect.Lists;

import com.netflix.spectator.api.Counter;
import com.netflix.spectator.api.DistributionSummary;
import com.netflix.spectator.api.Gauge;
import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.LongTaskTimer;
import com.netflix.spectator.api.Meter;
import com.netflix.spectator.api.Measurement;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.Spectator;
import com.netflix.spectator.api.Tag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;


/* These are builtin metric types that we might be able to use
 * except probably not because there isnt a label to say this is our use
 * as opposed to the assumption that "there is only one" of them.

    "agent.googleapis.com/jvm/memory/usage"
    "agent.googleapis.com/jvm/cpu/time"
    "agent.googleapis.com/jvm/gc/time"
    "agent.googleapis.com/jvm/gc/time"
    "agent.googleapis.com/jvm/thread/num_live"
    "agent.googleapis.com/jvm/thread/peak"
    "agent.googleapis.com/jvm/uptime"

    "agent.googleapis.com/tomcat/manager/sessions"
    "agent.googleapis.com/tomcat/request_processor/error_count"
    "agent.googleapis.com/tomcat/request_processor/processing_time"
    "agent.googleapis.com/tomcat/request_processor/request_count"
    "agent.googleapis.com/tomcat/request_processor/traffic_count"
    "agent.googleapis.com/tomcat/threads/current"
    "agent.googleapis.com/tomcat/threads/busy"
*/    


// This class is not thread safe, but is assumed to be called from a
// single thread.
public class StackdriverWriter {
  static final String COUNTER_KIND = "GAUGE";
  static final Map<String, String> KIND_MAP;
  static {
      Map<String, String> prototype = new HashMap<String, String>();
      prototype.put("Gauge", "GAUGE");
      prototype.put("Counter", COUNTER_KIND);
      prototype.put("Timer", COUNTER_KIND);
      KIND_MAP = Collections.unmodifiableMap(prototype);
  }
  static final int MAX_TS_PER_REQUEST = 200;
  static final String CUSTOM_TYPE_PREFIX = "custom.googleapis.com/spinnaker/";
  static final String COMPONENT_LABEL =  "ComponentName";
  static final SimpleDateFormat rfc3339 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
  static final Logger log = LoggerFactory.getLogger(StackdriverWriter.class);

  // These are the custom MetricDescriptor types we know about so that we know whether we need to create
  // a new one or not. Types are created on demand, but remembered across executions, so only created once,
  // forever. Instead of even remembering them, we could probably just assume it exists then respond to an
  // error by creating it and trying again.
  // Not thread safe.
  static final HashMap<String, MetricDescriptor> knownDescriptors = new HashMap<String, MetricDescriptor>();
  static final HashSet<String> badDescriptorNames = new HashSet<String>();

  Monitoring service;
  String projectResourceName;
  String applicationName;

  public Pattern namePattern = Pattern.compile(".+");
  public Pattern tagNamePattern = Pattern.compile(".+");
  public Pattern tagValuePattern = Pattern.compile(".*");

  private void handleResponseException(HttpResponseException rex, String msg) {
      log.error("Caught HttpResponseException " + msg, rex);
    if (rex.getStatusCode() == 429) {
      log.info("HEADERS {}", rex.getHeaders().keySet());
      log.info("RetryAfter {}", rex.getHeaders().getRetryAfter());
    }
  }

  /**
   * The constructor will establish the client stub that talks to the monitoring service.
   */
  public StackdriverWriter(
      String projectName, String applicationName, String applicationVersion, String credentialsPath) {
    String userAgentName = "default user-agent";
    Monitoring monitoring;

    if (projectName == "") {
      throw new IllegalStateException("You need to configure a stackdriver.projectName.");
    }

    try {
      HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
      JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
      GoogleCredential credential = loadCredential(httpTransport, jsonFactory,
                                                   credentialsPath);
      Monitoring.Builder builder = new Monitoring.Builder(httpTransport, jsonFactory, credential);
      if (applicationName != "") {
          userAgentName = applicationName + "/" + applicationVersion;
          builder.setApplicationName(userAgentName);
      }
      monitoring = builder.build();
    } catch (IOException|java.security.GeneralSecurityException e) {
        throw new IllegalStateException(e);
    }

    this.projectResourceName = "projects/" + projectName;
    this.applicationName = applicationName;
    this.service = monitoring;
    log.info("Created StackdriverMonitoring client '{}' using project '{}'",
             userAgentName, projectResourceName);
    try {
      initKnownDescriptors();
    } catch (HttpResponseException rex) {
      handleResponseException(rex, "Initializing KnownDescriptors.");
    } catch (IOException ioex) {
      log.error("Caught IOException initializing KnownDescriptors.", ioex);
    }
  }

  /**
   * Helper function for the constructor that reads our credentials for talking to Stackdriver.
   */
  public GoogleCredential loadCredential(
      HttpTransport transport, JsonFactory factory, String credentialsPath)
      throws IOException {
    GoogleCredential credential;
    if (!credentialsPath.isEmpty()) {
      FileInputStream stream = new FileInputStream(credentialsPath);
      credential = GoogleCredential.fromStream(stream, transport, factory)
                      .createScoped(Collections.singleton(MonitoringScopes.MONITORING));
      log.info("Loaded credentials from from " + credentialsPath);
    } else {
      log.info("stackdriver.monitoring.enabled without stackdriver.credentialsPath. " +
                   "Using default application credentials. Using default credentials.");
      credential = GoogleCredential.getApplicationDefault();
    }
    return credential;
  }

  /**
   * Lookup all the pre-existing custom metrics for spinnaker so that we
   * know if we need to create new metric descriptors or not.
   */
  public void initKnownDescriptors() throws HttpResponseException, IOException {
    ListMetricDescriptorsResponse response =
      service.projects().metricDescriptors().list(this.projectResourceName)
      .execute();

    for (MetricDescriptor descriptor : response.getMetricDescriptors()) {
      if (descriptor.getType().startsWith(CUSTOM_TYPE_PREFIX)) {
        knownDescriptors.put(descriptor.getName(), descriptor);
      }
    }
  }

  /**
   * Helper function to fetch an individual descriptor from Stackdriver.
   * This is used to lookup whether a type we want to use exists already or not.
   * Normally we would already know this from initKnownDescriptors, however there is
   * a race condition on first usage where the descriptor did not exist on our startup
   * but another process has since created it. Fetching it will correct for that.
   */
   MetricDescriptor fetchDescriptorHelper(String descriptorName)
       throws IOException {
    MetricDescriptor descriptor = service.projects().metricDescriptors().get(descriptorName).execute();
    knownDescriptors.put(descriptorName, descriptor);
    return descriptor;
  }

  void hackMaybeAddLabels(String[] keyList, List<LabelDescriptor> labels) {
    for (String key : keyList) {
      boolean found = false;
      for (LabelDescriptor label : labels) {
        if (label.getKey().equals(key)) {
          log.info("*** FOUND '{}'", key);
          found = true;
          break;
        }
      }
      if (! found) {
        log.info("*** ADDING '{}'", key);
        LabelDescriptor labelDescriptor = new LabelDescriptor();
        log.info("Hacking in label '{0}'.".format(key));
        labelDescriptor.setKey(key);
        labelDescriptor.setValueType("STRING");
        labels.add(labelDescriptor);
      }
    }
  }

  /**
   * Creates a new descriptor. Since descriptors are global and persistent, this only happens
   * the first time we wish to use it over the lifetime of the project. It is also possible a
   * extra times in other processes encountering a race condition on the first-time use, but
   * these calls will fail.
   */
   MetricDescriptor createDescriptorHelper(Id id, Meter meter)
       throws IOException {
    String descriptorName = idToDescriptorName(id);
    MetricDescriptor descriptor = new MetricDescriptor();
    descriptor.setName(descriptorName);
    descriptor.setType(idToDescriptorType(id));
    descriptor.setValueType("DOUBLE");
    descriptor.setMetricKind(meterToKind(meter));

    List<LabelDescriptor> labels = new ArrayList<LabelDescriptor>();
    LabelDescriptor labelDescriptor = new LabelDescriptor();
    labelDescriptor.setKey(COMPONENT_LABEL);
    labelDescriptor.setValueType("STRING");
    labels.add(labelDescriptor);

    // All measurements have the same tags, so just look at the first.
    Measurement measurement = meter.measure().iterator().next();
    for (Tag tag : measurement.id().tags()) {
       labelDescriptor = new LabelDescriptor();
       labelDescriptor.setKey(tag.key());
       labelDescriptor.setValueType("STRING");
       labels.add(labelDescriptor);
    }
    log.info("*** CHECKING '{}'", descriptorName);
    if (descriptorName.endsWith("/controller.invocations")) {
        log.info("*** HACK CHECK '{}'", descriptorName);
      // Inconsistent across apps.
      // This needs generalized because stackdriver cannot deal with it.
      hackMaybeAddLabels(new String[] {"account", "application"}, labels);
    }

    descriptor.setLabels(labels);

    MetricDescriptor response = service.projects().metricDescriptors()
      .create(projectResourceName, descriptor)
      .execute();
    log.info("Created new MetricDescriptor {}", response.toString());
    knownDescriptors.put(response.getName(), response);
    return response;
  }

  /**
   * Ensure a descriptor for the meter exists, creating one if needed.
   */
  public MetricDescriptor ensureDescriptor(Id id, Meter meter) {
    if (knownDescriptors.isEmpty()) {
      try {
          initKnownDescriptors();
      } catch (HttpResponseException rex) {
        handleResponseException(rex, "initializing KnownDescriptors");
        return null;
      } catch (IOException ioex) {
        log.error("Caught IOException initializing knownDescriptors.", ioex);
        return null;
      }
    }

    String descriptorName = idToDescriptorName(id);
    MetricDescriptor found = knownDescriptors.get(descriptorName);
    if (found != null) {
      return found;
    } else if (badDescriptorNames.contains(descriptorName)) {
      return null;
    }
    Iterator<Measurement> iterator = meter.measure().iterator();
    if (!iterator.hasNext() || !iterator.next().id().tags().iterator().hasNext()) {
        // Ignore metrics whose measures do not have labels.
        // These are gleaned from spring and the interesting
        // ones should be replicated by other explicit Spectator
        // metrics having labels.
        badDescriptorNames.add(descriptorName);
        return null;
    }

    try {
      return fetchDescriptorHelper(descriptorName);
    } catch (HttpResponseException rex) {
      handleResponseException(rex, "fetching descriptor");
    } catch (IOException ioex) {
    }

    try {
      return createDescriptorHelper(id, meter);
    } catch (HttpResponseException rex) {
      handleResponseException(rex, "creating descriptor");
    } catch (IOException ioex) {
    }

    try {
      return fetchDescriptorHelper(descriptorName);
    } catch (HttpResponseException rex) {
      handleResponseException(rex, "fetching descriptor");
    } catch (IOException ioex) {
      log.error("Could not find or create descriptor '{}':", descriptorName, ioex);
    }
    return null;
  }

  public String idToDescriptorType(Id id) {
    return CUSTOM_TYPE_PREFIX + id.name();
  }

  public String idToDescriptorName(Id id) {
    return projectResourceName + "/metricDescriptors/" + idToDescriptorType(id);
  }

  public String meterToKind(Meter meter) {
    // Treat everything as a gauge because Stackdriver counters require a start time
    return "GAUGE";
  }

  /**
   * Convert a Spectator metric Meter into a Stackdriver TimeSeries entry.
   */
  public void addMeasurementsToTimeSeries(
        MetricDescriptor descriptor,
        List<Measurement> measurements,
        List<TimeSeries> tsList) {
    Iterator<Measurement> iterator = measurements.iterator();
    Measurement measurement = iterator.next();
    long maxTimestamp = measurement.timestamp();
    double aggregateValue = measurement.value();
    HashMap<String, String> labels = new HashMap<String, String>();
    labels.put(COMPONENT_LABEL, applicationName);
    for (Tag tag : measurement.id().tags()) {
      labels.put(tag.key(), tag.value());
    }
    log.info("  Adding ID={} + {}", measurement.id().name(), measurement.id().tags());

    while (iterator.hasNext()) {
      // If there are multiple values (e.g. AggrMeter) then
      // combine them together and report as the most recent time value.
      measurement = iterator.next();
      maxTimestamp = Math.max(maxTimestamp, measurement.timestamp());
      aggregateValue += measurement.value();
    }

    TimeInterval timeInterval = new TimeInterval();
    timeInterval.setEndTime(rfc3339.format(new Date(maxTimestamp)));

    TypedValue typedValue = new TypedValue();
    typedValue.setDoubleValue(aggregateValue);

    Point point = new Point();
    point.setValue(typedValue);
    point.setInterval(timeInterval);

    Metric metric = new Metric();
    metric.setType(descriptor.getType());
    metric.setLabels(labels);

    TimeSeries ts = new TimeSeries();
    ts.setMetric(metric);
    ts.setMetricKind(descriptor.getMetricKind());
    ts.setValueType("DOUBLE");
    ts.setPoints(Lists.<Point>newArrayList(point));
    tsList.add(ts);
  }

  public Map<Id, List<Measurement>> collectMeasurements(Registry registry) {
    log.info("Collecting metrics...");
    Map<Id, List<Measurement>> collection = new HashMap<Id, List<Measurement>>();
    Iterator<Meter> iterator = registry.iterator();

    while (iterator.hasNext()) {
       Meter meter = iterator.next();
       List<Id> new_ids = MetricController.collectValues(
           collection, meter, namePattern, tagNamePattern, tagValuePattern);
       for (Id id : new_ids) {
         if (ensureDescriptor(id, meter) == null) {
           collection.remove(id);
         }
       }
    }
    return collection;
  }

  public List<TimeSeries> measurementsToTimeSeries(
        Map<Id, List<Measurement>> collection) {
    ArrayList<TimeSeries> tsList = new ArrayList<TimeSeries>();
    for (Map.Entry<Id, List<Measurement>> entry : collection.entrySet()) {
        String descriptorName = idToDescriptorName(entry.getKey());
        MetricDescriptor descriptor = knownDescriptors.get(descriptorName);
        if (descriptor == null) {
            log.error("*** No stackdriver descriptor for {}", descriptorName);
            continue;
        }
        addMeasurementsToTimeSeries(descriptor, entry.getValue(), tsList);
    }
    return tsList;
  }

  /**
   * Update Stackdriver with the current Spectator metric registry values.
   */
  public void writeRegistry(Registry registry) {
    Map<Id, List<Measurement>> collection = collectMeasurements(registry);
    List<TimeSeries> tsList = measurementsToTimeSeries(collection);
    if (tsList.isEmpty()) {
       log.info("No metric data points.");
       return;
    }

    CreateTimeSeriesRequest tsRequest = new CreateTimeSeriesRequest();
    int offset = 0;
    List<TimeSeries> nextN;

    log.info("Writing metrics...");
    while (offset < tsList.size()) {
      if (offset + MAX_TS_PER_REQUEST < tsList.size()) {
        nextN = tsList.subList(offset, offset + MAX_TS_PER_REQUEST);
        offset += MAX_TS_PER_REQUEST + 1;
      } else {
        nextN = tsList.subList(offset, tsList.size());
        offset = tsList.size();
      }
      tsRequest.setTimeSeries(nextN);
      try {
        log.info("Writing {} points.", nextN.size());
        service.projects().timeSeries().create(projectResourceName, tsRequest).execute();
      } catch (HttpResponseException rex) {
        handleResponseException(rex, "creating time series");
        return;
      } catch (IOException ioex) {
        log.error("Caught HttpResponseException creating time series " + ioex);
      }
      log.info("Wrote {} values", tsList.size());
    }
  }
}
