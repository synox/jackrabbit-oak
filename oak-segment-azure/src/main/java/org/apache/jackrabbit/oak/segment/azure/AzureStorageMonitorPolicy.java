/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.segment.azure;

import java.util.concurrent.TimeUnit;

import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import org.apache.jackrabbit.oak.segment.spi.monitor.RemoteStoreMonitor;
import org.jetbrains.annotations.NotNull;
import reactor.core.publisher.Mono;

/**
 * TODO OAK-8413: verify
 */
public class AzureStorageMonitorPolicy implements HttpPipelinePolicy {
    private RemoteStoreMonitor monitor;

    public void setMonitor(@NotNull final RemoteStoreMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
        long start = System.currentTimeMillis();
        return next.process()
                .doOnNext(rsp -> {
                    monitor.requestDuration(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
                    monitor.requestCount();
                })
                .doOnError(err -> monitor.requestError());
    }
}