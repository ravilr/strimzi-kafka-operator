/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.strimzi.crdgenerator.annotations.Description;
import io.sundr.builder.annotations.Buildable;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.emptyMap;

@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"partitions", "replicas", "config"})
public class KafkaMirrorMakerSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    private String whitelist;

    private String blacklist;

    private String source;

    private KafkaMirrorMakerTls sourceTls;

    private KafkaMirrorMakerAuthentication sourceAuthentication;

    private String destination;

    private KafkaMirrorMakerTls destinationTls;

    private KafkaMirrorMakerAuthentication destinationAuthentication;

    private Map<String, Object> config;

    private Map<String, Object> additionalProperties;

    @Description("The whitelist of the Mirror Maker")
    public String getWhitelist() {
        return whitelist;
    }

    public void setWhitelist(String whitelist) {
        this.whitelist = whitelist;
    }

    @Description("The blacklist of the Mirror Maker")
    public String getBlacklist() {
        return blacklist;
    }

    public void setBlacklist(String blacklist) {
        this.blacklist = blacklist;
    }

    @Description("Source TLS configuration")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public KafkaMirrorMakerTls getSourceTls() {
        return sourceTls;
    }

    public void setSourceTls(KafkaMirrorMakerTls tls) {
        this.sourceTls = tls;
    }


    @Description("Source TLS configuration")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public KafkaMirrorMakerTls getDestinationTls() {
        return destinationTls;
    }

    public void setDestinationTls(KafkaMirrorMakerTls tls) {
        this.destinationTls = tls;
    }

    @Description("Authentication configuration for Kafka Mirror Maker source")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public KafkaMirrorMakerAuthentication getSourceAuthentication() {
        return sourceAuthentication;
    }

    public void setSourceAuthentication(KafkaMirrorMakerAuthentication authentication) {
        this.sourceAuthentication = authentication;
    }

    @Description("Authentication configuration for Kafka Mirror Maker destination")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public KafkaMirrorMakerAuthentication getDestinationAuthentication() {
        return destinationAuthentication;
    }

    public void setDestinationAuthentication(KafkaMirrorMakerAuthentication authentication) {
        this.destinationAuthentication = authentication;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    @Description("Destination address configuration")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getDestination() {
        return destination;
    }


    public void setSource(String source) {
        this.source = source;
    }

    @Description("Destination address configuration")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getSource() {
        return source;
    }



    @Description("The Mirror Maker configuration.")
    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties != null ? this.additionalProperties : emptyMap();
    }

    @JsonAnySetter
    public void setAdditionalProperty(String name, Object value) {
        if (this.additionalProperties == null) {
            this.additionalProperties = new HashMap<>();
        }
        this.additionalProperties.put(name, value);
    }
}
