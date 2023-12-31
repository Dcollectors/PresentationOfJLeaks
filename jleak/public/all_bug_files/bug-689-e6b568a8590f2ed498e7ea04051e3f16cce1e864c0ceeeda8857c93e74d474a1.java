/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.grpc;

import java.util.Map;
import java.util.function.Function;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.DefaultHttpRequest;
import com.linecorp.armeria.common.http.DefaultHttpResponse;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer.ByteBufOrStream;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer.Listener;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageFramer;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.http.ByteBufHttpData;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerMethodDefinition;
import io.grpc.Status;
import io.netty.buffer.ByteBuf;

/**
 * A {@link SimpleDecoratingService} which allows {@link GrpcService} to serve requests without the framing
 * specified by the GRPC wire protocol. This can be useful for serving both legacy systems and GRPC clients with
 * the same business logic.
 *
 * <p>Limitations:
 * <ul>
 *     <li>Only unary methods (single request, single response) are supported.</li>
 *     <li>
 *         Message compression is not supported.
 *         {@link com.linecorp.armeria.server.http.encoding.HttpEncodingService} should be used instead for
 *         transport level encoding.
 *     </li>
 * </ul>
 */
class UnframedGrpcService extends SimpleDecoratingService<HttpRequest, HttpResponse> {

    private final Map<String, MethodDescriptor<?, ?>> methodsByName;

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     */
    UnframedGrpcService(Service<HttpRequest, HttpResponse> delegate) {
        super(delegate);
        GrpcService grpcService =
                delegate.as(GrpcService.class)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Decorated service must be a GrpcService."));
        methodsByName = grpcService.services()
                                   .stream()
                                   .flatMap(service ->  service.getMethods().stream())
                                   .map(ServerMethodDefinition::getMethodDescriptor)
                                   .collect(ImmutableMap.toImmutableMap(MethodDescriptor::getFullMethodName,
                                                                        Function.identity()));
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpHeaders clientHeaders = req.headers();
        final String contentType = clientHeaders.get(HttpHeaderNames.CONTENT_TYPE);
        if (contentType == null) {
            // All GRPC requests, whether framed or non-framed, must have content-type. If it's not sent, let
            // the delegate return its usual error message.
            return delegate().serve(ctx, req);
        }

        MediaType mediaType = MediaType.parse(contentType);
        for (SerializationFormat format : GrpcSerializationFormats.values()) {
            if (format.isAccepted(mediaType)) {
                // Framed request, so just delegate.
                return delegate().serve(ctx, req);
            }
        }

        String methodName = GrpcRequestUtil.determineMethod(ctx);
        MethodDescriptor<?, ?> method = methodName != null ? methodsByName.get(methodName) : null;
        if (method == null) {
            // Unknown method, let the delegate return a usual error.
            return delegate().serve(ctx, req);
        }

        DefaultHttpResponse res = new DefaultHttpResponse();

        if (method.getType() != MethodType.UNARY) {
            res.respond(HttpStatus.BAD_REQUEST,
                        MediaType.PLAIN_TEXT_UTF_8,
                        "Only unary methods can be used with non-framed requests.");
            return res;
        }

        HttpHeaders grpcHeaders = HttpHeaders.copyOf(clientHeaders);

        if (mediaType.is(MediaType.PROTOBUF)) {
            grpcHeaders.set(
                    HttpHeaderNames.CONTENT_TYPE, GrpcSerializationFormats.PROTO.mediaType().toString());
        } else {
            res.respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        MediaType.PLAIN_TEXT_UTF_8,
                        "Unsupported media type. Only application/protobuf is supported.");
            return res;
        }

