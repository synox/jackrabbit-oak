/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment.azure;


import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobStorageException;
import org.apache.jackrabbit.oak.segment.azure.compat.CloudBlobDirectory;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFileReader;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFileWriter;
import org.junit.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AzureJournalFileConcurrencyIT {
    private static final Logger log = LoggerFactory.getLogger(AzureJournalFileConcurrencyIT.class);

    private static BlobContainerClient container;

    private static int suffix;

    private AzurePersistence persistence;
    private static String containerName;

    @BeforeClass
    public static void connectToAzure() throws URISyntaxException, InvalidKeyException, BlobStorageException {
        String azureConnectionString = System.getenv("AZURE_CONNECTION");
        Assume.assumeNotNull(azureConnectionString);

        containerName = "oak-test-" + System.currentTimeMillis();
         container = new BlobServiceClientBuilder()
                .connectionString(azureConnectionString)
                .buildClient()
                .getBlobContainerClient(containerName);

        if (!container.exists()) container.create();
        suffix = 1;
    }

    @Before
    public void setup() throws BlobStorageException, InvalidKeyException, URISyntaxException, IOException, InterruptedException {
        String directoryName = "oak-" + (suffix++);
        persistence = new AzurePersistence(new CloudBlobDirectory(container, containerName, directoryName));
        writeJournalLines(300, 0);
        log.info("Finished writing initial content to journal!");
    }

    @AfterClass
    public static void cleanupContainer() throws BlobStorageException {
        if (container != null && container.exists()) {
            container.delete();
        }
    }

    @Test
    public void testConcurrency() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Exception> exContainer = new AtomicReference<>();

        Thread producer = new Thread(() -> {
            try {
                while (!stop.get()) {
                    writeJournalLines(300, 100);
                }
            } catch(Exception e) {
                exContainer.set(e);
                stop.set(true);
            }
        });

        Thread consumer = new Thread(() -> {
            try {
                while (!stop.get()) {
                    readJournal();
                }
            } catch (IOException e) {
                exContainer.set(e);
                stop.set(true);
            }
        });

        producer.start();
        consumer.start();

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 30_000 && !stop.get()) {
            Thread.sleep(100);
        }
        stop.set(true);

        producer.join();
        consumer.join();

        if (exContainer.get() != null) {
            throw exContainer.get();
        }
    }

    private void readJournal() throws IOException {
        JournalFile file = persistence.getJournalFile();
        try (JournalFileReader reader = file.openJournalReader()) {
            String line = null;
            while ((line = reader.readLine()) != null) {
                log.info(line);
            }
        }
    }

    private void writeJournalLines(int lines, int delayMillis) throws IOException, InterruptedException {
        JournalFile file = persistence.getJournalFile();
        try (JournalFileWriter writer = file.openJournalWriter()) {
            for (int i = 0; i < lines; i++) {
                writer.writeLine(String.format("%4X - %s", i, UUID.randomUUID().toString()));
                Thread.sleep(delayMillis);
            }
        }
    }

}