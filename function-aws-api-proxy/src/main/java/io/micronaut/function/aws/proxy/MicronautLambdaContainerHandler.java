/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.function.aws.proxy;

import com.amazonaws.serverless.exceptions.ContainerInitializationException;
import com.amazonaws.serverless.proxy.AwsProxySecurityContextWriter;
import com.amazonaws.serverless.proxy.internal.testutils.Timer;
import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextProvider;
import io.micronaut.context.env.Environment;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.http.*;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Status;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.http.filter.HttpFilter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.server.binding.RequestArgumentSatisfier;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.jackson.codec.JsonMediaTypeCodec;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.web.router.RouteMatch;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteMatch;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import org.reactivestreams.Publisher;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Main entry for AWS API proxy with Micronaut.
 *
 * @author graemerocher
 * @since 1.1
 */
public final class MicronautLambdaContainerHandler
        extends AbstractLambdaContainerHandler<AwsProxyRequest, AwsProxyResponse, MicronautAwsProxyRequest<?>, MicronautAwsProxyResponse<?>> implements ApplicationContextProvider, Closeable, AutoCloseable {

    private static final String TIMER_INIT = "MICRONAUT_COLD_START";
    private static final String TIMER_REQUEST = "MICRONAUT_HANDLE_REQUEST";
    private final ApplicationContextBuilder applicationContextBuilder;
    private final LambdaContainerState lambdaContainerEnvironment;
    private ApplicationContext applicationContext;
    private RequestArgumentSatisfier requestArgumentSatisfier;
    private ExecutorService executorService;

    /**
     * constructor.
     * @param lambdaContainerEnvironment The container environment
     * @param applicationContextBuilder The context builder
     * @throws ContainerInitializationException if the container couldn't be started
     */
    private MicronautLambdaContainerHandler(
            LambdaContainerState lambdaContainerEnvironment,
            ApplicationContextBuilder applicationContextBuilder) throws ContainerInitializationException {
        super(
                AwsProxyRequest.class,
                AwsProxyResponse.class,
                new MicronautRequestReader(lambdaContainerEnvironment),
                new MicronautResponseWriter(lambdaContainerEnvironment),
                new AwsProxySecurityContextWriter(),
                new MicronautAwsProxyExceptionHandler(lambdaContainerEnvironment)

        );
        ArgumentUtils.requireNonNull("applicationContextBuilder", applicationContextBuilder);
        this.lambdaContainerEnvironment = lambdaContainerEnvironment;
        this.applicationContextBuilder = applicationContextBuilder;
        initialize();
    }

    /**
     * constructor.
     * @param lambdaContainerEnvironment The environment
     * @throws ContainerInitializationException if the container couldn't be started
     */
    private MicronautLambdaContainerHandler(LambdaContainerState lambdaContainerEnvironment) throws ContainerInitializationException {
        this(lambdaContainerEnvironment, ApplicationContext.build());
    }

    /**
     * Gets a new {@link MicronautLambdaContainerHandler} should be called exactly once.
     * @return The {@link MicronautLambdaContainerHandler}
     * @throws ContainerInitializationException if the container couldn't be started
     */
    public static MicronautLambdaContainerHandler getAwsProxyHandler() throws ContainerInitializationException {
        return new MicronautLambdaContainerHandler(new LambdaContainerState());
    }

    /**
     * Gets a new {@link MicronautLambdaContainerHandler} should be called exactly once.
     * @param builder The builder to customize the context creation
     * @return The {@link MicronautLambdaContainerHandler}
     * @throws ContainerInitializationException if the container couldn't be started
     */
    public static MicronautLambdaContainerHandler getAwsProxyHandler(ApplicationContextBuilder builder) throws ContainerInitializationException {
        ArgumentUtils.requireNonNull("builder", builder);
        return new MicronautLambdaContainerHandler(new LambdaContainerState(), builder);
    }

    /**
     * @return The underlying application context
     */
    @Override
    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    @Override
    protected ObjectMapper objectMapper() {
        return lambdaContainerEnvironment.getJsonCodec().getObjectMapper();
    }

    @Override
    protected ObjectWriter writerFor(Class<AwsProxyResponse> responseClass) {
        return lambdaContainerEnvironment
                .getJsonCodec()
                .getObjectMapper()
                .writerFor(responseClass);
    }

    @Override
    protected ObjectReader readerFor(Class<AwsProxyRequest> requestClass) {
        return lambdaContainerEnvironment
                    .getJsonCodec()
                    .getObjectMapper()
                    .readerFor(requestClass);
    }

    @Override
    protected MicronautAwsProxyResponse<?> getContainerResponse(MicronautAwsProxyRequest<?> request, CountDownLatch latch) {
        request.setResponse(new MicronautAwsProxyResponse(
                request.getAwsProxyRequest(),
                latch,
                lambdaContainerEnvironment
        ));
        return request.getResponse();
    }

    @Override
    public void initialize() throws ContainerInitializationException {
        Timer.start(TIMER_INIT);
        try {
            this.applicationContext = applicationContextBuilder.environments(Environment.FUNCTION)
                    .build()
                    .start();
            this.lambdaContainerEnvironment.setApplicationContext(applicationContext);
            this.lambdaContainerEnvironment.setJsonCodec(applicationContext.getBean(JsonMediaTypeCodec.class));
            this.lambdaContainerEnvironment.setRouter(applicationContext.getBean(Router.class));
            this.executorService = applicationContext.getBean(ExecutorService.class, Qualifiers.byName(TaskExecutors.IO));
            this.requestArgumentSatisfier = new RequestArgumentSatisfier(
                    applicationContext.getBean(RequestBinderRegistry.class)
            );
        } catch (Exception e) {
            throw new ContainerInitializationException(
                    "Error starting Micronaut container: " + e.getMessage(),
                    e
            );
        }
        Timer.stop(TIMER_INIT);
    }

    @Override
    protected void handleRequest(
            MicronautAwsProxyRequest<?> containerRequest,
            MicronautAwsProxyResponse<?> containerResponse,
            Context lambdaContext) throws Exception {
        Timer.start(TIMER_REQUEST);

        try {
            // process filters & invoke servlet
            ServerRequestContext.with(containerRequest, () -> {
                final Optional<UriRouteMatch> routeMatch = containerRequest.getAttribute(
                        HttpAttributes.ROUTE_MATCH,
                        UriRouteMatch.class
                );

                if (routeMatch.isPresent()) {
                    try {
                        final UriRouteMatch finalRoute = routeMatch.get();
                        containerRequest.setAttribute(
                                HttpAttributes.ROUTE_MATCH, finalRoute
                        );

                        final MediaType responseContentType = finalRoute.getAnnotationMetadata().getValue(Produces.class, MediaType.class).orElse(null);
                        if (responseContentType != null) {
                            containerResponse.contentType(responseContentType);
                        }

                        final MediaType expectedContentType = finalRoute.getAnnotationMetadata().getValue(Consumes.class, MediaType.class).orElse(null);
                        final MediaType requestContentType = containerRequest.getContentType().orElse(null);

                        if (expectedContentType != null && !expectedContentType.equals(requestContentType)) {
                            containerResponse.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
                            containerResponse.close();
                            return;
                        }

                        final Flowable<MutableHttpResponse<?>> responsePublisher = Flowable.defer(() -> {
                            final RouteMatch<?> boundRoute = requestArgumentSatisfier.fulfillArgumentRequirements(
                                    finalRoute,
                                    containerRequest,
                                    false
                            );
                            final Object result = boundRoute.execute();
                            if (result == null) {
                                applyStatus(containerResponse, finalRoute);
                                return Flowable.just(containerResponse);
                            }
                            if (Publishers.isConvertibleToPublisher(result)) {
                                final Single<?> single = Publishers.convertPublisher(result, Single.class);
                                return single.map((Function<Object, MutableHttpResponse<?>>) o -> {
                                    if (!(o instanceof MicronautAwsProxyResponse)) {
                                        ((MutableHttpResponse) containerResponse).body(o);
                                    }
                                    applyStatus(containerResponse, finalRoute);
                                    return containerResponse;
                                }).toFlowable();
                            } else {
                                if (!(result instanceof MicronautAwsProxyResponse)) {
                                    applyStatus(containerResponse, finalRoute);
                                    ((MutableHttpResponse) containerResponse).body(result);
                                }
                                return Flowable.just(containerResponse);
                            }
                        });

                        final AtomicReference<HttpRequest<?>> requestReference = new AtomicReference<>(containerRequest);
                        final Flowable<? extends MutableHttpResponse<?>> filterPublisher = filterPublisher(
                                requestReference,
                                responsePublisher,
                                executorService
                        );

                        filterPublisher.blockingFirst();
                    } finally {
                        containerResponse.close();
                    }

                } else {
                    try {
                        final Stream<UriRouteMatch<Object, Object>> matches = lambdaContainerEnvironment
                                .getRouter()
                                .findAny(containerRequest.getPath());
                        if (matches.findFirst().isPresent()) {
                            containerResponse.status(HttpStatus.METHOD_NOT_ALLOWED);
                        } else {
                            final MicronautAwsProxyResponse<?> res = containerRequest.getResponse();
                            res.status(HttpStatus.NOT_FOUND);
                            res.close();
                        }
                    } finally {
                        containerResponse.close();
                    }
                }
            });
        } finally {
            Timer.stop(TIMER_REQUEST);
        }


    }

    private void applyStatus(MicronautAwsProxyResponse<?> containerResponse, UriRouteMatch finalRoute) {
        finalRoute.getValue(Status.class, HttpStatus.class).ifPresent(httpStatus -> containerResponse.status(httpStatus));
    }

    @Override
    public void close() throws IOException {
        this.applicationContext.close();
    }

    private Flowable<? extends MutableHttpResponse<?>> filterPublisher(
            AtomicReference<HttpRequest<?>> requestReference,
            Publisher<MutableHttpResponse<?>> routePublisher, ExecutorService executor) {
        Publisher<? extends io.micronaut.http.MutableHttpResponse<?>> finalPublisher;
        List<HttpFilter> filters = new ArrayList<>(lambdaContainerEnvironment.getRouter().findFilters(requestReference.get()));
        if (!filters.isEmpty()) {
            // make the action executor the last filter in the chain
            filters.add((HttpServerFilter) (req, chain) -> routePublisher);

            AtomicInteger integer = new AtomicInteger();
            int len = filters.size();
            ServerFilterChain filterChain = new LambdaFilterChain(integer, len, filters, requestReference);
            HttpFilter httpFilter = filters.get(0);
            Publisher<? extends HttpResponse<?>> resultingPublisher = httpFilter.doFilter(requestReference.get(), filterChain);
            finalPublisher = (Publisher<? extends MutableHttpResponse<?>>) resultingPublisher;
        } else {
            finalPublisher = routePublisher;
        }

        // Handle the scheduler to subscribe on
        if (finalPublisher instanceof Flowable) {
            return ((Flowable<MutableHttpResponse<?>>) finalPublisher)
                    .subscribeOn(Schedulers.from(executor));
        } else {
            return Flowable.fromPublisher(finalPublisher)
                    .subscribeOn(Schedulers.from(executor));
        }
    }

    /**
     * Holds state for the running container.
     */
    private static class LambdaContainerState implements MicronautLambdaContainerContext {
        private Router router;
        private ApplicationContext applicationContext;
        private JsonMediaTypeCodec jsonCodec;

        @Override
        public Router getRouter() {
            return router;
        }

        @Override
        public JsonMediaTypeCodec getJsonCodec() {
            return jsonCodec;
        }

        @Override
        public ApplicationContext getApplicationContext() {
            return applicationContext;
        }

        void setJsonCodec(JsonMediaTypeCodec jsonCodec) {
            this.jsonCodec = jsonCodec;
        }

        void setRouter(Router router) {
            this.router = router;
        }

        void setApplicationContext(ApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }
    }

    /**
     * Implementation of {@link ServerFilterChain} for Lambda.
     */
    private static class LambdaFilterChain implements ServerFilterChain {
        private final AtomicInteger integer;
        private final int len;
        private final List<HttpFilter> filters;
        private final AtomicReference<HttpRequest<?>> requestReference;

        LambdaFilterChain(
                AtomicInteger integer,
                int len,
                List<HttpFilter> filters,
                AtomicReference<HttpRequest<?>> requestReference) {
            this.integer = integer;
            this.len = len;
            this.filters = filters;
            this.requestReference = requestReference;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Publisher<MutableHttpResponse<?>> proceed(HttpRequest<?> request) {
            int pos = integer.incrementAndGet();
            if (pos > len) {
                throw new IllegalStateException("The FilterChain.proceed(..) method should be invoked exactly once per filter execution. The method has instead been invoked multiple times by an erroneous filter definition.");
            }
            HttpFilter httpFilter = filters.get(pos);
            return (Publisher<MutableHttpResponse<?>>) httpFilter.doFilter(requestReference.getAndSet(request), this);
        }
    }
}
