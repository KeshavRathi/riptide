package org.zalando.riptide;

/*
 * ⁣​
 * Riptide
 * ⁣⁣
 * Copyright (C) 2015 Zalando SE
 * ⁣⁣
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ​⁣
 */

import lombok.SneakyThrows;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.AsyncClientHttpRequest;
import org.springframework.http.client.AsyncClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.util.concurrent.FailureCallback;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureAdapter;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.util.concurrent.SettableListenableFuture;
import org.springframework.util.concurrent.SuccessCallback;
import org.springframework.web.client.AsyncRestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.DefaultUriTemplateHandler;
import org.springframework.web.util.UriTemplateHandler;
import org.springframework.web.client.ResourceAccessException;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static java.lang.String.format;

public final class Rest extends RestClient {

    private final AsyncClientHttpRequestFactory asyncClientHttpRequestFactory;
    private final List<HttpMessageConverter<?>> converters;
    private final UriTemplateHandler uriTemplateHandler;

    private Rest(final AsyncClientHttpRequestFactory asyncClientHttpRequestFactory, final List<HttpMessageConverter<?>> converters, final UriTemplateHandler uriTemplateHandler) {
        this.asyncClientHttpRequestFactory = asyncClientHttpRequestFactory;
        this.converters = converters;
        this.uriTemplateHandler = uriTemplateHandler;
    }

    @Override
    protected Requester execute(final HttpMethod method, final String uriTemplate,
            final Object... uriVariables) {
        return execute(method, uriTemplateHandler.expand(uriTemplate, uriVariables));
    }

    @Override
    protected Requester execute(final HttpMethod method, final URI uri) {
        return new ListenableFutureRequester(uri, method);
    }

    private class ListenableFutureRequester extends Requester {

        private final URI url;
        private final HttpMethod method;

        public ListenableFutureRequester(final URI url, final HttpMethod method) {
            this.url = url;
            this.method = method;
        }

        @Override
        protected <T> Dispatcher execute(final HttpHeaders headers, @Nullable final T body) {
            try {
                final HttpEntity<T> entity = new HttpEntity<>(body, headers);
                final AsyncClientHttpRequest request = asyncClientHttpRequestFactory.createAsyncRequest(url, method);
                writeRequestEntity(entity, new AsyncClientHttpRequestAdapter(request));
                final ListenableFuture<ClientHttpResponse> responseFuture = request.executeAsync();
                final ExceptionWrappingFuture future = new ExceptionWrappingFuture(responseFuture);

                return new Dispatcher() {
                    @Override
                    public <A> ListenableFuture<Capture> dispatch(final RoutingTree<A> tree) {
                        final MessageReader reader = new DefaultMessageReader(converters); // TODO cache?
                        final SettableListenableFuture<Capture> capture = new SettableListenableFuture<>();
                        final SuccessCallback<ClientHttpResponse> success = new Success<>(reader, capture, tree);
                        future.addCallback(success, capture::setException);
                        return capture;
                    }
                };
            } catch (final IOException ex) {
                final String message = String.format("I/O error on %s request for \"%s\":%s", method.name(), url, ex.getMessage());
                throw new ResourceAccessException(message, ex);
            }
        }

        private  <T> void writeRequestEntity(final HttpEntity<T> entity, final ClientHttpRequest request)
                throws IOException {
            final HttpHeaders headers = entity.getHeaders();
            request.getHeaders().putAll(headers);

            @Nullable final T body = entity.getBody();

            if (body == null) {
                return;
            }

            final Class<?> type = body.getClass();
            @Nullable final MediaType contentType = headers.getContentType();

            converters.stream()
                    .filter(c -> c.canWrite(type, contentType))
                    .map(this::<T>cast)
                    .findFirst()
                    .orElseThrow(() -> {
                        final String message = format(
                                "Could not write request: no suitable HttpMessageConverter found for request type [%s]",
                                type.getName());

                        if (contentType == null) {
                            return new RestClientException(message);
                        } else {
                            return new RestClientException(format("%s and content type [%s]", message, contentType));
                        }
                    })
                    .write(body, contentType, request);
        }

        @SuppressWarnings("unchecked")
        private <T> HttpMessageConverter<T> cast(final HttpMessageConverter<?> converter) {
            return (HttpMessageConverter<T>) converter;
        }

    }

    static class ExceptionWrappingFuture extends ListenableFutureAdapter<ClientHttpResponse, ClientHttpResponse> {

        public ExceptionWrappingFuture(final ListenableFuture<ClientHttpResponse> clientHttpResponseFuture) {
            super(clientHttpResponseFuture);
        }

        @Override
        protected final ClientHttpResponse adapt(final ClientHttpResponse response) throws ExecutionException {
            return response;
        }
    }

    private static class Success<A> implements SuccessCallback<ClientHttpResponse> {

        private final MessageReader reader;
        private final SettableListenableFuture<Capture> capture;

        private final RoutingTree<A> tree;

        public Success(final MessageReader reader, final SettableListenableFuture<Capture> future,
                final RoutingTree<A> tree) {
            this.reader = reader;
            this.capture = future;
            this.tree = tree;
        }

        @Override
        @SneakyThrows
        public void onSuccess(final ClientHttpResponse response) {
            capture.set(tree.execute(response, reader));
        }

    }

    @Deprecated
    public static Rest create(final AsyncRestTemplate template) {
        return create(template.getAsyncRequestFactory(), template.getMessageConverters(), template.getUriTemplateHandler());
    }

    public static Rest create(final AsyncClientHttpRequestFactory asyncClientHttpRequestFactory, final List<HttpMessageConverter<?>> converters) {
        return create(asyncClientHttpRequestFactory, converters, new DefaultUriTemplateHandler());
    }


    public static Rest create(final AsyncClientHttpRequestFactory asyncClientHttpRequestFactory, final List<HttpMessageConverter<?>> converters, final UriTemplateHandler uriTemplateHandler) {
        return new Rest(asyncClientHttpRequestFactory, converters, uriTemplateHandler);
    }

    public static <T> ListenableFutureCallback<T> handle(final FailureCallback callback) {
        return new ListenableFutureCallback<T>() {
            @Override
            public void onSuccess(final T result) {
                // ignored
            }

            @Override
            public void onFailure(final Throwable ex) {
                callback.onFailure(ex);
            }
        };
    }

}
