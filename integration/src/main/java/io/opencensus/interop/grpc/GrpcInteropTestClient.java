/*
 * Copyright 2018, OpenCensus Authors
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

package io.opencensus.interop.grpc;

import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opencensus.common.Scope;
import io.opencensus.interop.EchoRequest;
import io.opencensus.interop.EchoResponse;
import io.opencensus.interop.EchoServiceGrpc;
import io.opencensus.interop.EchoServiceGrpc.EchoServiceBlockingStub;
import io.opencensus.tags.InternalUtils;
import io.opencensus.tags.Tag;
import io.opencensus.tags.TagContext;
import io.opencensus.tags.TagContextBuilder;
import io.opencensus.tags.TagKey;
import io.opencensus.tags.TagValue;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.Tags;
import io.opencensus.tags.propagation.TagContextBinarySerializer;
import io.opencensus.tags.propagation.TagContextDeserializationException;
import io.opencensus.trace.SpanBuilder;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.samplers.Samplers;
import java.math.BigInteger;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Interop test client for gRPC interop testing. */
public final class GrpcInteropTestClient {

  private int serverPort;
  private static String HOST = "localhost";

  private static final Logger logger = Logger.getLogger(GrpcInteropTestClient.class.getName());
  private static final Tagger tagger = Tags.getTagger();
  private static final Tracer tracer = Tracing.getTracer();

  private static final String SPAN_NAME = "gRPC-client-span";
  private static final TagKey OPERATION_KEY = TagKey.create("operation");
  private static final TagKey PROJECT_KEY = TagKey.create("project");
  private static final TagKey METHOD_KEY = TagKey.create("method");
  private static final TagValue OPERATION_VALUE = TagValue.create("interop-test");
  private static final TagValue PROJECT_VALUE = TagValue.create("open-census");
  private static final TagValue METHOD_VALUE =
      TagValue.create(EchoServiceGrpc.getEchoMethod().getFullMethodName());

  private GrpcInteropTestClient(int serverPort) {
    this.serverPort = serverPort;
  }

  private void run() {
    ExecutorService executor = Executors.newFixedThreadPool(1);
    ManagedChannel channel =
        ManagedChannelBuilder.forAddress(HOST, serverPort)
            .executor(executor)
            // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
            // needing certificates.
            .usePlaintext(true)
            .build();
    SpanBuilder spanBuilder =
        tracer.spanBuilderWithExplicitParent(SPAN_NAME, null).setSampler(Samplers.alwaysSample());
    TagContextBuilder tagContextBuilder =
        tagger.emptyBuilder().put(OPERATION_KEY, OPERATION_VALUE).put(PROJECT_KEY, PROJECT_VALUE);

    boolean succeeded = false;
    try (Scope scopedSpan = spanBuilder.startScopedSpan();
        Scope scopedTags = tagContextBuilder.buildScoped()) {
      EchoServiceBlockingStub stub = EchoServiceGrpc.newBlockingStub(channel);
      EchoResponse response = stub.echo(EchoRequest.getDefaultInstance());
      succeeded = verifyResponse(response);
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Exception thrown when sending request.", e);
    } finally {
      if (succeeded) {
        logger.info("PASSED.");
      } else {
        logger.info("FAILED.");
      }
      channel.shutdownNow();
    }
  }

  // Returns true if all tracing values and tags received in the response match the expected values.
  private static boolean verifyResponse(EchoResponse response) {
    boolean succeeded = true;
    TagContextBinarySerializer serializer = Tags.getTagPropagationComponent().getBinarySerializer();
    SpanContext spanContext = tracer.getCurrentSpan().getContext();
    ByteString expectedTraceIdByteStr = ByteString.copyFrom(spanContext.getTraceId().getBytes());
    int expectedTraceOption = new BigInteger(spanContext.getTraceOptions().getBytes()).intValue();
    TagContext expectedTagContext = tagger.currentBuilder().put(METHOD_KEY, METHOD_VALUE).build();

    if (!response.getTraceId().equals(expectedTraceIdByteStr)) {
      succeeded = false;
      logger.severe(
          String.format(
              "Client received bad trace id. got %s, want %s.",
              response.getTraceId(), expectedTraceIdByteStr));
    }

    int spanIdInt = new BigInteger(spanContext.getSpanId().getBytes()).intValue();
    if (spanIdInt == 0) {
      succeeded = false;
      logger.severe("Client received bad span id. Got 0, want non-zero.");
    }

    if (!(response.getTraceOptions() == expectedTraceOption)) {
      succeeded = false;
      logger.severe(
          String.format(
              "Client received bad trace options. got %d, want %d.",
              response.getTraceOptions(), expectedTraceOption));
    }

    try {
      TagContext actualTagContext = serializer.fromByteArray(response.getTagsBlob().toByteArray());
      if (!actualTagContext.equals(expectedTagContext)) {
        succeeded = false;
        logger.severe(
            String.format(
                "Client received wrong TagContext. Got %s, want %s.",
                Lists.<Tag>newArrayList(InternalUtils.getTags(actualTagContext)),
                Lists.<Tag>newArrayList(InternalUtils.getTags(expectedTagContext))));
      }
    } catch (TagContextDeserializationException e) {
      succeeded = false;
      logger.log(Level.SEVERE, "Bad binary format for TagContext.", e);
    }

    return succeeded;
  }

  /** Main launcher of the test client. */
  public static void main(String[] args) {
    for (Entry<String, Integer> setup : GrpcInteropTestUtils.SETUP_MAP.entrySet()) {
      int port = GrpcInteropTestUtils.getPortOrDefault(setup.getKey(), setup.getValue());
      new GrpcInteropTestClient(port).run();
    }
  }
}
