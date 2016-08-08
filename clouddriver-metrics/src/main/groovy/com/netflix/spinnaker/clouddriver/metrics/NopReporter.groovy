package com.netflix.spinnaker.clouddriver.metrics

import com.codahale.metrics.ScheduledReporter
import com.codahale.metrics.Counter
import com.codahale.metrics.Gauge
import com.codahale.metrics.Histogram
import com.codahale.metrics.Meter
import com.codahale.metrics.Timer
import com.codahale.metrics.MetricFilter
import com.codahale.metrics.MetricRegistry

import java.util.concurrent.TimeUnit
import java.util.SortedMap

public class NopReporter extends ScheduledReporter {
   static class Builder {
     MetricFilter filter
     MetricRegistry registry
     String name
     TimeUnit durationUnit
     TimeUnit rateUnit

     Builder(MetricRegistry registry) {
       this.registry = registry
       rateUnit = TimeUnit.SECONDS
       durationUnit = TimeUnit.SECONDS
       name = 'NopReporter'
       filter = MetricFilter.ALL
     }

     NopReporter build() {
       return new NopReporter(registry, name, filter, rateUnit, durationUnit)
     }
   }


   static Builder forRegistry(MetricRegistry registry) {
     return new Builder(registry)
   }

   NopReporter(MetricRegistry registry, String name, MetricFilter filter,
               TimeUnit rateUnit, TimeUnit durationUnit) {
       super(registry, name, filter, rateUnit, durationUnit)
   }

    void report(SortedMap<String,Gauge> gauges,
                SortedMap<String,Counter> counters,
                SortedMap<String,Histogram> histograms,
                SortedMap<String,Meter> meters,
                SortedMap<String,Timer> timers) {
        println 'GAUGES:'
            for ( item in gauges ) {
                println '  ' + item.key + ':' + item.value.getValue().toString()
            }
        println 'COUNTERS:'
            for ( item in counters ) {
                println '  ' + item.key + ':' + item.value.getCount()
            }
        println 'HISTOGRAMS:'
            for ( item in histograms ) {
                println '  ' + item.key + ': ' + item.value.getCount()
            }
        println 'METERS:'
            for ( item in meters ) {
                println '  ' + item.key + ': count=' + item.value.getCount + ' 1m=' + item.value.getOneMinuteRate()
            }
        println 'TIMERS:'
            for ( item in timers ) {
                t = item.value
                println '  ' + item.key + ': [' + t.getRateUnit() + '] count=' + t.getCount() + ' 1m=' +t.getOneMinuteRate()
            }
    }
}




