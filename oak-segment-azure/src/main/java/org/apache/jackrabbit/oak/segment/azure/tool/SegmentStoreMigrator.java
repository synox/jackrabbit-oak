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
package org.apache.jackrabbit.oak.segment.azure.tool;

import org.apache.jackrabbit.oak.commons.Buffer;
import org.apache.jackrabbit.oak.segment.azure.AzurePersistence;
import org.apache.jackrabbit.oak.segment.azure.compat.CloudBlobDirectory;
import org.apache.jackrabbit.oak.segment.azure.tool.ToolUtils.SegmentStoreType;
import org.apache.jackrabbit.oak.segment.file.tar.TarPersistence;
import org.apache.jackrabbit.oak.segment.spi.RepositoryNotReachableException;
import org.apache.jackrabbit.oak.segment.spi.monitor.FileStoreMonitorAdapter;
import org.apache.jackrabbit.oak.segment.spi.monitor.IOMonitorAdapter;
import org.apache.jackrabbit.oak.segment.spi.monitor.RemoteStoreMonitorAdapter;
import org.apache.jackrabbit.oak.segment.spi.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

import static org.apache.jackrabbit.oak.segment.azure.tool.ToolUtils.fetchByteArray;
import static org.apache.jackrabbit.oak.segment.azure.tool.ToolUtils.storeDescription;

public class SegmentStoreMigrator implements Closeable  {

    private static final Logger log = LoggerFactory.getLogger(SegmentStoreMigrator.class);

    private static final int READ_THREADS = 20;

    private final SegmentNodeStorePersistence source;

    private final SegmentNodeStorePersistence target;

    private final String sourceName;

    private final String targetName;

    private final boolean appendMode;

    private final boolean onlyLastJournalEntry;

    private ExecutorService executor = Executors.newFixedThreadPool(READ_THREADS + 1);

    private SegmentStoreMigrator(Builder builder) {
        this.source = builder.source;
        this.target = builder.target;
        this.sourceName = builder.sourceName;
        this.targetName = builder.targetName;
        this.appendMode = builder.appendMode;
        this.onlyLastJournalEntry = builder.onlyLastJournalEntry;
    }

    public void migrate() throws IOException, ExecutionException, InterruptedException {
        runWithRetry(() -> migrateJournal(), 16, 5);
        runWithRetry(() -> migrateGCJournal(), 16, 5);
        runWithRetry(() -> migrateManifest(), 16, 5);
        migrateArchives();
    }