        if (grpcHeaders.get(GrpcHeaderNames.GRPC_ENCODING) != null) {
            res.respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        MediaType.PLAIN_TEXT_UTF_8,
                        "GRPC encoding is not supported for non-framed requests.");
        }

        // All clients support no encoding, and we don't support GRPC encoding for non-framed requests, so just
        // clear the header if it's present.
        grpcHeaders.remove(GrpcHeaderNames.GRPC_ACCEPT_ENCODING);

        req.aggregate().whenCompleteAsync(
                (clientRequest, t) -> {
                    if (t != null) {
                        res.close(t);
                    } else {
                        frameAndServe(ctx, grpcHeaders, clientRequest, res);
                    }
                },
                ctx.eventLoop());

        return res;
    }

    private void frameAndServe(
            ServiceRequestContext ctx,
            HttpHeaders grpcHeaders,
            AggregatedHttpMessage clientRequest,
            DefaultHttpResponse res) {
        final ArmeriaMessageFramer framer = new ArmeriaMessageFramer(
                ctx.alloc(), ArmeriaMessageFramer.NO_MAX_OUTBOUND_MESSAGE_SIZE);

        HttpData content = clientRequest.content();
        ByteBuf message = ctx.alloc().buffer(content.length());
        message.writeBytes(content.array(), content.offset(), content.length());

        HttpData frame = framer.writePayload(message);
        DefaultHttpRequest grpcRequest = new DefaultHttpRequest(grpcHeaders);
        grpcRequest.write(frame);
        grpcRequest.close();

        final HttpResponse grpcResponse;
        try {
            grpcResponse = delegate().serve(ctx, grpcRequest);
        } catch (Exception e) {
            res.close(e);
            return;
        }

        grpcResponse.aggregate().whenCompleteAsync(
                (framedResponse, t) -> {
                    if (t != null) {
                        res.close(t);
                    } else {
                        deframeAndRespond(ctx, framedResponse, res);
                    }
                },
                ctx.eventLoop());
    }

    private void deframeAndRespond(
            ServiceRequestContext ctx, AggregatedHttpMessage grpcResponse, DefaultHttpResponse res) {
        HttpHeaders trailers = !grpcResponse.trailingHeaders().isEmpty() ?
                               grpcResponse.trailingHeaders() : grpcResponse.headers();
        String grpcStatusCode = trailers.get(GrpcHeaderNames.GRPC_STATUS);
        Status grpcStatus = Status.fromCodeValue(Integer.parseInt(grpcStatusCode));

        if (!grpcStatus.getCode().equals(Status.OK.getCode())) {
            StringBuilder message = new StringBuilder("grpc-status: " + grpcStatusCode);
            String grpcMessage = trailers.get(GrpcHeaderNames.GRPC_MESSAGE);
            if (grpcMessage != null) {
                message.append(", ").append(grpcMessage);
            }
            res.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                        MediaType.PLAIN_TEXT_UTF_8,
                        message.toString());
            return;
        }

        MediaType grpcMediaType = MediaType.parse(
                grpcResponse.headers().get(HttpHeaderNames.CONTENT_TYPE));
        HttpHeaders unframedHeaders = HttpHeaders.copyOf(grpcResponse.headers());
        if (grpcMediaType.is(GrpcSerializationFormats.PROTO.mediaType())) {
            unframedHeaders.set(HttpHeaderNames.CONTENT_TYPE, MediaType.PROTOBUF.toString());
        }

        ArmeriaMessageDeframer deframer = new ArmeriaMessageDeframer(
                new Listener() {
                    @Override
                    public void messageRead(ByteBufOrStream message) {
                        // We know there is only one message in total, so don't bother with checking endOfStream
                        // We also know that we don't support compression, so this is always a ByteBuffer.
                        HttpData unframedContent = new ByteBufHttpData(message.buf(), true);
                        res.respond(AggregatedHttpMessage.of(
                                unframedHeaders, unframedContent));
                    }

                    @Override
                    public void endOfStream() {}
                },
                // Max outbound message size is handled by the GrpcService, so we don't need to set it here.
                Integer.MAX_VALUE,
                ctx.alloc());
        deframer.request(1);
        deframer.deframe(grpcResponse.content(), true);
    }
}
