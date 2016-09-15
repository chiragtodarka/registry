/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hortonworks.registries.schemaregistry.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.hortonworks.registries.schemaregistry.IncompatibleSchemaException;
import com.hortonworks.registries.schemaregistry.InvalidSchemaException;
import com.hortonworks.registries.schemaregistry.SchemaFieldQuery;
import com.hortonworks.registries.schemaregistry.SchemaVersionInfo;
import com.hortonworks.registries.schemaregistry.SchemaVersionKey;
import com.hortonworks.registries.schemaregistry.SchemaInfo;
import com.hortonworks.registries.schemaregistry.SchemaKey;
import com.hortonworks.registries.schemaregistry.SchemaNotFoundException;
import com.hortonworks.registries.schemaregistry.SerDesInfo;
import com.hortonworks.registries.schemaregistry.VersionedSchema;
import com.hortonworks.registries.schemaregistry.serde.SerDesException;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.media.multipart.BodyPart;
import org.glassfish.jersey.media.multipart.MultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.StreamDataBodyPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.hortonworks.registries.schemaregistry.client.SchemaRegistryClient.Options.SCHEMA_REGISTRY_URL;

/**
 * This is the default implementation of {@link ISchemaRegistryClient} which connects to the given {@code rootCatalogURL}.
 * <pre>
 * This can be used to
 *      - register schemas
 *      - add new versions of a schema
 *      - fetch different versions of schema
 *      - fetch latest version of a schema
 *      - check whether the given schema text is compatible with a latest version of the schema
 *      - register serializer/deserializer for a schema
 *      - fetch serializer/deserializer for a schema
 * </pre>
 */
public class SchemaRegistryClient implements ISchemaRegistryClient {
    private static final Logger LOG = LoggerFactory.getLogger(SchemaRegistryClient.class);

    private static final String SCHEMA_REGISTRY_PATH = "/schemaregistry";
    private static final String SCHEMAS_PATH = SCHEMA_REGISTRY_PATH + "/schemas/";
    private static final String FILES_PATH = SCHEMA_REGISTRY_PATH + "/files/";
    private static final String SERIALIZERS_PATH = SCHEMA_REGISTRY_PATH + "/serializers/";
    private static final String DESERIALIZERS_PATH = SCHEMA_REGISTRY_PATH + "/deserializers/";

    private final Client client;
    private final WebTarget rootTarget;
    private final WebTarget schemasTarget;
    private final WebTarget searchFieldsTarget;

    private final Options options;
    private final ClassLoaderCache classLoaderCache;
    private LoadingCache<SchemaVersionKey, SchemaVersionInfo> schemaCache;

    public SchemaRegistryClient(Map<String, ?> conf) {
        options = new Options(conf);

        client = ClientBuilder.newClient(new ClientConfig());
        client.register(MultiPartFeature.class);
        String rootCatalogURL = (String) conf.get(SCHEMA_REGISTRY_URL);
        rootTarget = client.target(rootCatalogURL);
        schemasTarget = rootTarget.path(SCHEMAS_PATH);
        searchFieldsTarget = schemasTarget.path("search/fields");

        classLoaderCache = new ClassLoaderCache(this);

        schemaCache = CacheBuilder.newBuilder()
                .maximumSize(options.getMaxSchemaCacheSize())
                .expireAfterAccess(options.getSchemaExpiryInMillis(), TimeUnit.MILLISECONDS)
                .build(new CacheLoader<SchemaVersionKey, SchemaVersionInfo>() {
                    @Override
                    public SchemaVersionInfo load(SchemaVersionKey schemaVersionKey) throws Exception {
                        return _getSchema(schemaVersionKey);
                    }
                });
    }

    public Options getOptions() {
        return options;
    }

    @Override
    public void close() {
        client.close();
    }

    @Override
    public boolean registerSchemaMetadata(SchemaInfo schemaInfo) {
        return postEntity(schemasTarget, schemaInfo, Boolean.class);
    }

    @Override
    public Integer registerSchema(SchemaInfo schemaInfo, VersionedSchema versionedSchema) throws InvalidSchemaException {
        SchemaKey schemaKey = schemaInfo.getSchemaKey();
        WebTarget path = schemaMetadataPath(schemaKey);
        SchemaDetails schemaDetails = new SchemaDetails(schemaInfo.getDescription(), schemaInfo.getCompatibility(), versionedSchema);
        return postEntity(path, schemaDetails, Integer.class);
    }

    private WebTarget schemaMetadataPath(SchemaKey schemaKey) {
        return schemasTarget.path(
                String.format("types/%s/groups/%s/names/%s",
                        schemaKey.getType(), schemaKey.getSchemaGroup(), schemaKey.getName()));
    }

