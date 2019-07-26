/**
 * Copyright 2019 Google Inc. All Rights Reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.spanner;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.cloud.ServiceOptions;
import com.google.cloud.spanner.Database;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerExceptionFactory;
import com.google.cloud.spanner.SpannerOptions;
import com.google.spanner.admin.database.v1.CreateDatabaseMetadata;
import io.opencensus.common.Scope;
import io.opencensus.contrib.grpc.metrics.RpcViews;
import io.opencensus.exporter.stats.stackdriver.StackdriverStatsExporter;
import io.opencensus.exporter.trace.stackdriver.StackdriverTraceConfiguration;
import io.opencensus.exporter.trace.stackdriver.StackdriverTraceExporter;
import io.opencensus.stats.Aggregation;
import io.opencensus.stats.Aggregation.Distribution;
import io.opencensus.stats.BucketBoundaries;
import io.opencensus.stats.Measure.MeasureLong;
import io.opencensus.stats.Measure.MeasureDouble;
import io.opencensus.stats.Stats;
import io.opencensus.stats.StatsRecorder;
import io.opencensus.tags.TagKey;
import io.opencensus.stats.View;
import io.opencensus.stats.View.Name;
import io.opencensus.stats.ViewManager;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.config.TraceConfig;
import io.opencensus.trace.samplers.Samplers;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * This sample demonstrates how to enable opencensus tracing and stats in cloud spanner client.
 */
public class App {

  // [START configChanges]
  private static final String PROJECT_ID = ServiceOptions.getDefaultProjectId();
  private static final String INSTANCE_ID = System.getenv("INSTANCE_ID");
  private static final String DATABASE_ID = System.getenv("DATABASE_ID");
  private static final int EXPORT_INTERVAL = 70;
  // [END configChanges]

  // The read latency in milliseconds
  private static final MeasureDouble M_READ_LATENCY_MS = MeasureDouble.create("spannerapp/read_latency", "The latency in milliseconds for read", "ms");

  // [START config_oc_write_latency_measure]
  // The write latency in milliseconds
  private static final MeasureDouble M_WRITE_LATENCY_MS = MeasureDouble.create("spannerapp/write_latency", "The latency in milliseconds for write", "ms");
  // [END config_oc_write_latency_measure]

  // Counts the number of transactions
  private static final MeasureLong M_TRANSACTION_SETS = MeasureLong.create("spannerapp/transaction_set_count", "The count of transactions", "1");

  // Define the tags for potential grouping
  private static final TagKey KEY_LATENCY = TagKey.create("latency");
  private static final TagKey KEY_TRANSACTIONS = TagKey.create("transactions");

  private static final StatsRecorder STATS_RECORDER = Stats.getStatsRecorder();

  private static void registerMetricViews() {
    // [START config_oc_latency_distribution]
    Aggregation latencyDistribution = Distribution.create(BucketBoundaries.create(
      Arrays.asList(
        0.0, 5.0, 10.0, 25.0, 100.0, 200.0, 400.0, 800.0, 10000.0)));
    // [END config_oc_latency_distribution]

    // Define the count aggregation
    Aggregation countAggregation = Aggregation.Count.create();


    View[] views = new View[]{
      View.create(Name.create("spannerappmetrics/read_latency"),
        "The distribution of the read latencies",
        M_READ_LATENCY_MS,
        latencyDistribution,
        Collections.singletonList(KEY_LATENCY)),

      // [START config_oc_write_latency_view]
      View.create(Name.create("spannerappmetrics/write_latency"),
        "The distribution of the write latencies",
        M_WRITE_LATENCY_MS,
        latencyDistribution,
        Collections.singletonList(KEY_LATENCY)),
      // [END config_oc_write_latency_view]

      View.create(Name.create("spannerappmetrics/transaction_set_count"),
        "The number of transaction sets performed",
        M_TRANSACTION_SETS,
        countAggregation,
        Collections.singletonList(KEY_TRANSACTIONS))
    };

    // Ensure that they are registered so
    // that measurements won't be dropped.
    ViewManager manager = Stats.getViewManager();
    for (View view : views)
      manager.registerView(view);
  }

  // [START config_oc_stackdriver_export]
  private static void configureOpenCensusExporters() throws IOException {
    TraceConfig traceConfig = Tracing.getTraceConfig();

    // Sampler is set to Samplers.alwaysSample() for demonstration. In production
    // or in high QPS environment please use default sampler.
    traceConfig.updateActiveTraceParams(
      traceConfig.getActiveTraceParams().toBuilder().setSampler(Samplers.alwaysSample()).build());

    // Create the Stackdriver trace exporter
    StackdriverTraceExporter.createAndRegister(
      StackdriverTraceConfiguration.builder()
        .setProjectId(PROJECT_ID)
        .build());

    // Create the Stackdriver monitoring exporter
    StackdriverStatsExporter.createAndRegister();

    // [END config_oc_stackdriver_export]

    // -------------------------------------------------------------------------------------------
    // Register all the gRPC views
    // OC will automatically go and instrument gRPC. It's going to capture app level metrics
    // like latency, req/res bytes, count of req/res messages, started rpc etc.
    // -------------------------------------------------------------------------------------------
    RpcViews.registerAllGrpcViews();
  }

