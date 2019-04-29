/*
 * Copyright 2018 Google LLC. All rights reserved.
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

package com.google.cloud.tools.jib.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.tools.jib.blob.Blobs;
import com.google.cloud.tools.jib.filesystem.LockFile;
import com.google.cloud.tools.jib.image.DescriptorDigest;
import com.google.cloud.tools.jib.image.ImageReference;
import com.google.cloud.tools.jib.image.json.ContainerConfigurationTemplate;
import com.google.cloud.tools.jib.image.json.ManifestTemplate;
import com.google.cloud.tools.jib.image.json.OCIManifestTemplate;
import com.google.cloud.tools.jib.image.json.V21ManifestTemplate;
import com.google.cloud.tools.jib.image.json.V22ManifestTemplate;
import com.google.cloud.tools.jib.json.JsonTemplateMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Reads from the default cache storage engine. */
class CacheStorageReader {

  private final CacheStorageFiles cacheStorageFiles;

  CacheStorageReader(CacheStorageFiles cacheStorageFiles) {
    this.cacheStorageFiles = cacheStorageFiles;
  }

  /**
   * Lists all the layer digests stored.
   *
   * @return the list of layer digests
   * @throws CacheCorruptedException if the cache was found to be corrupted
   * @throws IOException if an I/O exception occurs
   */
  Set<DescriptorDigest> fetchDigests() throws IOException, CacheCorruptedException {
    try (Stream<Path> layerDirectories = Files.list(cacheStorageFiles.getLayersDirectory())) {
      List<Path> layerDirectoriesList = layerDirectories.collect(Collectors.toList());
      Set<DescriptorDigest> layerDigests = new HashSet<>(layerDirectoriesList.size());
      for (Path layerDirectory : layerDirectoriesList) {
        try {
          layerDigests.add(DescriptorDigest.fromHash(layerDirectory.getFileName().toString()));

        } catch (DigestException ex) {
          throw new CacheCorruptedException("Found non-digest file in layers directory", ex);
        }
      }
      return layerDigests;
    }
  }

  /**
   * Retrieves the cached manifest for an image reference.
   *
   * @param imageReference the image reference
   * @return the manifest for the image reference, if found
   * @throws IOException if an I/O exception occurs
   */
  Optional<ManifestTemplate> retrieveManifest(ImageReference imageReference) throws IOException {
    Path manifestPath =
        cacheStorageFiles.getImageDirectory(imageReference).resolve("manifest.json");
    if (!Files.exists(manifestPath)) {
      return Optional.empty();
    }

    try (LockFile ignored = LockFile.lock(Paths.get(manifestPath.toString() + ".lock"))) {
      // TODO: Consolidate with ManifestPuller
      ObjectNode node =
          new ObjectMapper().readValue(Files.newInputStream(manifestPath), ObjectNode.class);
      if (!node.has("schemaVersion")) {
        return Optional.empty();
      }
      int schemaVersion = node.get("schemaVersion").asInt(-1);
      if (schemaVersion == -1) {
        return Optional.empty();
      }

      if (schemaVersion == 1) {
        return Optional.of(
            JsonTemplateMapper.readJsonFromFile(manifestPath, V21ManifestTemplate.class));
      }
      if (schemaVersion == 2) {
        // 'schemaVersion' of 2 can be either Docker V2.2 or OCI.
        String mediaType = node.get("mediaType").asText();
        if (V22ManifestTemplate.MANIFEST_MEDIA_TYPE.equals(mediaType)) {
          return Optional.of(
              JsonTemplateMapper.readJsonFromFile(manifestPath, V22ManifestTemplate.class));
        }
        if (OCIManifestTemplate.MANIFEST_MEDIA_TYPE.equals(mediaType)) {
          return Optional.of(
              JsonTemplateMapper.readJsonFromFile(manifestPath, OCIManifestTemplate.class));
        }
      }
      return Optional.empty();
    }
  }

  /**
   * Retrieves the cached container configuration for an image reference.
   *
   * @param imageReference the image reference
   * @return the container configuration for the image reference, if found
   * @throws IOException if an I/O exception occurs
   */
  Optional<ContainerConfigurationTemplate> retrieveContainerConfiguration(
      ImageReference imageReference) throws IOException {
    Path configPath = cacheStorageFiles.getImageDirectory(imageReference).resolve("config.json");
    if (!Files.exists(configPath)) {
      return Optional.empty();
    }

    try (LockFile ignored = LockFile.lock(Paths.get(configPath.toString() + ".lock"))) {
      return Optional.of(
          JsonTemplateMapper.readJsonFromFile(configPath, ContainerConfigurationTemplate.class));
    }
  }

  /**
   * Retrieves the {@link CachedLayer} for the layer with digest {@code layerDigest}.
   *
   * @param layerDigest the layer digest
   * @return the {@link CachedLayer} referenced by the layer digest, if found
   * @throws CacheCorruptedException if the cache was found to be corrupted
   * @throws IOException if an I/O exception occurs
   */
  Optional<CachedLayer> retrieve(DescriptorDigest layerDigest)
      throws IOException, CacheCorruptedException {
    Path layerDirectory = cacheStorageFiles.getLayerDirectory(layerDigest);
    if (!Files.exists(layerDirectory)) {
      return Optional.empty();
    }

    CachedLayer.Builder cachedLayerBuilder = CachedLayer.builder().setLayerDigest(layerDigest);

    try (Stream<Path> filesInLayerDirectory = Files.list(layerDirectory)) {
      for (Path fileInLayerDirectory : filesInLayerDirectory.collect(Collectors.toList())) {
        if (CacheStorageFiles.isLayerFile(fileInLayerDirectory)) {
          if (cachedLayerBuilder.hasLayerBlob()) {
            throw new CacheCorruptedException(
                "Multiple layer files found for layer with digest "
                    + layerDigest.getHash()
                    + " in directory: "
                    + layerDirectory);
          }
          cachedLayerBuilder
              .setLayerBlob(Blobs.from(fileInLayerDirectory))
              .setLayerDiffId(CacheStorageFiles.getDiffId(fileInLayerDirectory))
              .setLayerSize(Files.size(fileInLayerDirectory));
        }
      }
    }

    return Optional.of(cachedLayerBuilder.build());
  }

  /**
   * Retrieves the layer digest selected by the {@code selector}.
   *
   * @param selector the selector
   * @return the layer digest {@code selector} selects, if found
   * @throws CacheCorruptedException if the selector file contents was not a valid layer digest
   * @throws IOException if an I/O exception occurs
   */
  Optional<DescriptorDigest> select(DescriptorDigest selector)
      throws CacheCorruptedException, IOException {
    Path selectorFile = cacheStorageFiles.getSelectorFile(selector);
    if (!Files.exists(selectorFile)) {
      return Optional.empty();
    }

    String selectorFileContents =
        new String(Files.readAllBytes(selectorFile), StandardCharsets.UTF_8);
    try {
      return Optional.of(DescriptorDigest.fromHash(selectorFileContents));

    } catch (DigestException ex) {
      throw new CacheCorruptedException(
          "Expected valid layer digest as contents of selector file `"
              + selectorFile
              + "` for selector `"
              + selector.getHash()
              + "`, but got: "
              + selectorFileContents);
    }
  }
}
