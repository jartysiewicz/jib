/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.crepecake.cache;

import com.google.cloud.tools.crepecake.blob.BlobDescriptor;
import com.google.cloud.tools.crepecake.hash.CountingDigestOutputStream;
import com.google.cloud.tools.crepecake.image.DescriptorDigest;
import com.google.cloud.tools.crepecake.image.UnwrittenLayer;
import com.google.common.io.ByteStreams;
import com.google.common.io.CountingOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Writes {@link UnwrittenLayer}s to the cache. */
public class CacheWriter {

  private final Cache cache;

  public CacheWriter(Cache cache) {
    this.cache = cache;
  }

  /** Compresses and writes an {@link UnwrittenLayer}'s uncompressed layer content BLOB to cache. */
  public CachedLayer writeLayer(UnwrittenLayer layer) throws IOException {
    // Writes to a temporary file first because the UnwrittenLayer needs to be written first to
    // obtain its digest.
    Path tempLayerFile = Files.createTempFile(cache.getCacheDirectory(), null, null);
    // TODO: Find a way to do this with java.nio.file
    tempLayerFile.toFile().deleteOnExit();

    // Writes the UnwrittenLayer layer BLOB to a file to convert into a CachedLayer.
    try (CountingDigestOutputStream compressedDigestOutputStream =
        new CountingDigestOutputStream(
            new BufferedOutputStream(Files.newOutputStream(tempLayerFile)))) {
      // Writes the layer with GZIP compression. The original bytes are captured as the layer's
      // diff ID and the bytes outputted from the GZIP compression are captured as the layer's
      // content descriptor.
      GZIPOutputStream compressorStream = new GZIPOutputStream(compressedDigestOutputStream);
      DescriptorDigest diffId = layer.getBlob().writeTo(compressorStream).getDigest();

      // The GZIPOutputStream must be closed in order to write out the remaining compressed data.
      compressorStream.close();
      BlobDescriptor compressedBlobDescriptor = compressedDigestOutputStream.toBlobDescriptor();

      // Renames the temporary layer file to the correct filename.
      Path layerFile =
          CacheFiles.getLayerFile(cache.getCacheDirectory(), compressedBlobDescriptor.getDigest());
      // TODO: Should probably check for existence of target file and whether or not it's the same.
      try {
        Files.move(tempLayerFile, layerFile);

      } catch (IOException ex) {
        throw new IOException(
            "Could not rename layer "
                + compressedBlobDescriptor.getDigest().getHash()
                + " to "
                + layerFile,
            ex);
      }

      return new CachedLayer(layerFile, compressedBlobDescriptor, diffId);
    }
  }

  /**
   * @return the {@link CountingOutputStream} to write to to cache a layer with the specified
   *     compressed digest
   */
  public CountingOutputStream getLayerOutputStream(DescriptorDigest layerDigest)
      throws IOException {
    Path layerFile = getLayerFile(layerDigest);
    return new CountingOutputStream(new BufferedOutputStream(Files.newOutputStream(layerFile)));
  }

  /**
   * @return a {@link CachedLayer} from a layer digest and the {@link CountingOutputStream} the
   *     layer BLOB was written to
   */
  public CachedLayer getCachedLayer(
      DescriptorDigest layerDigest, CountingOutputStream countingOutputStream) throws IOException {
    Path layerFile = getLayerFile(layerDigest);
    countingOutputStream.close();
    return new CachedLayer(
        layerFile,
        new BlobDescriptor(countingOutputStream.getCount(), layerDigest),
        getDiffId(layerFile));
  }

  /** @return the path to the file for the layer with the specified compressed digest */
  private Path getLayerFile(DescriptorDigest compressedDigest) {
    return CacheFiles.getLayerFile(cache.getCacheDirectory(), compressedDigest);
  }

  /** @return the layer diff ID by decompressing the layer content file */
  private DescriptorDigest getDiffId(Path layerFile) throws IOException {
    CountingDigestOutputStream diffIdCaptureOutputStream =
        new CountingDigestOutputStream(ByteStreams.nullOutputStream());
    try (InputStream fileInputStream = new BufferedInputStream(Files.newInputStream(layerFile));
        GZIPInputStream decompressorStream = new GZIPInputStream(fileInputStream)) {
      ByteStreams.copy(decompressorStream, diffIdCaptureOutputStream);
    }
    return diffIdCaptureOutputStream.toBlobDescriptor().getDigest();
  }
}