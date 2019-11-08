/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
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
package org.apache.jackrabbit.oak.segment.azure.tool;

import com.azure.storage.blob.models.BlobStorageException;
import com.google.common.base.Stopwatch;
import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.oak.commons.Buffer;
import org.apache.jackrabbit.oak.segment.azure.AzurePersistence;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.segment.file.InvalidFileStoreVersionException;
import org.apache.jackrabbit.oak.segment.file.tar.TarPersistence;
import org.apache.jackrabbit.oak.segment.spi.monitor.FileStoreMonitorAdapter;
import org.apache.jackrabbit.oak.segment.spi.monitor.IOMonitorAdapter;
import org.apache.jackrabbit.oak.segment.spi.monitor.RemoteStoreMonitorAdapter;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveManager;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentNodeStorePersistence;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.MessageFormat;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.apache.jackrabbit.oak.segment.compaction.SegmentGCOptions.defaultGCOptions;

/**
 * Utility class for common stuff pertaining to tooling.
 */
public class ToolUtils {

    private ToolUtils() {
        // prevent instantiation
    }

    public enum SegmentStoreType {
        TAR("TarMK Segment Store"), AZURE("Azure Segment Store");

        public static final String AZ_PREFIX = "az:";

        private String type;

        SegmentStoreType(String type) {
            this.type = type;
        }

        public String description(String pathOrUri) {
            String location = withoutAzPrefix(pathOrUri);
            return type + "@" + location;
        }

        @NotNull
        public static String withoutAzPrefix(String location) {
            return StringUtils.removeStart(location, AZ_PREFIX);
        }
    }

    public static FileStore newFileStore(SegmentNodeStorePersistence persistence, File directory,
                                         boolean strictVersionCheck, int segmentCacheSize, long gcLogInterval)
            throws IOException, InvalidFileStoreVersionException, BlobStorageException {
        FileStoreBuilder builder = FileStoreBuilder.fileStoreBuilder(directory)
                .withCustomPersistence(persistence).withMemoryMapping(false).withStrictVersionCheck(strictVersionCheck)
                .withSegmentCacheSize(segmentCacheSize)
                .withGCOptions(defaultGCOptions().setOffline().setGCLogInterval(gcLogInterval));

        return builder.build();
    }

    public static SegmentNodeStorePersistence newSegmentNodeStorePersistence(SegmentStoreType storeType,
                                                                             String pathOrUri) {
        SegmentNodeStorePersistence persistence;

        switch (storeType) {
            case AZURE:
                String uriWithoutPrefix = SegmentStoreType.withoutAzPrefix(pathOrUri);

                String azureSecretKey = System.getenv("AZURE_SECRET_KEY");
                Objects.requireNonNull(azureSecretKey, "Could not connect to the Azure Storage. Please verify if AZURE_SECRET_KEY environment variable "
                        + "is correctly set!");

                persistence = AzurePersistence.createAzurePersistenceByUri(uriWithoutPrefix, azureSecretKey);
                break;
            default:
                persistence = new TarPersistence(new File(pathOrUri));
        }

        return persistence;
    }

    public static SegmentArchiveManager createArchiveManager(SegmentNodeStorePersistence persistence) {
        SegmentArchiveManager archiveManager = null;
        try {
            archiveManager = persistence.createArchiveManager(false, false, new IOMonitorAdapter(),
                    new FileStoreMonitorAdapter(), new RemoteStoreMonitorAdapter());
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Could not access the Azure Storage. Please verify the path provided!");
        }

        return archiveManager;
    }

    public static SegmentStoreType storeTypeFromPathOrUri(String pathOrUri) {
        if (pathOrUri.startsWith(SegmentStoreType.AZ_PREFIX)) {
            return SegmentStoreType.AZURE;
        }

        return SegmentStoreType.TAR;
    }

    public static String storeDescription(SegmentStoreType storeType, String pathOrUri) {
        return storeType.description(pathOrUri);
    }

    public static String printableStopwatch(Stopwatch s) {
        return String.format("%s (%ds)", s, s.elapsed(TimeUnit.SECONDS));
    }

    public static void printMessage(PrintWriter pw, String format, Object... arg) {
        pw.println(MessageFormat.format(format, arg));
    }

    public static byte[] fetchByteArray(Buffer buffer) throws IOException {
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return data;
    }
}