  /**
   * Connects to Cloud Spanner, runs some basic operations.
   */
  private static void doSpannerOperations() {
    long startRead;
    long endRead;
    long startWrite;
    long endWrite;

    // Instantiate the client.
    SpannerOptions options = SpannerOptions.getDefaultInstance();
    Spanner spanner = options.getService();

    try {
      DatabaseId db = DatabaseId.of(
        options.getProjectId(), INSTANCE_ID, DATABASE_ID);
      // And then create the Spanner database client.
      DatabaseClient dbClient = spanner.getDatabaseClient(db);
      DatabaseAdminClient dbAdminClient = spanner.getDatabaseAdminClient();

      createDatabase(dbAdminClient, db);

      try (Scope ss = Tracing.getTracer().spanBuilder("create-players").startScopedSpan()) {
        // Warm up the spanner client session. In normal usage
        // you'd have hit this point after the first operation.
        startRead = System.currentTimeMillis();
        dbClient.singleUse().readRow("Players", Key.of("foo@gmail.com"),
          Collections.singletonList("email"));
        endRead = System.currentTimeMillis();

        // [START spanner_insert_data]
        for (int i = 0; i < 3; i++) {
          String up = i + "-" + (System.currentTimeMillis() / 1000) + ".";
          List<Mutation> mutations = Arrays.asList(
            playerMutation("Poke", "Mon", up + "poke.mon@example.org",
              "f1578551-eb4b-4ecd-aee2-9f97c37e164e"),
            playerMutation("Go", "Census", up + "go.census@census.io",
              "540868a2-a1d8-456b-a995-b324e4e7957a"),
            playerMutation("Quick", "Sort", up + "q.sort@gmail.com",
              "2b7e0098-a5cc-4f32-aabd-b978fc6b9710")
          );

          // write to Spanner
          startWrite = System.currentTimeMillis();
          dbClient.write(mutations);
          endWrite = System.currentTimeMillis();


          // [START opencensus_metric_record]
          // record read, write latency metrics and count
          STATS_RECORDER.newMeasureMap()
            .put(M_READ_LATENCY_MS, endRead - startRead)
            .put(M_WRITE_LATENCY_MS, endWrite - startWrite)
            .put(M_TRANSACTION_SETS, 1)
            .record();
          // [END opencensus_metric_record]
        }
        // [END spanner_insert_data]
      }
    } catch (Exception e) {
      System.out.print("Exception while adding player: " + e);
    } finally {
      // Closes the client which will free up the resources used
      spanner.close();
    }
  }

  // [START spanner_create_database]
  private static void createDatabase(DatabaseAdminClient dbAdminClient, DatabaseId id) {
    OperationFuture<Database, CreateDatabaseMetadata> op =
      dbAdminClient.createDatabase(
        id.getInstanceId().getInstance(),
        id.getDatabase(),
        Collections.singletonList(
          "CREATE TABLE Players (\n"
            + "  first_name STRING(1024),\n"
            + "  last_name  STRING(1024),\n"
            + "  email   STRING(1024),\n"
            + "  uuid STRING(1024)\n"
            + ") PRIMARY KEY (email)"));
    try {
      // Initiate the request which returns an OperationFuture.
      Database db = op.get();
      System.out.println("Created database [" + db.getId() + "]");
    } catch (ExecutionException e) {
      // If the operation failed during execution, expose the cause.
      throw (SpannerException) e.getCause();
    } catch (InterruptedException e) {
      // Throw when a thread is waiting, sleeping, or otherwise occupied,
      // and the thread is interrupted, either before or during the activity.
      throw SpannerExceptionFactory.propagateInterrupt(e);
    }
  }
  // [END spanner_create_database]

  // [START spanner_insert_data]
  private static Mutation playerMutation(String firstName, String lastName, String email,
    String uuid) {
    return Mutation.newInsertBuilder("Players")
      .set("first_name")
      .to(firstName)
      .set("last_name")
      .to(lastName)
      .set("uuid")
      .to(uuid)
      .set("email")
      .to(email)
      .build();
  }
  // [END spanner_insert_data]

  private static void sleep(int ms) {
    try {
      Thread.sleep(ms);
    } catch (Exception ignored) { }
  }

  public static void main(String ...args) throws Exception {

    // set up the views to expose the metrics
    registerMetricViews();

    configureOpenCensusExporters();
    doSpannerOperations();

    // The default export interval is 60 seconds. The thread with the StackdriverStatsExporter must
    // live for at least the interval past any metrics that must be collected, or some risk being
    // lost if they are recorded after the last export.
    sleep(EXPORT_INTERVAL * 1000);
  }
}
