/*
 * Licensed to DuraSpace under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.
 *
 * DuraSpace licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.camel.indexing.http.integration;

import org.apache.camel.CamelContext;
import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.activemq.ActiveMQComponent;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.spring.javaconfig.CamelConfiguration;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.fcrepo.client.FcrepoClient;
import org.fcrepo.client.FcrepoResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockserver.model.HttpRequest;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.verify.VerificationTimes;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.AnnotationConfigContextLoader;

import java.net.URI;

import static java.lang.Integer.parseInt;
import static org.apache.camel.util.ObjectHelper.loadResourceAsStream;
import static org.fcrepo.camel.indexing.http.integration.TestUtils.ASSERT_PERIOD_MS;
import static org.fcrepo.camel.indexing.http.integration.TestUtils.createClient;
import static org.fcrepo.camel.indexing.http.integration.TestUtils.getEvent;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Test the route workflow.
 *
 * @author Aaron Coburn
 * @since 2015-04-10
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {RouteUpdateIT.ContextConfig.class}, loader = AnnotationConfigContextLoader.class)
public class RouteUpdateIT {

    final private Logger logger = getLogger(RouteUpdateIT.class);

    private static ClientAndServer server = null;

    private static final String AS_NS = "https://www.w3.org/ns/activitystreams#";

    private String fullPath = "";

    private static final String MOCKSERVER_PORT = System.getProperty(
            "mockserver.dynamic.test.port", "8080");

    private static final String FCREPO_PORT = System.getProperty(
            "fcrepo.dynamic.test.port", "8080");

    private static final String JMS_PORT = System.getProperty(
            "fcrepo.dynamic.jms.port", "61616");


    @EndpointInject("mock:result")
    protected MockEndpoint resultEndpoint;

    @Produce("direct:start")
    protected ProducerTemplate template;

    @Autowired
    private CamelContext camelContext;

    @BeforeClass
    public static void beforeClass() {
        System.setProperty("http.indexer.enabled", "true");
        System.setProperty("http.baseUrl", "http://localhost:" + MOCKSERVER_PORT + "/endpoint");
        System.setProperty("jms.brokerUrl", "tcp://localhost:" + JMS_PORT);
        System.setProperty("http.input.stream", "direct:start");
        System.setProperty("http.reindex.stream", "direct:reindex");
        System.setProperty("fcrepo.baseUrl", "http://localhost:" + FCREPO_PORT + "/fcrepo/rest");
    }

    @After
    public void tearDownMockServer() throws Exception {
        logger.info("Stopping MockServer");
        server.stop();
    }

    @Before
    public void setUpMockServer() throws Exception {
        final FcrepoClient client = createClient();
        final FcrepoResponse res = client.post(URI.create("http://localhost:" + FCREPO_PORT + "/fcrepo/rest"))
                                         .body(loadResourceAsStream("indexable.ttl"), "text/turtle").perform();
        fullPath = res.getLocation().toString();
        logger.info("full path {}", fullPath);

        mockServer = new WireMockServer(WireMockConfiguration.options().port(parseInt(MOCKSERVER_PORT)));
        mockServer.start();
    }

    @DirtiesContext
    @Test
    public void testAddedEventRouter() throws Exception {
        final String fcrepoEndpoint = "mock:fcrepo:http://localhost:" + FCREPO_PORT + "/fcrepo/rest";
        final String mockServerBase = "http://localhost:" + MOCKSERVER_PORT + "/endpoint";
        final String mockServerEndpoint = "mock:http:localhost:" + MOCKSERVER_PORT + "/endpoint";

        final var context = camelContext.adapt(ModelCamelContext.class);

        server.verify(
            HttpRequest.request()
                .withMethod("POST")
                .withPath("/endpoint")
                .withBody("{id: \"foo\", type: \"bar\"}"),
            VerificationTimes.exactly(1)
        );

        AdviceWith.adviceWith(context, "FcrepoHttpRouter", a -> {
            a.mockEndpoints("*");
        });

        AdviceWith.adviceWith(context, "FcrepoHttpAddType", a -> {
            a.mockEndpoints("*");
        });

        AdviceWith.adviceWith(context, "FcrepoHttpSend", a -> {
            a.mockEndpoints("*");
        });

        final var mockServerMockEndpoint = MockEndpoint.resolve(camelContext, mockServerEndpoint);
        mockServerMockEndpoint.expectedMessageCount(1);
        mockServerMockEndpoint.expectedHeaderReceived(Exchange.HTTP_RESPONSE_CODE, 200);
        final var updateEndpoint = MockEndpoint.resolve(camelContext, "mock://direct:send.to.http");
        updateEndpoint.expectedMessageCount(1);

        final var fcrepoMockEndpoint = MockEndpoint.resolve(camelContext, fcrepoEndpoint);
        fcrepoMockEndpoint.expectedMessageCount(2);

        logger.info("fullPath={}", fullPath);
        template.sendBody("direct:start", getEvent(fullPath, AS_NS + "Create"));

        MockEndpoint.assertIsSatisfied(mockServerMockEndpoint, fcrepoMockEndpoint, updateEndpoint);
    }

    @Configuration
    @ComponentScan(basePackages = "org.fcrepo.camel")
    static class ContextConfig extends CamelConfiguration {
        @Bean
        public ActiveMQComponent broker() {
            final var component = new ActiveMQComponent();
            component.setBrokerURL("tcp://localhost:" + JMS_PORT);
            return component;
        }

    }
}
