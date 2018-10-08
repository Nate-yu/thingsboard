/**
 * Copyright © 2016-2018 The Thingsboard Authors
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
package org.thingsboard.server.service.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.dao.device.DeviceCredentialsService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.gen.transport.TransportProtos.DeviceInfoProto;
import org.thingsboard.server.gen.transport.TransportProtos.TransportApiRequestMsg;
import org.thingsboard.server.gen.transport.TransportProtos.TransportApiResponseMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ValidateDeviceTokenRequestMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ValidateDeviceTokenResponseMsg;
import org.thingsboard.server.kafka.TBKafkaConsumerTemplate;
import org.thingsboard.server.kafka.TBKafkaProducerTemplate;
import org.thingsboard.server.kafka.TbKafkaResponseTemplate;
import org.thingsboard.server.kafka.TbKafkaSettings;
import org.thingsboard.server.service.cluster.discovery.DiscoveryService;

import javax.annotation.PostConstruct;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by ashvayka on 05.10.18.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "transport.remote", value = "enabled", havingValue = "true")
public class RemoteTransportApiService implements TransportApiService {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${transport.remote.transport_api.requests_topic}")
    private String transportApiRequestsTopic;
    @Value("${transport.remote.transport_api.responses_topic}")
    private String transportApiResponsesTopic;
    @Value("${transport.remote.transport_api.max_pending_requests}")
    private int maxPendingRequests;
    @Value("${transport.remote.transport_api.request_timeout}")
    private long requestTimeout;
    @Value("${transport.remote.transport_api.request_poll_interval}")
    private int responsePollDuration;
    @Value("${transport.remote.transport_api.request_auto_commit_interval}")
    private int autoCommitInterval;

    @Autowired
    private TbKafkaSettings kafkaSettings;

    @Autowired
    private DiscoveryService discoveryService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private DeviceCredentialsService deviceCredentialsService;

    private ExecutorService transportCallbackExecutor;

    private TbKafkaResponseTemplate<TransportApiRequestMsg, TransportApiResponseMsg> transportApiTemplate;

    @PostConstruct
    public void init() {
        this.transportCallbackExecutor = Executors.newCachedThreadPool();

        TBKafkaProducerTemplate.TBKafkaProducerTemplateBuilder<TransportApiResponseMsg> responseBuilder = TBKafkaProducerTemplate.builder();
        responseBuilder.settings(kafkaSettings);
        responseBuilder.defaultTopic(transportApiResponsesTopic);
        responseBuilder.encoder(new TransportApiResponseEncoder());

        TBKafkaConsumerTemplate.TBKafkaConsumerTemplateBuilder<TransportApiRequestMsg> requestBuilder = TBKafkaConsumerTemplate.builder();
        requestBuilder.settings(kafkaSettings);
        requestBuilder.topic(transportApiRequestsTopic);
        requestBuilder.clientId(discoveryService.getNodeId());
        requestBuilder.groupId("tb-node");
        requestBuilder.autoCommit(true);
        requestBuilder.autoCommitIntervalMs(autoCommitInterval);
        requestBuilder.decoder(new TransportApiRequestDecoder());

        TbKafkaResponseTemplate.TbKafkaResponseTemplateBuilder
                <TransportApiRequestMsg, TransportApiResponseMsg> builder = TbKafkaResponseTemplate.builder();
        builder.requestTemplate(requestBuilder.build());
        builder.responseTemplate(responseBuilder.build());
        builder.maxPendingRequests(maxPendingRequests);
        builder.requestTimeout(requestTimeout);
        builder.pollInterval(responsePollDuration);
        builder.executor(transportCallbackExecutor);
        builder.handler(this);
        transportApiTemplate = builder.build();
        transportApiTemplate.init();
    }

    @Override
    public ListenableFuture<TransportApiResponseMsg> handle(TransportApiRequestMsg transportApiRequestMsg) throws Exception {
        if (transportApiRequestMsg.hasValidateTokenRequestMsg()) {
            ValidateDeviceTokenRequestMsg msg = transportApiRequestMsg.getValidateTokenRequestMsg();
            //TODO: Make async and enable caching
            DeviceCredentials credentials = deviceCredentialsService.findDeviceCredentialsByCredentialsId(msg.getToken());
            if (credentials != null && credentials.getCredentialsType() == DeviceCredentialsType.ACCESS_TOKEN) {
                return getDeviceInfo(credentials.getDeviceId());
            } else {
                return getEmptyTransportApiResponseFuture();
            }
        }
        return getEmptyTransportApiResponseFuture();
    }

    private ListenableFuture<TransportApiResponseMsg> getDeviceInfo(DeviceId deviceId) {
        return Futures.transform(deviceService.findDeviceByIdAsync(deviceId), device -> {
            if (device == null) {
                log.trace("[{}] Failed to lookup device by id", deviceId);
                return getEmptyTransportApiResponse();
            }
            try {
                DeviceInfoProto deviceInfoProto = DeviceInfoProto.newBuilder()
                        .setTenantIdMSB(device.getTenantId().getId().getMostSignificantBits())
                        .setTenantIdLSB(device.getTenantId().getId().getLeastSignificantBits())
                        .setDeviceIdMSB(device.getId().getId().getMostSignificantBits())
                        .setDeviceIdLSB(device.getId().getId().getLeastSignificantBits())
                        .setDeviceName(device.getName())
                        .setDeviceType(device.getType())
                        .setAdditionalInfo(mapper.writeValueAsString(device.getAdditionalInfo()))
                        .build();
                return TransportApiResponseMsg.newBuilder()
                        .setValidateTokenResponseMsg(ValidateDeviceTokenResponseMsg.newBuilder().setDeviceInfo(deviceInfoProto).build()).build();
            } catch (JsonProcessingException e) {
                log.warn("[{}] Failed to lookup device by id", deviceId, e);
                return getEmptyTransportApiResponse();
            }
        });
    }

    private ListenableFuture<TransportApiResponseMsg> getEmptyTransportApiResponseFuture() {
        return Futures.immediateFuture(getEmptyTransportApiResponse());
    }

    private TransportApiResponseMsg getEmptyTransportApiResponse() {
        return TransportApiResponseMsg.newBuilder()
                .setValidateTokenResponseMsg(ValidateDeviceTokenResponseMsg.getDefaultInstance()).build();
    }
}
