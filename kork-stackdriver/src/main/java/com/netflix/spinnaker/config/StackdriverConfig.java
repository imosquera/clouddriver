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

package com.netflix.spinnaker.config;

import com.netflix.spinnaker.stackdriver.StackdriverWriter;

import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.Spectator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;

import java.util.regex.Pattern;

@Configuration
@ComponentScan("com.netflix.spinnaker.controllers")
@EnableScheduling
@ConditionalOnExpression("${stackdriver.monitoring.enabled:false}")
class StackdriverConfig {

  @Value("${stackdriver.monitoring.period:30}")
  private long period_seconds;

  @Value("${stackdriver.credentialsPath:}")
  private String credentialsPath;

  @Value("${stackdriver.projectName:}")
  private String projectName;

  @Value("${stackdriver.applicationName:}")
  private String applicationName;

  @Value("${stackdriver.applicationVersion:UnknownVersion}")
  private String applicationVersion;

  private StackdriverWriter stackdriver;

  @Bean
  public StackdriverWriter defaultStackdriverWriter() {
    Logger log = LoggerFactory.getLogger(getClass());
    log.info("Creating StackdriverWriter.");

    stackdriver = new StackdriverWriter(
        projectName, applicationName, applicationVersion, credentialsPath);
    return stackdriver;
  }

  @Scheduled(fixedDelay = 30000L) //"${stackdriver.monitoring.period:30}"
  void dumpMetrics() {
    stackdriver.writeRegistry(Spectator.globalRegistry());
  }

  @Bean
  Registry defaultMetricRegistry() {
    Logger log = LoggerFactory.getLogger(getClass());
    log.info("Creating default registry.");
    return new DefaultRegistry(com.netflix.spectator.api.Clock.SYSTEM);
  }
};