    @Override
    public Integer addVersionedSchema(SchemaKey schemaKey, VersionedSchema versionedSchema) throws InvalidSchemaException, IncompatibleSchemaException {
        WebTarget path = schemaMetadataPath(schemaKey);
        SchemaDetails schemaDetails = new SchemaDetails(versionedSchema);
        return postEntity(path, schemaDetails, Integer.class);
    }

    @Override
    public SchemaVersionInfo getSchema(SchemaVersionKey schemaVersionKey) {
        try {
            return schemaCache.get(schemaVersionKey);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public SchemaVersionInfo _getSchema(SchemaVersionKey schemaVersionKey) {
        SchemaKey schemaKey = schemaVersionKey.getSchemaKey();
        WebTarget webTarget = schemasTarget.path(
                String.format("types/%s/groups/%s/names/%s/versions/%d",
                        schemaKey.getType(), schemaKey.getSchemaGroup(), schemaKey.getName(), schemaVersionKey.getVersion()));

        return getEntity(webTarget, SchemaVersionInfo.class);
    }

    @Override
    public SchemaVersionInfo getLatestSchema(SchemaKey schemaKey) throws SchemaNotFoundException {
        WebTarget webTarget = schemasTarget.path(
                String.format("types/%s/groups/%s/names/%s/versions/latest",
                        schemaKey.getType(), schemaKey.getSchemaGroup(), schemaKey.getName()));
        return getEntity(webTarget, SchemaVersionInfo.class);
    }

    @Override
    public Collection<SchemaVersionInfo> getAllVersions(SchemaKey schemaKey) throws SchemaNotFoundException {
        WebTarget webTarget = schemasTarget.path(
                String.format("types/%s/groups/%s/names/%s/versions",
                        schemaKey.getType(), schemaKey.getSchemaGroup(), schemaKey.getName()));
        return getEntities(webTarget, SchemaVersionInfo.class);
    }

    @Override
    public boolean isCompatibleWithAllVersions(SchemaKey schemaKey, String toSchemaText) throws SchemaNotFoundException {
        WebTarget webTarget = schemasTarget.path(
                String.format("types/%s/groups/%s/names/%s/compatibility",
                        schemaKey.getType(), schemaKey.getSchemaGroup(), schemaKey.getName()));
        String response = webTarget.request().post(Entity.text(toSchemaText), String.class);
        return readEntity(response, Boolean.class);
    }

    @Override
    public Collection<SchemaVersionKey> findSchemasByFields(SchemaFieldQuery schemaFieldQuery) {
        WebTarget target = searchFieldsTarget;
        for (Map.Entry<String, String> entry : schemaFieldQuery.toQueryMap().entrySet()) {
            target = target.queryParam(entry.getKey(), entry.getValue());
        }

        return getEntities(target, SchemaVersionKey.class);
    }

    @Override
    public String uploadFile(InputStream inputStream) {
        MultiPart multiPart = new MultiPart();
        BodyPart filePart = new StreamDataBodyPart("file", inputStream, "file");
        multiPart.bodyPart(filePart);

        String response = rootTarget.path(FILES_PATH).request().post(Entity.entity(multiPart, MediaType.MULTIPART_FORM_DATA), String.class);
        return readEntity(response, String.class);
    }

    @Override
    public InputStream downloadFile(String fileId) {
        return rootTarget.path(FILES_PATH).path("download/" + fileId).request().get(InputStream.class);
    }

    @Override
    public Long addSerializer(SerDesInfo serializerInfo) {
        return postEntity(rootTarget.path(SERIALIZERS_PATH), serializerInfo, Long.class);
    }

    @Override
    public Long addDeserializer(SerDesInfo deserializerInfo) {
        String response = postEntity(rootTarget.path(DESERIALIZERS_PATH), deserializerInfo, String.class);
        return readEntity(response, Long.class);
    }

    @Override
    public void mapSchemaWithSerDes(SchemaKey schemaKey, Long serDesId) {
        String path = String.format("types/%s/groups/%s/names/%s/mapping/%s", schemaKey.getType(), schemaKey.getSchemaGroup(), schemaKey.getName(), serDesId.toString());

        Boolean success = postEntity(schemasTarget.path(path), null, Boolean.class);
        LOG.info("Received response while mapping schemaMetadataKey [{}] with serialzer/deserializer [{}] : [{}]", schemaKey, serDesId, success);
    }

    @Override
    public Collection<SerDesInfo> getSerializers(SchemaKey schemaKey) {
        String path = String.format("types/%s/groups/%s/names/%s/serializers/", schemaKey.getType(), schemaKey.getSchemaGroup(), schemaKey.getName());
        return getEntities(schemasTarget.path(path), SerDesInfo.class);
    }

    @Override
    public Collection<SerDesInfo> getDeserializers(SchemaKey schemaKey) {
        String path = String.format("types/%s/groups/%s/names/%s/deserializers/", schemaKey.getType(), schemaKey.getSchemaGroup(), schemaKey.getName());
        return getEntities(schemasTarget.path(path), SerDesInfo.class);
    }

    public <T> T createSerializerInstance(SerDesInfo serializerInfo) {
        return createInstance(serializerInfo);
    }

    @Override
    public <T> T createDeserializerInstance(SerDesInfo deserializerInfo) {
        return createInstance(deserializerInfo);
    }

    private <T> T createInstance(SerDesInfo serializerInfo) {
        // loading serializer, create a class loader and and keep them in cache.
        String fileId = serializerInfo.getFileId();
        // get class loader for this file ID
        ClassLoader classLoader = classLoaderCache.getClassLoader(fileId);

        //
        T t;
        try {
            Class<T> clazz = (Class<T>) Class.forName(serializerInfo.getClassName(), true, classLoader);
            t = clazz.newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            throw new SerDesException(e);
        }

        return t;
    }

    private <T> List<T> getEntities(WebTarget target, Class<T> clazz) {
        List<T> entities = new ArrayList<>();
        String response = target.request(MediaType.APPLICATION_JSON_TYPE).get(String.class);
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(response);
            Iterator<JsonNode> it = node.get("entities").elements();
            while (it.hasNext()) {
                entities.add(mapper.treeToValue(it.next(), clazz));
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return entities;
    }

    private <T> T postEntity(WebTarget target, Object json, Class<T> responseType) {
        String response = target.request(MediaType.APPLICATION_JSON_TYPE).post(Entity.json(json), String.class);

        return readEntity(response, responseType);
    }

    private <T> T readEntity(String response, Class<T> clazz) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(response);
            return mapper.treeToValue(node.get("entity"), clazz);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private <T> T getEntity(WebTarget target, Class<T> clazz) {
        String response = target.request(MediaType.APPLICATION_JSON_TYPE).get(String.class);

        return readEntity(response, clazz);
    }

    public static class Options {
        // we may want to remove schema.registry prefix from configuration properties as these are all properties
        // given by client.
        public static final String SCHEMA_REGISTRY_URL = "schema.registry.url";
        public static final String LOCAL_JAR_PATH = "schema.registry.local.jars.path";
        public static final String CLASSLOADER_CACHE_SIZE = "schema.registry.class.loader.cache.size";
        public static final String CLASSLOADER_CACHE_EXPIRY_INTERVAL = "schema.registry.class.loader.cache.expiry.interval";
        public static final int DEFAULT_CLASS_LOADER_CACHE_SIZE = 1024;
        public static final long DEFAULT_CLASSLOADER_CACHE_EXPIRY_INTERVAL_MILLISECS = 60 * 60 * 1000L;
        public static final String DEFAULT_LOCAL_JARS_PATH = "/tmp/schema-registry/local-jars";
        public static final String SCHEMA_CACHE_SIZE = "schema.registry.schema.cache.size";
        public static final String SCHEMA_CACHE_EXPIRY_INTERVAL = "schema.registry.schema.cache.expiry.interval";
        public static final int DEFAULT_SCHEMA_CACHE_SIZE = 1024;
        public static final long DEFAULT_SCHEMA_CACHE_EXPIRY_INTERVAL_MILLISECS = 60 * 60 * 1000L;

        private final Map<String, ?> config;

        public Options(Map<String, ?> config) {
            this.config = config;
        }

        private Object getPropertyValue(String propertyKey, Object defaultValue) {
            Object value = config.get(propertyKey);
            return value != null ? value : defaultValue;
        }

        public int getClassLoaderCacheSize() {
            return (Integer) getPropertyValue(CLASSLOADER_CACHE_SIZE, DEFAULT_CLASS_LOADER_CACHE_SIZE);
        }

        public long getClassLoaderCacheExpiryInMilliSecs() {
            return (Long) getPropertyValue(CLASSLOADER_CACHE_EXPIRY_INTERVAL, DEFAULT_CLASSLOADER_CACHE_EXPIRY_INTERVAL_MILLISECS);
        }

        public String getLocalJarPath() {
            return (String) getPropertyValue(LOCAL_JAR_PATH, DEFAULT_LOCAL_JARS_PATH);
        }

        public int getMaxSchemaCacheSize() {
            return (Integer) getPropertyValue(SCHEMA_CACHE_SIZE, DEFAULT_SCHEMA_CACHE_SIZE);
        }

        public long getSchemaExpiryInMillis() {
            return (Long) getPropertyValue(SCHEMA_CACHE_EXPIRY_INTERVAL, DEFAULT_SCHEMA_CACHE_EXPIRY_INTERVAL_MILLISECS);
        }
    }
}