    private Void migrateJournal() throws IOException {
        log.info("{}/journal.log -> {}", sourceName, targetName);
        if (!source.getJournalFile().exists()) {
            log.info("No journal at {}; skipping.", sourceName);
            return null;
        }
        List<String> journal = new ArrayList<>();

        try (JournalFileReader reader = source.getJournalFile().openJournalReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    journal.add(line);
                }
                if (!journal.isEmpty() && onlyLastJournalEntry) {
                    break;
                }
            }
        }
        Collections.reverse(journal);

        try (JournalFileWriter writer = target.getJournalFile().openJournalWriter()) {
            writer.truncate();
            for (String line : journal) {
                writer.writeLine(line);
            }
        }
        return null;
    }

    private Void migrateGCJournal() throws IOException {
        log.info("{}/gc.log -> {}", sourceName, targetName);
        GCJournalFile targetGCJournal = target.getGCJournalFile();
        if (appendMode) {
            targetGCJournal.truncate();
        }
        for (String line : source.getGCJournalFile().readLines()) {
            targetGCJournal.writeLine(line);
        }
        return null;
    }

    private Void migrateManifest() throws IOException {
        log.info("{}/manifest -> {}", sourceName, targetName);
        if (!source.getManifestFile().exists()) {
            log.info("No manifest at {}; skipping.", sourceName);
            return null;
        }
        Properties manifest = source.getManifestFile().load();
        target.getManifestFile().save(manifest);
        return null;
    }

    private void migrateArchives() throws IOException, ExecutionException, InterruptedException {
        if (!source.segmentFilesExist()) {
            log.info("No segment archives at {}; skipping.", sourceName);
            return;
        }
        SegmentArchiveManager sourceManager = source.createArchiveManager(false, false, new IOMonitorAdapter(),
                new FileStoreMonitorAdapter(), new RemoteStoreMonitorAdapter());
        SegmentArchiveManager targetManager = target.createArchiveManager(false, false, new IOMonitorAdapter(),
                new FileStoreMonitorAdapter(), new RemoteStoreMonitorAdapter());
        List<String> targetArchives = targetManager.listArchives();
        for (String archiveName : sourceManager.listArchives()) {
            log.info("{}/{} -> {}", sourceName, archiveName, targetName);
            if (appendMode && targetArchives.contains(archiveName)) {
                log.info("Already exists, skipping.");
                continue;
            }
            try (SegmentArchiveReader reader = sourceManager.forceOpen(archiveName)) {
                SegmentArchiveWriter writer = targetManager.create(archiveName);
                try {
                    migrateSegments(reader, writer);
                    migrateBinaryRef(reader, writer);
                    migrateGraph(reader, writer);
                } finally {
                    writer.close();
                }
            }
        }
    }

    private void migrateSegments(SegmentArchiveReader reader, SegmentArchiveWriter writer) throws ExecutionException, InterruptedException, IOException {
        List<Future<Segment>> futures = new ArrayList<>();
        for (SegmentArchiveEntry entry : reader.listSegments()) {
            futures.add(executor.submit(() -> runWithRetry(() -> {
                Segment segment = new Segment(entry);
                segment.read(reader);
                return segment;
            }, 16, 5)));
        }

        for (Future<Segment> future : futures) {
            Segment segment = future.get();
            segment.write(writer);
        }
    }

    private void migrateBinaryRef(SegmentArchiveReader reader, SegmentArchiveWriter writer) throws IOException {
        Buffer binaryReferences = reader.getBinaryReferences();
        if (binaryReferences != null) {
            byte[] array = fetchByteArray(binaryReferences);
            writer.writeBinaryReferences(array);
        }
    }

    private void migrateGraph(SegmentArchiveReader reader, SegmentArchiveWriter writer) throws IOException {
        if (reader.hasGraph()) {
            Buffer graph = reader.getGraph();
            byte[] array = fetchByteArray(graph);
            writer.writeGraph(array);
        }
    }

    private static <T> T runWithRetry(Producer<T> producer, int maxAttempts, int intervalSec) throws IOException {
        IOException ioException = null;
        RepositoryNotReachableException repoNotReachableException = null;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                return producer.produce();
            } catch (IOException e) {
                log.error("Can't execute the operation. Retrying (attempt {})", i, e);
                ioException = e;
            } catch (RepositoryNotReachableException e) {
                log.error("Can't execute the operation. Retrying (attempt {})", i, e);
                repoNotReachableException = e;
            }
            try {
                Thread.sleep(intervalSec * 1000);
            } catch (InterruptedException e) {
                log.error("Interrupted", e);
            }
        }
        if (ioException != null) {
            throw ioException;
        } else if (repoNotReachableException != null) {
            throw repoNotReachableException;
        } else {
            throw new IllegalStateException();
        }
    }

    @Override
    public void close() throws IOException {
        executor.shutdown();
        try {
            while (!executor.awaitTermination(100, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    @FunctionalInterface
    private interface Producer<T> {
        T produce() throws IOException;
    }

    private static class Segment {

        private final SegmentArchiveEntry entry;

        private volatile Buffer data;

        private Segment(SegmentArchiveEntry entry) {
            this.entry = entry;
        }

        private void read(SegmentArchiveReader reader) throws IOException {
            data = reader.readSegment(entry.getMsb(), entry.getLsb());
        }

        private void write(SegmentArchiveWriter writer) throws IOException {
            final byte[] array = data.array();
            final int offset = 0;
            writer.writeSegment(entry.getMsb(), entry.getLsb(), array, offset, entry.getLength(), entry.getGeneration(),
                    entry.getFullGeneration(), entry.isCompacted());
        }

        @Override
        public String toString() {
            return new UUID(entry.getMsb(), entry.getLsb()).toString();
        }
    }

    public static class Builder {

        private SegmentNodeStorePersistence source;

        private SegmentNodeStorePersistence target;

        private String sourceName;

        private String targetName;

        private boolean appendMode;

        private boolean onlyLastJournalEntry;

        public Builder withSource(File dir) {
            this.source = new TarPersistence(dir);
            this.sourceName = storeDescription(SegmentStoreType.TAR, dir.getPath());
            return this;
        }

        public Builder withSource(CloudBlobDirectory dir){
            this.source = new AzurePersistence(dir);
            this.sourceName = storeDescription(SegmentStoreType.AZURE, Paths.get(dir.getContainerName(), dir.getPrefix()).toString());
            return this;
        }

        public Builder withSourcePersistence(SegmentNodeStorePersistence source, String sourceName) {
            this.source = source;
            this.sourceName = sourceName;
            return this;
        }

        public Builder withTargetPersistence(SegmentNodeStorePersistence target, String targetName) {
            this.target = target;
            this.targetName = targetName;
            return this;
        }

        public Builder withTarget(File dir) {
            this.target = new TarPersistence(dir);
            this.targetName = storeDescription(SegmentStoreType.TAR, dir.getPath());
            return this;
        }

        public Builder withTarget(CloudBlobDirectory dir) {
            this.target = new AzurePersistence(dir);
            this.targetName = storeDescription(SegmentStoreType.AZURE, Paths.get(dir.getContainerName(), dir.getPrefix()).toString());

            return this;
        }

        public Builder setAppendMode() {
            this.appendMode = true;
            return this;
        }

        public Builder withOnlyLastJournalEntry() {
            this.onlyLastJournalEntry = true;
            return this;
        }

        public SegmentStoreMigrator build() {
            return new SegmentStoreMigrator(this);
        }
    }
}