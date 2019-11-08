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

package org.apache.jackrabbit.oak.segment.azure.compat;

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobListDetails;
import com.azure.storage.blob.models.ListBlobsOptions;
import org.apache.jackrabbit.oak.commons.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.stream.Stream;

/**
 * Represents a virtual directory of blobs, designated by a delimiter character.
 * <p>
 * Inspired by azure-storage-java v8 (https://azure.github.io/azure-storage-java/com/microsoft/azure/storage/blob/CloudBlobDirectory.html)
 */
public class CloudBlobDirectory {
    private static Logger LOG = LoggerFactory.getLogger(CloudBlobDirectory.class);

    private final BlobContainerClient containerClient;
    private final String directory;

    public CloudBlobDirectory(@NotNull final BlobContainerClient containerClient,
                              @NotNull final String directory) {
        this.containerClient = containerClient;
        this.directory = directory;
    }

    public PagedIterable<BlobItem> listBlobs() {
        return containerClient.listBlobs(new ListBlobsOptions().setPrefix(getPrefix()), null);
    }

    public PagedIterable<BlobItem> listBlobsStartingWith(String filePrefix) {
        String prefix = Paths.get(directory, filePrefix).toString();
        return containerClient.listBlobsByHierarchy("/",
                new ListBlobsOptions().setPrefix(prefix)
                        // While "Details" is an optional parameter, Azurite 3.2 complained that it must not be empty.
                        // TODO OAK-8413: verify after development
                        .setDetails(new BlobListDetails().setRetrieveMetadata(true))
                , null);
    }

    public Stream<BlobClient> listBlobClientsStartingWith(String filePrefix) {
        return this.listBlobsStartingWith(filePrefix)
                .stream()
                .map(this::getBlobClientAbsolute);
    }


    /**
     * Get the files and directories in this directory.
     *
     * <p>
     * E.g. listing a directory containing  a blob 'journal.log' and a 'data00000a.tar' directory
     * (which contains a blobs '0000.cafe'), will return the following results:
     *
     * <ul>
     * <li>data00000a.tar
     * <li>journal.log
     * </ul>
     *
     * @return all files and directories
     */
    public Stream<String> listItemsInDirectory() {
        // It is important that the prefix ends with a slash:
        String prefixWithSlash = IOUtils.addTrailingSlash(getPrefix());

        return containerClient.listBlobsByHierarchy("/",
                new ListBlobsOptions().setPrefix(prefixWithSlash)
                        // While "Details" is an optional parameter, Azurite 3.2 complained that it must not be empty.
                        // TODO OAK-8413: verify after development
                        .setDetails(new BlobListDetails().setRetrieveMetadata(true))
                , null)
                .stream()
                // getName() returns the full path with trailing slash. Just use the filename.
                .map(blobItem -> Paths.get(blobItem.getName()).getFileName().toString());

    }


    public PagedIterable<BlobItem> listBlobs(ListBlobsOptions options, Duration timeout) {
        String prefix = Paths.get(directory, options.getPrefix()).toString();
        return containerClient.listBlobsByHierarchy("/",
                new ListBlobsOptions().setPrefix(prefix), timeout);
    }

    /**
     * @param filename filename without the directory prefix
     */
    public BlobClient getBlobClient(@NotNull final String filename) {
        return containerClient.getBlobClient(Paths.get(directory, filename).toString());
    }

    /**
     * @param blobItem a reference to a blob (contains the directory prefix)
     */
    public BlobClient getBlobClientAbsolute(@NotNull BlobItem blobItem) {
        return containerClient.getBlobClient(blobItem.getName());
    }

    /**
     * @param dirName name of the sub directory
     * @return a sub directory
     */
    public CloudBlobDirectory getSubDirectory(@NotNull final String dirName) {
        return new CloudBlobDirectory(containerClient, Paths.get(directory, dirName).toString());
    }

    public void deleteBlobIfExists(BlobClient blob) {
        if (blob.exists()) blob.delete();
    }

    public URI getUri() {
        try {
            URL containerUrl = new URL(containerClient.getBlobContainerUrl());
            String path = Paths.get(containerUrl.getPath(), directory).toString();
            return new URI(containerUrl.getProtocol(),
                    containerUrl.getUserInfo(),
                    containerUrl.getHost(),
                    containerUrl.getPort(),
                    path,
                    containerUrl.getQuery(),
                    null);
        } catch (URISyntaxException | MalformedURLException e) {
            LOG.warn("Unable to format directory URI", e);
            return null;
        }
    }

    public String getContainerName() {
        return containerClient.getBlobContainerName();
    }

    /**
     * @return The path of this directory: parent directories and this directory.
     */
    public String getPrefix() {
        return directory;
    }

    /**
     * @return the name of the directory *only*
     */
    public String getFilename() {
        return Paths.get(getPrefix()).getFileName().toString();

    }

    public BlobContainerClient getContainerClient() {
        return containerClient;
    }




}
