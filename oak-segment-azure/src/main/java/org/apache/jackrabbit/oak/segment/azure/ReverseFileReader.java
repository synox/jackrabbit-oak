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

import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.specialized.BlobClientBase;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.lang.Math.min;

public class ReverseFileReader {

    private static final int BUFFER_SIZE = 16 * 1024;

    private int bufferSize;

    private final BlobClientBase blobClient;

    private byte[] buffer;

    private int bufferOffset;

    private int fileOffset;

    public ReverseFileReader(BlobClientBase blobClient) throws BlobStorageException {
        this (blobClient, BUFFER_SIZE);
    }

    public ReverseFileReader(BlobClientBase blobClient, int bufferSize) throws BlobStorageException {
        this.blobClient = blobClient;
        if (blobClient.exists()) {
            this.fileOffset = (int) blobClient.getProperties().getBlobSize();
        } else {
            this.fileOffset = 0;
        }
        this.bufferSize = bufferSize;
    }

    private void readBlock() throws IOException {
        if (buffer == null) {
            buffer = new byte[min(fileOffset, bufferSize)];
        } else if (fileOffset < buffer.length) {
            buffer = new byte[fileOffset];
        }

        if (buffer.length > 0) {
            fileOffset -= buffer.length;
            try {
                BlobRange range = new BlobRange(fileOffset, (long) buffer.length);

                try (InputStream in = blobClient.openInputStream( range, null) ) {
                    buffer = IOUtils.toByteArray(in);
                }
            } catch (BlobStorageException e) {
                throw new IOException(e);
            }
        }
        bufferOffset = buffer.length;
    }

    private String readUntilNewLine() {
        if (bufferOffset == -1) {
            return "";
        }
        int stop = bufferOffset;
        while (--bufferOffset >= 0) {
            if (buffer[bufferOffset] == '\n') {
                break;
            }
        }
        // bufferOffset points either the previous '\n' character or -1
        return new String(buffer, bufferOffset + 1, stop - bufferOffset - 1, Charset.defaultCharset());
    }

    public String readLine() throws IOException {
        if (bufferOffset == -1 && fileOffset == 0) {
            return null;
        }

        if (buffer == null) {
            readBlock();
        }

        List<String> result = new ArrayList<>(1);
        while (true) {
            result.add(readUntilNewLine());
            if (bufferOffset > -1) { // stopped on the '\n'
                break;
            }
            if (fileOffset == 0) { // reached the beginning of the file
                break;
            }
            readBlock();
        }
        Collections.reverse(result);
        return String.join("", result);
    }
}
