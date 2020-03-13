/*
 * Copyright (c) 2015 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.tchannel.tracing;

import com.google.common.collect.ImmutableMap;
import com.uber.tchannel.api.handlers.TFutureCallback;
import com.uber.tchannel.handlers.OutRequest;
import com.uber.tchannel.messages.Request;
import com.uber.tchannel.messages.Response;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.jaegertracing.internal.JaegerSpanContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public final class Tracing {

    private static final Logger logger = LoggerFactory.getLogger(Tracing.class);

    /**
     * Prefix used with all keys generated by the tracer when marshalling SpanContext
     * to a map of headers.
     * <p>
     * This string value is shared across all implementations of TChannel.
     */
    public static final String HEADER_KEY_PREFIX = "$tracing$";

    private Tracing() {}

    /**
     * @throws RuntimeException
     *     if the outbound request should fail immediately
     */
    public static <V extends Response> void startOutboundSpan(
            @NotNull OutRequest<V> outRequest,
            @Nullable Tracer tracer,
            @Nullable TracingContext tracingContext
    ) throws RuntimeException {
        if (tracer == null || tracingContext == null) {
            return;
        }

        Request request = outRequest.getRequest();

        Tracer.SpanBuilder builder = tracer.buildSpan(request.getEndpoint());
        if (tracingContext.hasSpan()) {
            builder.asChildOf(tracingContext.currentSpan().context());
        }
        // TODO add tags for peer host:port
        builder
            .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
            .withTag(Tags.PEER_SERVICE.getKey(), request.getService())
            .withTag("as", request.getArgScheme().name());

        final Span span = builder.startManual();

        if (tracingContext instanceof RequestSpanInterceptor) {
            try {
                ((RequestSpanInterceptor) tracingContext).interceptOutbound(request, span);
            } catch (RuntimeException e) {
                span.log(ImmutableMap.of("exception", e));
                span.finish();
                throw e;
            }
        }

        // if Jaeger span context, set Trace fields
        if (span.context() instanceof JaegerSpanContext) {
            JaegerSpanContext jaegerSpanContext = (JaegerSpanContext) span.context();
            Trace trace = new Trace(
                jaegerSpanContext.getSpanId(),
                jaegerSpanContext.getParentId(),
                // tchannel only support 64bit IDs, https://github.com/uber/tchannel/blob/master/docs/protocol.md#tracing
                jaegerSpanContext.getTraceIdLow(),
                jaegerSpanContext.getFlags());
            request.setTrace(trace);
        }

        // if request has headers, inject tracing context
        if (request instanceof TraceableRequest) {
            TraceableRequest traceableRequest = (TraceableRequest) request;
            //Format.Builtin.TEXT_MAP
            Map<String, String> headers = traceableRequest.getHeaders();
            PrefixedHeadersCarrier carrier = new PrefixedHeadersCarrier(headers);
            try {
                tracer.inject(span.context(), Format.Builtin.TEXT_MAP, carrier);
                traceableRequest.setHeaders(headers);
            } catch (Exception e) {
                logger.error("Failed to inject span context into headers", e);
            }
        }
        outRequest.getFuture().addCallback(new TFutureCallback<V>() {
            @Override
            public void onResponse(Response response) {
                if (response.isError()) {
                    Tags.ERROR.set(span, true);
                    span.log(response.getError().getMessage());
                }
                span.finish();
            }
        });
    }

    /**
     * @throws RuntimeException
     *     if the inbound request should fail immediately
     */
    public static @NotNull Span startInboundSpan(
        @NotNull Request request,
        @NotNull Tracer tracer,
        @NotNull TracingContext tracingContext
    ) throws RuntimeException {
        tracingContext.clear();
        Tracer.SpanBuilder builder = tracer.buildSpan(request.getEndpoint());
        SpanContext parent = null;

        if (request instanceof TraceableRequest) {
            TraceableRequest traceableRequest = (TraceableRequest) request;
            Map<String, String> headers = traceableRequest.getHeaders();
            PrefixedHeadersCarrier carrier = new PrefixedHeadersCarrier(headers);
            try {
                parent = tracer.extract(Format.Builtin.TEXT_MAP, carrier);
                Map<String, String> nonTracingHeaders = carrier.getNonTracingHeaders();
                if (nonTracingHeaders.size() < headers.size()) {
                    traceableRequest.setHeaders(nonTracingHeaders);
                }
            } catch (RuntimeException e) {
                logger.error("Failed to extract span context from headers", e);
            }
        }

        // if parent isn't in headers, try to extract parent from request Trace fields
        if (parent == null && request.getTrace() != null) {
            Trace trace = request.getTrace();
            parent = new JaegerSpanContext(
                // tchannel only support 64bit IDs, https://github.com/uber/tchannel/blob/master/docs/protocol.md#tracing
                0,
                trace.traceId,
                trace.spanId,
                trace.parentId,
                trace.traceFlags);
        }

        builder
            .asChildOf(parent)
            .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
            .withTag("as", request.getArgScheme().name());
        Map<String, String> transportHeaders = request.getTransportHeaders();
        if (transportHeaders != null && transportHeaders.containsKey("cn")) {
            builder.withTag(Tags.PEER_SERVICE.getKey(), transportHeaders.get("cn"));
        }
        // TODO add tags for peer host:port

        Span span = builder.startManual();

        // invoke additional processing of the request and the span by request span interceptor(s), if any
        if (tracingContext instanceof RequestSpanInterceptor) {
            try {
                ((RequestSpanInterceptor) tracingContext).interceptInbound(request, span);
            } catch (RuntimeException e) {
                span.log(ImmutableMap.of("exception", e));
                span.finish();
                throw e;
            }
        }

        tracingContext.pushSpan(span);
        return span;
    }

}
