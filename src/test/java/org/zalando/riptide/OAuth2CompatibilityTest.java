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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.AsyncRestTemplate;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;
import static org.zalando.riptide.Bindings.on;
import static org.zalando.riptide.Selectors.status;

public class OAuth2CompatibilityTest {

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    private final URI url = URI.create("http://localhost");

    @Test
    public void dispatchesConsumedResponseAgain() throws IOException, ExecutionException, InterruptedException {
        final AsyncRestTemplate template = new AsyncRestTemplate();
        final MockRestServiceServer server = MockRestServiceServer.createServer(template);
        
        server.expect(requestTo(url))
                .andRespond(withUnauthorizedRequest()
                        .body(new byte[]{0x13, 0x37}));

        final Rest rest = Rest.create(template);

        final ClientHttpResponse response = rest.get(url)
                .dispatch(status(),
                        on(UNAUTHORIZED).capture())
                .get().to(ClientHttpResponse.class);

        assertThat(response.getBody().available(), is(2));
    }

    @Test
    public void dispatchesConsumedAsyncResponseAgain() throws IOException {
        final AsyncRestTemplate template = new AsyncRestTemplate();
        final MockRestServiceServer server = MockRestServiceServer.createServer(template);
        
        server.expect(requestTo(url))
                .andRespond(withUnauthorizedRequest()
                        .body(new byte[]{0x13, 0x37}));

        final Rest rest = Rest.create(template);

        final AtomicReference<ClientHttpResponse> reference = new AtomicReference<>();
        
        rest.get(url)
                .dispatch(status(),
                        on(UNAUTHORIZED).call(reference::set));

        assertThat(reference.get().getBody().available(), is(2));
    }

}
